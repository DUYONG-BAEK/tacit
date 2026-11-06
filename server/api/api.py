"""불량 판정 API — PD(제품 탐지)가 제품을 찾은 프레임마다 QI(불량 탐지)로 판정한다.

실행: uvicorn api:app --host 0.0.0.0 --port 8000
문서: http://localhost:8000/docs

POST /predict (multipart, field "file") -> 사진 한 장 확인·테스트용 (result_id·DB 기록 없음). /ws와 같은 판정을 낸다.
    {
      "products": PD 상자 [{label, conf, box}], "detections": 제품 상자 안의 QI 결함 [{label, conf, box}],
      "score": 결함 최대 conf (없으면 0), "threshold": QI conf 기준,
      "decision": OK/NG/REVIEW, "defect_label": 가장 확실한 결함 (OK면 null),
      "overlay_jpg": base64 JPEG (결함 상자를 그린 사진)
    }
좌표는 사진 픽셀 좌표(EXIF 회전 반영)다.
이미지를 읽지 못하면(형식 오류·잘린 파일·해상도 초과) 400, PD가 제품을 찾지 못하면 422.

WebSocket /ws — 앱이 프레임(JPEG 등 이미지 바이너리)을 계속 보내면, 가장 최근 프레임 하나씩 PD → (제품이 있으면) QI로
판정해 바로 보낸다 (A4_WEBRTC_API.md §3). 서버 -> 앱은 JSON:
    {"state": "searching"}                          연결 직후 한 번 (게이트웨이가 연결 확인으로 쓴다)
    {"state": "searching", "reason": "no_product"}  제품이 안 보임. (state, reason)이 바뀔 때만
    {"state": "inspect", "seq", "frame_size", "decision", "defect_label", "score", "threshold",
     "products", "detections", "result_id", "overlay_url"}   제품이 보인 프레임마다
판정 규칙은 stream.py. 결함 프레임(NG·REVIEW)은 연결마다 SAVE_INTERVAL_S에 한 장까지 저장하고, 저장한 프레임의
inspect에만 result_id·overlay_url이 붙는다 (나머지는 null). inspect를 먼저 보내고 저장은 뒤에서 한다.
읽을 수 없는 프레임은 메시지 없이 건너뛴다. 프레임 해상도는 정하지 않는다.
/ws?client=<게이트웨이 연결 ID> — 게이트웨이를 거친 연결. 없으면 직접 연결(direct)로 기록한다.
/ws?station=<station_id> — 값이 있으면(빈 값 포함) DB stations에 등록됐는지 검증한다. 미등록이면 close 4403,
DB 장애면 4503.

결과 조회 (DEPLOY.md §4.3, 게이트웨이가 같은 경로로 넘겨준다):
    GET  /results/<result_id>               저장된 결함 프레임의 판정. 없으면 404
    GET  /results/<result_id>/overlay       오버레이 이미지 (results/<날짜>/<result_id>/overlay.jpg)

로그 (DEPLOY.md §4.1): 요청·결과를 logs/<날짜>.jsonl에 한 줄씩 남긴다 (stdout에도 같은 줄).
날짜가 바뀌면 새 파일에 쓰고, 지우지 않는다. 저장한 결함 프레임은 받은 바이트 그대로
frames/<날짜>/<result_id>.<확장자>에 쓴다.
"""

import asyncio
import base64
import datetime
import io
import json
import threading
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
from fastapi import FastAPI, File, HTTPException, Request, UploadFile, WebSocket
from fastapi.responses import FileResponse
from PIL import Image
from ultralytics import YOLO, settings

import db
import detect
from stream import REVIEW_MARGIN, Searching, judge

# Ultralytics는 기본값으로 사용 통계(추론 포함)를 외부로 보낸다 — 판정 서버에서는 끈다 (DEPLOY.md §7).
settings.update({"sync": False})
pd_model = YOLO(str(detect.PD_MODEL))
qi_model = YOLO(str(detect.QI_MODEL))
# QI 추론 하한. 이보다 낮은 결함은 점수를 QI_CONF × (1 - REVIEW_MARGIN) 아래로 두어 OK 판정을 바꾸지
# 않으므로 받지 않는다 — 기준값은 detect.QI_CONF 하나다.
QI_MIN_CONF = detect.QI_CONF * (1 - REVIEW_MARGIN)
# Ultralytics 모델은 스레드 안전하지 않다 — 모델마다 한 번에 한 호출만. 락을 모델별로 둬서
# 한 연결의 QI가 도는 동안 다른 연결·/predict의 PD는 돌 수 있다.
pd_lock = threading.Lock()
qi_lock = threading.Lock()

SAVE_INTERVAL_S = 1.0  # 결함 프레임 저장 간격 (연결마다). A4_WEBRTC_API.md §2.2

LOG_DIR = Path("logs")
FRAME_DIR = Path("frames")
RESULT_DIR = Path("results")  # 결과 오버레이 (results/<날짜>/<result_id>/overlay.jpg)
# 로그·프레임 폴더의 날짜와 시각은 컨테이너 시간대(Dockerfile의 TZ=Asia/Seoul)를 따른다.
log_lock = threading.Lock()  # /predict는 스레드풀에서, /ws는 이벤트 루프와 스레드에서 쓴다


def model_id(path: Path) -> str:
    return f"{path.name}@{datetime.datetime.fromtimestamp(path.stat().st_mtime):%Y-%m-%dT%H:%M:%S}"


# 결과마다 남겨, 모델이 바뀌어도 어느 모델의 판정인지 구분한다 (DB results.checkpoint).
MODEL_ID = f"{model_id(detect.PD_MODEL)}|{model_id(detect.QI_MODEL)}"


def log_event(event: str, **fields) -> None:
    """이벤트 한 줄(JSON)을 stdout과 logs/<날짜>.jsonl에 남긴다. 파일에 못 써도 서비스는 계속한다."""
    now = datetime.datetime.now().astimezone()
    line = json.dumps({"ts": now.isoformat(timespec="milliseconds"), "event": event, **fields}, ensure_ascii=False)
    print(line, flush=True)
    try:
        with log_lock:
            LOG_DIR.mkdir(exist_ok=True)
            with (LOG_DIR / f"{now:%Y-%m-%d}.jsonl").open("a", encoding="utf-8") as f:
                f.write(line + "\n")
    except OSError as e:  # 디스크 부족 등
        print(f"[log] 파일 기록 실패: {e}", flush=True)


# 예열: 기동 시 한 번씩 돌려 GPU로 올리고 CUDA를 초기화한다. 첫 프레임이 몇 초 늦어지지 않게 하고,
# GPU가 없으면(--gpus 누락) 요청을 받기 전에 여기서 멈추게 한다 (detect.DEVICE).
_blank = np.zeros((detect.TILE, detect.TILE, 3), np.uint8)
detect.find_products(pd_model, _blank)
detect.inspect(qi_model, _blank, detect.QI_CONF)

log_event("startup", checkpoint=MODEL_ID, pd_classes=list(pd_model.names.values()),
          qi_classes=list(qi_model.names.values()), pd_conf=detect.PD_CONF, qi_conf=detect.QI_CONF)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # DB 접속은 import가 아니라 기동 시점에 한다 (DATABASE_URL은 db.init()에서만 읽는다).
    # 접속에 실패하면 기동이 멈추고 컨테이너의 --restart가 다시 시도한다.
    await asyncio.to_thread(db.init)
    log_event("db_ready")
    yield
    db.close()


app = FastAPI(title="Product defect inspection", lifespan=lifespan)


def to_base64(image: Image.Image, fmt: str) -> str:
    buffer = io.BytesIO()
    # 오버레이는 보기용이라 JPEG
    image.save(buffer, format=fmt, **({"quality": 90} if fmt == "JPEG" else {}))
    return base64.b64encode(buffer.getvalue()).decode()


def ms(start: float, end: float) -> int:
    return round((end - start) * 1000)


@app.post("/predict")
def predict_endpoint(request: Request, file: UploadFile = File(...)) -> dict:
    # async가 아닌 def: FastAPI가 스레드풀에서 돌려 추론이 이벤트 루프를 막지 않는다.
    t0 = time.monotonic()
    data = file.file.read()
    req = {"req": uuid.uuid4().hex[:8], "client": request.client.host if request.client else None,
           "filename": file.filename, "bytes": len(data)}
    try:
        image = detect.read_image(data)
    except OSError as e:  # UnidentifiedImageError와 잘린 파일 모두
        log_event("predict", **req, status=400, error="unreadable_image")
        raise HTTPException(status_code=400, detail="이미지를 읽을 수 없습니다 (이미지가 아니거나 잘린 파일).") from e
    except Image.DecompressionBombError as e:  # OSError가 아니다 — 픽셀 수가 Pillow 한도의 2배를 넘음
        log_event("predict", **req, status=400, error="too_large")
        raise HTTPException(status_code=400, detail="이미지 해상도가 너무 큽니다.") from e
    req["image_size"] = [image.shape[1], image.shape[0]]

    t1 = time.monotonic()
    with pd_lock:
        products = detect.find_products(pd_model, image)
    t2 = time.monotonic()
    if not products:
        log_event("predict", **req, status=422, error="product_not_found", ms={"decode": ms(t0, t1), "pd": ms(t1, t2)})
        raise HTTPException(status_code=422, detail="제품을 찾지 못했습니다.")
    with qi_lock:
        detections = detect.inspect(qi_model, image, QI_MIN_CONF)
    t3 = time.monotonic()
    verdict = judge(products, detections, detect.QI_CONF)  # 제품 상자 밖의 결함은 여기서 빠진다
    response = {
        "products": products,
        "detections": verdict["detections"],
        "score": verdict["score"],
        "threshold": detect.QI_CONF,
        "decision": verdict["decision"],
        "defect_label": verdict["defect_label"],
        "overlay_jpg": to_base64(detect.draw(image, verdict["detections"]), "JPEG"),
    }
    log_event("predict", **req, status=200, products=len(products), defects=len(verdict["detections"]),
              score=response["score"], decision=response["decision"], threshold=detect.QI_CONF, checkpoint=MODEL_ID,
              ms={"decode": ms(t0, t1), "pd": ms(t1, t2), "qi": ms(t2, t3), "encode": ms(t3, time.monotonic())})
    return response


def process_frame(data: bytes) -> tuple[np.ndarray, list[dict], list[dict], dict]:
    """/ws 프레임 하나: 디코딩 → PD → (제품이 있으면) QI. 스레드에서 돈다.
    반환: (BGR 프레임, PD 상자, QI 결함(제품 상자 필터 전), ms {"decode", "pd"[, "qi"]}).
    "qi"는 QI를 돌렸을 때만 있다. 읽을 수 없는 프레임은 read_image의 예외가 그대로 간다.
    """
    t0 = time.monotonic()
    image = detect.read_image(data)
    t1 = time.monotonic()
    with pd_lock:
        products = detect.find_products(pd_model, image)
    t2 = time.monotonic()
    timing = {"decode": ms(t0, t1), "pd": ms(t1, t2)}
    detections = []
    if products:
        with qi_lock:
            detections = detect.inspect(qi_model, image, QI_MIN_CONF)
        timing["qi"] = ms(t2, time.monotonic())
    return image, products, detections, timing


def record_frame(result_id: str, data: bytes, image: np.ndarray, message: dict, fields: dict, timing: dict) -> None:
    """저장하기로 한 결함 프레임 하나를 기록한다. 스레드에서 돌고, 예외를 밖으로 내지 않는다.
    1. 원본을 받은 바이트 그대로 frames/<날짜>/<result_id>.<확장자>   2. 오버레이 results/<날짜>/<result_id>/overlay.jpg
    3. DB 행 (done)   4. inspect_saved 로그.
    DB 행은 파일을 쓴 뒤에 넣는다 — 조회(/overlay)는 DB의 overlay_path를 보므로, 행이 보이면 파일도 있다.
    """
    day = f"{datetime.datetime.now():%Y-%m-%d}"
    file = None
    try:
        with Image.open(io.BytesIO(data)) as header:  # 헤더만 읽는다
            fmt = header.format
        name = f"{result_id}.{'jpg' if fmt == 'JPEG' else fmt.lower()}"
        (FRAME_DIR / day).mkdir(parents=True, exist_ok=True)
        (FRAME_DIR / day / name).write_bytes(data)
        file = f"{day}/{name}"
    except OSError as e:  # 디스크 부족 등 — 로그는 남기고 판정 서비스는 계속한다
        log_event("frame_save_error", result_id=result_id, error=str(e))
    out = RESULT_DIR / day / result_id
    overlay_path = None
    try:
        out.mkdir(parents=True, exist_ok=True)
        detect.draw(image, message["detections"]).save(out / "overlay.jpg", format="JPEG", quality=90)
        overlay_path = str(out / "overlay.jpg")
    except Exception as e:  # 디스크 부족 등 — 판정은 남기고 이미지 조회만 404가 된다
        log_event("result_save_error", result_id=result_id, error=f"{type(e).__name__}: {e}")
    try:
        db.insert_result(result_id, fields["session"], fields["station"], message["seq"], message, MODEL_ID,
                         file, overlay_path)
    except Exception as e:
        log_event("db_error", where="insert_result", error=f"{type(e).__name__}: {e}")
    log_event("inspect_saved", result_id=result_id, **fields, seq=message["seq"], decision=message["decision"],
              defect_label=message["defect_label"], score=message["score"], detections=message["detections"],
              products=len(message["products"]), frame_size=message["frame_size"], threshold=message["threshold"],
              checkpoint=MODEL_ID, file=file, ms=timing)


async def db_call(fn, *args):
    """/ws 안의 DB 호출. 실패해도 판정은 계속한다 — db_error만 남기고 None (DEPLOY.md §4.3)."""
    try:
        return await asyncio.to_thread(fn, *args)
    except Exception as e:
        log_event("db_error", where=fn.__name__, error=f"{type(e).__name__}: {e}")
        return None


@app.get("/results/{result_id}")
def result_endpoint(result_id: str) -> dict:
    result = db.get_result(result_id)
    if result is None:
        raise HTTPException(status_code=404, detail="결과가 없습니다.")
    return result


@app.get("/results/{result_id}/overlay")
def result_overlay_endpoint(result_id: str) -> FileResponse:
    path = db.overlay_path(result_id)
    if not path or not Path(path).is_file():  # 결과 없음(아직 저장 중 포함), 저장 실패
        raise HTTPException(status_code=404, detail="이미지가 없습니다.")
    return FileResponse(path, media_type="image/jpeg")


@app.websocket("/ws")
async def stream_endpoint(ws: WebSocket) -> None:
    await ws.accept()
    # ?station=: 값이 있으면 빈 값이어도 검증한다 — 빈 값을 "생략"으로 보면 앱의 설정 오류가 조용히 넘어간다.
    # accept 후 close 코드로 거부해야 게이트웨이가 미등록(4403)과 DB 장애(4503)를 구분한다.
    station = ws.query_params.get("station")
    if station is not None:
        try:
            registered = await asyncio.to_thread(db.station_exists, station)
        except Exception as e:
            log_event("db_error", where="station_exists", error=f"{type(e).__name__}: {e}")
            await ws.close(code=4503)
            return
        if not registered:
            log_event("ws_rejected", station=station, reason="unregistered")
            await ws.close(code=4403)
            return
    await ws.send_json({"state": "searching"})  # 게이트웨이는 이 첫 메시지를 연결 확인으로 쓴다

    # 로그: 게이트웨이는 ?client=<연결 ID>를 붙인다. 없으면 직접 연결 — result_id에 direct_를 붙인다.
    # 세션 ID는 result_id(DB 기본키)에 들어가므로 12자리 — 6자리는 세션 약 5,000개에서 충돌 확률 50%다.
    client = ws.query_params.get("client")
    session_id = uuid.uuid4().hex[:12]
    log_fields = {"session": session_id, "source": "gateway" if client else "direct", "client": client,
                  "station": station}
    opened = time.monotonic()
    # ws_close 집계. frames(받은 수) = processed + skipped_frames(깨진 프레임) + 처리가 밀려 건너뛴 수.
    # saved는 저장을 시작한 수(result_id를 보낸 수)다 — 저장 실패는 frame_save_error 등으로 따로 남는다.
    counts = {"processed": 0, "skipped_frames": 0, "product_frames": 0, "defect_frames": 0, "saved": 0}
    totals = {"decode": 0, "pd": 0, "qi": 0}  # ms 합계. decode·pd는 processed, qi는 product_frames로 나눈다
    log_event("ws_open", **log_fields, peer=ws.client.host if ws.client else None)
    await db_call(db.open_session, session_id, station, log_fields["source"], client)

    searching = Searching()
    last_saved = float("-inf")
    saves: set[asyncio.Task] = set()  # 진행 중인 저장 — 끊긴 뒤에도 끝날 때까지 기다린다

    # 수신은 따로 돌리고 가장 최근 프레임 하나만 남긴다. 처리가 프레임 속도보다 느려도
    # 화면과 판정 사이가 벌어지지 않게 하기 위해서다.
    latest: bytes | None = None
    latest_seq = 0  # 받은 순번 (건너뛴 프레임 포함) — inspect의 seq
    arrived = asyncio.Event()

    async def receive() -> None:
        nonlocal latest, latest_seq
        while True:
            latest = await ws.receive_bytes()
            latest_seq += 1
            arrived.set()

    receiver = asyncio.create_task(receive())
    error = None
    try:
        while True:
            waiter = asyncio.create_task(arrived.wait())
            await asyncio.wait({receiver, waiter}, return_when=asyncio.FIRST_COMPLETED)
            waiter.cancel()
            if receiver.done():  # 앱이 연결을 끊음
                break
            arrived.clear()
            data, latest = latest, None
            seq = latest_seq
            # 디코딩·PD·QI는 스레드에서 — 이벤트 루프(수신)를 막지 않는다
            try:
                image, products, detections, timing = await asyncio.to_thread(process_frame, data)
            except (OSError, Image.DecompressionBombError):  # 이미지가 아니거나 잘렸거나 너무 큰 프레임 — 이 프레임만 건너뛴다
                counts["skipped_frames"] += 1
                continue
            counts["processed"] += 1
            for key, value in timing.items():
                totals[key] += value
            if not products:
                messages = searching.no_product()
            else:
                counts["product_frames"] += 1
                searching.inspected()
                h, w = image.shape[:2]
                message = judge(products, detections, detect.QI_CONF) | {
                    "seq": seq, "frame_size": [w, h], "result_id": None, "overlay_url": None}
                if message["decision"] != "OK":
                    counts["defect_frames"] += 1
                    now = time.monotonic()
                    if now - last_saved >= SAVE_INTERVAL_S:
                        last_saved = now
                        result_id = f"{'' if client else 'direct_'}{session_id}-{seq:06d}"
                        message |= {"result_id": result_id, "overlay_url": f"/results/{result_id}/overlay"}
                        counts["saved"] += 1
                        # inspect를 먼저 보내고 저장은 뒤에서 한다 — 오버레이 인코딩(4K 약 90ms)이 전송을 늦추지 않게.
                        # 이 뒤로 message를 바꾸지 않는다 (저장 스레드와 전송이 같은 dict를 읽는다)
                        task = asyncio.create_task(asyncio.to_thread(
                            record_frame, result_id, data, image, message, log_fields, timing))
                        saves.add(task)
                        task.add_done_callback(saves.discard)
                messages = [message]
            try:
                for message in messages:
                    await ws.send_json(message)
            except Exception:  # 앱이 끊음 — 수신 쪽도 곧 끝난다
                break
    except Exception as e:  # 추론 오류 등 — ws_close에 남기고 그대로 올린다
        error = e
        raise
    finally:
        if not receiver.done():
            receiver.cancel()
        elif not receiver.cancelled():
            receiver.exception()  # 연결 종료 예외를 회수 (미회수 경고 방지)
        if saves:  # 끊겨도 진행 중인 저장은 끝낸다. asyncio.wait는 빈 집합을 받지 않는다
            await asyncio.wait(saves, timeout=10)
        await db_call(db.close_session, session_id)
        processed, product_frames = counts["processed"], counts["product_frames"]
        ms_avg = {"decode": round(totals["decode"] / processed) if processed else None,
                  "pd": round(totals["pd"] / processed) if processed else None,
                  "qi": round(totals["qi"] / product_frames) if product_frames else None}
        log_event("ws_close", **log_fields, duration_s=round(time.monotonic() - opened, 1), frames=latest_seq,
                  **counts, ms_avg=ms_avg, error=f"{type(error).__name__}: {error}" if error else None)
