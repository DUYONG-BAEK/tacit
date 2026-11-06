"""PD(제품 탐지) → QI(불량 탐지) 모델 래퍼. api.py가 쓴다.

    read_image(data)                         프레임 바이트 -> BGR 배열 (qi_test.py의 cv2.imread와 같은 채널 순서)
    read_frame(data)                         /ws 프레임 (게이트웨이의 I420 원본 또는 이미지 파일) -> (BGR, rx_at, 이미지 파일 여부)
    encode_jpeg(image, quality)              BGR -> JPEG 바이트 (제품 프레임 사진)
    find_products(pd, image)                 PD 상자 목록. 하나라도 있으면(클래스 무관) 그 프레임을 QI로 넘긴다 (api.process_frame)
    inspect(qi, image, conf)                 QI 결함 목록. 프레임을 640 타일로 덮어 추론 -> 프레임 좌표 -> 클래스별 NMS
    filter_in_products(detections, products) 상자 중심이 어느 제품 상자 안에 있는 결함만 남긴다 (stream.judge)
    draw(image, detections)                  결함 상자를 상자 바깥쪽 테두리로 그린 PIL 이미지 (결과 오버레이)

검출 결과는 [{"label": 클래스 이름, "conf": 신뢰도, "box": [x1, y1, x2, y2]}] (프레임 픽셀 좌표, conf 내림차순).
"""

import io
import struct
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

MODEL_DIR = Path("models")  # 호스트 폴더를 읽기 전용으로 붙인다 (docker run -v $PWD/models:/app/models:ro)
PD_MODEL = MODEL_DIR / "yolo_product_detection_model_20261020111232.pt"
QI_MODEL = MODEL_DIR / "yolo_quality_inspection_model_20261021091500.pt"

DEVICE = 0  # 컨테이너에 보이는 첫 GPU (docker run --gpus). GPU가 없으면 api.py 기동 시 예열에서 멈춘다 — CPU로 조용히 돌지 않게
PD_IMGSZ = 640  # PD 학습 imgsz — 프레임 전체를 줄여서 본다
PD_CONF = 0.4  # PD 상자로 인정하는 신뢰도 (Ultralytics predict 기본값)
# QI 결함 판정 기준 (임시, 가모델). 프레임 점수 = 그 프레임 결함의 최대 conf, 결함이 없으면 0.
# 판정은 stream.py: 점수 > QI_CONF × 1.1이면 NG, < QI_CONF × 0.9이면 OK, 그 사이는 REVIEW.
QI_CONF = 0.4
TILE, STEP = 640, 512  # QI 학습 격자 (qi_test.py와 같음, 겹침 128px)
NMS_IOU = 0.5  # 겹친 타일에서 같은 자리가 두 번 잡힌 것 정리 (qi_test.py와 같은 클래스별 NMS)

FONT_PATH = "/usr/share/fonts/truetype/nanum/NanumGothic.ttf"  # 한글 클래스 이름용 (Dockerfile의 fonts-nanum)


def read_image(data: bytes) -> np.ndarray:
    """프레임 바이트 -> BGR uint8 (H, W, 3). EXIF 회전을 반영한다 (폰 사진).
    이미지가 아니거나 잘렸으면 OSError, 픽셀 수가 Pillow 한도의 2배를 넘으면 Image.DecompressionBombError.
    """
    with Image.open(io.BytesIO(data)) as image:
        rgb = np.asarray(ImageOps.exif_transpose(image).convert("RGB"))
    return np.ascontiguousarray(rgb[:, :, ::-1])


I420_MAGIC = b"I420"
I420_HEADER = struct.Struct("<4sHHq")  # magic, width, height, rx_at(epoch ms) — 게이트웨이가 붙인다 (gateway.to_i420)


def read_frame(data: bytes) -> tuple[np.ndarray, int | None, bool]:
    """/ws 프레임 하나 -> (BGR 프레임, rx_at, 이미지 파일 여부).
    게이트웨이는 디코딩한 원본 픽셀을 I420으로 보낸다 (머리말 I420_HEADER + Y·U·V 평면). rx_at은 게이트웨이가
    프레임을 받은 시각(epoch ms). 그 밖(JPEG 등 이미지 파일, 테스트 클라이언트·직접 연결)은 read_image로 읽고 rx_at은 None.
    I420 길이가 머리말과 맞지 않으면 OSError (깨진 프레임으로 건너뛴다).
    """
    if data[:4] != I420_MAGIC:
        return read_image(data), None, True
    if len(data) < I420_HEADER.size:
        raise OSError("I420 머리말이 잘렸다")
    _, w, h, rx_at = I420_HEADER.unpack_from(data)
    if w == 0 or h == 0 or w % 2 or h % 2 or len(data) - I420_HEADER.size != w * h * 3 // 2:
        raise OSError(f"I420 크기가 맞지 않는다 ({w}x{h}, {len(data)}바이트)")
    yuv = np.frombuffer(data, np.uint8, offset=I420_HEADER.size).reshape(h * 3 // 2, w)
    return cv2.cvtColor(yuv, cv2.COLOR_YUV2BGR_I420), rx_at, False


def encode_jpeg(image: np.ndarray, quality: int) -> bytes:
    """BGR 프레임 -> JPEG 바이트 (제품 프레임 사진 저장용)."""
    ok, buffer = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise OSError("JPEG 인코딩 실패")
    return buffer.tobytes()


def find_products(model, image: np.ndarray) -> list[dict]:
    """PD 상자 목록 (클래스 무관). 비어 있으면 제품이 없는 프레임이다."""
    result = model.predict(image, imgsz=PD_IMGSZ, conf=PD_CONF, device=DEVICE, verbose=False)[0]
    return _as_list(model.names, *_boxes(result, 0, 0))


def inspect(model, image: np.ndarray, conf: float) -> list[dict]:
    """프레임 전체를 TILE 타일(간격 STEP)로 덮어 QI 추론 -> 프레임 좌표로 옮김 -> 클래스별 NMS.
    conf 미만의 결함은 받지 않는다.
    """
    h, w = image.shape[:2]
    offsets = [(x, y) for y in _starts(h) for x in _starts(w)]
    crops = [image[y:y + TILE, x:x + TILE] for x, y in offsets]
    results = model.predict(crops, imgsz=TILE, conf=conf, device=DEVICE, verbose=False)
    parts = [_boxes(r, x, y) for (x, y), r in zip(offsets, results, strict=True)]
    xyxy, cls, scores = (np.concatenate(p) for p in zip(*parts))
    if len(xyxy) == 0:
        return []
    rects = [[x1, y1, x2 - x1, y2 - y1] for x1, y1, x2, y2 in xyxy.tolist()]
    keep = np.asarray(cv2.dnn.NMSBoxesBatched(rects, scores.tolist(), cls.tolist(), conf, NMS_IOU), int).ravel()
    return _as_list(model.names, xyxy[keep], cls[keep], scores[keep])


def filter_in_products(detections: list[dict], products: list[dict]) -> list[dict]:
    """결함 상자의 중심이 어느 제품 상자 안(경계 포함)에 있는 결함만 남긴다. 순서(conf 내림차순)는 유지한다.
    제품 밖(배경)의 오탐을 버리기 위해서다. 제품 상자가 결함 부위를 덜 덮으면 그 결함도 버려진다.
    """
    boxes = [p["box"] for p in products]
    kept = []
    for d in detections:
        x1, y1, x2, y2 = d["box"]
        cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
        if any(px1 <= cx <= px2 and py1 <= cy <= py2 for px1, py1, px2, py2 in boxes):
            kept.append(d)
    return kept


def draw(image: np.ndarray, detections: list[dict]) -> Image.Image:
    """BGR 프레임 위에 결함 상자와 "이름 conf"를 빨간색으로 그린다.
    테두리와 글자는 상자 바깥쪽에 그린다 — 결함은 수십 px로 작아서, 안쪽에 그리면 결함을 덮는다.
    """
    out = Image.fromarray(np.ascontiguousarray(image[:, :, ::-1]))
    pen = ImageDraw.Draw(out)
    size = max(16, round(min(out.size) / 40))  # 1080p에서 27px
    width = max(2, round(min(out.size) / 360))  # 1080p에서 3px
    try:
        font = ImageFont.truetype(FONT_PATH, size)
    except OSError:  # 폰트가 없는 환경 (Docker 밖) — 한글이 깨져 보일 뿐 그리기는 계속한다
        font = ImageFont.load_default(size)
    for d in detections:
        x1, y1, x2, y2 = d["box"]
        # Pillow는 width만큼 사각형 안쪽으로 채우므로, width만큼 넓혀 그리면 테두리가 원래 상자 바깥에 놓인다
        pen.rectangle((x1 - width, y1 - width, x2 + width, y2 + width), outline=(255, 0, 0), width=width)
        text = f"{d['label']} {d['conf']:.2f}"
        # 글자 배경의 아래 끝을 테두리 위에 맞춘다. 높이는 폰트마다 size보다 커서(한글 폰트) 실제 글자 상자로 잰다.
        # 위에 자리가 없으면(프레임 위 끝) 테두리 아래에 둔다
        _, top, _, bottom = pen.textbbox((0, 0), text, font=font)
        y = y1 - width - bottom
        if y + top < 0:
            y = y2 + width - top
        origin = (x1 - width, y)
        pen.rectangle(pen.textbbox(origin, text, font=font), fill=(255, 0, 0))
        pen.text(origin, text, fill=(255, 255, 255), font=font)
    return out


def _starts(length: int) -> list[int]:
    """0부터 STEP 간격, 마지막 타일은 그림 끝에 맞춤 (1920 -> 0,512,1024,1280 / 1080 -> 0,440).
    TILE보다 짧은 변은 타일 하나로 덮는다 (모델이 letterbox로 채운다).
    """
    if length <= TILE:
        return [0]
    s = list(range(0, length - TILE + 1, STEP))
    if s[-1] + TILE < length:
        s.append(length - TILE)
    return s


def _boxes(result, dx: int, dy: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Ultralytics 결과 -> (xyxy (N, 4), 클래스 번호 (N,), conf (N,)). 좌표는 (dx, dy)만큼 옮긴다."""
    boxes = result.boxes
    xyxy = boxes.xyxy.cpu().numpy().reshape(-1, 4) + (dx, dy, dx, dy)
    return xyxy, boxes.cls.cpu().numpy().astype(int).ravel(), boxes.conf.cpu().numpy().ravel()


def _as_list(names: dict, xyxy: np.ndarray, cls: np.ndarray, scores: np.ndarray) -> list[dict]:
    order = np.argsort(-scores, kind="stable")
    return [{"label": names[int(cls[i])], "conf": float(scores[i]), "box": [float(v) for v in xyxy[i]]}
            for i in order]
