# A4 WebRTC 게이트웨이 — GPU·torch 불필요. A4 API 이미지와 별개로 빌드한다.
FROM python:3.12-slim

# 로그·판정 프레임의 날짜와 시각을 한국 시간으로 (DEPLOY.md §4.2).
# tzdata가 없으면 TZ를 설정해도 에러 없이 UTC로 돈다 — 베이스 이미지마다 포함 여부가 달라 직접 설치한다.
ENV TZ=Asia/Seoul
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY *.py ./

EXPOSE 8080
# WebRTC는 UDP 포트를 동적으로 쓰므로 docker run --network host 로 실행한다 (README).
CMD ["uvicorn", "gateway:app", "--host", "0.0.0.0", "--port", "8080"]
