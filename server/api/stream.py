"""프레임 단위 판정 — 한 프레임의 inspect 메시지를 만들고, searching을 보낼지 정한다 (api.py /ws).

    프레임마다 api.process_frame()(디코딩 → PD → 제품이 있으면 QI) 다음:
        제품 없음  Searching.no_product() — searching은 (state, reason)이 바뀔 때만 보낸다
        제품 있음  judge() → inspect 메시지, Searching.inspected()

판정 (threshold = detect.QI_CONF):
    detections = QI 결함 중 상자 중심이 어느 제품(PD) 상자 안에 있는 것 (detect.filter_in_products)
    score      = 남은 결함의 최대 conf, 없으면 0
    decision   = score > threshold × (1 + REVIEW_MARGIN) → NG, < threshold × (1 - REVIEW_MARGIN) → OK, 그 사이 → REVIEW
QI 하한이 threshold × (1 - REVIEW_MARGIN)(api.QI_MIN_CONF)이므로 결함이 하나라도 남으면 REVIEW 이상이다 —
OK ⇔ detections == [], 그래서 OK면 defect_label이 None이다.

제품 단위 판정 (Products, PLAN_product_feedback.md D2~D4 — 옛 앱 AiFlow의 규칙을 옮겼다):
    구간    첫 제품 프레임에서 시작, 마지막 제품 프레임 뒤 SEGMENT_END_S 동안 제품 프레임이 없거나(tick) 연결이 끊기면(close) 끝.
            searching 한 장은 구간을 끝내지 않는다 (PD가 제품을 놓친 한 장에도 searching이 간다)
    확정    구간 안 최근 CONFIRM_WINDOW장 중 CONFIRM_HITS장 이상이 NG·REVIEW면 확정 — 그 안에 NG가 있으면 NG, 없으면 REVIEW.
            나중에 NG로 다시 확정되면 올린다. 확정을 만든 프레임은 늘 결함 프레임이다 (OK를 더해도 결함 수는 늘지 않는다)
    판정    확정이 없으면 PASS(ai_decision OK), 있으면 FAIL(ai_decision = 확정값)
    면      PD 라벨별로 그 라벨 상자가 있었던 프레임 수. FACE_MIN_FRAMES 이상이면 그 면을 본 것. 모두 보면 complete.
            complete는 구간을 끝내지 않는다

GPU·I/O 없는 순수 로직이다. 한 연결의 메서드는 모두 그 연결의 이벤트 루프에서 불린다.
"""

from collections import deque

from detect import filter_in_products

REVIEW_MARGIN = 0.1  # 판정 유보 폭 (threshold 대비). api.QI_MIN_CONF가 이 값을 쓴다

# 제품 단위 판정 출발값 (옛 앱 Profile과 같은 값 — 현장 시험으로 조정)
SEGMENT_END_S = 1.0  # 마지막 제품 프레임 뒤 이만큼 제품 프레임이 없으면 제품 끝
CONFIRM_WINDOW = 5  # 결함 확정: 최근 CONFIRM_WINDOW장 중
CONFIRM_HITS = 2  #   CONFIRM_HITS장 이상이 NG·REVIEW면 확정
FACE_MIN_FRAMES = 3  # 이 장수 이상 잡힌 PD 라벨(면)을 "봄"으로 친다
FEEDBACK_MODES = ("manual", "auto")


def judge(products: list[dict], detections: list[dict], threshold: float) -> dict:
    """한 프레임의 inspect 메시지 본문. seq·frame_size·result_id·overlay_url은 호출자가 붙인다."""
    found = filter_in_products(detections, products)
    score = max((d["conf"] for d in found), default=0.0)
    if score > threshold * (1 + REVIEW_MARGIN):
        decision = "NG"
    elif score < threshold * (1 - REVIEW_MARGIN):
        decision = "OK"
    else:
        decision = "REVIEW"
    return {"state": "inspect", "decision": decision, "defect_label": found[0]["label"] if found else None,
            "score": score, "threshold": threshold, "products": products, "detections": found}


class Searching:
    """searching을 (state, reason)이 바뀔 때만 보내게 한다.
    연결 직후의 첫 searching(reason 없음)을 이미 보낸 상태에서 시작한다."""

    def __init__(self) -> None:
        self.last: tuple[str, str | None] = ("searching", None)

    def no_product(self) -> list[dict]:
        """제품이 안 보인 프레임. 바뀌었으면 보낼 메시지 하나, 아니면 빈 목록."""
        if self.last == ("searching", "no_product"):
            return []
        self.last = ("searching", "no_product")
        return [{"state": "searching", "reason": "no_product"}]

    def inspected(self) -> None:
        """inspect를 보냈다 — 다음 no_product에서 searching을 다시 보낸다."""
        self.last = ("inspect", None)


def confirm(recent, hits: int) -> str | None:
    """결함 확정 — 최근 판정 중 NG·REVIEW가 hits장 이상이면 NG(NG가 있으면) 또는 REVIEW, 아니면 None."""
    defects = [d for d in recent if d != "OK"]
    if len(defects) < hits:
        return None
    return "NG" if "NG" in defects else "REVIEW"


def _rank(decision: str | None) -> int:
    return {None: 0, "REVIEW": 1, "NG": 2}[decision]


class Product:
    """진행 중인 제품 하나 (구간). Products가 만든다."""

    def __init__(self, product_id: str, started_at: str, now: float, faces: list[str]) -> None:
        self.product_id = product_id
        self.started_at = started_at
        self.last = now
        self.frames = 0
        self.defect_frames = 0
        self.faces = dict.fromkeys(faces, 0)
        self.recent: deque[str] = deque(maxlen=CONFIRM_WINDOW)
        self.confirmed: str | None = None
        self.label: str | None = None
        self.threshold: float | None = None
        self.saved: list[tuple[str, float]] = []  # (result_id, score)

    def faces_seen(self) -> list[str]:
        return [name for name, n in self.faces.items() if n >= FACE_MIN_FRAMES]

    def complete(self) -> bool:
        return bool(self.faces) and len(self.faces_seen()) == len(self.faces)


class Products:
    """한 연결의 제품 구간·결함 확정·면 진행. 시각은 호출자가 넘긴다 (now: time.monotonic, wall: ISO 문자열).

        inspect(now, wall, message) -> (제품, 새로 시작했는지)   제품 프레임마다. message에 product_id·faces 등을 붙인다
        tick(now, wall) -> product_end 메시지 또는 None          제품 프레임이 없는 동안 주기적으로 (SEGMENT_END_S 검사)
        close(wall) -> product_end 메시지 또는 None               연결이 끝날 때 (end_reason "disconnect")
    """

    def __init__(self, prefix: str, feedback_mode: str, faces: list[str]) -> None:
        self.prefix = prefix  # "[direct_]<세션 12자리>"
        self.feedback_mode = feedback_mode
        self.face_names = list(faces)
        self.count = 0
        self.current: Product | None = None

    def inspect(self, now: float, wall: str, message: dict) -> tuple[Product, bool]:
        started = self.current is None
        if started:
            self.count += 1
            self.current = Product(f"{self.prefix}-p{self.count:04d}", wall, now, self.face_names)
        p = self.current
        p.last = now
        p.frames += 1
        p.threshold = message["threshold"]
        for name in {box["label"] for box in message["products"]}:
            p.faces[name] = p.faces.get(name, 0) + 1
        decision = message["decision"]
        if decision != "OK":
            p.defect_frames += 1
            if message["result_id"]:
                p.saved.append((message["result_id"], message["score"]))
        p.recent.append(decision)
        c = confirm(p.recent, CONFIRM_HITS)
        if c is not None and _rank(c) > _rank(p.confirmed):
            p.confirmed = c
            p.label = message["defect_label"]
        message |= {"product_id": p.product_id, "faces": dict(p.faces), "faces_seen": p.faces_seen(),
                    "complete": p.complete()}
        return p, started

    def tick(self, now: float, wall: str) -> dict | None:
        if self.current is None or now - self.current.last < SEGMENT_END_S:
            return None
        return self._end(wall, "gap")

    def close(self, wall: str) -> dict | None:
        return None if self.current is None else self._end(wall, "disconnect")

    def _end(self, wall: str, reason: str) -> dict:
        p, self.current = self.current, None
        ids = [rid for rid, _ in sorted(p.saved, key=lambda s: -s[1])]
        return {
            "state": "product_end", "product_id": p.product_id, "started_at": p.started_at, "ended_at": wall,
            "end_reason": reason, "decision": "FAIL" if p.confirmed else "PASS", "ai_decision": p.confirmed or "OK",
            "defect_label": p.label, "frames": p.frames, "defect_frames": p.defect_frames, "faces": dict(p.faces),
            "faces_seen": p.faces_seen(), "complete": p.complete(), "best_result_id": ids[0] if ids else None,
            "result_ids": ids, "threshold": p.threshold, "feedback_mode": self.feedback_mode,
            "feedback": initial_feedback(self.feedback_mode),
        }


def initial_feedback(feedback_mode: str) -> str:
    """제품의 피드백 초기값: 수동 모드는 미입력(NOT_ENTERED), 자동 모드는 피드백 없음(NONE)."""
    return "NOT_ENTERED" if feedback_mode == "manual" else "NONE"
