"""stream.Inspections (면 검사) 시험. 실행: cd server/api && python -m pytest test_stream.py

숫자(INSPECT_FRAMES·DEFECT_MIN_FRAMES)를 바꿔도 그대로 통과하도록 상수로 쓴다.
"""

import pytest

import stream
from stream import DEFECT_MIN_FRAMES, INSPECT_FRAMES, Inspections, face_of

N = INSPECT_FRAMES
WALL = "2026-11-23T10:00:00.000+09:00"


def box(label: str, size: float = 100) -> dict:
    return {"label": label, "conf": 0.9, "box": [0, 0, size, size]}


class Feed:
    """프레임을 차례로 넣고, 앱에 갈 product_end(완료)와 기록만 하는 끊긴 검사를 모은다."""

    def __init__(self) -> None:
        self.ins = Inspections("s", "auto")
        self.seq = 0
        self.done: list[dict] = []
        self.dropped: list[dict] = []
        self.started = 0

    def face(self, label: str, decision: str = "OK", score: float = 0.0, *, products=None, result_id=None) -> dict:
        self.seq += 1
        msg = {"seq": self.seq, "decision": decision, "score": score, "threshold": 0.25,
               "defect_label": None if decision == "OK" else f"결함{self.seq}",
               "products": products or [box(label)], "result_id": result_id}
        dropped, _, started, done = self.ins.inspect(WALL, msg)
        self.started += started
        if dropped:
            self.dropped.append(dropped)
        if done:
            self.done.append(done)
        return msg

    def none(self) -> None:
        self.seq += 1
        end = self.ins.no_product(WALL)
        if end:
            self.dropped.append(end)


def test_n_frames_same_face_is_one_inspection():
    f = Feed()
    msgs = [f.face("상면") for _ in range(N)]
    assert [m["count"] for m in msgs] == list(range(1, N + 1))
    assert {m["face"] for m in msgs} == {"상면"}
    assert len({m["product_id"] for m in msgs}) == 1
    [d] = f.done
    assert (d["face"], d["end_reason"], d["decision"], d["ai_decision"]) == ("상면", "done", "PASS", "OK")
    assert d["frames"] == N and d["best_seq"] == N and d["defect_label"] is None
    assert f.ins.current is None


def test_until_n_minus_one_frames_not_done():
    f = Feed()
    for _ in range(N - 1):
        f.face("상면")
    assert f.done == [] and f.ins.current is not None


def test_2n_frames_is_two_inspections():
    f = Feed()
    msgs = [f.face("상면") for _ in range(2 * N)]
    assert len(f.done) == 2 and f.started == 2
    assert msgs[N]["count"] == 1 and msgs[N]["product_id"] != msgs[0]["product_id"]


def test_other_face_restarts():
    f = Feed()
    for _ in range(N - 1):
        f.face("상면")
    m = f.face("엣지")
    assert m["count"] == 1 and m["face"] == "엣지"
    [d] = f.dropped
    assert (d["face"], d["end_reason"], d["decision"], d["frames"]) == ("상면", "incomplete", None, N - 1)
    for _ in range(N - 1):
        f.face("엣지")
    [done] = f.done
    assert done["face"] == "엣지"


def test_no_face_frame_drops():
    f = Feed()
    for _ in range(N - 1):
        f.face("상면")
    f.none()
    [d] = f.dropped
    assert d["end_reason"] == "incomplete" and d["decision"] is None
    m = f.face("상면")
    assert m["count"] == 1  # 처음부터 다시


def test_extra_frames_after_done_are_dropped_if_cut():
    # 상면 N+2장 → 1건 완료, 뒤 2장은 다른 면이 오면 미완료로 버려진다
    f = Feed()
    for _ in range(N + 2):
        f.face("상면")
    f.face("하면비닐")
    assert len(f.done) == 1
    [d] = f.dropped
    assert d["frames"] == 2 and d["end_reason"] == "incomplete"


def test_no_product_when_idle_is_nothing():
    f = Feed()
    f.none()
    assert f.dropped == [] and f.done == []


def test_close_mid_inspection_is_disconnect():
    f = Feed()
    f.face("상면")
    end = f.ins.close(WALL)
    assert end["end_reason"] == "disconnect" and end["decision"] is None
    assert f.ins.close(WALL) is None


@pytest.mark.parametrize("defects", range(0, INSPECT_FRAMES + 1))
def test_defect_min_frames(defects):
    f = Feed()
    for i in range(N):
        if i < defects:
            f.face("상면", "REVIEW", 0.3)
        else:
            f.face("상면")
    [d] = f.done
    assert d["defect_frames"] == defects
    if defects >= DEFECT_MIN_FRAMES:
        assert (d["decision"], d["ai_decision"]) == ("FAIL", "REVIEW")
    else:
        assert (d["decision"], d["ai_decision"]) == ("PASS", "OK")


def test_fail_takes_worst_frame_and_ng_wins(monkeypatch):
    monkeypatch.setattr(stream, "DEFECT_MIN_FRAMES", 1)
    f = Feed()
    f.face("상면", "REVIEW", 0.26)
    f.face("상면", "NG", 0.8, result_id="r2")
    f.face("상면", "NG", 0.5, result_id="r3")
    for _ in range(N - 3):
        f.face("상면")
    [d] = f.done
    assert (d["decision"], d["ai_decision"]) == ("FAIL", "NG")
    assert d["best_seq"] == 2 and d["defect_label"] == "결함2"
    assert d["best_result_id"] == "r2" and d["result_ids"] == ["r2", "r3"]


def test_defect_min_frames_is_read_at_judgement(monkeypatch):
    # 숫자만 바꾸면 판정이 바뀐다 (예: 3장)
    monkeypatch.setattr(stream, "DEFECT_MIN_FRAMES", 3)
    f = Feed()
    f.face("상면", "NG", 0.8)
    f.face("상면", "NG", 0.8)
    for _ in range(N - 2):
        f.face("상면")
    assert f.done[0]["decision"] == "PASS"


def test_face_is_largest_box():
    assert face_of([box("엣지", 50), box("상면", 200), box("하면비닐", 100)]) == "상면"
    f = Feed()
    m = f.face("상면", products=[box("엣지", 300), box("상면", 100)])
    assert m["face"] == "엣지"


def test_product_ids_count_up():
    f = Feed()
    for _ in range(N):
        f.face("상면")
    f.face("엣지")
    assert f.done[0]["product_id"] == "s-p0001"
    assert f.ins.current.product_id == "s-p0002"
