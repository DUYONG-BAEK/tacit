# 제품 단위 판정·작업자 피드백·영상 화질 — 변경 규격 초안

작성: 2026-11-06. **상태: 구현 끝 (2026-11-06), 실서버·실기기 확인 전.** 서버·모바일·HMD 세 쪽을 함께 바꿨다. 구현하며 정하거나 계획과 달리 한 것은 §14. 배포는 `DEPLOY.md` §5.B.

| 문서 | 역할 |
|---|---|
| `PLAN_product_feedback.md` (이 문서) | 이번 변경의 목표 규격과 단계 |
| `A4_WEBRTC_API.md` | 앱 연동 규격 (2026-11-06 개정 — 이 문서의 §3·§4를 옮겼다) |
| `PLAN_realtime_inspect.md` | 직전 변경 (프레임 단위 판정). `inspect`·`searching`은 그대로 유지한다 |
| `DEPLOY.md` | 구성·배포·운영. 구현 후 DB·저장·로그 절을 고친다 |

전제: 판정 모델(PD·QI)은 가모델이다. 이 문서는 **기능 파이프라인**만 다루고 모델 성능은 다루지 않는다.

---

## 1. 결정

| # | 결정 | 내용 |
|---|---|---|
| D1 | 판정은 전부 서버 | 제품 구간·결함 확정·제품 판정·면 진행 계산을 서버가 한다. 모바일은 **영상 중계, 결과 표시(폰·HMD), 피드백 입력 전달**만 한다. 앱 `AiFlow`의 구간·확정 로직은 서버 `stream.py`로 옮기고 앱에서 지운다 |
| D2 | 제품 구간 규칙은 지금 앱 규칙 그대로 | 첫 제품 프레임에서 시작, 마지막 제품 프레임 뒤 `SEGMENT_END_S`(1.0초) 동안 제품 프레임이 없거나 연결이 끊기면 끝. `searching` 한 장은 구간을 끝내지 않는다. 한 번에 제품 하나를 든다고 본다 (추적기는 쓰지 않음, §13) |
| D3 | 결함 확정·제품 판정도 그대로 | 구간 안 최근 `CONFIRM_WINDOW`(5)장 중 `CONFIRM_HITS`(2)장 이상이 NG·REVIEW면 확정 — 그 안에 NG가 있으면 NG, 없으면 REVIEW, 나중에 NG로 올라갈 수 있다. 확정이 없으면 **합격(PASS)**, 있으면 **불합격(FAIL)** (REVIEW 확정도 불합격) |
| D4 | 면 진행 | PD 라벨(상면·엣지·하면비닐)별로 그 제품에서 잡힌 프레임 수를 센다. `FACE_MIN_FRAMES`(3)장 이상이면 그 면을 "봄"으로 친다. 세 면을 모두 보면 `complete = true`. **`complete`는 구간을 끝내지 않는다** (끝은 D2의 시간 규칙) |
| D5 | 모드 | **수동**: 제품이 끝나면 폰 화면에 결과와 버튼 3개를 띄운다. **자동**: 결과만 띄우고 피드백을 받지 않는다. 앱 M-01에서 고르고, 연결 때 `/offer`로 서버에 알린다. 모드를 바꾸면 앱이 다시 연결한다 (새 세션) |
| D6 | 피드백 값 | 버튼 3개: **맞음**(`CORRECT`), **틀림**(`WRONG`), **확인필요**(`UNSURE`). 서버가 정하는 값 2개: **미입력**(`NOT_ENTERED` — 수동 모드 제품의 초기값), **피드백 없음**(`NONE` — 자동 모드 제품의 초기값). 버튼을 누르기 전에 다음 제품이 끝나면 앱은 새 결과로 넘어가고, 이전 제품은 미입력으로 남는다 |
| D7 | 피드백 수정 | 이력(M-05)에서 **누구나** 어느 제품의 피드백이든 맞음·틀림·확인필요로 바꿀 수 있다. 인증은 없다. 바꿀 때마다 변경 기록을 남긴다 |
| D8 | 학습 라벨 | 최신 피드백으로 정한다. 맞음 → 제품 판정 그대로, 틀림 → 반대, 확인필요·미입력·피드백 없음 → 학습에서 제외. 기록과 이미지는 지우지 않는다 |
| D9 | 프레임 저장 | 결함 프레임만 1초에 1장 저장하던 방식에서 **제품이 보인 프레임 전부를 사진(JPEG)으로** 저장하는 방식으로 바꾼다. 영상 파일로는 저장하지 않는다. 작업자·작업장이 찍히는 것은 문제없음으로 확인됐다. 세부는 §6 |
| D10 | 자동 삭제 없음 | 프레임·오버레이·로그를 자동으로 지우지 않는다. 디스크 사용량은 모바일 앱에서 본다 (`GET /storage`, §4.7) |
| D11 | 영상 경로는 WebRTC 유지, 손실 압축 3번 → 2번 | 게이트웨이의 JPEG 재압축을 없애고 디코딩한 원본 픽셀을 API로 넘긴다. 남는 압축은 HMD JPEG와 폰 VP8이고, 둘 다 압축 정도를 낮춘다 (§7) |
| D12 | 게이트웨이 프레임 간격 버그 수정 | 실측 초당 7.2장의 원인으로 보이는 간격 제한을 없앤다 (§7.2) |
| D13 | `inspect`·`searching` 유지 | 실시간 결함 표시용으로 지금 규격 그대로 둔다. 필드만 추가한다 (§3.2) |

---

## 2. 흐름

```
HMD ─MJPEG(JPEG q95)─▶ 폰 ─WebRTC VP8─▶ 게이트웨이 ─원본 I420─▶ 판정 API ─▶ DB·저장소
                                                                     │ (제품 프레임 사진 저장, §6)
폰 ◀── 데이터 채널: searching · inspect(프레임마다) · product_end(제품마다) ──┘
폰 ─BLE─▶ HMD: LIVE(결함 원) · FACES(면 칩) · RESULT(제품 결과)
폰 ─HTTP─▶ PUT /products/{id}/feedback · GET /products · GET /storage
```

한 제품의 메시지 순서:

```
searching ▶ inspect(product_id=P1, faces …) × N ▶ (1초 무소식) ▶ product_end(P1) ▶ searching …
```

---

## 3. 서버 → 앱 메시지 (데이터 채널)

### 3.1 `searching`

변경 없음.

### 3.2 `inspect` — 필드 추가

지금 필드는 그대로 두고 아래를 더한다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `product_id` | string | 이 프레임이 속한 제품 (§5.1 형식) |
| `faces` | object | 이 제품에서 지금까지 면별로 잡힌 프레임 수. 예: `{"상면": 12, "엣지": 3, "하면비닐": 0}`. 키는 PD 클래스 이름 (모델에서 읽음) |
| `faces_seen` | string[] | `FACE_MIN_FRAMES` 이상 잡힌 면 |
| `complete` | bool | 모든 면을 봤는지 |
| `rx_at` | int | 게이트웨이가 이 프레임을 받은 시각 (epoch ms). 지연 측정용 (§10) |

### 3.3 `product_end` — 새로 추가

```json
{
  "state": "product_end",
  "product_id": "c311ab4b00e5-p0007",
  "started_at": "2026-11-06T10:12:03.120+09:00",
  "ended_at": "2026-11-06T10:12:09.870+09:00",
  "end_reason": "gap",
  "decision": "FAIL",
  "ai_decision": "NG",
  "defect_label": "칩찍힘",
  "frames": 61,
  "defect_frames": 7,
  "faces": {"상면": 30, "엣지": 22, "하면비닐": 9},
  "faces_seen": ["상면", "엣지", "하면비닐"],
  "complete": true,
  "best_result_id": "c311ab4b00e5-000123",
  "result_ids": ["c311ab4b00e5-000123", "c311ab4b00e5-000131"],
  "feedback_mode": "manual",
  "feedback": "NOT_ENTERED"
}
```

| 필드 | 설명 |
|---|---|
| `end_reason` | `gap`(1초 무소식) · `disconnect`(연결 끊김) |
| `decision` | 제품 판정 `PASS` / `FAIL` (D3) |
| `ai_decision` | 확정 결과 `OK` / `NG` / `REVIEW` (확정이 없으면 `OK`) |
| `defect_label` | 확정을 만든 프레임의 결함 종류. 합격이면 `null` |
| `best_result_id` | 가장 점수가 높은 저장 결함 프레임. 합격이면 `null`. 확대 보기에 쓴다 |
| `result_ids` | 저장된 결함 프레임, 점수 높은 순 |
| `feedback` | 초기값. 수동 `NOT_ENTERED`, 자동 `NONE` |

- `end_reason = disconnect`이면 메시지를 보낼 수 없다. 서버는 제품을 기록만 하고, 앱은 다시 연결한 뒤 `GET /products?station=…&limit=1`로 마지막 제품을 받아 카드에 띄운다.

---

## 4. HTTP API

게이트웨이(`:8080`)는 아래 경로를 모두 판정 API(`:8000`)로 그대로 넘긴다 (지금 `/results/*` 중계와 같다).

### 4.1 `POST /offer` — 필드 추가

```json
{ "sdp": "…", "type": "offer", "station_id": "ST-02", "feedback_mode": "manual" }
```

- `feedback_mode`: `manual` / `auto`. 없으면 `auto`로 본다 (옛 앱 호환). 다른 값이면 `422`.
- 게이트웨이는 이 값을 `/ws?…&mode=`로 넘기고, API는 세션에 기록한다.

### 4.2 `PUT /products/{product_id}/feedback` — 피드백 입력·수정

```json
{ "value": "CORRECT", "source": "live", "device": "SM-S931N-1a2b" }
```

| 필드 | 설명 |
|---|---|
| `value` | `CORRECT` / `WRONG` / `UNSURE`만 받는다 (`NOT_ENTERED`·`NONE`은 서버만 정한다) |
| `source` | `live`(M-07 현장 카드) / `history`(M-05) |
| `device` | 앱이 정하는 기기 식별 문자열. 인증이 아니라 변경 기록용이다 |

- 응답 `200`: 바뀐 제품 (§4.4와 같은 모양). 같은 값으로 다시 보내도 `200`이고 변경 기록은 남기지 않는다.
- `404`: 없는 제품. `422`: 잘못된 `value`·`source`.
- 처음 입력과 수정을 구분하지 않는다. 모드와 상관없이 어느 제품이든 바꿀 수 있다 (D7).

### 4.3 `GET /products` — 이력 목록

```
GET /products?station=ST-02&from=2026-11-06&to=2026-11-06&feedback=NOT_ENTERED,UNSURE&decision=FAIL&limit=50&before=<product_id>
```

- 최신 순. `before`로 다음 페이지를 받는다. 필터는 모두 선택.
- 항목 필드: `product_id`, `station_id`, `started_at`, `ended_at`, `decision`, `ai_decision`, `defect_label`, `frames`, `defect_frames`, `complete`, `best_result_id`, `feedback_mode`, `feedback`, `feedback_at`.
- 응답: `{"items": [...], "next_before": "<product_id>" | null}`.

### 4.4 `GET /products/{product_id}` — 제품 상세

§4.3 항목 + `faces`, `faces_seen`, `result_ids`, `threshold`, `checkpoint`, `end_reason`, `feedback_log`(아래, 오래된 순).

```json
"feedback_log": [
  { "before": "NOT_ENTERED", "after": "UNSURE", "source": "live", "device": "…", "changed_at": "…" },
  { "before": "UNSURE", "after": "WRONG", "source": "history", "device": "…", "changed_at": "…" }
]
```

### 4.5 `GET /products/{product_id}/frames` — 제품의 프레임 목록

```
GET /products/{product_id}/frames?offset=0&limit=100
```

- 항목: `seq`, `ts`, `decision`, `score`, `defect_label`, `products`(PD 상자), `detections`, `frame_size`, `result_id`(저장된 결함 프레임이면).
- 응답: `{"items": [...], "total": 61}`.

### 4.6 `GET /products/{product_id}/frames/{seq}.jpg` — 프레임 한 장

- 저장된 사진 파일(§6)을 그대로 준다. `?overlay=1`이면 결함 상자를 그려서 준다 (`detect.draw`와 같은 그리기).
- 이력 화면은 목록을 먼저 띄우고 이미지를 차례로 받는다.

### 4.7 `GET /storage` — 디스크 사용량

```json
{
  "measured_at": "2026-11-06T10:15:00+09:00",
  "disk_total_bytes": 2000000000000,
  "disk_used_bytes": 812000000000,
  "disk_free_bytes": 1188000000000,
  "data_bytes": { "product_frames": 640000000000, "results": 21000000000, "frames": 3000000000, "logs": 900000000 },
  "per_day_bytes": [ { "date": "2026-11-05", "bytes": 61000000000 } ]
}
```

- 서버가 백그라운드에서 `STORAGE_SCAN_S`(300초)마다 계산해 둔 값을 준다. 요청마다 폴더를 훑지 않는다.
- 디스크 값은 데이터 폴더가 있는 파일시스템 기준.

### 4.8 그대로 두는 것

`GET /results/{result_id}`, `GET /results/{result_id}/overlay`는 그대로 둔다 (실시간 확대 보기용).

---

## 5. DB

### 5.1 식별자

- `product_id` = `<session_id>-p<4자리 순번>` (예: `c311ab4b00e5-p0007`). 직접 연결이면 `direct_`를 붙인다 (`result_id`와 같은 규칙). 앱은 형식을 해석하지 않는다.

### 5.2 테이블 (추가·변경)

```sql
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS feedback_mode text NOT NULL DEFAULT 'auto';  -- manual | auto

CREATE TABLE IF NOT EXISTS products (
  product_id     text PRIMARY KEY,
  session_id     text NOT NULL REFERENCES sessions,
  station_id     text REFERENCES stations,
  feedback_mode  text NOT NULL,                 -- manual | auto (세션 값 복사)
  started_at     timestamptz NOT NULL,
  ended_at       timestamptz,
  end_reason     text,                          -- gap | disconnect
  decision       text,                          -- PASS | FAIL
  ai_decision    text,                          -- OK | NG | REVIEW
  defect_label   text,
  frames         int NOT NULL DEFAULT 0,
  defect_frames  int NOT NULL DEFAULT 0,
  faces          jsonb,                         -- {"상면": 30, …}
  complete       boolean NOT NULL DEFAULT false,
  best_result_id text REFERENCES results,
  threshold      double precision,
  checkpoint     text,
  feedback       text NOT NULL,                 -- CORRECT | WRONG | UNSURE | NOT_ENTERED | NONE
  feedback_at    timestamptz
);
CREATE INDEX IF NOT EXISTS products_station_started ON products (station_id, started_at DESC);
CREATE INDEX IF NOT EXISTS products_feedback ON products (feedback);

CREATE TABLE IF NOT EXISTS frames (            -- 처리한 프레임 한 장 = 한 행 (제품 프레임만)
  session_id   text NOT NULL REFERENCES sessions,
  seq          int NOT NULL,
  product_id   text NOT NULL REFERENCES products,
  ts           timestamptz NOT NULL,
  file         text,                            -- §6 사진 파일 경로 (<날짜>/<session_id>/<seq 6자리>.jpg). 저장 실패면 NULL
  decision     text NOT NULL,                   -- OK | NG | REVIEW
  score        double precision NOT NULL,
  defect_label text,
  products     jsonb NOT NULL,                  -- PD 상자
  detections   jsonb NOT NULL,
  frame_size   jsonb NOT NULL,
  result_id    text REFERENCES results,         -- 저장된 결함 프레임이면
  PRIMARY KEY (session_id, seq)
);
CREATE INDEX IF NOT EXISTS frames_product ON frames (product_id, seq);

CREATE TABLE IF NOT EXISTS feedback_log (
  id          bigserial PRIMARY KEY,
  product_id  text NOT NULL REFERENCES products,
  before      text NOT NULL,
  after       text NOT NULL,
  source      text NOT NULL,                    -- live | history
  device      text,
  changed_at  timestamptz NOT NULL DEFAULT now()
);
```

- `results` 테이블과 결함 프레임 저장(1초에 1장, 오버레이 JPEG)은 그대로 둔다. 실시간 확대 보기와 `best_result_id`가 쓴다.
- `frames`는 스테이션 하나당 초당 최대 10행이다 (시간당 약 36,000행). PostgreSQL에서 문제없는 양으로 본다.
- `products` 행은 제품 시작 때 넣고(`decision` NULL), 끝날 때 채운다. 연결이 끊기면 `end_reason = 'disconnect'`로 채운다. 서버가 기록 중 멈춘(재시작 등) 행은 `ended_at`·`decision`이 NULL로 남는다 — GPU마다 API 컨테이너가 DB를 나눠 쓸 수 있어(`DEPLOY.md` §6.4) 기동 때 일괄로 닫지 않는다 (§14 I3).

---

## 6. 프레임 사진 저장 (D9·D10)

- **무엇을**: 제품이 보인 프레임(PD 통과, `inspect`를 보낸 프레임) 전부. 제품이 안 보인 프레임은 저장하지 않는다.
- **어떻게**: 프레임마다 JPEG 한 장. `product_frames/<날짜>/<session_id>/<seq 6자리>.jpg`. 경로는 `frames.file`에 넣는다.
- **화질**: 게이트웨이가 원본 픽셀(I420)을 넘기므로(§7.1) API가 JPEG로 압축한다. 품질 `FRAME_JPEG_QUALITY` **90** (2026-11-06 결정). JPEG를 받은 연결(테스트·직접 연결)은 받은 바이트를 그대로 쓴다.
- **순서**: 지금 결함 프레임 저장과 같다 — `inspect`를 먼저 보내고 압축·파일 쓰기·DB 행은 뒤에서(스레드) 한다. 실패해도 판정은 계속하고 `file`만 NULL로 남긴다.
- **건너뛴 프레임**: 처리가 밀려 API가 건너뛴 프레임은 남지 않는다. 지금 실측(처리 약 14장/초 > 입력 10장/초)이면 실제로는 거의 전부 남는다.
- **용량 (추정)**: 1440×1080 JPEG 품질 90이 한 장 약 0.2~0.3MB라면 초당 10장, 제품을 계속 비출 때 스테이션 하나당 시간당 약 7~11GB (2026-11-10 HMD 1440×1080 — 이전 1920×1440 약 11~18GB). 실제 양은 `GET /storage`(앱 M-06)로 본다.
- 결함 프레임 저장(`results`, 1초에 1장, 오버레이)은 그대로 둔다. 실시간 확대 보기용이다.

---

## 7. 영상 화질 (D11·D12)

### 7.1 게이트웨이 → API: 원본 픽셀 전달

- 게이트웨이는 `to_jpeg` 대신 디코딩한 프레임을 **I420(yuv420p) 그대로** `/ws` 바이너리 메시지로 보낸다.
- 메시지 형식: `b"I420"` (4바이트) + `width` uint16 LE + `height` uint16 LE + `rx_at` int64 LE (epoch ms) + Y·U·V 평면.
- 크기: 1920×1440은 약 4.1MB, 4K는 약 12.4MB. uvicorn WebSocket 기본 최대(16MB) 안이다. 게이트웨이와 API가 같은 서버라 대역폭 부담은 없다.
- API `read_image`는 앞 4바이트가 `I420`이면 위 형식으로, 아니면 지금처럼 이미지 파일(JPEG 등)로 읽는다. 테스트용 `stream_client.py`·직접 연결은 JPEG를 계속 쓸 수 있다.

### 7.2 게이트웨이 프레임 간격 제한 삭제

- `forward()`의 `if now - last < 1 / FORWARD_FPS: continue`를 지운다. 앱이 이미 초당 10장 이하로 보내고(`AiEdgeLink.FRAME_MS`), API는 가장 최근 프레임만 처리하므로 게이트웨이에서 따로 거를 필요가 없다.
- 확인: `ws_close`의 `frames / 제품을 비춘 시간`이 초당 10장 가까이 나오는지 본다 (2026-11-05 실측 7.2장).

### 7.3 폰 VP8

- `AiEdgeLink.MAX_BITRATE` 6Mbps → **12Mbps** (출발값). `MIN_BITRATE`도 함께 올린다 (4Mbps 출발값).
- 폰 CPU로 1920×1440 소프트웨어 VP8이 12Mbps에서 초당 10장을 낼 수 있는지 확인한다 (WebRTC stats `framesPerSecond`, `qualityLimitationReason`). 못 내면 하드웨어 H.264 인코더(`DefaultVideoEncoderFactory`, EGL 필요)를 검토한다. aiortc는 H.264를 받을 수 있다.

### 7.4 HMD JPEG

- 품질 80 → **95** (`YuvToJpeg.compress`). 2026-11-10: 95 → **90** — 실측 송출이 초당 약 7장(목표 12)이라 압축 시간·크기를 줄인다.
- 품질을 올리면 프레임이 커지므로, HMD 핫스팟 대역폭을 위해 MJPEG 송출을 **초당 12장**으로 제한한다 (출발값). 2026-11-09부터 폰은 HMD 프레임을 받는 대로 서버로 보내므로 이 장수가 서버 도착 상한이다.
- 2026-11-10 실측 송출이 초당 약 7장 — HMD 카메라는 1920×1440을 초당 30장까지 주므로(dumpsys) HMD의 CPU 변환(NV21 복사 + JPEG 압축)을 원인으로 본다. HMD가 5초마다 센서·송출 장수와 단계별 시간을 logcat `TacitQ.Hmd`에 남긴다. 그래도 12장이 안 되면 카메라 하드웨어 JPEG(Camera2)로 바꾼다. → 줄 단위 복사로 초당 12.0장 (NV21 13ms + JPEG 37ms), 하드웨어 JPEG는 필요 없음.
- 2026-11-10 결정: **4:3 1440×1080 · 품질 80 · 초당 10장** (HMD `MainActivity`). 1920×1440 · 품질 90 · 초당 12장(HMD 영상 약 27Mbps)에서 같은 Wi-Fi의 폰 → 서버 패킷 손실이 약 27%였고, 960×720으로 낮추면 약 2.8%였다 (DEPLOY §8). 서버가 보는 화질은 폰 VP8이 정하므로 품질 80은 서버 쪽 화질에 거의 영향이 없다. 1080p 이상(API §5.5)은 지킨다.

---

## 8. 모바일 앱 변경

| 위치 | 변경 |
|---|---|
| `ai/AiMessages` | `inspect` 추가 필드(§3.2), `product_end`(§3.3) 해석 |
| `ai/AiFlow` | 제품 구간·결함 확정·`JudgeResult` 생성 로직 삭제. `inspect` → 실시간 표시, `product_end` → 결과 카드·HMD 결과, `faces` → 면 칩 |
| `ai/AiResultsApi` | `putFeedback`, `products`, `product`, `productFrames`, `frameJpeg`, `storage` 추가 |
| `ai/AiEdgeLink` | `/offer`에 `feedback_mode`. 비트레이트 (§7.3) |
| 로컬 DB (Room) | 이력 화면은 더 쓰지 않는다. 이력의 원본은 서버다 (상태 기계는 `product_end`마다 지금처럼 기록을 남긴다 — §14 I5) |
| M-01 | **모드 수동/자동** 선택 추가. HMD 결함 원 켜기/끄기는 그대로. 모드를 바꾸면 다시 연결 |
| M-07 | 수동 모드: `product_end`를 받으면 결과 카드(판정, 결함 종류, `best_result_id` 확대 사진, 면 3개) + **맞음 / 틀림 / 확인필요** 버튼. 누르면 `PUT …/feedback`(`source: live`). 새 `product_end`가 오면 카드를 새 제품으로 바꾼다 (이전 제품은 미입력 그대로). 자동 모드: 결과 카드만 |
| M-05 | 서버 `GET /products`로 목록. 필터(피드백, 판정, 날짜). 상세: 프레임 전체 넘겨 보기(§4.5·§4.6), 결함 확대(기존 `DefectViewer`), 현재 피드백과 **맞음 / 틀림 / 확인필요** 버튼(`source: history`), 변경 기록 |
| M-06 | 디스크 사용량(`GET /storage`): 전체·사용·남음, 종류별, 날짜별. 남은 공간이 정한 값(출발값 10%) 아래면 경고 색 |
| BLE (폰 → HMD) | 새 쪽지 2종 (§9) |

---

## 9. HMD 앱 변경

| 쪽지 | 형식 | 표시 |
|---|---|---|
| 면 진행 | `{"cmd":"FACES","s":["상면","엣지"],"all":["상면","엣지","하면비닐"],"c":false}` | 조준 화면에 면 칩 3개. 본 면은 켜짐. `c=true`면 "검사 완료" |
| 제품 결과 | `{"cmd":"RESULT","d":"FAIL","l":"칩찍힘"}` | 합격(초록)/불합격(빨강)을 크게, `RESULT_SHOW_MS`(2000) 동안. 이후 조준 화면으로 |

- 면 칩은 `inspect`의 `faces_seen`이 바뀔 때만 보낸다. `searching`·`product_end` 뒤에는 칩을 끈다.
- 음성·모션 입력은 쓰지 않는다. HMD에는 입력이 없고 표시만 한다.
- JPEG 품질·송출 속도 (§7.4).

---

## 10. 지연 측정

- **물리 측정 (기준)**: HMD 카메라 앞에 결함 표시가 확실히 나오는 시료를 두고, 가렸다가 치운다. HMD 화면과 밀리초 스톱워치를 다른 카메라로 함께 촬영해, 치운 순간부터 결함 원이 뜰 때까지를 센다. (가)·(나) 같은 조건 비교와 설정 변경 전후 비교에 쓴다.
- **구간 로그 (보조)**: `inspect.rx_at`(게이트웨이 수신) → 앱 수신 시각(통신 로그) → BLE 쓰기 시각 → HMD 렌더 시각(HMD 로그). 기기마다 시계가 조금씩 다르므로 구간 합은 참고값으로만 본다.

---

## 11. 구현 순서

| 단계 | 내용 | 확인 |
|---|---|---|
| 1 | 게이트웨이: 간격 제한 삭제(§7.2), I420 전달(§7.1) | 초당 처리 장수. 지연 물리 측정으로 기준값 확보 |
| 2 | API: 제품 구간·확정·면 진행(`stream.py`), `product_end`, DB(§5), 제품 프레임 사진 저장(§6) | 작성 환경 시험: 지금 앱 `AiFlowTest`의 구간·확정 사례를 서버 시험으로 옮겨 같은 결과 |
| 3 | API: 피드백·이력·프레임·저장소 API(§4), 게이트웨이 중계 | 피드백 변경 기록, 페이지 넘김, 프레임 JPEG |
| 4 | 모바일: §8 | 단위 시험 갱신, 실기기 수동/자동 모드 |
| 5 | HMD: §9, §7.4 | 면 칩·결과 표시, 핫스팟 대역폭 |
| 6 | 문서: `A4_WEBRTC_API.md` 개정, `DEPLOY.md` (DB·저장·로그·디스크) | |

---

## 12. 아직 정할 것

| # | 내용 | 제안 |
|---|---|---|
| Q1 | ~~프레임 사진 JPEG 품질~~ | **90으로 결정** (2026-11-06) |
| Q2 | 출발값: `FACE_MIN_FRAMES` 3, VP8 12Mbps, HMD JPEG q95·초당 12장 (→ 2026-11-10 1440×1080 · q80 · 초당 10장), `RESULT_SHOW_MS` 2000, 디스크 경고 10% | 실기기에서 보고 조정 |

---

## 13. 하지 않는 것

- 제품 추적기(ByteTrack 등). 한 번에 제품 하나라는 전제가 깨질 때 다시 본다. 이때 PD 클래스(면)를 구분하지 않고 추적해야 한다는 점을 함께 본다.
- 인증·권한. 누구나 피드백을 바꿀 수 있고 변경 기록만 남긴다.
- 프레임·로그 자동 삭제.
- 음성·모션 인식 입력.
- 고해상도 정지 사진 경로. 실제 결함의 크기(mm)가 1440×1080(2026-11-10~, 이전 1920×1440)에서 너무 작게 찍힌다는 측정이 나올 때만 다시 본다.
- 모델 재학습·기준값 조정 (가모델 단계).

---

## 14. 구현 결과 (2026-11-06)

**서버** (`api/stream.py`·`api.py`·`db.py`·`detect.py`, `gateway/gateway.py`, 테스트 클라이언트 2개): 이 문서 §3~§7 그대로. 작성 환경 시험은 `DEPLOY.md` §8 "확인한 것 — 제품 단위·피드백" (실제 FastAPI·websockets·aiortc·PostgreSQL 16 + 가짜 YOLO, `/ws`·HTTP 42건 + WebRTC 게이트웨이 종단).

**모바일**: `ai/AiMessages`·`AiFlow`·`AiEdgeLink`·`AiResultsApi`·`Feedback`(신규), `hmd/ShowWire`(FACES·RESULT)·`HudMirror`, `service/InspectionService`, 화면 M-01(모드)·M-05(서버 이력)·M-06(디스크)·M-07(결과 카드)·`ProductViews`(신규). JVM 단위 시험 52건 통과 (안드로이드와 무관한 코드만 — 화면·서비스·WebRTC 코드는 이 환경에서 빌드하지 못했다).

**HMD**: `protocol/Wire`(FACES·RESULT), `hud/LiveHold`(RESULT 2초·면 칩 줄), `hud/HudController`, `MainActivity`(JPEG 95·초당 12장). JVM 단위 시험 17건 통과.

구현하며 정하거나 계획과 달리 한 것:

| # | 항목 | 계획 | 구현 | 이유 |
|---|---|---|---|---|
| I1 | JPEG 입력 사진 | I420을 품질 90으로 압축 | JPEG를 받은 연결은 받은 바이트 그대로 저장 | 다시 압축하면 손실만 더해진다 (테스트·직접 연결) |
| I2 | 기록 순서 | 뒤에서(스레드) 쓴다 | 연결마다 한 줄(큐 + 작업 하나)로 차례대로 | `products` → `results` → `frames` 외래 키 순서. 스레드를 따로 띄우면 순서가 뒤바뀐다 |
| I3 | 끝나지 않은 제품 행 | 서버 재시작 때 disconnect로 닫기 | NULL로 둔다 | API 컨테이너 여럿이 DB를 나눠 쓰면 다른 컨테이너의 진행 중 제품까지 닫는다 |
| I4 | `/ws` 잘못된 mode | 정하지 않음 | close `4422` | 4403(스테이션)과 구분. 게이트웨이는 `/offer`에서 422로 먼저 거른다 |
| I5 | 폰 기기 DB | 판정 기록 저장 중단 | `product_end`를 상태 기계에 `JudgeResult`로 넘겨 지금처럼 기록·건수(알림 문구)를 유지. M-05는 서버를 본다 | 상태 기계·엔진 시험을 그대로 지키려고. 기기 DB 기록은 화면에 쓰지 않는다 — 정리할지는 나중에 정한다 |
| I6 | `product_end` 필드 | §3.3 | `threshold` 추가 | 제품 행에 남기는 값과 맞춤 |
| I7 | `/storage` 디스크 값 | 5분마다 계산한 값 | 디스크 전체·사용·남음은 요청 때, 폴더별·날짜별만 5분마다. 이틀 전까지의 날짜 폴더는 캐시 | 디스크 값은 가볍다. 폴더 훑기만 무겁다 |
| I8 | 끊긴 사이 끝난 제품 | 재연결 뒤 마지막 제품 조회 | 교대 첫 연결은 빼고, 다시 연결될 때만 | 교대 시작 때 지난 교대의 제품이 카드로 뜨지 않게 |
| I9 | HMD 송출 초당 12장 | 제한 | 예정 시각 + 반 간격 허용 (카메라 30·15fps 모두 평균 12장) | 게이트웨이와 같은 실수(엄격한 간격 → 장수가 깎임)를 피하려고 |
| I10 | 모드 변경 | 바꾸면 다시 연결 | 검사 중에는 M-01 모드를 못 바꾼다(다른 연결 설정과 같다) — 검사를 멈추고 바꾼 뒤 다시 시작 | 연결 중 바꾸기를 따로 만들 필요가 없다 |
| I11 | M-05 오늘 건수 | — | 뺐다 | 서버 이력은 페이지로 받으므로 오늘 건수를 앞 페이지로 셀 수 없다. 필요하면 서버 집계 API를 더한다 |

