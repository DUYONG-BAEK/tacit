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

GPU·I/O 없는 순수 로직이다. 한 연결의 메서드는 모두 그 연결의 이벤트 루프에서 불린다.
"""

from detect import filter_in_products

REVIEW_MARGIN = 0.1  # 판정 유보 폭 (threshold 대비). api.QI_MIN_CONF가 이 값을 쓴다


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
