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

면 검사 (Inspections, 2026-11-23 — 제품 하나를 끝까지 본다는 가정을 버리고 면 단위로 검사한다):
    면      한 프레임의 면 = PD 상자 중 가장 큰 상자의 라벨 (엣지·상면·하면비닐)
    시작    면이 잡힌 프레임에서, 진행 중인 검사가 없으면 그 면의 검사를 시작한다
    완료    같은 면이 연속 INSPECT_FRAMES장 → 판정하고 product_end(end_reason "done")를 보낸다.
            그 뒤에도 같은 면이 계속 들어오면 새 검사를 시작한다 (같은 면을 10장 보면 검사 2건)
    미완료  INSPECT_FRAMES장 전에 다른 면이 잡히면 그 면으로 새로 시작, 면이 없는 프레임이 오면 처음 상태로.
            끊긴 검사는 end_reason "incomplete"(연결 종료면 "disconnect")로 기록만 하고 앱에는 보내지 않는다
    판정    결함 프레임(NG·REVIEW)이 DEFECT_MIN_FRAMES장 이상이면 FAIL — 그중 NG가 있으면 ai_decision NG, 없으면 REVIEW.
            아니면 PASS(ai_decision OK). defect_label은 점수가 가장 높은 결함 프레임의 것
    대표    best_seq = FAIL이면 점수가 가장 높은 결함 프레임, PASS면 마지막 프레임 (앱 최근 검사 카드의 사진)
메시지·DB·API의 이름(product_id, product_end, products 테이블)은 그대로 두고, 한 건이 면 검사 1건을 뜻한다.

GPU·I/O 없는 순수 로직이다. 한 연결의 메서드는 모두 그 연결의 이벤트 루프에서 불린다.
"""

from detect import filter_in_products

REVIEW_MARGIN = 0.1  # 판정 유보 폭 (threshold 대비). api.QI_MIN_CONF가 이 값을 쓴다

# 면 검사 (현장 시험으로 조정 — 숫자만 바꾸면 된다)
INSPECT_FRAMES = 5  # 면 검사 1건 = 같은 면 연속 이 장수
DEFECT_MIN_FRAMES = 1  # 이 중 결함(NG·REVIEW) 프레임이 이 장수 이상이면 FAIL
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


def face_of(products: list[dict]) -> str:
    """프레임의 면 — 가장 큰 PD 상자의 라벨 (넓이가 같으면 앞의 것)."""
    def area(p: dict) -> float:
        x1, y1, x2, y2 = p["box"]
        return (x2 - x1) * (y2 - y1)
    return max(products, key=area)["label"]


class Inspection:
    """진행 중인 면 검사 하나. Inspections가 만든다."""

    def __init__(self, product_id: str, face: str, started_at: str) -> None:
        self.product_id = product_id
        self.face = face
        self.started_at = started_at
        self.seqs: list[int] = []
        self.defects: list[dict] = []  # 결함 프레임의 inspect 메시지
        self.threshold: float | None = None
        self.saved: list[tuple[str, float]] = []  # (result_id, score)


class Inspections:
    """한 연결의 면 검사. 시각(wall: ISO 문자열)은 호출자가 넘긴다.

        inspect(wall, message) -> (끊긴 검사, 검사, 새로 시작했는지, 완료)   면이 잡힌 프레임마다.
                                   message에 product_id·face·count를 붙인다. 끊긴 검사·완료는 product_end 메시지 또는 None
        no_product(wall) -> product_end 메시지 또는 None                  면이 없는 프레임 (진행 중이던 검사를 끊는다)
        close(wall) -> product_end 메시지 또는 None                       연결이 끝날 때
    product_end는 완료(done)만 앱에 보내고, 끊긴 검사(incomplete·disconnect)는 기록만 한다.
    """

    def __init__(self, prefix: str, feedback_mode: str) -> None:
        self.prefix = prefix  # "[direct_]<세션 12자리>"
        self.feedback_mode = feedback_mode
        self.count = 0
        self.current: Inspection | None = None

    def inspect(self, wall: str, message: dict) -> tuple[dict | None, Inspection, bool, dict | None]:
        face = face_of(message["products"])
        dropped = None
        if self.current is not None and self.current.face != face:
            dropped = self._end(wall, "incomplete")
        started = self.current is None
        if started:
            self.count += 1
            self.current = Inspection(f"{self.prefix}-p{self.count:04d}", face, wall)
        c = self.current
        c.seqs.append(message["seq"])
        c.threshold = message["threshold"]
        if message["decision"] != "OK":
            c.defects.append(message)
            if message["result_id"]:
                c.saved.append((message["result_id"], message["score"]))
        message |= {"product_id": c.product_id, "face": face, "count": len(c.seqs)}
        done = self._end(wall, "done") if len(c.seqs) >= INSPECT_FRAMES else None
        return dropped, c, started, done

    def no_product(self, wall: str) -> dict | None:
        return None if self.current is None else self._end(wall, "incomplete")

    def close(self, wall: str) -> dict | None:
        return None if self.current is None else self._end(wall, "disconnect")

    def _end(self, wall: str, reason: str) -> dict:
        c, self.current = self.current, None
        decision = ai_decision = label = None
        best_seq = c.seqs[-1]
        if reason == "done":
            if len(c.defects) >= DEFECT_MIN_FRAMES:
                worst = max(c.defects, key=lambda m: m["score"])
                decision, label, best_seq = "FAIL", worst["defect_label"], worst["seq"]
                ai_decision = "NG" if any(m["decision"] == "NG" for m in c.defects) else "REVIEW"
            else:
                decision, ai_decision = "PASS", "OK"
        ids = [rid for rid, _ in sorted(c.saved, key=lambda s: -s[1])]
        return {
            "state": "product_end", "product_id": c.product_id, "face": c.face, "started_at": c.started_at,
            "ended_at": wall, "end_reason": reason, "decision": decision, "ai_decision": ai_decision,
            "defect_label": label, "frames": len(c.seqs), "defect_frames": len(c.defects), "best_seq": best_seq,
            "best_result_id": ids[0] if ids else None, "result_ids": ids, "threshold": c.threshold,
            "feedback_mode": self.feedback_mode, "feedback": initial_feedback(self.feedback_mode),
        }


def initial_feedback(feedback_mode: str) -> str:
    """제품의 피드백 초기값: 수동 모드는 미입력(NOT_ENTERED), 자동 모드는 피드백 없음(NONE)."""
    return "NOT_ENTERED" if feedback_mode == "manual" else "NONE"
