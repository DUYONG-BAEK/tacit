"""앱 대신 동영상 파일을 WebRTC로 게이트웨이에 보내는 테스트 클라이언트.

사용: python webrtc_client.py 동영상.mp4 --station ST-01 [--gateway http://localhost:8080] [--out 폴더] [--linger 2]
    동영상을 실시간 속도로 재생하며 보낸다 (파일 길이만큼 걸린다).
    데이터 채널로 온 메시지를 경과 시간과 함께 출력한다. inspect는 한 줄로 줄이고, 이어진 OK는
    "inspect OK ×N" 한 줄로 묶는다. --out이 있으면 result_id가 붙은(서버가 저장한) inspect마다 오버레이를
    --gateway에서 받아 NNN_<decision>_<result_id>.jpg로 저장한다 (404면 0.5초 뒤 한 번 더).
    영상이 끝나면 마지막 프레임의 판정을 받으려고 --linger초 더 연결을 유지한다.
    /offer가 실패하면 상태 코드와 본문을 출력하고 끝난다 (403 미등록 스테이션, 503 판정 서버 장애).
    연결 상태 변화를 출력하고, 게이트웨이가 연결을 닫으면(장애) 영상 끝을 기다리지 않고 끝난다.
    끝에 요약: 받은 inspect 수, decision별 개수, 저장(result_id) 수, product_end의 판정별 수.
"""

import argparse
import asyncio
import json
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

from aiortc import RTCPeerConnection, RTCSessionDescription
from aiortc.contrib.media import MediaPlayer


def post_json(url: str, body: dict) -> dict:
    request = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read())


def download(url: str, path: Path) -> None:
    """오버레이를 받아 저장한다. 서버는 inspect를 보낸 뒤 저장하므로 404면 0.5초 뒤 한 번 더."""
    for attempt in range(2):
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                path.write_bytes(response.read())
            return
        except urllib.error.HTTPError as e:
            if e.code != 404 or attempt:
                print(f"오버레이 받기 실패: {url} {e.code}")
                return
        except urllib.error.URLError as e:
            print(f"오버레이 받기 실패: {url} {e.reason}")
            return
        time.sleep(0.5)


class Report:
    """메시지 출력과 끝의 요약. 이어진 OK inspect는 한 줄(OK ×N)로 묶는다 (stream_client.py와 같다)."""

    def __init__(self) -> None:
        self.start = time.perf_counter()
        self.ok_run, self.ok_from = 0, 0.0
        self.decisions: Counter = Counter()
        self.saved = 0
        self.products: Counter = Counter()  # product_end의 decision별 수

    def elapsed(self) -> float:
        return time.perf_counter() - self.start

    def flush(self) -> None:
        if self.ok_run:
            print(f"{self.ok_from:6.2f}s  inspect OK ×{self.ok_run}")
            self.ok_run = 0

    def line(self, text: str) -> None:
        """inspect 외의 줄 (연결 상태 등)."""
        self.flush()
        print(f"{self.elapsed():6.2f}s  {text}")

    def message(self, message: dict) -> None:
        now = self.elapsed()
        if message["state"] != "inspect":
            if message["state"] == "product_end":
                self.products[message["decision"]] += 1
            self.line(str(message))
            return
        self.decisions[message["decision"]] += 1
        if message["decision"] == "OK":
            if not self.ok_run:
                self.ok_from = now
            self.ok_run += 1
            return
        self.flush()
        if message["result_id"]:
            self.saved += 1
        defects = ", ".join(f"{d['label']}:{d['conf']:.2f}" for d in message["detections"])
        saved = f"  result_id {message['result_id']}" if message["result_id"] else ""
        print(f"{now:6.2f}s  inspect seq {message['seq']} {message['decision']} [{defects}] "
              f"products {len(message['products'])}{saved}")

    def summary(self) -> None:
        self.flush()
        d = self.decisions
        print(f"요약: inspect {sum(d.values())} (OK {d['OK']} / REVIEW {d['REVIEW']} / NG {d['NG']}), "
              f"저장 {self.saved}, 제품 {sum(self.products.values())} (PASS {self.products['PASS']} / "
              f"FAIL {self.products['FAIL']})")


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("video", type=Path)
    parser.add_argument("--station", required=True, help="스테이션 ID (DB stations에 등록된 값)")
    parser.add_argument("--gateway", default="http://localhost:8080")
    parser.add_argument("--mode", choices=["manual", "auto"], default="auto", help="피드백 모드 (/offer feedback_mode)")
    parser.add_argument("--out", type=Path, help="저장된 결함 프레임의 오버레이를 받을 폴더")
    parser.add_argument("--linger", type=float, default=2, help="영상 종료 후 마지막 판정을 기다리는 시간(초)")
    args = parser.parse_args()
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)

    pc = RTCPeerConnection()
    channel = pc.createDataChannel("results")
    player = MediaPlayer(str(args.video))
    pc.addTrack(player.video)
    ended = asyncio.Event()
    player.video.on("ended", ended.set)
    peer_closed = asyncio.Event()  # 게이트웨이가 연결을 닫음 (장애)
    closing = False  # 이 클라이언트가 직접 닫는 중
    report = Report()
    downloads = []

    @pc.on("connectionstatechange")
    def on_state() -> None:
        report.line(f"연결 상태 {pc.connectionState}")
        if pc.connectionState in ("failed", "closed") and not closing:
            peer_closed.set()

    @channel.on("message")
    def on_message(text: str) -> None:
        message = json.loads(text)
        report.message(message)
        if args.out and message["state"] == "inspect" and message["result_id"]:
            path = args.out / f"{report.saved:03d}_{message['decision']}_{message['result_id']}.jpg"
            downloads.append(asyncio.ensure_future(
                asyncio.to_thread(download, args.gateway + message["overlay_url"], path)))

    async def close() -> None:
        nonlocal closing
        closing = True
        await pc.close()

    await pc.setLocalDescription(await pc.createOffer())
    try:
        answer = await asyncio.to_thread(
            post_json, args.gateway + "/offer",
            {"sdp": pc.localDescription.sdp, "type": pc.localDescription.type, "station_id": args.station,
             "feedback_mode": args.mode})
    except urllib.error.HTTPError as e:  # 403 미등록 스테이션, 503 판정 서버 장애, 422 station_id 오류
        print(f"/offer 실패: {e.code} {e.read().decode()}")
        await close()
        raise SystemExit(1) from e
    await pc.setRemoteDescription(RTCSessionDescription(**answer))

    try:
        waits = [asyncio.ensure_future(ended.wait()), asyncio.ensure_future(peer_closed.wait())]
        _, rest = await asyncio.wait(waits, return_when=asyncio.FIRST_COMPLETED)
        for task in rest:
            task.cancel()
        # 게이트웨이가 연결을 닫으면 플레이어 트랙도 끝나 ended가 같이 온다 — 닫힘을 먼저 본다
        if peer_closed.is_set():
            report.line("게이트웨이가 연결을 닫음 — 종료")
            return
        report.line(f"영상 끝 — {args.linger}초 대기")
        try:  # 대기 중에 게이트웨이가 닫으면 바로 끝낸다
            await asyncio.wait_for(peer_closed.wait(), args.linger)
            report.line("게이트웨이가 연결을 닫음 — 종료")
        except TimeoutError:
            await close()
    finally:
        await asyncio.gather(*downloads)
        report.summary()


if __name__ == "__main__":
    asyncio.run(main())
