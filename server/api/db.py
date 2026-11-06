"""판정 기록 DB (PostgreSQL) — 스테이션 검증, 세션 기록, 저장된 결함 프레임 기록·조회 (DEPLOY.md §4.3).

api.py가 기동 시(lifespan) init(), 종료 시 close()를 부른다. 함수는 모두 동기다 — /ws에서는
asyncio.to_thread로, 엔드포인트에서는 스레드풀(동기 def)에서 부른다.
DATABASE_URL은 init()에서만 읽는다.

results 한 행 = /ws에서 저장한 결함 프레임(NG·REVIEW) 하나. 파일(원본·오버레이)을 쓴 뒤 done으로 한 번에 넣는다.
제품 단위 판정 때의 행(pending/failed, 여러 장의 scores, alert_id)과 decisions·alerts 테이블은 옛 데이터 보존용으로
남아 있고, 새 코드는 쓰지 않는다.
"""

import os

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

# 테이블 정의는 제품 단위 판정 때와 같다 (스키마를 바꾸지 않는다). 프레임 단위에서 컬럼의 뜻:
#   attempt     -> 그 연결에서 프레임의 seq
#   status      -> 항상 done (pending·failed는 옛 행에만 있다)
#   scores      -> [score] (한 장)
#   frames_dir  -> 원본 프레임 파일 경로 (<날짜>/<result_id>.jpg). 저장 실패면 NULL
#   alert_id, decisions, alerts -> 쓰지 않는다
SCHEMA = """
CREATE TABLE IF NOT EXISTS stations (
  station_id  text PRIMARY KEY,
  name        text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS sessions (
  session_id  text PRIMARY KEY,
  station_id  text REFERENCES stations,
  source      text NOT NULL,              -- gateway | direct
  client      text,                       -- 게이트웨이 연결 ID
  opened_at   timestamptz NOT NULL DEFAULT now(),
  closed_at   timestamptz
);
CREATE TABLE IF NOT EXISTS alerts (
  alert_id    bigserial PRIMARY KEY,
  station_id  text NOT NULL REFERENCES stations,
  type        text NOT NULL,              -- consecutive_ng | consecutive_review (옛 데이터)
  count       int NOT NULL,
  result_ids  jsonb NOT NULL,             -- 오래된 순
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS results (
  result_id    text PRIMARY KEY,
  session_id   text NOT NULL REFERENCES sessions,
  station_id   text REFERENCES stations,
  attempt      int NOT NULL,                      -- 프레임 단위: seq
  status       text NOT NULL DEFAULT 'pending',   -- 프레임 단위: 항상 done (옛 행: pending | done | failed)
  decision     text,                              -- OK | NG | REVIEW (프레임 단위: NG | REVIEW)
  defect_label text,                              -- 가장 확실한 결함 (OK면 NULL)
  score        double precision,                  -- 제품 상자 안 결함의 최대 QI conf
  scores       jsonb,                             -- 프레임 단위: [score]
  threshold    double precision,                  -- QI conf 기준 (detect.QI_CONF)
  detections   jsonb,                             -- 결함 [{label, conf, box}]
  frame_size   jsonb,                             -- [w, h] — detections 좌표의 기준
  checkpoint   text,                              -- PD·QI 모델 ID (api.MODEL_ID)
  frames_dir   text,                              -- 프레임 단위: 원본 프레임 파일 경로
  overlay_path text,
  alert_id     bigint REFERENCES alerts,          -- 프레임 단위: 쓰지 않음
  captured_at  timestamptz NOT NULL DEFAULT now(),
  result_at    timestamptz
);
CREATE TABLE IF NOT EXISTS decisions (
  result_id   text PRIMARY KEY REFERENCES results,
  final       text NOT NULL,              -- OK | NG (옛 데이터)
  method      text NOT NULL,              -- nod | shake | tray | timeout
  decided_at  timestamptz NOT NULL DEFAULT now()
);
"""

pool: ConnectionPool | None = None


def init() -> None:
    """풀을 열고 스키마를 만든다. 실패하면 예외를 그대로 올린다 — API 기동이 멈추고 --restart가 다시 시도한다."""
    global pool
    # timeout: 연결을 얻으려고 기다리는 최대 시간. DB 장애 때 기본값(30초)만큼 /ws 연결(스테이션 검증)이
    # 늦어지지 않게 짧게 둔다. 정상일 때는 바로 얻는다.
    # check: 꺼낼 때 연결을 확인해 끊긴 연결을 버린다. 없으면 DB 재시작 뒤 풀에 남은 끊긴 연결마다
    # 첫 사용이 한 번씩 실패하고, 그 사용이 결함 프레임 기록(insert_result)이면 기록이 빠진다.
    # 있어도 재시작 직후 첫 호출 하나는 새 연결을 기다리다 실패할 수 있다 (db_error, 스테이션 검증이면 4503).
    pool = ConnectionPool(os.environ["DATABASE_URL"], kwargs={"row_factory": dict_row}, timeout=1,
                          check=ConnectionPool.check_connection, open=True)
    pool.wait()
    with pool.connection() as conn:
        conn.execute(SCHEMA)


def close() -> None:
    if pool is not None:
        pool.close()


def station_exists(station_id: str) -> bool:
    with pool.connection() as conn:
        return conn.execute("SELECT 1 FROM stations WHERE station_id = %s", (station_id,)).fetchone() is not None


def open_session(session_id: str, station_id: str | None, source: str, client: str | None) -> None:
    with pool.connection() as conn:
        conn.execute("INSERT INTO sessions (session_id, station_id, source, client) VALUES (%s, %s, %s, %s)",
                     (session_id, station_id, source, client))


def close_session(session_id: str) -> None:
    with pool.connection() as conn:
        conn.execute("UPDATE sessions SET closed_at = now() WHERE session_id = %s", (session_id,))


def insert_result(result_id: str, session_id: str, station_id: str | None, seq: int, message: dict,
                  checkpoint: str, frame_path: str | None, overlay_path: str | None) -> None:
    """저장한 결함 프레임 하나를 done으로 기록한다. message는 그 프레임의 inspect 메시지.
    captured_at(기본값)과 result_at은 같은 트랜잭션의 now()라 같은 값이다."""
    with pool.connection() as conn:
        conn.execute(
            "INSERT INTO results (result_id, session_id, station_id, attempt, status, decision, defect_label, score,"
            " scores, threshold, detections, frame_size, checkpoint, frames_dir, overlay_path, result_at)"
            " VALUES (%s, %s, %s, %s, 'done', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, now())",
            (result_id, session_id, station_id, seq, message["decision"], message["defect_label"], message["score"],
             Jsonb([message["score"]]), message["threshold"], Jsonb(message["detections"]),
             Jsonb(message["frame_size"]), checkpoint, frame_path, overlay_path))


def get_result(result_id: str) -> dict | None:
    """조회 응답 (A4_WEBRTC_API.md §4.1). 없으면 None. alert는 이전 규격과 모양을 맞추려고 남긴 필드로 항상 None."""
    with pool.connection() as conn:
        row = conn.execute("SELECT * FROM results WHERE result_id = %s", (result_id,)).fetchone()
    if row is None:
        return None
    done = row["status"] == "done"
    return {
        "result_id": row["result_id"],
        "station_id": row["station_id"],
        "status": row["status"],
        "decision": row["decision"],
        "defect_label": row["defect_label"],
        "score": row["score"],
        "scores": row["scores"],
        "threshold": row["threshold"],
        "detections": row["detections"],
        "frame_size": row["frame_size"],
        "overlay_url": f"/results/{result_id}/overlay" if done else None,
        "captured_at": _iso(row["captured_at"]),
        "result_at": _iso(row["result_at"]),
        "alert": None,
    }


def overlay_path(result_id: str) -> str | None:
    """결과 오버레이 파일 경로. 결과가 없거나 저장 실패면 None."""
    with pool.connection() as conn:
        row = conn.execute("SELECT overlay_path FROM results WHERE result_id = %s", (result_id,)).fetchone()
    return row and row["overlay_path"]


def _iso(value) -> str | None:
    return value.isoformat(timespec="milliseconds") if value else None
