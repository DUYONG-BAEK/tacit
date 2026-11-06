# 실시간 프레임 판정 전환 — 서버 구현 계획

작성: 2026-11-03. 이 문서만 보고 다른 세션에서 구현·검증·배포할 수 있게 쓴다.

**상태 (2026-11-03): 구현·작성 환경 검증(§7.1) 끝, 실서버 배포 전.** 구현하며 정하거나 계획과 달리 한 것은 §11. 배포 절차는 `DEPLOY.md` §5.A, 실서버 확인은 `DEPLOY.md` §5 ⑧.

| 문서 | 역할 |
|---|---|
| `PLAN_realtime_inspect.md` (이 문서) | 서버 코드 변경 계획 |
| `A4_WEBRTC_API.md` (2026-11-03 개정) | **목표 규격.** 앱이 이 규격으로 고친다. 서버 구현은 이 규격을 따른다 |
| `DEPLOY.md` | 현재(제품 단위) 서버의 구성·배포·운영. 구현 후 §8에 적은 대로 고친다 |
| `gateway/README.md` | 게이트웨이. 구현 후 고친다 |

---

## 1. 배경과 결정

**배경.** 작업자는 팔을 쭉 뻗어 제품을 든 채 돌려 본다. 멈춰서 프레임을 모아 줄 수 없다. 지금 서버는 "같은 제품을 3~10장 모아 중앙값으로 한 번 판정"하는 제품 단위 구조라, 움직이면 다음이 깨진다.
- 수집 중 PD가 3프레임 연속 놓치면 모은 것을 버려 판정이 안 나온다.
- 결함이 일부 프레임에만 보이면 중앙값이 0이 되어 OK로 나온다.
- `captured`("내려놓아도 됨") 안내가 의미 없다.

**결정 (2026-11-03 확정).**

| # | 결정 | 내용 |
|---|---|---|
| D1 | 프레임 단위 판정 | PD가 제품을 잡은 프레임마다 QI를 돌려 그 프레임의 판정·결함 위치를 바로 `inspect` 메시지로 보낸다. 제품 단위 구조(`hold`/`captured`/`result`, 3~10장 수집, 중앙값, 놓침·치움 규칙)는 **지운다** (옛 모드를 남기지 않는다) |
| D2 | 깜빡임은 앱이 처리 | 서버는 프레임마다 그대로 보낸다. 앱이 결함 표시를 약 0.5초 유지한다 |
| D3 | 결함 프레임만 저장 | NG·REVIEW 프레임만 원본·오버레이·DB 행을 남긴다. **연결마다 1초에 최대 1장** (`SAVE_INTERVAL_S`) |
| D4 | 사람의 최종 판정·연속 알림 제거 | `POST /results/{id}/decision`과 `alert` 메시지·집계를 지운다. DB의 `decisions`·`alerts` 테이블은 옛 데이터 보존을 위해 그대로 둔다 |
| D5 | PD 통과 조건은 그대로 | PD 상자가 하나라도 있으면 통과 (크기 하한 없음) |
| D6 | QI 결과를 제품 상자 안으로 제한 | 결함 상자의 **중심**이 어느 PD 상자 안에 있는 결함만 남긴다. 배경 오탐을 줄인다 |
| D7 | 오버레이 테두리를 바깥쪽으로 | `detect.draw`가 테두리를 상자 안쪽으로 그려 작은 결함을 덮는다 (4K 14×13px 결함에서 상자 안 97%가 빨강). 상자 바깥쪽으로 그린다 |
| D8 | 좌표는 픽셀 그대로 | `box`는 프레임 픽셀 좌표 + `frame_size` (지금과 같다). 정규화는 앱이 한다 |

결정이 바뀌면 이 표와 `A4_WEBRTC_API.md`를 함께 고친다.

---

## 2. 현재 상태 (2026-11-03, 실서버 확인)

- 서버: gpudev `/home/admin/odServer`. 컨테이너 `a4-db`(postgres:17), `a4-api`(GPU 0, :8000), `a4-gateway`(host 네트워크, :8080). 스테이션 ST-01(서버 테스트용)·ST-02(앱용) 등록.
- 코드: `api/` 8개 파일, `gateway/` 5개 파일 (`DEPLOY.md` §2). 이 코드로 `DEPLOY.md` §5 ⑧ 전체와 실시간 `alert`까지 확인했다.
- 실측 (4K 3840×2160 사진, `/predict` 로그 `ms`): decode 97~103ms, PD 10ms, QI 63ms(32타일), overlay encode 86~93ms. 1080p 실측은 아직 없다 (검은 1080p 화면 decode 20ms, PD 11ms).
- OpenCV 5.0.0 (ultralytics가 설치). `cv2.dnn.NMSBoxesBatched` 정상.
- DB 비밀번호는 `a4-db` 컨테이너 환경변수에서 읽는다 (`DEPLOY.md` §6.1의 `export A4_DB_PASSWORD=$(docker inspect …)`).

---

## 3. 목표 동작 (요약 — 상세는 `A4_WEBRTC_API.md` §3·§4)

```
/ws 세션: 프레임 수신 (가장 최근 1장만 남김)
  → 디코딩 실패: 건너뜀 (메시지 없음, skipped_frames += 1)
  → PD 상자 없음: searching {"reason": "no_product"} — (state, reason)이 바뀔 때만
  → PD 상자 있음: QI → 제품 상자 안 결함만 → inspect 전송
        decision != OK 이고 마지막 저장 후 SAVE_INTERVAL_S 지남:
            result_id = "[direct_]<세션 12자리>-<seq 6자리>", inspect에 result_id·overlay_url을 넣어 보낸 뒤
            백그라운드로 원본·오버레이 저장 + DB insert
```

`inspect` 필드: `state`, `seq`, `frame_size`, `decision`, `defect_label`, `score`, `threshold`, `products`, `detections`, `result_id`, `overlay_url`. base64 이미지는 두 전송 모두 넣지 않는다.

판정 (지금 `stream.aggregate`와 같은 규칙, 한 프레임에 적용):
- `score` = 남은 결함의 최대 `conf`, 없으면 0
- `score > T × 1.1` → NG, `score < T × 0.9` → OK, 그 사이 → REVIEW (`T = detect.QI_CONF`, `REVIEW_MARGIN = 0.1`)
- QI 하한이 `T × 0.9`이므로 결함이 하나라도 남으면 REVIEW 이상이다. OK ⇔ `detections == []`

첫 메시지: WebSocket 연결 직후 `{"state": "searching"}` (reason 없음) — **게이트웨이가 이 메시지로 연결을 확인하므로 반드시 유지한다.** 이후 첫 no-product 프레임에서 `{"state": "searching", "reason": "no_product"}`가 한 번 간다 (reason이 바뀌었으므로).

---

## 4. 파일별 변경

### 4.1 `api/detect.py`

1. **`filter_in_products(detections, products) -> list[dict]`** 추가 (D6)
   - 결함 상자 중심 `((x1+x2)/2, (y1+y2)/2)`가 어느 제품 상자 `[px1, py1, px2, py2]` 안(경계 포함)에 있으면 남긴다. 입력 순서(conf 내림차순)를 유지한다.
2. **`draw()` 테두리를 바깥쪽으로** (D7)
   - `pen.rectangle((x1 - width, y1 - width, x2 + width, y2 + width), outline=…, width=width)` — Pillow는 `width`를 사각형 안쪽으로 채우므로, 사각형을 `width`만큼 넓히면 테두리가 원래 상자 바깥에 놓인다.
   - 글자 위치도 그만큼 올린다: `origin = (x1 - width, max(0, y1 - width - size - width))`.
   - 확인: 4K 프레임에 실측 상자 `[2205.33, 1955.26, 2219.30, 1968.01]`(width 6)를 그렸을 때 상자 안 픽셀이 원본과 같아야 한다 (지금은 210픽셀 중 204픽셀이 빨강).
3. 모듈 docstring의 `inspect`·`draw` 설명을 맞춘다. 나머지(`read_image`, `find_products`, `inspect`, 타일·NMS)는 그대로.

### 4.2 `api/stream.py` — 다시 쓴다

지금의 `StreamSession`, `aggregate`, 상수(`MIN_FRAMES`, `MAX_FRAMES`, `MARGIN`, `MISS_FRAMES`, `ABSENT_FRAMES`)를 지우고 아래만 둔다. GPU·I/O 없이 단위 테스트할 수 있는 순수 로직이다.

```python
REVIEW_MARGIN = 0.1  # 판정 유보 폭 (threshold 대비). api.QI_MIN_CONF가 이 값을 쓴다

def judge(products, detections, threshold) -> dict:
    """한 프레임의 inspect 메시지 본문 (result_id·overlay_url·seq·frame_size는 호출자가 붙인다)."""
    found = filter_in_products(detections, products)
    score = max((d["conf"] for d in found), default=0.0)
    decision = "NG" if score > threshold * (1 + REVIEW_MARGIN) else \
               "OK" if score < threshold * (1 - REVIEW_MARGIN) else "REVIEW"
    return {"state": "inspect", "decision": decision,
            "defect_label": found[0]["label"] if found else None,
            "score": score, "threshold": threshold, "products": products, "detections": found}

class Searching:
    """searching을 (state, reason)이 바뀔 때만 보내게 한다. 연결 직후의 첫 searching(reason 없음)을 보낸 상태에서 시작한다."""
    def __init__(self): self.last = ("searching", None)
    def no_product(self) -> list[dict]:   # 바뀌었으면 [{"state": "searching", "reason": "no_product"}], 아니면 []
    def inspected(self) -> None:          # self.last = ("inspect", None)
```

- `OK`이면 `detections`가 비어 있으므로 `defect_label`은 `None`이다 (지금 `aggregate`의 "NG·REVIEW면 비어 있지 않다" 성질 유지).
- 모듈 docstring을 새 흐름으로 다시 쓴다 (지금 docstring은 상태·조기 종료 설명이다).

### 4.3 `api/api.py`

**지운다**
- `from stream import REVIEW_MARGIN, StreamSession, aggregate` → `from stream import REVIEW_MARGIN, Searching, judge`
- `build_result`, `record_result`, `locked_inspect`(아래 `process_frame`으로 대체), `top_conf`(stream.judge가 대신), `Decision` 모델과 `POST /results/{result_id}/decision`, `Literal` import
- `/ws` 핸들러의 추론 워커(`infer`), `track`, `product_log`, `pending`/`result_done`, `send_lock`, 끊긴 뒤 기다리기(`finally`의 `asyncio.wait(... PENDING_TIMEOUT_S)`), `db.insert_pending`·`fail_result`·`check_alert` 호출
- 모듈 docstring의 `/ws`·결과 조회·로그 설명 → 새 흐름으로

**추가·변경**

```python
SAVE_INTERVAL_S = 1.0  # 결함 프레임 저장 간격 (연결마다). A4_WEBRTC_API.md §2.2

def process_frame(data: bytes) -> tuple[np.ndarray, list[dict], list[dict], dict]:
    """디코딩 → PD → (제품 있으면) QI. 스레드에서 돈다. 읽을 수 없으면 read_image의 예외가 그대로 간다.
    반환: (image, products, detections(필터 전), ms {"decode", "pd", "qi"})"""
    # pd_lock / qi_lock은 지금처럼 모델별로 따로 잡는다
```

- **`/ws` 핸들러** — 유지: `accept`, `?station=` 검증(4403/4503), 첫 `{"state": "searching"}`, `client`/`session_id`/`log_fields`, `ws_open`, `db.open_session`, `receive()`(최신 프레임 1장만, `latest_seq` 포함).
- 메인 루프 (전송은 이 루프에서만 하므로 락이 필요 없다):

```python
searching, last_saved, saves = Searching(), -inf, set()
while True:
    (최신 프레임 대기 — 지금 코드와 같은 receiver/arrived 패턴)
    try:
        image, products, detections, ms = await asyncio.to_thread(process_frame, data)
    except (OSError, Image.DecompressionBombError):
        counts["skipped_frames"] += 1; continue
    counts["processed"] += 1; (ms 누적)
    if not products:
        messages = searching.no_product()
    else:
        searching.inspected()
        message = judge(products, detections, detect.QI_CONF)
        message |= {"seq": seq, "frame_size": [w, h], "result_id": None, "overlay_url": None}
        if message["decision"] != "OK":
            counts["defect_frames"] += 1
            if time.monotonic() - last_saved >= SAVE_INTERVAL_S:
                last_saved = time.monotonic()
                rid = f"{'' if client else 'direct_'}{session_id}-{seq:06d}"
                message |= {"result_id": rid, "overlay_url": f"/results/{rid}/overlay"}
                task = asyncio.create_task(asyncio.to_thread(record_frame, rid, data, image, message, log_fields, ms))
                saves.add(task); task.add_done_callback(saves.discard)
        messages = [message]
    for m in messages: await ws.send_json(m)   # 실패하면 끊김 → 루프 종료
```

- 순서: **`inspect`를 먼저 보내고 저장은 백그라운드**로 한다. 4K 오버레이 인코딩만 약 90ms라 전송을 늦추지 않기 위해서다. 앱 규격(§4.2)에 "받자마자 요청하면 404일 수 있으니 0.5초 뒤 한 번 더"라고 적어 두었다.
- `finally`: `receiver` 취소·예외 회수(지금과 같음) → 남은 저장 태스크를 `asyncio.wait(saves, timeout=10)`로 기다림 → `db_call(db.close_session)` → `ws_close` 로그.

- **`record_frame(result_id, data, image, message, fields, ms)`** (스레드에서 실행, 예외를 밖으로 내지 않는다)
  1. 원본 바이트를 `frames/<날짜>/<result_id>.<ext>`에 그대로 쓴다 (확장자는 지금 `record_result`처럼 헤더로 판단). 실패하면 `frame_save_error`.
  2. `detect.draw(image, message["detections"])`를 JPEG(quality 90)로 `results/<날짜>/<result_id>/overlay.jpg`에 쓴다. 실패하면 `result_save_error`.
  3. `db.insert_result(...)`. 실패하면 `db_error` (where `insert_result`).
  4. `inspect_saved` 로그: `result_id`, `fields`(session/source/client/station), `seq`, `decision`, `defect_label`, `score`, `detections`, `products`(개수), `frame_size`, `threshold`, `checkpoint`, `file`, `ms`.

- **`/predict`**: QI 결과에 `filter_in_products`를 적용하고 `stream.judge`로 `decision`·`defect_label`을 계산해 응답에 **추가**한다 (기존 필드 `products`·`detections`·`score`·`threshold`·`overlay_jpg`는 유지, `detections`는 필터된 것). 로그 `predict`에도 `decision`을 넣는다. `/ws`와 같은 판정을 사진 한 장으로 확인하는 용도다.
- **`GET /results/{id}`, `GET /results/{id}/overlay`**: 유지 (db 쪽만 바뀜).
- `QI_MIN_CONF = detect.QI_CONF * (1 - REVIEW_MARGIN)`: 유지 (import 위치만 바뀜).
- 기동 예열·`startup`·`settings.update({"sync": False})`·lifespan: 그대로.

### 4.4 `api/db.py`

- **지운다**: `CONSECUTIVE_N`, `PENDING_TIMEOUT_S`, `EXPIRED`, `insert_pending`, `complete_result`, `fail_result`, `expire_pending`, `upsert_decision`, `check_alert`, `_alert_message`. `init()`의 `expire_pending()` 호출.
- **추가**:
  ```python
  def insert_result(result_id, session_id, station_id, seq, message, checkpoint, frame_path, overlay_path) -> None:
      # INSERT INTO results (result_id, session_id, station_id, attempt, status, decision, defect_label, score, scores,
      #   threshold, detections, frame_size, checkpoint, frames_dir, overlay_path, result_at)
      # VALUES (..., seq, 'done', ..., Jsonb([score]), ..., now())
  ```
  - `attempt` 컬럼에 `seq`, `frames_dir` 컬럼에 원본 파일 경로(`<날짜>/<result_id>.jpg`)를 넣는다. 컬럼 이름과 뜻이 어긋나지만 스키마를 바꾸지 않기 위해서다 — `SCHEMA` 주석에 적는다.
- **`get_result`**: 만료 `UPDATE`와 `alerts` 조회를 지운다. 응답 모양은 그대로(`status` 등 포함), `alert`는 항상 `None`.
- **`SCHEMA`**: 테이블 정의는 그대로 둔다 (`decisions`·`alerts`는 옛 데이터 보존, 새 코드는 쓰지 않음). 주석만 고친다: `results` 한 행 = 저장된 결함 프레임 하나, `status`는 항상 `done`, `alert_id`·`decisions`·`alerts`는 쓰지 않음.
- 모듈 docstring 갱신. 풀 설정(`timeout=1`, `check`)은 그대로.

### 4.5 `api/stream_client.py`

- 출력: `searching`은 지금처럼 한 줄. `inspect`는 한 줄 요약 `seq, decision, defect_label:conf …, products 개수, result_id`. OK `inspect`가 연속되면 줄이 많아지므로 `--quiet-ok` 없이도 OK는 "OK ×N"으로 묶어 찍는다 (상태가 바뀔 때 N을 출력).
- `--out`: `result_id`가 있는 `inspect`마다 `overlay_url`을 HTTP로 받아 `NNN_<decision>_<result_id>.jpg`로 저장한다. HTTP 주소는 `--url`에서 만든다 (`ws://h:p/ws?…` → `http://h:p`). 404면 0.5초 뒤 한 번 더 (백그라운드 스레드로, 수신을 막지 않게).
- 끝에 요약: 받은 `inspect` 수, decision별 개수, 저장 수.
- docstring의 판정 설명(`OK / NG / REVIEW` 파일명)을 맞춘다.

### 4.6 `gateway/gateway.py`

- 필수 변경 없음: `/ws` 메시지를 그대로 넘기고, 첫 메시지 확인·장애 시 닫기·`/results/*` 프록시는 그대로 맞다.
- `relay_messages`의 `if message["state"] == "result": message.pop("overlay_jpg", None)`은 새 규격에서 해당 메시지가 없으므로 지운다 (주석·docstring의 `result`/`overlay_jpg` 설명도).
- `POST /results/{result_id}/decision` 프록시 라우트를 지운다 (A4 API에 없음). docstring 3번째 줄 경로 목록도.
- **로그 양** (권장): 지금은 모든 메시지를 `a4_message`로 남긴다. `inspect`가 초당 최대 10개라 기기 1대가 한 시간 비추면 약 36,000줄이다. `decision`이 OK인 `inspect`는 파일 로그에 남기지 않고, 연결마다 개수만 세어 연결 종료 때 한 줄(`relay_summary`: inspect 수, OK/REVIEW/NG 수, 데이터 채널 닫혀 못 보낸 수)로 남긴다. `searching`과 OK가 아닌 `inspect`는 지금처럼 남긴다.

### 4.7 `gateway/webrtc_client.py`

- `inspect` 출력·`--out` 저장을 `stream_client.py`와 같은 방식으로 (`overlay_url`은 `--gateway` 주소로 받음, 404면 0.5초 뒤 한 번 더).
- `--stop-after-captured`와 `poll_result`, `captured` 관련 코드를 지운다 (해당 메시지 없음).
- 끝에 요약 (받은 `inspect` 수, decision별, 저장 수).

---

## 5. 설정값 (변경 후)

| 파일 | 이름 | 값 | 비고 |
|---|---|---|---|
| `api/detect.py` | `QI_CONF`, `PD_CONF`, `TILE`, `STEP`, `NMS_IOU`, `DEVICE`, 모델 경로 | 그대로 | |
| `api/stream.py` | `REVIEW_MARGIN` | 0.1 | 남는 유일한 상수 |
| `api/api.py` | `SAVE_INTERVAL_S` | 1.0 | **새로 추가** (D3) |
| `gateway/gateway.py` | `FORWARD_FPS` | 10 | 그대로 |
| 환경변수 | `CONSECUTIVE_N` | — | **없어짐** (지금 docker run에서 넘기지 않으므로 명령은 바뀌지 않는다) |

---

## 6. 로그 (변경 후, `logs/a4-api/<날짜>.jsonl`)

| event | 변경 | 필드 |
|---|---|---|
| `startup`, `db_ready`, `db_error`, `ws_rejected`, `ws_open` | 그대로 | |
| `predict` | `decision` 추가 | |
| `inspect_saved` | **새로** | result_id, session, source, client, station, seq, decision, defect_label, score, detections, products(개수), frame_size, threshold, checkpoint, file, ms(decode/pd/qi) |
| `frame_save_error`, `result_save_error` | 그대로 | |
| `ws_close` | 필드 변경 | session, duration_s, frames(받은 수 = 마지막 seq), processed, skipped_frames, product_frames, defect_frames, saved, ms_avg(decode/pd/qi — §11의 분모), error |
| `product_reset`, `ws_frame`, `ws_result`, `result_timeout`, `result_late`, `decision` | **없어짐** | |

`ws_close.processed / duration_s`가 실제 처리 속도(fps)다. 10에 못 미치면 처리가 밀려 프레임을 건너뛴 것이다 (`frames - processed - skipped_frames` — `skipped_frames`는 깨진 프레임이다, §11).

---

## 7. 검증

### 7.1 작성 환경 (GPU 없이)

지금까지처럼 PD·QI를 가짜 모델로 바꿔 실제 `api.py`·`stream.py`·`detect.py`·`db.py`를 돌린다 (`DEPLOY.md` §8 방식). 가짜 모델 예: 프레임의 특정 색 영역을 PD 상자로, 다른 색 점을 QI 결함으로 돌려주는 객체 (`predict()`가 Ultralytics 결과처럼 `.boxes.xyxy/.cls/.conf`를 가짐, `names` 속성).

| 확인 | 기대 |
|---|---|
| `stream.judge` | 결함 없음 → OK·`defect_label` None / 최대 conf 0.24 → REVIEW / 0.30 → NG (T=0.25) |
| `filter_in_products` | 중심이 상자 안 → 남음, 밖 → 버림, 제품 2개 중 하나 안 → 남음, 순서 유지 |
| `Searching` | 첫 no_product → 1개, 연속 no_product → 0개, inspect 뒤 no_product → 1개 |
| `draw` 바깥 테두리 | 4K 실측 상자(§4.1)에서 상자 안 픽셀 = 원본 |
| `/ws` 첫 메시지 | `{"state": "searching"}` (reason 없음) |
| `/ws` 제품 프레임 | 프레임마다 `inspect`, 필드가 `A4_WEBRTC_API.md` §3.3 표와 정확히 같음 (`overlay_jpg` 없음) |
| 배경 결함 | 제품 상자 밖 결함은 `detections`에 없음 |
| 저장 간격 | 0.1초 간격 결함 프레임 3초 → 저장 3~4장, 나머지 `result_id` null |
| 저장물 | `frames/<날짜>/<rid>.jpg` = 보낸 바이트, `results/<날짜>/<rid>/overlay.jpg`, DB 행(status done, scores [score], attempt seq) |
| 조회 | `GET /results/<rid>` 200·`alert` null, `/overlay` 200 `image/jpeg`, `/decision` POST 404 |
| 끊김 | 저장 중 끊겨도 저장 완료, `ws_close`의 saved와 파일 수 일치 |
| 깨진 프레임 | 건너뜀, 세션 유지, `skipped_frames` 증가 |
| 스테이션 | 미등록·빈 값 4403, DB 장애 4503 (기존 동작 유지) |
| `/predict` | `decision`·`defect_label` 추가, `detections` 필터됨, 400·422 그대로 |
| 게이트웨이 | 가짜 aiortc 또는 실제 `webrtc_client.py`로 `inspect`가 데이터 채널까지 그대로 감, decision 라우트 404 |

### 7.2 실서버 (gpudev) — `DEPLOY.md` §5 ⑧을 새로 쓴다

옛 ⑧에서 **없앨 것**: `captured 전 종료`, `시간 초과·늦은 결과`, `연속 판정 알림 규칙`, `실시간 alert`, 최종 판정 3줄(200/200/422), `--stop-after-captured`.
**유지할 것**: `/predict` 사진·속도·예외(400/422)·동시 요청, `/ws` 깨진 프레임, 스테이션 검증, 게이트웨이 422/403/503, 테스트 데이터·파일 정리. (파일명 공백 대비 따옴표는 지금 문서대로.)
**새로 넣을 것**:

```bash
# /ws 사진 — 사진마다 10장(제품) + 검은 30장. 기대: searching → inspect(seq…) → searching …
docker run --rm --network host -v $PWD/samples:/samples:ro -v $PWD/stream_out/photos:/out \
  a4-api sh -c 'ls /samples/*.jpg | head -3 | tr "\n" "\0" | xargs -0 python stream_client.py --out /out'
#   기대: 같은 사진 10장이라 inspect 10개가 같은 판정. 결함 사진은 1초에 1장꼴로 result_id (사진 10장 = 1초 → 1~2장 저장)
grep inspect_saved logs/a4-api/$D.jsonl | tail -n 3
ls frames/$D/ results/$D/
RID=$(grep inspect_saved logs/a4-api/$D.jsonl | tail -n 1 | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['result_id'])"); echo $RID
curl -s localhost:8000/results/$RID; echo                                          # status done, alert null
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" localhost:8000/results/$RID/overlay   # 200 image/jpeg
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"final": "OK", "method": "nod"}' localhost:8000/results/$RID/decision   # 404
# 오버레이를 열어 작은 결함이 빨간 테두리에 가려지지 않았는지 눈으로 확인 (D7)

# 처리 속도 — 영상을 /ws로 보낸 뒤 ws_close의 processed/duration_s·ms_avg
docker run --rm --network host -v $PWD/videos:/videos:ro a4-api sh -c 'python stream_client.py /videos/*'
grep '"event": "ws_close"' logs/a4-api/$D.jsonl | tail -n 1
#   기록: 해상도별(4K 테스트 영상, 가능하면 1080p 영상) fps와 ms_avg. 1080p에서 10fps 미만이면 §9 R1

# 게이트웨이 경유 — inspect가 데이터 채널로 오는지, 키 확인
for v in videos/*; do docker run --rm --network host -v $PWD/videos:/videos:ro -v $PWD/webrtc_out:/out \
  a4-gateway python webrtc_client.py "/videos/${v##*/}" --station ST-01 --out "/out/${v##*/}"; done
grep '"a4_message"' logs/a4-gateway/$D.jsonl | python3 -c "
import sys, json
m = [json.loads(l)['message'] for l in sys.stdin if json.loads(l)['message']['state'] == 'inspect']
print(sorted(m[-1]))"
#   기대: ['decision', 'defect_label', 'detections', 'frame_size', 'overlay_url', 'products', 'result_id', 'score', 'seq', 'state', 'threshold']
```

---

## 8. 배포 (gpudev)

1. 코드 교체 전 보관: `mkdir -p archive_product_mode && cp -a api gateway DEPLOY.md A4_WEBRTC_API.md archive_product_mode/`.
2. 새 코드·문서 복사, `docker build -t a4-api api/ --no-cache`, `docker build -t a4-gateway gateway/ --no-cache` (게이트웨이도 `gateway.py`·`webrtc_client.py`가 바뀌므로 함께).
3. DB 덤프: `docker exec a4-db pg_dump -U a4 a4 > archive_product_mode/a4_db.sql`.
4. 옛 `pending` 행 정리 (새 코드에는 만료 처리가 없다): `docker exec a4-db psql -U a4 -d a4 -c "UPDATE results SET status = 'failed' WHERE status = 'pending';"`
5. `docker rm -f a4-gateway a4-api` → `DEPLOY.md` §6.1 순서로 비밀번호를 읽고 §5 ⑤의 `docker run`(바뀌지 않음) → `startup`·`db_ready` 확인 → §5 ⑦로 게이트웨이. `a4-db`는 그대로 둔다.
6. §7.2 확인.
7. 문서 고치기:
   - `A4_WEBRTC_API.md` 머리말 "서버는 아직 이 규격으로 구현되지 않았다" → 배포 날짜와 확인 결과로. §3.4의 지연 "측정 전 추정"을 실측이 있으면 바꾼다.
   - `DEPLOY.md`: 머리말 기준일 · §1 구성(설명 문구) · §3 판정 규칙(3.3·3.4를 프레임 단위로, 3.5 설정값 표) · §4.1 로그 표 · §4.2 저장 경로(`frames/<날짜>/<result_id>.jpg`) · §4.3 DB(행의 뜻, `decisions`·`alerts` 미사용, 예시 쿼리에서 최종 판정 쿼리 삭제) · §5 ⑧(§7.2) · §8(확인한 것·제약).
   - `gateway/README.md`: 메시지 목록, `result` 이미지 처리 문단, 테스트 클라이언트 옵션(`--stop-after-captured` 삭제), 로그 표(`relay_summary`).
8. 앱 개발자에게 배포를 알린다 (개발 서버가 새 규격으로 바뀜).

되돌리기: `archive_product_mode/`의 `api`·`gateway`로 다시 빌드해 같은 명령으로 띄운다. DB는 새 행이 더해지기만 하므로 그대로 둬도 된다.

---

## 9. 위험과 확인할 것

| # | 내용 | 대응 |
|---|---|---|
| R1 | **처리 속도.** 프레임마다 디코딩+PD+QI가 직렬이다. 4K 실측 합계 약 175ms(decode 100 + PD 10 + QI 63) → 약 5.7fps. 1080p는 측정 전 | §7.2에서 `ms_avg`로 측정. 1080p에서 10fps 미만이면 (a) 다음 프레임 PD를 이전 프레임 QI와 겹쳐 돌리기(파이프라인), (b) QI를 PD 상자와 겹치는 타일에만 돌리기 순으로 검토 |
| R2 | **지연·밀린 메시지.** 데이터 채널은 순서 보장·재전송이라 망이 느리면 오래된 `inspect`가 쌓여 늦게 도착할 수 있다 | 실기기에서 확인. 필요하면 게이트웨이가 `channel.bufferedAmount`가 클 때 OK `inspect`를 버린다 |
| R3 | **저장량.** 결함이 계속 보이면 연결마다 초당 원본 1장 + 오버레이 1장 (1080p 약 0.3~0.6MB씩). 가모델 오탐이 많아 실제로 계속 저장될 수 있다 | `du -sh frames results` 관찰. 필요하면 `SAVE_INTERVAL_S`를 늘린다 |
| R4 | **오탐이 화면에 그대로 보인다.** 가모델은 결함 없는 사진 24장 중 14장에서 검출 (`DEPLOY.md` §8). 제품 상자 제한(D6)으로 배경 오탐은 줄지만 제품 위 오탐은 남는다 | 현장 반응을 보고 `QI_CONF` 조정 또는 재학습 |
| R5 | 제품 상자 제한(D6)이 제품 가장자리 결함을 버릴 수 있다 (PD 상자가 제품을 덜 덮으면) | 실제 영상의 `products`와 버려진 결함을 비교해 보고 필요하면 "중심" 대신 "겹침"으로 바꾼다 |
| R6 | 게이트웨이 로그 양 (§4.6) | OK `inspect` 요약 로그로 줄인다 |

## 10. 하지 않는 것

- 제품 추적(같은 제품을 프레임 사이에서 묶기), 제품 단위 판정·기록
- 사람의 최종 판정, 연속 판정 알림 (D4 — 현장 사용 후 다시 정한다)
- 앱 쪽에서 PD 직접 실행
- PD 크기 하한 (D5)
- DB 스키마 변경·테이블 삭제

---

## 11. 구현 결과 (2026-11-03)

계획 §4대로 구현했다. 계획에 없던 것·계획과 다르게 한 것만 적는다. 검증은 §7.1 표 전부 (작성 환경, 대역 + 실제 PostgreSQL 16, 125개 확인 통과 — `DEPLOY.md` §8).

| # | 항목 | 계획 | 구현 | 이유 |
|---|---|---|---|---|
| I1 | `ws_close.saved` | 뜻이 정해지지 않음 | **저장을 시작한 수** (= `result_id`를 보낸 수) | §7.1 "끊김" 확인(`saved` = 파일 수)이 잘린 저장을 잡아내려면 시도 수여야 한다. 실패는 `frame_save_error` 등으로 따로 남는다 (Prometheus의 "시도 + 실패" 관례) |
| I2 | `ms_avg` 분모 | "처리한 프레임 평균" | decode·pd는 `processed`, **qi는 `product_frames`** | QI는 제품 프레임에서만 돈다. `processed`로 나누면 QI 시간이 작게 보여 R1 판단을 그르친다 (WebRTC `totalDecodeTime / framesDecoded`, Triton·Frigate와 같은 원칙). 값은 타일이 아닌 프레임 한 장 기준 |
| I3 | 밀린 수 | `frames - processed` | `frames - processed - skipped_frames` | `skipped_frames`(깨진 프레임)도 `processed`에 안 들어간다. 필드 이름은 이전 로그·확인 절차와 맞추려고 그대로 두고 문서에 뜻을 적었다 |
| I4 | `product_frames`·`saved` 증가 위치 | 의사코드에 없음 | 제품 분기 / `result_id`를 붙이는 곳 | — |
| I5 | 빈 `saves` | `asyncio.wait(saves, timeout=10)` | `if saves:`로 감쌈 | `asyncio.wait`는 빈 집합에 `ValueError` — 저장이 없는 연결이 끊길 때마다 `ws_close`가 안 남는다 |
| I6 | 전송 실패 | "실패하면 끊김 → 루프 종료" | `send_json`을 `try`로 잡아 `break` | 그대로 두면 정상적인 끊김이 핸들러 오류로 기록된다 |
| I7 | `/predict` 필터 | `filter_in_products` 적용 후 `judge` | `judge`만 부르고 그 `detections`를 응답·오버레이에 쓴다 | `judge`가 안에서 필터한다 (두 번 할 이유가 없다) |
| I8 | `draw` 글자 위치 | `origin = (x1 - width, max(0, y1 - width - size - width))` | 글자 상자(`textbbox`)의 실제 높이로 테두리 위에 맞추고, 위에 자리가 없으면 상자 아래 | 한글 폰트(CJK)는 글자 상자가 `size`보다 커서 계획식으로는 4K 실측 상자 210픽셀 중 45픽셀을 글자 배경이 덮었다. 프레임 위 끝에서는 `max(0, …)`가 글자를 상자 위에 얹었다 |
| I9 | 게이트웨이 `relay_summary` | 연결 종료 때 한 줄 | `forward`의 `finally`에서 남긴다 | `relay_messages`는 `forward`가 취소하므로 그 안의 루프 뒤에서는 실행되지 않는다. `undelivered`는 모든 메시지 기준 |
| I10 | `webrtc_client.py --linger` | 언급 없음 | 기본값 12 → **2초** | 12초는 "최대 10장 추론" 기준이었다. 프레임 단위는 마지막 프레임 하나만 기다리면 된다 |
| I11 | 테스트 클라이언트 출력 | OK 묶기, 요약 | `Report` 클래스 (두 클라이언트에 같은 코드) | 두 파일은 서로 다른 이미지에 들어가 공유 모듈을 두지 않았다 |

**배포 순서 (`DEPLOY.md` §5.A)**: §8의 4(옛 `pending` 정리)를 `docker rm -f a4-gateway a4-api` **뒤로** 옮겼다 — 옛 API가 살아 있는 동안 정리하면 그 사이에 새 `pending`이 생길 수 있다.
