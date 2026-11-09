"""판정 기록 DB (PostgreSQL) — 스테이션 검증, 세션 기록, 저장된 결함 프레임 기록·조회 (DEPLOY.md §4.3).

api.py가 기동 시(lifespan) init(), 종료 시 close()를 부른다. 함수는 모두 동기다 — /ws에서는
asyncio.to_thread로, 엔드포인트에서는 스레드풀(동기 def)에서 부른다.
DATABASE_URL은 init()에서만 읽는다.

results 한 행 = /ws에서 저장한 결함 프레임(NG·REVIEW) 하나 (연결마다 1초에 1장, 실시간 확대 보기용). 오버레이를 쓴 뒤 done으로 넣는다.
products 한 행 = 제품 하나 (PLAN_product_feedback.md §5). 시작 때 넣고(insert_product), 끝날 때 채운다(end_product).
frames 한 행 = 제품이 보인 프레임 하나 (사진 파일 경로 포함). feedback_log 한 행 = 피드백 변경 하나.
한 연결의 products·frames·results 쓰기는 api.py가 연결마다 한 줄로 차례대로 부른다 (외래 키 순서).
제품 단위 판정 때의 행(pending/failed, 여러 장의 scores, alert_id)과 decisions·alerts 테이블은 옛 데이터 보존용으로
남아 있고, 새 코드는 쓰지 않는다.
"""

import os

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

# stations·sessions·alerts·results·decisions는 옛 정의 그대로 두고(sessions에 feedback_mode·config·stats만 추가),
# products·frames·feedback_log를 더한다 (PLAN_product_feedback.md §5). results에서 컬럼의 뜻:
#   attempt     -> 그 연결에서 프레임의 seq
#   status      -> 항상 done (pending·failed는 옛 행에만 있다)
#   scores      -> [score] (한 장)
#   frames_dir  -> 그 프레임의 사진 경로 (product_frames/ 아래 <날짜>/<session_id>/<seq 6자리>.jpg, frames.file과 같다).
#                  옛 행은 frames/ 아래 <날짜>/<result_id>.jpg. 저장 실패면 NULL
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
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS feedback_mode text NOT NULL DEFAULT 'auto';  -- manual | auto
-- 2026-11-09: 연결할 때의 판정 설정값·모델 (api.CONFIG), 닫을 때의 연결 통계 (ws_close와 같은 값). 옛 행은 NULL
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS config jsonb;
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS stats jsonb;
CREATE TABLE IF NOT EXISTS products (
  product_id     text PRIMARY KEY,
  session_id     text NOT NULL REFERENCES sessions,
  station_id     text REFERENCES stations,
  feedback_mode  text NOT NULL,                 -- manual | auto (세션 값)
  started_at     timestamptz NOT NULL,
  ended_at       timestamptz,                   -- NULL: 끝나지 않음 (서버가 기록 중 멈춤)
  end_reason     text,                          -- gap | disconnect
  decision       text,                          -- PASS | FAIL
  ai_decision    text,                          -- OK | NG | REVIEW
  defect_label   text,
  frames         int NOT NULL DEFAULT 0,
  defect_frames  int NOT NULL DEFAULT 0,
  faces          jsonb,                         -- {"상면": 30, …}
  faces_seen     jsonb,
  complete       boolean NOT NULL DEFAULT false,
  best_result_id text REFERENCES results,
  result_ids     jsonb,                         -- 저장된 결함 프레임, 점수 높은 순
  threshold      double precision,
  checkpoint     text,
  feedback       text NOT NULL,                 -- CORRECT | WRONG | UNSURE | NOT_ENTERED | NONE
  feedback_at    timestamptz
);
CREATE INDEX IF NOT EXISTS products_station_started ON products (station_id, started_at DESC, product_id DESC);
CREATE INDEX IF NOT EXISTS products_started ON products (started_at DESC, product_id DESC);
CREATE TABLE IF NOT EXISTS frames (
  session_id   text NOT NULL REFERENCES sessions,
  seq          int NOT NULL,
  product_id   text NOT NULL REFERENCES products,
  ts           timestamptz NOT NULL,
  decision     text NOT NULL,                   -- OK | NG | REVIEW
  score        double precision NOT NULL,
  defect_label text,
  products     jsonb NOT NULL,                  -- PD 상자
  detections   jsonb NOT NULL,
  frame_size   jsonb NOT NULL,
  file         text,                            -- product_frames/ 아래 경로 (<날짜>/<session_id>/<seq 6자리>.jpg). 저장 실패면 NULL
  result_id    text REFERENCES results,
  PRIMARY KEY (session_id, seq)
);
CREATE INDEX IF NOT EXISTS frames_product ON frames (product_id, seq);
CREATE TABLE IF NOT EXISTS feedback_log (
  id          bigserial PRIMARY KEY,
  product_id  text NOT NULL REFERENCES products,
  before      text NOT NULL,
  after       text NOT NULL,
  source      text NOT NULL,                    -- live | history
  device      text,
  changed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS feedback_log_product ON feedback_log (product_id, id);
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


def open_session(session_id: str, station_id: str | None, source: str, client: str | None,
                 feedback_mode: str, config: dict) -> None:
    """config: 이 연결에 쓰는 판정 설정값·모델 (api.CONFIG). 설정을 바꾼 뒤에도 옛 기록이 어느 값으로 계산됐는지 안다."""
    with pool.connection() as conn:
        conn.execute("INSERT INTO sessions (session_id, station_id, source, client, feedback_mode, config)"
                     " VALUES (%s, %s, %s, %s, %s, %s)",
                     (session_id, station_id, source, client, feedback_mode, Jsonb(config)))


def close_session(session_id: str, stats: dict) -> None:
    """stats: 연결 통계 (ws_close 로그와 같은 값 — duration_s, frames, processed, …, ms_avg, error)."""
    with pool.connection() as conn:
        conn.execute("UPDATE sessions SET closed_at = now(), stats = %s WHERE session_id = %s",
                     (Jsonb(stats), session_id))


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


# ── 제품 (PLAN_product_feedback.md §4·§5) ──

FEEDBACK_VALUES = ("CORRECT", "WRONG", "UNSURE")  # 사람이 넣는 값. NOT_ENTERED·NONE은 서버만 정한다
_PRODUCT_LIST_COLUMNS = ("product_id, station_id, started_at, ended_at, decision, ai_decision, defect_label, frames,"
                         " defect_frames, complete, best_result_id, feedback_mode, feedback, feedback_at")


def insert_product(product_id: str, session_id: str, station_id: str | None, feedback_mode: str, started_at: str,
                   feedback: str, checkpoint: str) -> None:
    with pool.connection() as conn:
        conn.execute("INSERT INTO products (product_id, session_id, station_id, feedback_mode, started_at, feedback,"
                     " checkpoint) VALUES (%s, %s, %s, %s, %s, %s, %s)",
                     (product_id, session_id, station_id, feedback_mode, started_at, feedback, checkpoint))


def insert_frame(session_id: str, message: dict, ts: str, file: str | None) -> None:
    """제품 프레임 하나. message는 그 프레임의 inspect 메시지 (product_id 포함)."""
    with pool.connection() as conn:
        conn.execute(
            "INSERT INTO frames (session_id, seq, product_id, ts, decision, score, defect_label, products, detections,"
            " frame_size, file, result_id) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
            (session_id, message["seq"], message["product_id"], ts, message["decision"], message["score"],
             message["defect_label"], Jsonb(message["products"]), Jsonb(message["detections"]),
             Jsonb(message["frame_size"]), file, message["result_id"]))


def end_product(end: dict) -> None:
    """product_end 메시지로 제품 행을 채운다. 피드백은 건드리지 않는다 (그 사이에 들어왔을 수 있다)."""
    with pool.connection() as conn:
        conn.execute(
            "UPDATE products SET ended_at = %s, end_reason = %s, decision = %s, ai_decision = %s, defect_label = %s,"
            " frames = %s, defect_frames = %s, faces = %s, faces_seen = %s, complete = %s, best_result_id = %s,"
            " result_ids = %s, threshold = %s WHERE product_id = %s",
            (end["ended_at"], end["end_reason"], end["decision"], end["ai_decision"], end["defect_label"],
             end["frames"], end["defect_frames"], Jsonb(end["faces"]), Jsonb(end["faces_seen"]), end["complete"],
             end["best_result_id"], Jsonb(end["result_ids"]), end["threshold"], end["product_id"]))


def set_feedback(product_id: str, value: str, source: str, device: str | None) -> dict | None:
    """피드백을 바꾸고 변경 기록을 남긴다. 같은 값이면 기록하지 않는다. 없는 제품이면 None."""
    with pool.connection() as conn, conn.transaction():
        row = conn.execute("SELECT feedback FROM products WHERE product_id = %s FOR UPDATE", (product_id,)).fetchone()
        if row is None:
            return None
        if row["feedback"] != value:
            conn.execute("UPDATE products SET feedback = %s, feedback_at = now() WHERE product_id = %s",
                         (value, product_id))
            conn.execute("INSERT INTO feedback_log (product_id, before, after, source, device) VALUES (%s, %s, %s, %s, %s)",
                         (product_id, row["feedback"], value, source, device))
    return get_product(product_id)


def list_products(station: str | None, start, end, feedback: list[str] | None, decision: str | None,
                  limit: int, before: str | None) -> dict:
    """이력 목록, 최신 순. start·end는 started_at 범위(aware datetime, end는 미포함). before는 이전 페이지의 마지막 product_id."""
    where, args = [], []
    if station is not None:
        where.append("station_id = %s"); args.append(station)
    if start is not None:
        where.append("started_at >= %s"); args.append(start)
    if end is not None:
        where.append("started_at < %s"); args.append(end)
    if feedback:
        where.append("feedback = ANY(%s)"); args.append(feedback)
    if decision is not None:
        where.append("decision = %s"); args.append(decision)
    if before is not None:
        where.append("(started_at, product_id) < (SELECT started_at, product_id FROM products WHERE product_id = %s)")
        args.append(before)
    sql = (f"SELECT {_PRODUCT_LIST_COLUMNS} FROM products" + (" WHERE " + " AND ".join(where) if where else "")
           + " ORDER BY started_at DESC, product_id DESC LIMIT %s")
    with pool.connection() as conn:
        rows = conn.execute(sql, (*args, limit + 1)).fetchall()
    items = [_product_item(r) for r in rows[:limit]]
    return {"items": items, "next_before": items[-1]["product_id"] if len(rows) > limit else None}


def get_product(product_id: str) -> dict | None:
    with pool.connection() as conn:
        row = conn.execute("SELECT * FROM products WHERE product_id = %s", (product_id,)).fetchone()
        if row is None:
            return None
        log = conn.execute("SELECT before, after, source, device, changed_at FROM feedback_log WHERE product_id = %s"
                           " ORDER BY id", (product_id,)).fetchall()
    item = _product_item(row)
    item |= {"faces": row["faces"], "faces_seen": row["faces_seen"], "result_ids": row["result_ids"] or [],
             "threshold": row["threshold"], "checkpoint": row["checkpoint"], "end_reason": row["end_reason"],
             "feedback_log": [{**r, "changed_at": _iso(r["changed_at"])} for r in log]}
    return item


def list_frames(product_id: str, offset: int, limit: int) -> dict | None:
    """제품의 프레임 목록 (seq 순). 없는 제품이면 None."""
    with pool.connection() as conn:
        if conn.execute("SELECT 1 FROM products WHERE product_id = %s", (product_id,)).fetchone() is None:
            return None
        total = conn.execute("SELECT count(*) AS n FROM frames WHERE product_id = %s", (product_id,)).fetchone()["n"]
        rows = conn.execute(
            "SELECT seq, ts, decision, score, defect_label, products, detections, frame_size, result_id, file"
            " FROM frames WHERE product_id = %s ORDER BY seq OFFSET %s LIMIT %s", (product_id, offset, limit)).fetchall()
    items = [{"seq": r["seq"], "ts": _iso(r["ts"]), "decision": r["decision"], "score": r["score"],
              "defect_label": r["defect_label"], "products": r["products"], "detections": r["detections"],
              "frame_size": r["frame_size"], "result_id": r["result_id"], "has_image": r["file"] is not None}
             for r in rows]
    return {"items": items, "total": total}


def get_frame(product_id: str, seq: int) -> dict | None:
    """프레임 한 장의 파일 경로와 결함 (이미지 조회용). 없으면 None."""
    with pool.connection() as conn:
        return conn.execute("SELECT file, detections FROM frames WHERE product_id = %s AND seq = %s",
                            (product_id, seq)).fetchone()


def _product_item(row: dict) -> dict:
    return {"product_id": row["product_id"], "station_id": row["station_id"], "started_at": _iso(row["started_at"]),
            "ended_at": _iso(row["ended_at"]), "decision": row["decision"], "ai_decision": row["ai_decision"],
            "defect_label": row["defect_label"], "frames": row["frames"], "defect_frames": row["defect_frames"],
            "complete": row["complete"], "best_result_id": row["best_result_id"], "feedback_mode": row["feedback_mode"],
            "feedback": row["feedback"], "feedback_at": _iso(row["feedback_at"])}
