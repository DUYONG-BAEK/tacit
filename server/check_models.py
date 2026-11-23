"""PD·QI 모델 교체 전 확인 — 새 모델이 지금 코드(detect.py·stream.py)에 그대로 맞는지 본다.

a4-api 이미지 안에서 돌린다 (같은 ultralytics·torch·GPU, 이미지의 /app/detect.py를 그대로 import).
    python check_models.py --pd models/새PD.pt ... --qi models/새QI.pt ... [--samples 폴더/파일 ...]

1. 모델별 확인 (FAIL: 지금 코드로는 교체 불가, WARN: 사람이 판단)
   - 로드, task == detect (detect._boxes가 result.boxes를 쓴다)
   - 학습한 ultralytics 버전 = 설치된 버전 (requirements.txt에 고정)
   - 학습 imgsz = 코드의 PD_IMGSZ / TILE
   - 클래스 이름: 지금 모델과 비교 (QI 이름은 defect_label로 앱·DB에 나간다)
   - 옆의 .json(AI팀 메타데이터) 내용 출력
   - 실행: 빈 프레임(예열과 같음)·1920×1080·1080×1920으로 detect.find_products / detect.inspect
2. --samples가 있으면 지금 모델 조합과 새 모델 조합의 판정(stream.judge)을 프레임마다 비교한다.
   사진은 그대로, 동영상은 --video-fps 간격으로 골라 JPEG(게이트웨이와 같은 quality 95)로 바꿔 넣는다.
   --pd-conf·--qi-conf에 값을 여러 개 주면 모델 × 임곗값의 모든 조합을 비교한다 (기본: 지금 코드 값 하나).
   기준은 언제나 지금 모델 + 지금 코드 값이다. 끝에 조합별 요약표를 출력한다.
   --diff-out이 있으면 판정이 바뀐 프레임마다 왼쪽 기준 / 오른쪽 조합 결과를 그린 JPEG를
   <폴더>/<조합>/<프레임>_<기준>_to_<조합>.jpg로 저장한다 (빨강: 판정에 든 결함 = 오버레이와 같음, 파랑: PD 제품 상자).
끝에 FAIL이 하나라도 있으면 종료 코드 1.
"""

import argparse
import itertools
import json
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

import cv2
import numpy as np
import ultralytics
from PIL import Image, ImageDraw, ImageFont
from ultralytics import YOLO, settings

import detect
from stream import REVIEW_MARGIN, judge

settings.update({"sync": False})  # api.py와 같이 사용 통계 전송을 끈다
# api.QI_MIN_CONF와 같은 값 — api는 import하면 모델을 올리고 DB에 붙으므로 여기서 계산한다
QI_MIN_CONF = detect.QI_CONF * (1 - REVIEW_MARGIN)
BASE_PD_CONF, BASE_QI_CONF = detect.PD_CONF, detect.QI_CONF  # 비교 기준 (compare가 detect.PD_CONF를 바꾸기 전 값)
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
VIDEO_EXT = {".mp4", ".mov", ".avi", ".mkv"}
ORDER = ["NO_PRODUCT", "OK", "REVIEW", "NG"]


def stamp(path: Path) -> str:
    """yolo_..._model_20261110100300.pt -> 20261110100300"""
    return path.stem.rsplit("_", 1)[-1]


def check_model(path: Path, kind: str, base: YOLO | None) -> tuple[YOLO | None, list[tuple[str, str]]]:
    """모델 하나를 확인한다. kind: PD | QI. base: 이름 비교용 지금 모델 (지금 모델 자신을 볼 때는 None)."""
    issues = []

    def note(level: str, text: str) -> None:
        print(f"  [{level}] {text}")
        if level != "INFO":
            issues.append((level, text))

    print(f"\n== {kind} {path}")
    if not path.is_file():
        note("FAIL", "파일이 없다")
        return None, issues
    try:
        model = YOLO(str(path))
    except Exception as e:
        note("FAIL", f"로드 실패: {type(e).__name__}: {e}")
        return None, issues

    ckpt = getattr(model, "ckpt", None) or {}
    args = ckpt.get("train_args") or {}
    print(f"  학습일 {ckpt.get('date')}  ultralytics {ckpt.get('version')}  task {model.task}  "
          f"imgsz {args.get('imgsz')}  nc {len(model.names)}")
    print(f"  classes {model.names}")

    if model.task != "detect":
        note("FAIL", f"task가 {model.task} — 코드는 detect 모델만 받는다 (detect._boxes가 result.boxes를 쓴다)")
    if ckpt.get("version") != ultralytics.__version__:
        note("WARN", f"학습 ultralytics {ckpt.get('version')} ≠ 설치 {ultralytics.__version__} "
                     "(requirements.txt는 학습 버전에 맞춰 고정돼 있다)")
    expect = detect.PD_IMGSZ if kind == "PD" else detect.TILE
    imgsz = args.get("imgsz")
    sizes = set(imgsz) if isinstance(imgsz, (list, tuple)) else {imgsz}
    if imgsz is None:
        note("WARN", f"체크포인트에 학습 imgsz가 없다 — AI팀에 확인 (코드는 {expect})")
    elif sizes != {expect}:
        where = "detect.PD_IMGSZ" if kind == "PD" else "detect.TILE·STEP (QI 타일 격자)"
        note("WARN", f"학습 imgsz {imgsz} ≠ 코드 {expect} — {where}를 맞춰야 할 수 있다")
    if not all(isinstance(n, str) and n for n in model.names.values()):
        note("FAIL", "빈 값이거나 문자열이 아닌 클래스 이름이 있다")
    if base is not None and model.names != base.names:
        old, new = set(base.names.values()), set(model.names.values())
        text = f"클래스 이름이 지금 모델과 다르다 (추가 {sorted(new - old)}, 삭제 {sorted(old - new)})"
        if kind == "QI":
            note("WARN", text + " — defect_label·detections.label 값이 바뀐다. 앱·DB를 쓰는 쪽 확인")
        else:
            note("INFO", text + " — PD는 클래스 무관이라 판정에는 영향 없음")

    meta = path.with_suffix(".json")
    if meta.is_file():
        text = meta.read_text(encoding="utf-8", errors="replace")
        try:
            text = json.dumps(json.loads(text), ensure_ascii=False, indent=2)
        except ValueError:
            pass
        print(f"  {meta.name}:\n" + "\n".join("    " + line for line in text.splitlines()))
    else:
        print(f"  ({meta.name} 없음)")

    try:
        t = time.monotonic()
        for shape in [(detect.TILE, detect.TILE, 3), (1080, 1920, 3), (1920, 1080, 3)]:
            frame = np.zeros(shape, np.uint8)
            if kind == "PD":
                detect.find_products(model, frame)
            else:
                detect.inspect(model, frame, QI_MIN_CONF)
        print(f"  실행 확인 OK ({round((time.monotonic() - t) * 1000)} ms, 첫 호출 포함)")
    except Exception as e:
        note("FAIL", f"detect.{'find_products' if kind == 'PD' else 'inspect'} 실행 실패: {type(e).__name__}: {e}")
        return None, issues
    return model, issues


def samples(paths: list[Path], limit: int, video_fps: float):
    """(이름, 프레임 바이트)를 limit개까지."""
    files = sorted(p for root in paths for p in ([root] if root.is_file() else root.rglob("*"))
                   if p.suffix.lower() in IMAGE_EXT | VIDEO_EXT)
    count = 0
    for f in files:
        if count >= limit:
            return
        if f.suffix.lower() in IMAGE_EXT:
            count += 1
            yield str(f), f.read_bytes()  # 전체 경로 — 하위 폴더가 달라도 같은 파일 이름이 있을 수 있다
            continue
        capture = cv2.VideoCapture(str(f))
        step = max(1, round((capture.get(cv2.CAP_PROP_FPS) or video_fps) / video_fps))
        index = 0
        while count < limit:
            ok, frame = capture.read()
            if not ok:
                break
            if index % step == 0:
                count += 1
                yield f"{f}#{index}", cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 95])[1].tobytes()
            index += 1
        capture.release()


def panel(image: np.ndarray, products: list[dict], detections: list[dict], title: str) -> Image.Image:
    """detect.draw(결과 오버레이와 같은 결함 상자) + 파란 PD 제품 상자 + 위쪽 제목 줄."""
    out = detect.draw(image, detections)
    pen = ImageDraw.Draw(out)
    width = max(2, round(min(out.size) / 360))
    for p in products:
        pen.rectangle(p["box"], outline=(0, 128, 255), width=width)
    size = max(16, round(min(out.size) / 30))
    try:
        font = ImageFont.truetype(detect.FONT_PATH, size)
    except OSError:
        font = ImageFont.load_default(size)
    bar = size * 2
    canvas = Image.new("RGB", (out.width, out.height + bar), "white")
    canvas.paste(out, (0, bar))
    ImageDraw.Draw(canvas).text((10, size // 2), title, fill="black", font=font)
    return canvas


def save_diff(path: Path, data: bytes, left: tuple, right: tuple) -> None:
    """left/right: (제목, products, detections). 두 패널을 가로로 붙여 저장한다."""
    image = detect.read_image(data)
    a, b = panel(image, left[1], left[2], left[0]), panel(image, right[1], right[2], right[0])
    canvas = Image.new("RGB", (a.width + b.width, a.height), "white")
    canvas.paste(a, (0, 0))
    canvas.paste(b, (a.width, 0))
    path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(path, format="JPEG", quality=90)


def label(combo: tuple) -> str:
    p, q, pc, qc = combo
    return f"{p}@{pc:g} + {q}@{qc:g}"


def compare(pds: dict[str, YOLO], qis: dict[str, YOLO], base_pd: str, base_qi: str, args) -> None:
    """프레임마다 모든 PD·QI를 한 번씩 돌려 두고, 조합(모델 × 임곗값)별 판정을 기준과 비교한다.
    기준 = 지금 모델 + 지금 코드의 PD_CONF·QI_CONF. 판정은 api.process_frame + stream.judge와 같다
    (제품이 없으면 NO_PRODUCT).

    임곗값마다 다시 추론하지 않는다: 가장 낮은 값으로 한 번 추론하고, 높은 값은 결과를 걸러 얻는다.
    NMS(Ultralytics 안·detect.inspect의 타일 NMS)는 점수 순으로 고르므로, 어떤 상자가 남을지는 그보다
    점수가 높은 상자만 정한다 — 낮게 추론한 뒤 거른 결과는 그 값으로 추론한 결과와 같다.
    Ultralytics와 cv2 모두 conf > 기준으로 남기므로 거를 때도 >를 쓴다."""
    pd_confs, qi_confs = args.pd_conf, args.qi_conf
    detect.PD_CONF = min([*pd_confs, BASE_PD_CONF])  # find_products가 이 값을 읽는다
    qi_floor = min([*qi_confs, BASE_QI_CONF]) * (1 - REVIEW_MARGIN)
    base = (base_pd, base_qi, BASE_PD_CONF, BASE_QI_CONF)
    combos = [c for c in itertools.product(pds, qis, pd_confs, qi_confs) if c != base]

    def decide(row, combo: tuple) -> tuple[str, float, list[dict], list[dict]]:
        """(decision, score, 제품 상자, 판정에 든 결함)"""
        p, q, pc, qc = combo
        products = [x for x in row[1][p] if x["conf"] > pc]
        if not products:
            return "NO_PRODUCT", 0.0, [], []
        found = [x for x in row[2][q] if x["conf"] > qc * (1 - REVIEW_MARGIN)]  # api.QI_MIN_CONF와 같은 식
        verdict = judge(products, found, qc)
        return verdict["decision"], verdict["score"], products, verdict["detections"]

    rows, times, skipped = [], defaultdict(int), 0
    print(f"\n== 샘플 비교 (최대 {args.max_samples}장)  기준: {label(base)}")
    print(f"  PD_CONF {[f'{c:g}' for c in pd_confs]} × QI_CONF {[f'{c:g}' for c in qi_confs]} — 조합 {len(combos)}개")
    for name, data in samples(args.samples, args.max_samples, args.video_fps):
        try:
            image = detect.read_image(data)
        except (OSError, Image.DecompressionBombError):
            skipped += 1
            continue
        pd_out, qi_out = {}, {}
        for key, model in pds.items():
            t = time.monotonic()
            pd_out[key] = detect.find_products(model, image)
            times[key] += time.monotonic() - t
        for key, model in qis.items():
            t = time.monotonic()
            qi_out[key] = detect.inspect(model, image, qi_floor)
            times[key] += time.monotonic() - t
        row = (name, pd_out, qi_out)
        # 비교 이미지용 바이트는 판정이 바뀐 프레임만 남긴다 (데이터셋이 크면 전부 들고 있을 수 없다)
        base_decision = decide(row, base)[0]
        keep = args.diff_out and any(decide(row, c)[0] != base_decision for c in combos)
        rows.append((*row, data if keep else None))
    print(f"  프레임 {len(rows)}장" + (f" (읽지 못해 건너뜀 {skipped})" if skipped else ""))
    if not rows:
        return
    for key in [*pds, *qis]:
        print(f"  평균 추론 {key}: {round(times[key] / len(rows) * 1000)} ms")

    table = [(Counter(decide(row, base)[0] for row in rows), None, label(base) + "  ← 기준")]
    for combo in combos:
        p, q, pc, qc = combo
        print(f"\n  -- 기준 ({label(base)})  →  ({label(combo)})")
        pairs = [(row, decide(row, base), decide(row, combo)) for row in rows]
        old_count = Counter(old[0] for _, old, _ in pairs)
        new_count = Counter(new[0] for _, _, new in pairs)
        print("  " + " / ".join(f"{d} {old_count[d]}→{new_count[d]}" for d in ORDER))
        matrix = Counter((old[0], new[0]) for _, old, new in pairs)
        corner = "지금 \\ 새"
        print(f"  {corner:>12}" + "".join(f"{d:>12}" for d in ORDER))
        for o in ORDER:
            print(f"  {o:>12}" + "".join(f"{matrix[o, n]:>12}" for n in ORDER))
        same = sum(matrix[d, d] for d in ORDER)
        print(f"  일치 {same}/{len(pairs)} ({same / len(pairs):.0%})")
        table.append((new_count, same / len(pairs), label(combo)))
        diffs = [(row, old, new) for row, old, new in pairs if old[0] != new[0]]
        for row, old, new in diffs[:args.show_diffs]:
            print(f"    {row[0]}: {old[0]} {old[1]:.2f} → {new[0]} {new[1]:.2f}")
        if len(diffs) > args.show_diffs:
            print(f"    … 외 {len(diffs) - args.show_diffs}장")
        if args.diff_out and diffs:
            folder = args.diff_out / f"{p}@{pc:g}+{q}@{qc:g}".replace(" ", "_")
            for row, old, new in diffs:
                frame = row[0].strip("/").replace("/", "__").replace("#", "_")  # data__samples__a__1.jpg
                path = folder / f"{frame}_{old[0]}_to_{new[0]}.jpg"
                save_diff(path, row[3],
                          (f"기준 {label(base)}: {old[0]} {old[1]:.2f}", old[2], old[3]),
                          (f"{label(combo)}: {new[0]} {new[1]:.2f}", new[2], new[3]))
            print(f"    비교 이미지 {len(diffs)}장 → {folder}")

    # 숫자 열을 앞에 둔다 — 조합 이름의 한글 폭 때문에 뒤 열이 어긋나지 않게
    print(f"\n== 조합별 요약 (프레임 {len(rows)}장, 일치 = 기준과 판정이 같은 비율)")
    print("  " + "".join(f"{d:>11}" for d in ORDER) + f"{'일치':>7}   조합")
    for counts, same, name in table:
        print("  " + "".join(f"{counts[d]:>11}" for d in ORDER) + (f"{same:>8.0%}" if same is not None else f"{'-':>8}")
              + f"   {name}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pd", nargs="+", type=Path, required=True, help="새 PD 모델 .pt")
    parser.add_argument("--qi", nargs="+", type=Path, required=True, help="새 QI 모델 .pt")
    parser.add_argument("--samples", nargs="*", type=Path, default=[], help="비교용 사진·동영상 (폴더 또는 파일)")
    parser.add_argument("--max-samples", type=int, default=300)
    parser.add_argument("--video-fps", type=float, default=2, help="동영상에서 초당 고를 프레임 수")
    parser.add_argument("--show-diffs", type=int, default=20, help="조합마다 출력할 판정이 바뀐 프레임 수")
    parser.add_argument("--diff-out", type=Path, help="판정이 바뀐 프레임의 비교 이미지(기준 | 조합)를 저장할 폴더")
    parser.add_argument("--pd-conf", nargs="+", type=float, default=[BASE_PD_CONF],
                        help=f"비교할 PD_CONF 값들 (기본: 지금 코드 값 {BASE_PD_CONF})")
    parser.add_argument("--qi-conf", nargs="+", type=float, default=[BASE_QI_CONF],
                        help=f"비교할 QI_CONF 값들 (기본: 지금 코드 값 {BASE_QI_CONF}). 하한은 값 × (1 - REVIEW_MARGIN)")
    args = parser.parse_args()

    print(f"ultralytics {ultralytics.__version__}  PD_IMGSZ {detect.PD_IMGSZ}  TILE {detect.TILE}/STEP {detect.STEP}  "
          f"PD_CONF {detect.PD_CONF}  QI_CONF {detect.QI_CONF} (QI 하한 {QI_MIN_CONF:.2f})")
    base_pd, _ = check_model(detect.PD_MODEL, "PD", None)
    base_qi, _ = check_model(detect.QI_MODEL, "QI", None)
    pds = {f"PD {stamp(detect.PD_MODEL)}(지금)": base_pd} if base_pd else {}
    qis = {f"QI {stamp(detect.QI_MODEL)}(지금)": base_qi} if base_qi else {}

    problems = []
    for kind, paths, base, bucket in [("PD", args.pd, base_pd, pds), ("QI", args.qi, base_qi, qis)]:
        for path in paths:
            model, issues = check_model(path, kind, base)
            problems += [(level, f"{path.name}: {text}") for level, text in issues]
            if model is not None:
                bucket[f"{kind} {stamp(path)}"] = model

    if args.samples:
        if base_pd and base_qi:
            compare(pds, qis, next(iter(pds)), next(iter(qis)), args)
        else:
            print("\n지금 모델을 올리지 못해 샘플 비교를 건너뛴다")

    fails = [text for level, text in problems if level == "FAIL"]
    warns = [text for level, text in problems if level == "WARN"]
    print("\n== 요약")
    for text in fails:
        print(f"  [FAIL] {text}")
    for text in warns:
        print(f"  [WARN] {text}")
    if fails:
        print(f"  결과: 지금 코드로는 교체 불가 (FAIL {len(fails)})")
    else:
        print("  결과: 코드상 교체 가능" + (f" — WARN {len(warns)}개는 위 내용을 확인" if warns else ""))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
