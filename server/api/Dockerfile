# 불량 판정 API 이미지 (PD·QI 추론 + /ws + DB 기록) — api/ 폴더 기준으로 빌드한다 (docker build -t a4-api api/)
FROM pytorch/pytorch:2.10.0-cuda13.0-cudnn9-devel

# 로그·판정 프레임의 날짜와 시각을 한국 시간으로 (DEPLOY.md §4.2).
# tzdata가 없으면 TZ를 설정해도 에러 없이 UTC로 돈다 — 베이스 이미지마다 포함 여부가 달라 직접 설치한다.
# fonts-nanum: 결과 오버레이의 한글 결함 이름 (detect.FONT_PATH).
# libgl1, libglib2.0-0: ultralytics가 설치하는 opencv-python이 import 시 요구한다.
ENV TZ=Asia/Seoul
# apt 미러: gpudev가 쓰는 KT DNS가 archive.ubuntu.com을 접속 안 되는 IP 하나(91.189.90.11)로만 알려 준다 (2026-11-09 확인)
RUN sed -i 's|http://archive.ubuntu.com/ubuntu|http://mirror.kakao.com/ubuntu|g' /etc/apt/sources.list.d/ubuntu.sources
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    tzdata fonts-nanum libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --break-system-packages -r requirements.txt

COPY *.py ./

EXPOSE 8000
# PD·QI 모델(.pt)은 이미지에 넣지 않는다 — docker run -v $PWD/models:/app/models:ro (detect.MODEL_DIR)
CMD ["uvicorn", "api:app", "--host", "0.0.0.0", "--port", "8000"]
