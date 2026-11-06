"""폰 대신 /ws에 프레임을 보내는 테스트 클라이언트.

사용: python stream_client.py 입력... [--url "ws://localhost:8000/ws?mode=manual"] [--fps 10] [--hold 10] [--gap 30] [--out 폴더]
    동영상(.mp4/.mov 등): --fps 간격으로 프레임을 골라 JPEG로 보낸다 (폰 영상을 그대로 써도 된다).
    사진: 같은 사진을 --hold장 연속으로 보낸다 (제품을 비추고 있는 상태).
    입력 사이에 검은 프레임을 --gap장 보낸다 (제품을 치운 상태 — 서버가 searching을 보낸다).
서버가 보낸 메시지를 경과 시간과 함께 출력한다. inspect는 한 줄로 줄이고, 이어진 OK는 "inspect OK ×N" 한 줄로 묶는다.
--out이 있으면 result_id가 붙은(서버가 저장한) inspect마다 오버레이를 HTTP로 받아
NNN_<decision>_<result_id>.jpg로 저장한다 (decision: NG / REVIEW). HTTP 주소는 --url에서 만든다
(ws://h:p/ws?… → http://h:p). 서버는 inspect를 보낸 뒤 저장하므로, 404면 0.5초 뒤 한 번 더 받는다.
끝에 요약: 받은 inspect 수, decision별 개수, 저장(result_id) 수.
"""

import argparse
import asyncio
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

import cv2
import numpy as np
import websockets

VIDEO_EXTENSIONS = {".mp4", ".mov", ".avi", ".mkv"}


def frames(inputs: list[Path], fps: float, hold: int, gap: int):
    black = cv2.imencode(".jpg", np.zeros((1920, 1080, 3), np.uint8))[1].tobytes()
    for path in inputs:
        if path.suffix.lower() in VIDEO_EXTENSIONS:
            capture = cv2.VideoCapture(str(path))
            step = max(1, round(capture.get(cv2.CAP_PROP_FPS) / fps))
            index = 0
            while True:
                ok, frame = capture.read()
                if not ok:
                    break
                if index % step == 0:
                    yield cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 90])[1].tobytes()
                index += 1
            capture.release()
        else:
            yield from [path.read_bytes()] * hold
        yield from [black] * gap


def http_base(ws_url: str) -> str:
    """ws://h:p/ws?… -> http://h:p (오버레이 조회 주소)."""
    parts = urllib.parse.urlsplit(ws_url)
    return f"{'https' if parts.scheme == 'wss' else 'http'}://{parts.netloc}"


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
    """메시지 출력과 끝의 요약. 이어진 OK inspect는 한 줄(OK ×N)로 묶는다."""

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

    def message(self, message: dict) -> None:
        now = self.elapsed()
        if message["state"] != "inspect":
            if message["state"] == "product_end":
                self.products[message["decision"]] += 1
            self.flush()
            print(f"{now:6.2f}s  {message}")
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
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--url", default="ws://localhost:8000/ws")
    parser.add_argument("--fps", type=float, default=10)
    parser.add_argument("--hold", type=int, default=10, help="사진 입력을 반복할 장수")
    parser.add_argument("--gap", type=int, default=30, help="입력 사이 검은 프레임 장수")
    parser.add_argument("--out", type=Path, help="저장된 결함 프레임의 오버레이를 받을 폴더")
    args = parser.parse_args()
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
    base = http_base(args.url)
    report = Report()
    downloads = []

    async with websockets.connect(args.url, max_size=None) as ws:
        async def send() -> None:
            for data in frames(args.inputs, args.fps, args.hold, args.gap):
                await ws.send(data)
                await asyncio.sleep(1 / args.fps)
            await ws.close()

        sender = asyncio.create_task(send())
        async for text in ws:
            message = json.loads(text)
            report.message(message)
            if args.out and message["state"] == "inspect" and message["result_id"]:
                path = args.out / f"{report.saved:03d}_{message['decision']}_{message['result_id']}.jpg"
                # 수신을 막지 않게 스레드에서 받는다
                downloads.append(asyncio.create_task(asyncio.to_thread(download, base + message["overlay_url"], path)))
        await sender
    await asyncio.gather(*downloads)
    report.summary()


if __name__ == "__main__":
    asyncio.run(main())
