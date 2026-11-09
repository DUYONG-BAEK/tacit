"""WebRTC 수신 게이트웨이 — 앱이 보낸 WebRTC 영상을 받아 디코딩한 원본 프레임(I420)을 A4 API의 /ws에 넘기고,
/ws가 보낸 메시지를 앱의 데이터 채널로 돌려준다. 분석은 하지 않는다 (A4 API가 전부 한다).

    앱 ── WebRTC 영상 ──────▶ gateway ── WebSocket(I420 원본 프레임) ──▶ A4 API /ws
    앱 ◀── 데이터 채널(JSON) ── gateway ◀── JSON(searching/inspect/product_end) ── A4 API /ws
    앱 ── /results/*, /products*, /storage ──▶ gateway ──▶ A4 API   (그대로 넘긴다)

프레임은 다시 압축하지 않는다 (PLAN_product_feedback.md §7.1): b"I420" + width·height(uint16 LE) + rx_at(int64 LE,
게이트웨이가 받은 시각 epoch ms) + Y·U·V 평면. 앱은 HMD 프레임을 받는 대로(초당 약 12장) 보내고 A4 API가 가장 최근 프레임만 처리하므로
게이트웨이는 프레임을 거르지 않는다.

연결 절차 (앱):
    1. RTCPeerConnection에 카메라 영상 트랙을 붙이고, 데이터 채널을 하나 연다 (이름 무관).
    2. offer를 만들어 POST /offer {"sdp", "type", "station_id", "feedback_mode"} -> answer {"sdp", "type"}를 받아 적용한다.
       feedback_mode는 manual / auto (없으면 auto) — /ws에 ?mode=로 넘긴다.
       게이트웨이는 answer를 만들기 전에 /ws에 먼저 연결해 확인한다.
       422: station_id가 없거나 빈 값, 403: 등록되지 않은 스테이션 (/ws close 4403),
       503: 판정 서버(A4 API 또는 DB)에 연결할 수 없음.
    3. 데이터 채널로 /ws와 같은 JSON이 그대로 온다 (searching / inspect / product_end, A4_WEBRTC_API.md §3).
       메시지에 이미지는 없다 — 저장된 결함 프레임의 이미지는 inspect의 overlay_url로 받는다.
    4. /ws가 끊기거나 중계 중 예외가 나면 게이트웨이가 WebRTC 연결을 닫는다. 앱은 새 POST /offer로 재연결한다.
       끊기기 전에 받은 result_id의 이미지는 재연결 뒤에도 GET /results/<result_id>/overlay로 받는다.

실행: uvicorn gateway:app --host 0.0.0.0 --port 8080   (컨테이너는 --network host, README 참고)

로그: 연결마다 ID(peer)를 만들어 모든 출력에 붙이고, /ws에 ?client=<ID>로 넘겨 A4 API 로그와 맞춘다.
출력(print)과 같은 내용을 logs/<날짜>.jsonl에도 남긴다. 날짜가 바뀌면 새 파일에 쓰고, 지우지 않는다.
decision이 OK인 inspect는 초당 10여 개라 한 줄씩 남기지 않고, 연결이 끝날 때 개수만
relay_summary 한 줄로 남긴다.
"""

import asyncio
import datetime
import json
import os
import time
import uuid
from pathlib import Path
from urllib.parse import quote

import struct
from typing import Literal

import httpx
import websockets
from aiortc import RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import MediaStreamError
from fastapi import FastAPI, HTTPException, Request, Response
from pydantic import BaseModel, Field

A4_WS_URL = os.environ.get("A4_WS_URL", "ws://localhost:8000/ws")
A4_HTTP_URL = os.environ.get("A4_HTTP_URL", "http://localhost:8000")  # /results/*·/products*·/storage 프록시 대상
I420_HEADER = struct.Struct("<4sHHq")  # magic, width, height, rx_at — A4 API detect.I420_HEADER와 같아야 한다
LOG_DIR = Path("logs")  # 날짜와 시각은 컨테이너 시간대(Dockerfile의 TZ=Asia/Seoul)를 따른다

app = FastAPI(title="A4 WebRTC gateway")
peers: set[RTCPeerConnection] = set()
http = httpx.AsyncClient(base_url=A4_HTTP_URL, timeout=10)


def log_event(text: str, event: str, **fields) -> None:
    """기존 형식의 한 줄(text)을 출력하고, 같은 내용을 logs/<날짜>.jsonl에 남긴다.
    이벤트 루프에서만 불리므로 락은 필요 없다. 파일에 못 써도 중계는 계속한다."""
    print(text, flush=True)
    now = datetime.datetime.now().astimezone()
    line = json.dumps({"ts": now.isoformat(timespec="milliseconds"), "event": event, **fields}, ensure_ascii=False)
    try:
        LOG_DIR.mkdir(exist_ok=True)
        with (LOG_DIR / f"{now:%Y-%m-%d}.jsonl").open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError as e:  # 디스크 부족 등
        print(f"[log] 파일 기록 실패: {e}", flush=True)


class Offer(BaseModel):
    sdp: str
    type: str
    station_id: str = Field(min_length=1)  # 없거나 빈 값이면 FastAPI가 422
    feedback_mode: Literal["manual", "auto"] = "auto"  # 다른 값이면 422. 옛 앱(필드 없음)은 auto


@app.post("/offer")
async def offer(body: Offer, request: Request) -> dict:
    peer = uuid.uuid4().hex[:6]  # 이 연결의 로그 ID. A4 API의 /ws 로그에는 client로 남는다
    client = request.client.host if request.client else None
    station = body.station_id
    mode = body.feedback_mode
    log_event(f"[peer {peer}] offer from {client} (station {station}, mode {mode})", "offer", peer=peer, client=client,
              station=station, mode=mode)

    # /ws를 먼저 연결하고 첫 메시지로 확인한다. 실패하면 answer를 만들지 않아 앱이 장애를 바로 안다.
    # ?client=: A4 API가 게이트웨이 경유로 기록하고, 두 로그를 이 ID로 맞춰 본다 (DEPLOY.md §4.1)
    ws = None
    try:
        ws = await websockets.connect(f"{A4_WS_URL}?client={peer}&station={quote(station)}&mode={mode}", max_size=None)
        await asyncio.wait_for(ws.recv(), timeout=5)  # {"state": "searching"} — 연결 확인용, 앱에는 보내지 않는다
    except websockets.ConnectionClosed as e:
        code = e.rcvd.code if e.rcvd else None  # A4 API의 거부: 4403 미등록, 4503 DB 장애. 프레임 없이 끊기면 None
        log_event(f"[peer {peer}] rejected: /ws close {code}", "offer_rejected", peer=peer, station=station, code=code)
        if code == 4403:
            raise HTTPException(status_code=403, detail="등록되지 않은 스테이션입니다.") from e
        raise HTTPException(status_code=503, detail="판정 서버에 연결할 수 없습니다.") from e
    except Exception as e:  # 연결 거부, 시간 초과 등
        if ws is not None:
            await ws.close()
        log_event(f"[peer {peer}] rejected: {type(e).__name__}: {e}", "offer_rejected", peer=peer, station=station,
                  error=f"{type(e).__name__}: {e}")
        raise HTTPException(status_code=503, detail="판정 서버에 연결할 수 없습니다.") from e

    pc = RTCPeerConnection()
    peers.add(pc)
    channel = asyncio.get_running_loop().create_future()

    @pc.on("datachannel")
    def on_datachannel(ch) -> None:
        if not channel.done():
            channel.set_result(ch)

    @pc.on("track")
    def on_track(track) -> None:
        if track.kind == "video":
            asyncio.ensure_future(forward(track, ws, pc, channel, peer))

    @pc.on("connectionstatechange")
    async def on_state() -> None:
        log_event(f"[peer {peer}] {pc.connectionState}", "peer_state", peer=peer, state=pc.connectionState)
        if pc.connectionState in ("failed", "closed"):
            await ws.close()  # 영상 트랙 없이 끝난 연결도 /ws를 닫는다
            await pc.close()
            peers.discard(pc)

    try:
        await pc.setRemoteDescription(RTCSessionDescription(sdp=body.sdp, type=body.type))
        await pc.setLocalDescription(await pc.createAnswer())
    except Exception:
        await ws.close()
        raise
    return {"sdp": pc.localDescription.sdp, "type": pc.localDescription.type}


async def forward(track, ws, pc: RTCPeerConnection, channel: asyncio.Future, peer: str) -> None:
    """영상 트랙이 끝날 때까지 프레임을 /ws로 넘긴다. 연결 하나 = /ws 세션 하나.
    어느 쪽이 끝나든 /ws와 WebRTC 연결을 모두 닫는다 — A4 API 쪽이 끊기면 앱이 연결 끊김으로 장애를 안다.
    A4 API가 /ws를 닫으면 다음 ws.send()가 ConnectionClosed를 낸다 (websockets의 끊김 감지 방법)."""
    summary = {"inspect": 0, "OK": 0, "REVIEW": 0, "NG": 0, "undelivered": 0}  # relay_messages가 센다
    relay = asyncio.create_task(relay_messages(ws, channel, peer, summary))
    try:
        while True:
            frame = await track.recv()
            rx_at = round(time.time() * 1000)
            # 거르지 않는다 — 앱이 HMD 프레임을 받는 대로(초당 약 12장) 보내고, A4 API가 가장 최근 프레임만 처리한다.
            # (옛 1/FORWARD_FPS 간격 제한은 도착 간격이 조금만 흔들려도 다음 장을 버린다 — 2026-11-05 실측 초당 약 7장의 원인으로 본다)
            await ws.send(await asyncio.to_thread(to_i420, frame, rx_at))
    except MediaStreamError:
        log_event(f"[peer {peer}] track ended", "track_ended", peer=peer)  # 앱이 영상을 끊음
    except Exception as e:  # A4 API 쪽 끊김 포함
        log_event(f"[forward {peer}] {type(e).__name__}: {e}", "forward_error", peer=peer,
                  error=f"{type(e).__name__}: {e}")
    finally:
        relay.cancel()
        try:
            await relay
        except asyncio.CancelledError:
            pass
        except Exception as e:  # 중계 중 예외 — 여기서 회수해야 아래 닫기가 실행된다
            log_event(f"[forward {peer}] {type(e).__name__}: {e}", "forward_error", peer=peer,
                      error=f"{type(e).__name__}: {e}")
        # relay는 위에서 취소되므로 요약은 여기서 남긴다
        log_event(f"[peer {peer}] relay summary {summary}", "relay_summary", peer=peer, **summary)
        await ws.close()
        await pc.close()


def to_i420(frame, rx_at: int) -> bytes:
    """디코딩한 프레임 -> b"I420" 머리말 + Y·U·V 평면 (다시 압축하지 않는다). 홀수 크기는 짝수로 줄인다."""
    if frame.format.name != "yuv420p" or frame.width % 2 or frame.height % 2:
        frame = frame.reformat(width=frame.width & ~1, height=frame.height & ~1, format="yuv420p")
    planes = frame.to_ndarray(format="yuv420p")  # (h * 3 / 2, w) — Y 다음 U, V
    return I420_HEADER.pack(b"I420", frame.width, frame.height, rx_at) + planes.tobytes()


async def relay_messages(ws, channel: asyncio.Future, peer: str, summary: dict) -> None:
    """/ws 메시지를 데이터 채널로 그대로 전달한다. 채널이 없어도 계속 읽는다 —
    읽지 않으면 /ws 쪽 전송이 막혀 판정 세션이 멈춘다.
    summary: inspect 수, decision별 수, 데이터 채널이 닫혀 앱에 못 보낸 메시지 수 (forward가 연결 끝에 남긴다).
    OK inspect는 파일 로그에 한 줄씩 남기지 않는다 — summary의 개수로만 남는다."""
    async for text in ws:
        message = json.loads(text)
        ch = channel.result() if channel.done() else None
        delivered = ch is not None and ch.readyState == "open"
        if not delivered:
            summary["undelivered"] += 1
        if message["state"] == "inspect":
            summary["inspect"] += 1
            summary[message["decision"]] += 1
        if message["state"] != "inspect" or message["decision"] != "OK":
            log_event(f"[a4 {peer}] {message}" + ("" if delivered else " (데이터 채널 닫힘 — 앱에 못 보냄)"),
                      "a4_message", peer=peer, message=message, delivered=delivered)
        if delivered:
            ch.send(json.dumps(message))


async def proxy(request: Request, path: str) -> Response:
    """/results/*·/products*·/storage 요청을 A4 API로 그대로 넘긴다 (쿼리 문자열 포함). 이미지는 A4 API 쪽에 있다."""
    content_type = request.headers.get("content-type")
    if request.url.query:
        path = f"{path}?{request.url.query}"
    try:
        upstream = await http.request(request.method, path, content=await request.body(),
                                      headers={"Content-Type": content_type} if content_type else {})
    except httpx.TransportError as e:  # A4 API에 연결할 수 없음
        raise HTTPException(status_code=503, detail="판정 서버에 연결할 수 없습니다.") from e
    return Response(upstream.content, status_code=upstream.status_code,
                    media_type=upstream.headers.get("content-type"))


@app.get("/results/{result_id}")
async def result(request: Request, result_id: str) -> Response:
    return await proxy(request, f"/results/{result_id}")


@app.get("/results/{result_id}/overlay")
async def result_overlay(request: Request, result_id: str) -> Response:
    return await proxy(request, f"/results/{result_id}/overlay")


@app.get("/products")
async def products(request: Request) -> Response:
    return await proxy(request, "/products")


@app.get("/products/{product_id}")
async def product(request: Request, product_id: str) -> Response:
    return await proxy(request, f"/products/{quote(product_id)}")


@app.put("/products/{product_id}/feedback")
async def product_feedback(request: Request, product_id: str) -> Response:
    return await proxy(request, f"/products/{quote(product_id)}/feedback")


@app.get("/products/{product_id}/frames")
async def product_frames(request: Request, product_id: str) -> Response:
    return await proxy(request, f"/products/{quote(product_id)}/frames")


@app.get("/products/{product_id}/frames/{seq}.jpg")
async def product_frame_image(request: Request, product_id: str, seq: str) -> Response:
    return await proxy(request, f"/products/{quote(product_id)}/frames/{quote(seq)}.jpg")


@app.get("/storage")
async def storage(request: Request) -> Response:
    return await proxy(request, "/storage")
