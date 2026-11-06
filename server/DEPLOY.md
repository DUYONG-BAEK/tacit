# 제품 불량 판정 서버 — 구성·배포·운영

기준: 2026-11-06 코드 (제품 단위 판정·작업자 피드백·제품 프레임 사진 저장). **이번 변경은 실서버 미확인** — 작성 환경에서 가짜 모델로만 확인했다 (§8). gpudev는 2026-11-03 코드(프레임 단위)로 돈다 — 올리는 절차는 §5.B.

이상탐지(DINOv2 + memory bank) 서버를 내리고, **PD(제품 탐지) → QI(불량 탐지)** 두 YOLO 모델로 다시 올린다. 이 문서가 서버의 기준 문서다.

> **2026-11-06 변경**: 제품 구간·결함 확정·제품 판정을 앱에서 **서버로** 옮겼다 (`product_end` 메시지, `products` 테이블). 작업자 피드백(맞음·틀림·확인필요)과 이력 조회 API, 제품이 보인 프레임 **전부의 사진 저장**, 디스크 사용량 API를 더했다. 게이트웨이는 JPEG로 다시 압축하지 않고 원본(I420)을 넘긴다. 결정과 근거는 `PLAN_product_feedback.md`.
>
> **2026-11-03 변경**: 판정을 제품 단위(같은 제품을 3~10장 모아 한 번)에서 **프레임 단위**(제품이 보이는 프레임마다 `inspect`)로 바꿨다. 작업자가 제품을 든 채 돌려 보며 멈추지 않기 때문이다. 결정과 근거는 `PLAN_realtime_inspect.md`, 앱 규격은 `A4_WEBRTC_API.md`(2026-11-03).

| 문서 | 내용 |
|---|---|
| `DEPLOY.md` (이 문서) | 구성, 판정 규칙, 기록, 배포·운영 절차 |
| `A4_WEBRTC_API.md` | 모바일 앱 연동 규격 (메시지·조회·연결) |
| `PLAN_product_feedback.md` | 제품 단위 판정·피드백·사진 저장의 결정·규격·구현 결과 (2026-11-06) |
| `PLAN_realtime_inspect.md` | 프레임 단위 전환의 결정·변경 내용·위험 (2026-11-03) |
| `gateway/README.md` | WebRTC 게이트웨이 상세 |
| `A4_PLAN.md` | **보관용.** 이상탐지 단계의 기획·측정 기록. §7.5(WebRTC·DB 설계 근거)는 지금 구성에도 그대로 적용된다 |

---

## 1. 구성

```
헤드마운트 카메라 → 앱 ─┬─ WebRTC 영상 (운영) ──▶ a4-gateway :8080 ── 원본 프레임(I420) ──▶ a4-api /ws :8000
                       └─ JPEG 이미지 (테스트) ──────────────────────────────────▶ a4-api /ws :8000
a4-api: 프레임마다 PD ─(제품 있음)─▶ QI ─▶ inspect (프레임마다) ─▶ 제품 구간·확정 ─▶ product_end (제품마다)
        제품 프레임은 전부 사진 저장, 결함 프레임은 1초에 1장 오버레이 ─▶ a4-db 기록
결과: a4-api ──▶ a4-gateway ──▶ 데이터 채널(JSON) ──▶ 앱
조회: 앱 ──▶ :8080/results/*, /products*, /storage ──▶ (프록시) ──▶ a4-api
```

| 컨테이너 | 이미지 | 역할 | GPU | 네트워크 |
|---|---|---|---|---|
| `a4-db` | `postgres:17` | 제품·프레임·결함 프레임·피드백 기록 (§4.3) | — | `a4-net` |
| `a4-api` | `a4-api` (`api/`) | PD·QI 추론, 제품 단위 판정, `/ws`, `/predict`, `/results/*`, `/products*`, `/storage`, DB 기록 | 1장 | `a4-net`, `-p 8000:8000` |
| `a4-gateway` | `a4-gateway` (`gateway/`) | WebRTC 수신·중계. 분석하지 않는다 | — | `--network host` (:8080) |

**모델** (서버는 추론만 한다. 학습은 서버 밖에서 하고 `.pt`만 가져온다)

| 모델 | 파일 | 클래스 | 입력 |
|---|---|---|---|
| PD | `yolo_product_detection_model_20261020111232.pt` | 3: 엣지, 상면, 하면비닐 | 프레임 전체를 640으로 줄여서 |
| QI | `yolo_quality_inspection_model_20261021091500.pt` | 18: 칩찍힘 … 크랙 | 프레임을 640 타일로 잘라서 |

- 둘 다 YOLO11n(약 2.6M 파라미터), Ultralytics 8.4.117로 학습했다. 서버도 같은 버전을 쓴다 (`api/requirements.txt`).
- **가모델이다.** 판정·결함 종류가 자주 틀린다. 지금 목표는 기능이 끝까지 도는 것이다.

---

## 2. 파일과 폴더

모든 경로는 **프로젝트 폴더(이하 `$PWD`, gpudev는 `/home/admin/odServer`)** 기준이고, 명령은 전부 이 폴더에서 실행한다.

```
$PWD/
├── DEPLOY.md                이 문서            ┐
├── A4_WEBRTC_API.md         앱 연동 규격        │ 이미지에는 안 들어감
├── PLAN_realtime_inspect.md 프레임 단위 전환 계획 │
├── A4_PLAN.md               보관 (이상탐지 기록) ┘
├── api/                     불량 판정 API (이미지 a4-api, 빌드 컨텍스트 = api/)
│   ├── .dockerignore        __pycache__/, *.pt, *.md 제외
│   ├── Dockerfile
│   ├── requirements.txt
│   ├── api.py               FastAPI: /ws, /predict, /results/*. 로그·프레임·결과 저장
│   ├── db.py                PostgreSQL: 스키마, 결함 프레임 기록, 조회
│   ├── detect.py            PD·QI 호출 (타일 분할·NMS·제품 상자 필터·오버레이 그리기), 모델 경로·기준값
│   ├── stream.py            프레임 판정(judge), searching 전송 여부
│   └── stream_client.py     테스트 클라이언트 (사진·영상을 /ws로)
├── gateway/                 WebRTC 게이트웨이 (이미지 a4-gateway, 빌드 컨텍스트 = gateway/)
│   ├── Dockerfile
│   ├── README.md
│   ├── gateway.py
│   ├── requirements.txt
│   └── webrtc_client.py     테스트 클라이언트 (영상을 WebRTC로)
├── models/                  ← 사람이 둔다. PD·QI .pt 두 개 (a4-api에 읽기 전용으로 붙인다)
├── samples/                 ← 사람이 둔다 (테스트용). 제품 사진 몇 장 (.jpg)
└── videos/                  ← 사람이 둔다 (테스트용). 제품을 찍은 영상 (.mp4/.mov)
```

- `api/`에 **8개**(숨김 파일 `.dockerignore` 포함, `ls -A api`), `gateway/`에 **5개**가 있어야 한다. 최상위에는 `.py`·`Dockerfile`이 없어야 한다.
- 이상탐지 코드(`a4_common.py`, `corelib/` 10개, `prepare_a4.py`, `build_a4.py`, `smoke_a4.py`, `bench_a4.py`)는 **없어야 한다** (§5 ①에서 지운다). 남아 있어도 이미지에는 복사되지만 아무도 쓰지 않는다.

**실행하면 생기는 폴더** (호스트 볼륨, 컨테이너를 지워도 남는다)

```
logs/a4-api/<날짜>.jsonl          API 이벤트 (§4.1)
logs/a4-gateway/<날짜>.jsonl      게이트웨이 이벤트 (gateway/README.md)
product_frames/<날짜>/<session_id>/<seq>.jpg  제품이 보인 프레임 사진 전부 (§4.2, 2026-11-06부터)
frames/<날짜>/<result_id>.jpg     옛 결함 프레임 원본 (2026-11-06 전. 제품 단위 때는 <result_id>/NN.jpg). 새 코드는 쓰지 않는다
results/<날짜>/<result_id>/overlay.jpg  결과 오버레이 (§4.2)
db/                               PostgreSQL 데이터 (a4-db). 호스트에서 직접 건드리지 않는다
stream_out/, webrtc_out/          테스트 클라이언트 출력 (지워도 된다)
archive_a4_<날짜>/                이상탐지 단계의 코드·데이터·기록 보관 (§5 ①③)
archive_product_mode/            제품 단위 단계의 코드·문서·DB 덤프 보관 (§5.A)
```

---

## 3. 판정 규칙

### 3.1 게이트 — PD (`api.process_frame`)

- 프레임마다 PD를 돌려 **상자가 하나라도 있으면**(클래스·개수·크기·위치 무관) 그 프레임을 QI로 넘긴다. 없으면 `searching` `{"reason": "no_product"}` (바뀔 때만).
- 읽을 수 없는 프레임(이미지 아님·잘림·해상도 초과)은 메시지 없이 건너뛰고 상태를 바꾸지 않는다 (`ws_close.skipped_frames`).
- 처리가 프레임 속도보다 느리면 가장 최근 프레임만 처리하고 나머지는 건너뛴다 (`seq`가 건너뛴다).

### 3.2 QI 추론 (`detect.inspect`)

- 프레임 전체를 640×640 타일(간격 512, 겹침 128)로 덮어 추론한다. 마지막 타일은 프레임 끝에 맞춘다. 1920×1080이면 4×2 = 8타일, 3840×2160이면 32타일. 640보다 짧은 변은 타일 하나로 덮는다.
- 타일의 상자를 프레임 좌표로 옮기고 **클래스별 NMS(IoU 0.5)** 로 겹친 타일의 중복을 정리한다 (`qi_test.py`와 같은 격자·NMS).
- 결함이 타일 경계에 걸치면 잘린 조각끼리 IoU가 낮아 상자가 2~4개 남을 수 있다. 판정(최대 conf)에는 영향이 없다.
- QI는 4K 원본을 자른 640 타일로 학습했다. 1080p 프레임에서는 결함이 학습 때의 절반 크기로 보인다 — 가모델 단계에서는 그대로 두고, 운영 프레임으로 재학습할 때 맞춘다.

### 3.3 판정 — 프레임 한 장 (`stream.judge`)

- **제품 상자 안의 결함만** 남긴다: 결함 상자의 **중심**이 어느 PD 상자 안(경계 포함)에 있으면 남기고, 아니면(배경) 버린다 (`detect.filter_in_products`).
- **score** = 남은 결함의 최대 conf. 결함이 없으면 0.
- **decision**: 기준값 `T = detect.QI_CONF`(임시 0.25)에 대해 `score > T × 1.1` → `NG`, `score < T × 0.9` → `OK`, 그 사이 → `REVIEW`.
- QI 추론 하한은 `T × 0.9`로 자동 계산한다. 그보다 낮은 결함은 판정을 OK에서 바꾸지 못하므로 받지 않는다. 그래서 **OK ⇔ `detections == []`** 이고, NG·REVIEW면 `detections`가 1개 이상이다.
- `defect_label` = 가장 확실한 결함 이름(`detections[0].label`), OK면 `null`.
- 프레임 사이에서 상자를 합치지 않는다. 깜빡임(결함이 잡혔다 안 잡혔다)은 앱이 표시를 약 0.5초 유지해 처리한다 (`A4_WEBRTC_API.md` §3.4). 프레임을 모아 제품 하나로 판정하는 것은 아래 §3.4의 제품 구간이 한다.

### 3.4 흐름·저장 (`api.stream_endpoint`)

```
연결 → {"state": "searching"} (게이트웨이가 연결 확인으로 쓴다)
프레임마다: 디코딩 → PD → 제품 없음: searching(no_product)  (바뀔 때만)
                       → 제품 있음: QI → judge → inspect     (프레임마다)
                                       NG·REVIEW이고 마지막 저장 후 SAVE_INTERVAL_S(1초) 지남 → 저장
```

- **제품 구간** (`stream.Products`, 2026-11-06 — 옛 앱 규칙을 옮김): 첫 제품 프레임에서 시작, 마지막 제품 프레임 뒤 `SEGMENT_END_S`(1초) 동안 제품 프레임이 없거나 연결이 끊기면 끝. `searching` 한 장은 끝내지 않는다. 프레임이 오지 않아도 `TICK_S`(0.2초)마다 끝을 검사한다. 한 번에 제품 하나를 든다고 본다 (추적기 없음).
- **결함 확정·제품 판정**: 구간 안 최근 `CONFIRM_WINDOW`(5)장 중 `CONFIRM_HITS`(2)장 이상이 NG·REVIEW면 확정 (안에 NG가 있으면 NG, 없으면 REVIEW, 나중에 NG로 올라갈 수 있다). 확정이 없으면 `PASS`, 있으면 `FAIL`. 끝나면 `product_end`를 보낸다 (연결이 끊겨 끝나면 보내지 못하고 기록만 한다).
- **면 진행**: PD 라벨(엣지·상면·하면비닐)별로 그 제품에서 잡힌 프레임 수. `FACE_MIN_FRAMES`(3)장 이상이면 그 면을 본 것, 모두 보면 `complete`. 구간을 끝내지는 않는다. `inspect`·`product_end`에 실린다.
- **피드백 모드**: `/ws?mode=manual|auto`(게이트웨이는 `/offer`의 `feedback_mode`를 넘긴다). 제품의 피드백 초기값이 수동이면 `NOT_ENTERED`(미입력), 자동이면 `NONE`(피드백 없음). 사람은 `PUT /products/{id}/feedback`으로 `CORRECT`·`WRONG`·`UNSURE`를 넣고 바꾼다 (누구나, 변경 기록 `feedback_log`).
- **저장**: 제품 프레임은 **전부** 사진(§4.2)과 `frames` 행을 남긴다. 결함 프레임(NG·REVIEW)은 그와 별도로 **연결마다 1초에 최대 1장** 오버레이와 `results` 행을 남기고, 그 프레임의 `inspect`에만 `result_id`·`overlay_url`이 붙는다 (나머지는 `null`).
- 순서: `inspect`를 **먼저 보내고** 사진·오버레이·DB 행은 뒤에서 쓴다. 기록은 연결마다 한 줄로 차례대로 한다 (`products` → `results` → `frames` 외래 키 순서). 그래서 `inspect`를 받자마자 오버레이를 요청하면 `404`일 수 있다 (앱은 0.5초 뒤 한 번 더).
- 연결이 끊겨도 남은 기록은 끝낸다 (최대 60초 기다림, 넘으면 `write_timeout`).
- 수집·조기 종료·놓침·치움, `hold`/`captured`/`result`, 중앙값, 연속 판정 알림(`alert`), 사람의 최종 판정은 **없어졌다** (2026-11-03, `PLAN_realtime_inspect.md` D1·D4).

### 3.5 설정값

| 파일 | 이름 | 값 | 뜻 |
|---|---|---|---|
| `api/detect.py` | `QI_CONF` | 0.25 (임시) | 판정 기준값 T. REVIEW 구간과 QI 추론 하한이 따라 움직인다 |
| | `PD_CONF` | 0.25 | PD 상자로 인정하는 conf (Ultralytics 기본값) |
| | `TILE`, `STEP` | 640, 512 | QI 타일 크기·간격 (학습 격자) |
| | `NMS_IOU` | 0.5 | 타일 중복 정리 |
| | `PD_MODEL`, `QI_MODEL` | 파일명 | `models/` 안의 모델 파일 |
| | `DEVICE` | 0 | 컨테이너에 보이는 첫 GPU |
| `api/stream.py` | `REVIEW_MARGIN` | 0.1 | REVIEW 폭 (T 대비) |
| | `SEGMENT_END_S` | 1.0 | 마지막 제품 프레임 뒤 이만큼 없으면 제품 끝 |
| | `CONFIRM_WINDOW`, `CONFIRM_HITS` | 5, 2 | 결함 확정: 최근 5장 중 2장 |
| | `FACE_MIN_FRAMES` | 3 | 면을 "봄"으로 치는 프레임 수 |
| `api/api.py` | `SAVE_INTERVAL_S` | 1.0 | 결함 프레임(오버레이·`results`) 저장 간격 (연결마다) |
| | `FRAME_JPEG_QUALITY` | 90 | 제품 프레임 사진 JPEG 품질 (I420 원본을 받았을 때. JPEG를 받으면 그대로 저장) |
| | `TICK_S` | 0.2 | 프레임이 없을 때 제품 끝 검사 간격 |
| | `STORAGE_SCAN_S` | 300 | `/storage` 용량 계산 간격 |

- 게이트웨이의 `FORWARD_FPS`(초당 10장 간격 제한)는 2026-11-06에 없앴다. 도착 간격이 조금만 흔들려도 다음 장을 버리므로 2026-11-05 실측 초당 약 7장의 원인으로 봤다. 앱이 초당 10장 이하로 보내고 API가 가장 최근 프레임만 처리한다.

- 설정값은 코드에 있다. **바꾸면 이미지를 다시 빌드하고 컨테이너를 새로 만든다** (§6.1).
- 환경변수 `CONSECUTIVE_N`(연속 판정 알림)은 없어졌다. `docker run`에서 넘기지 않았으므로 명령은 바뀌지 않는다.

### 3.6 `/predict` — 사진 한 장 확인용

- `POST /predict` (multipart `file`) → `products`(PD 상자), `detections`(제품 상자 안의 QI 결함), `score`, `threshold`, `decision`, `defect_label`, `overlay_jpg`(base64). 좌표는 사진 픽셀(EXIF 회전 반영). `/ws`의 `inspect`와 같은 판정이다.
- 400: 이미지가 아님·잘린 파일·해상도 초과(Pillow 한도의 2배). 422: 제품을 찾지 못함.
- 운영 입구가 아니다. `result_id`·DB 기록이 없다. 배포 확인, 모델·기준값 바꾼 뒤 사진 확인에 쓴다.

---

## 4. 기록

### 4.1 로그 (`logs/a4-api/<날짜>.jsonl`)

한 줄 = 한 이벤트(JSON). 같은 줄이 `docker logs`에도 찍힌다. 날짜별 새 파일, 자동 삭제 없음. 파일에 못 써도 판정은 계속한다.

| event | 시점 | 필드 |
|---|---|---|
| `startup` | 기동 (모델 로드·예열 끝) | checkpoint(`pd파일@수정시각\|qi파일@수정시각`), pd_classes, qi_classes, pd_conf, qi_conf |
| `db_ready` | DB 접속·스키마 확인 끝 | — |
| `db_error` | DB 호출 실패 (판정은 계속) | where(`station_exists`/`open_session`/`insert_result`/`insert_product`/`insert_frame`/`end_product`/`close_session`), error |
| `predict` | `/predict` 요청마다 | req, client, filename, bytes, image_size, status(200/400/422), error(`unreadable_image`/`too_large`/`product_not_found`), 200이면 products(개수), defects(제품 상자 안 결함 개수), score, decision, threshold, checkpoint, ms(decode/pd/qi/encode) |
| `ws_rejected` | `/ws` 스테이션 미등록 (4403)·잘못된 mode (4422) | station 또는 mode, reason(`unregistered`/`bad_mode`) |
| `ws_open` | `/ws` 연결 | session, source(`gateway`/`direct`), client(게이트웨이 연결 ID), station, mode, peer |
| `inspect_saved` | 결함 프레임을 저장한 뒤 (연결마다 1초에 최대 1건) | result_id, session, source, client, station, seq, decision, defect_label, score, detections, products(개수), frame_size, threshold, checkpoint, file(원본 경로, 실패면 null), ms(decode/pd/qi) |
| `frame_save_error` | 제품 프레임 사진 저장 실패 (디스크 등) | session, seq, error |
| `result_save_error` | 오버레이 저장 실패 | result_id, error |
| `product_end` | 제품이 끝난 뒤 (DB 행을 채운 뒤) | session, source, client, station, mode, product_id, started_at, ended_at, end_reason(`gap`/`disconnect`), decision, ai_decision, defect_label, frames, defect_frames, faces, faces_seen, complete, best_result_id, result_ids, threshold, feedback_mode, feedback |
| `feedback` | `PUT /products/{id}/feedback` 성공 | product_id, value, source(`live`/`history`), device |
| `storage_scan`, `storage_scan_error` | `/storage` 용량 계산 (`STORAGE_SCAN_S`마다) | ms, data_bytes / error |
| `write_error`, `write_timeout` | 연결의 기록 줄에서 예외 · 끊긴 뒤 60초 안에 남은 기록을 못 끝냄 | error / pending |
| `ws_close` | 연결 종료 | session, source, client, station, mode, duration_s, frames, processed, skipped_frames, product_frames, defect_frames, saved, products, ms_avg(decode/pd/qi), error |

**`ws_close` 숫자** (연결 하나):

| 필드 | 뜻 |
|---|---|
| `frames` | 받은 프레임 수 (= 마지막 `seq`) |
| `processed` | 디코딩·PD까지 처리한 수 |
| `skipped_frames` | **깨진 프레임**(이미지 아님·잘림·해상도 초과)으로 건너뛴 수. 처리가 밀려 건너뛴 수가 아니다 |
| `product_frames` | 제품이 보여 QI를 돌린 수 = 보낸 `inspect` 수 |
| `defect_frames` | 그중 NG·REVIEW 수 |
| `saved` | 결함 프레임(오버레이) 저장을 **시작한** 수 = `result_id`를 붙여 보낸 수. 저장 실패는 `result_save_error` 등으로 따로 남는다 |
| `products` | 이 연결에서 시작한 제품 수 |
| `ms_avg` | 프레임 한 장 평균 ms. decode·pd는 `processed`로, qi는 `product_frames`로 나눈다 (QI는 제품 프레임에서만 돈다). qi는 타일 하나가 아니라 **프레임 한 장**(1080p 8타일, 4K 32타일) 기준이다 |

- **처리가 밀려 건너뛴 수** = `frames − processed − skipped_frames`. 실제 처리 속도(fps)는 `processed / duration_s`다. 단 `duration_s`에는 영상이 오지 않은 시간도 들어가므로, 비추는 동안의 속도는 `frames` 행·`inspect_saved`의 `seq` 간격으로 본다 (2026-11-05 로그 분석).
- 연결 사이에 합칠 때는 평균의 평균을 내지 않는다 — `ms_avg × 횟수`로 합계를 되살려 더한 뒤 나눈다.
- `/predict`의 `ms.pd`·`ms.qi`는 모델 락 대기를 포함한다. `/ws`의 `ms`도 같다 (다른 연결·`/predict`와 모델을 나눠 쓴다).
- `/ws`는 프레임마다 로그를 남기지 않는다 (저장한 결함 프레임만 `inspect_saved`). 메시지 흐름은 게이트웨이 로그 `a4_message`(NG·REVIEW, `searching`)와 `relay_summary`(개수)에서 본다.
- 게이트웨이 이벤트는 `gateway/README.md`. 두 로그는 `ws_close.client` = 게이트웨이 `peer`로 맞추고, `relay_summary.inspect` = `ws_close.product_frames`여야 한다.
- 없어진 이벤트 (2026-11-03): `product_reset`, `ws_frame`, `ws_result`, `result_timeout`, `result_late`, `decision`.

### 4.2 프레임·결과 저장

- `product_frames/<날짜>/<session_id>/<seq 6자리>.jpg` (2026-11-06부터): **제품이 보인 프레임 전부**. 게이트웨이 경유(I420 원본)는 `FRAME_JPEG_QUALITY`(90)로 압축하고, JPEG를 받은 연결(테스트·직접 연결)은 받은 바이트 그대로 쓴다. 경로는 `frames.file`·`results.frames_dir`에 남는다. 제품이 안 보인 프레임은 저장하지 않는다.
- 양: 1920×1440 품질 90이 한 장 약 0.3~0.5MB면, 제품을 계속 비출 때 스테이션 하나당 시간당 약 11~18GB (추정). 실제 양은 `GET /storage`·앱 M-06에서 본다.
- `frames/<날짜>/<result_id>.jpg`: 2026-11-06 전에 저장한 결함 프레임의 원본 (받은 바이트 그대로 — 게이트웨이가 만든 JPEG quality 95). 새 코드는 쓰지 않는다.
- `results/<날짜>/<result_id>/overlay.jpg`: 그 프레임 위에 결함 상자. 테두리와 글자는 상자 **바깥쪽**에 그린다 (결함이 수십 px로 작아 안쪽에 그리면 가린다). 조회 엔드포인트가 이 파일을 돌려준다.
- `result_id` = `[direct_]<세션 12자리>-<seq 6자리>`. 게이트웨이를 거치지 않은 연결(테스트)은 `direct_`가 붙는다.
- 날짜·`ts`는 컨테이너 시간대(Dockerfile `TZ=Asia/Seoul`, `tzdata` 설치)를 따른다. `ts`가 `+09:00`으로 끝나야 한다.
- **저장된 프레임은 라벨이 아니다.** 라벨은 제품의 피드백(`products.feedback`)이다: `CORRECT` → 제품 판정 그대로, `WRONG` → 반대, 그 밖(`UNSURE`·`NOT_ENTERED`·`NONE`) → 학습에서 뺀다. 가모델의 오탐도 그대로 저장된다.
- **자동 삭제가 없다** (2026-11-06 결정). 디스크는 `GET /storage`(앱 M-06)나 `du -sh logs product_frames frames results db`로 지켜본다.
- 제품 단위 때의 폴더(`frames/<날짜>/<result_id>/NN.jpg`)는 그대로 남아 있다. 지우지 않는다.

### 4.3 DB (`a4-db`, PostgreSQL 17)

스키마는 `a4-api` 기동 시 없으면 만든다 (`db.py`의 `SCHEMA`, `CREATE … IF NOT EXISTS`·`ALTER … ADD COLUMN IF NOT EXISTS`). 기록 주체는 `a4-api`뿐이다. 2026-11-06에 `products`·`frames`·`feedback_log` 테이블과 `sessions.feedback_mode`를 더했다 — 옛 테이블·행은 그대로 둔다.

| 테이블 | 내용 |
|---|---|
| `stations` | 등록된 스테이션. SQL로 추가한다 (§5 ⑥) |
| `sessions` | `/ws` 세션. `feedback_mode`(manual/auto) |
| `products` | **제품 하나**: 시작 때 넣고 끝날 때 채운다. `decision`(PASS/FAIL), `ai_decision`(OK/NG/REVIEW), `defect_label`, `frames`, `defect_frames`, `faces`, `faces_seen`, `complete`, `best_result_id`, `result_ids`, `threshold`, `checkpoint`, `end_reason`, `feedback`(CORRECT/WRONG/UNSURE/NOT_ENTERED/NONE), `feedback_at`. `ended_at`이 NULL이면 서버가 기록 중 멈춘 제품이다 |
| `frames` | **제품 프레임 하나**: (`session_id`, `seq`), `product_id`, `ts`, `decision`, `score`, `defect_label`, `products`(PD 상자), `detections`, `frame_size`, `file`(사진 경로), `result_id`. 스테이션 하나당 초당 최대 10행 |
| `feedback_log` | 피드백 변경 한 줄: `product_id`, `before`, `after`, `source`(live/history), `device`, `changed_at` |
| `results` | **저장한 결함 프레임 하나** (1초에 1장): `status`(항상 `done`), `decision`(NG·REVIEW), `defect_label`, `score`, `scores`(`[score]`), `threshold`, `detections`, `frame_size`, `checkpoint`, `frames_dir`(사진 경로 — `frames.file`과 같다), `overlay_path`, `attempt`(그 연결의 `seq`), `captured_at` = `result_at` |
| `decisions`, `alerts` | 제품 단위 때의 사람의 최종 판정·연속 판정 알림. **옛 데이터 보존용** — 새 코드는 쓰지 않는다 |

- 행은 파일(원본·오버레이)을 쓴 뒤에 `done`으로 한 번에 넣는다. 그래서 조회에 행이 보이면 이미지도 있다.
- 제품 단위 때의 행(`pending`·`failed`, 여러 장의 `scores`, `alert_id`)도 같은 테이블에 남아 있다. 기간을 섞어 볼 때는 `scores`의 길이나 날짜·`checkpoint`로 나눈다.
- 엔드포인트: `GET /results/{id}`, `GET /results/{id}/overlay`, `PUT /products/{id}/feedback`, `GET /products`, `GET /products/{id}`, `GET /products/{id}/frames`, `GET /products/{id}/frames/{seq}.jpg`, `GET /storage` (규격은 `A4_WEBRTC_API.md` §4). 조회 응답의 `alert`는 이전 규격과 모양을 맞추려고 남긴 필드로 항상 `null`이다. `POST /results/{id}/decision`은 없어졌다 (`404`).
- 풀 설정(연결 대기 1초, 꺼낼 때 연결 확인)의 근거는 `A4_PLAN.md`(보관) §7.5·§8-16. 이번 전환에서 바뀌지 않았다.

```bash
# 오늘 스테이션별 저장된 결함 프레임 수
docker exec a4-db psql -U a4 -d a4 -c "
SELECT station_id, decision, count(*) FROM results
WHERE status = 'done' AND station_id IS NOT NULL AND captured_at >= current_date
GROUP BY station_id, decision ORDER BY station_id, decision;"

# 한 스테이션의 최근 결함 프레임 20건
docker exec a4-db psql -U a4 -d a4 -c "
SELECT result_id, captured_at, decision, defect_label, round(score::numeric, 3) AS score, frames_dir
FROM results WHERE station_id = 'ST-02' ORDER BY captured_at DESC LIMIT 20;"

# 결함 종류별 개수 (NG·REVIEW)
docker exec a4-db psql -U a4 -d a4 -c "
SELECT defect_label, decision, count(*) FROM results
WHERE status = 'done' AND decision <> 'OK' GROUP BY defect_label, decision ORDER BY count(*) DESC;"
```

- 저장은 1초에 1장으로 솎아낸 것이라, 위 개수는 "결함이 보인 프레임 수"가 아니다 (그건 `ws_close.defect_frames`, 또는 `frames`에서 `decision <> 'OK'`).

```bash
# 오늘 제품 판정·피드백 집계
docker exec a4-db psql -U a4 -d a4 -c "
SELECT station_id, decision, feedback, count(*) FROM products
WHERE started_at >= current_date GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;"

# 학습 라벨이 있는 제품 (맞음·틀림)과 그 사진 수
docker exec a4-db psql -U a4 -d a4 -c "
SELECT p.product_id, p.decision, p.feedback, count(f.file) AS photos FROM products p
JOIN frames f USING (product_id) WHERE p.feedback IN ('CORRECT', 'WRONG')
GROUP BY 1, 2, 3 ORDER BY p.product_id DESC LIMIT 20;"
```
- 모델을 바꾸면 판정 기준도 바뀐다. 기간을 길게 볼 때는 `checkpoint`로 나눠 본다.

---

## 5. 배포

### 5.A 제품 단위 → 프레임 단위 전환 (gpudev, 2026-11)

이미 PD·QI(제품 단위, 2026-10-29 코드)로 돌고 있는 서버에 이번 코드를 올리는 절차다. `a4-db`와 그 데이터·비밀번호는 그대로 쓴다. 처음 설치하는 서버는 아래 ①~⑧을 따른다. 근거와 변경 내용은 `PLAN_realtime_inspect.md`.

```bash
cd /home/admin/odServer   # 프로젝트 폴더 ($PWD)

# 1. 지금 코드·문서 보관 (되돌릴 때 쓴다) — 서비스는 계속 돈다
mkdir -p archive_product_mode && cp -a api gateway DEPLOY.md A4_WEBRTC_API.md archive_product_mode/ && ls archive_product_mode

# 2. 새 코드·문서 복사 (예: 묶음을 /tmp/new에 풀었을 때) → 빌드. 빌드가 실패하면 여기서 멈춘다 (서비스 그대로)
cp -a /tmp/new/api/. api/
cp -a /tmp/new/gateway/. gateway/
cp /tmp/new/DEPLOY.md /tmp/new/A4_WEBRTC_API.md /tmp/new/PLAN_realtime_inspect.md .
ls -A api; ls gateway
#   기대: api 8개 (.dockerignore Dockerfile api.py db.py detect.py requirements.txt stream.py stream_client.py)
#         gateway 5개 (Dockerfile README.md gateway.py requirements.txt webrtc_client.py)
grep -c "def judge" api/stream.py; grep -c "decision\")" gateway/gateway.py
#   기대: 1 / 0 (새 stream.py, decision 프록시 없음)
docker build -t a4-api api/ --no-cache
docker build -t a4-gateway gateway/ --no-cache

# 3. DB 덤프 — 여기서부터 서비스 중단
docker exec a4-db pg_dump -U a4 a4 > archive_product_mode/a4_db.sql && ls -l archive_product_mode/a4_db.sql
docker rm -f a4-gateway a4-api

# 4. 옛 pending 행 정리 — 새 코드에는 만료 처리가 없다. API를 지운 뒤에 해야 새 pending이 생기지 않는다
docker exec a4-db psql -U a4 -d a4 -c "UPDATE results SET status = 'failed' WHERE status = 'pending';"

# 5. API·게이트웨이 기동 — §6.1처럼 비밀번호를 읽고 §5 ⑤의 docker run(바뀌지 않음), startup·db_ready 확인, 이어서 §5 ⑦
export A4_DB_PASSWORD=$(docker inspect a4-db --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^POSTGRES_PASSWORD=//p')
[ -n "$A4_DB_PASSWORD" ] && echo "password set" || echo "EMPTY — 여기서 멈춘다"
```

- 6. §5 ⑧ 확인. 7. `A4_WEBRTC_API.md` 머리말을 배포 날짜와 확인 결과로 고치고, §8의 "확인하지 않은 것"을 옮긴다. 8. 앱 개발자에게 알린다 (개발 서버가 새 규격으로 바뀐다).
- **되돌리기**: `archive_product_mode/`의 `api`·`gateway`를 제자리로 복사해 다시 빌드하고 같은 명령으로 띄운다. DB는 새 행이 더해지기만 하므로 그대로 둬도 된다 (옛 코드는 새 행을 제품 단위 결과로 조회할 뿐이다).
- 옛 제품 단위 코드의 연속 판정 알림은 되돌린 뒤 새 행(결함 프레임)까지 세게 된다. 되돌린 뒤 알림이 이상하면 스테이션별 마지막 알림 이후 행을 확인한다.

### 5.B 프레임 단위 → 제품 단위·피드백 (gpudev, 2026-11-06 코드)

2026-11-03 코드(프레임 단위)로 돌고 있는 서버에 이번 코드를 올린다. `a4-db`와 데이터·비밀번호는 그대로 쓰고, 새 테이블은 API가 기동하며 만든다. **`product_frames/` 폴더를 새로 붙여야 한다** — 빠뜨리면 사진이 컨테이너 안에 쌓였다가 컨테이너를 다시 만들 때 사라지고, `/storage`의 디스크 값도 컨테이너 기준이 된다.

```bash
cd /home/admin/odServer   # 프로젝트 폴더 ($PWD)

# 1. 지금 코드·문서 보관 — 서비스는 계속 돈다
mkdir -p archive_frame_mode && cp -a api gateway DEPLOY.md A4_WEBRTC_API.md archive_frame_mode/ && ls archive_frame_mode

# 2. 새 코드·문서 복사 → 빌드. 빌드가 실패하면 여기서 멈춘다 (서비스 그대로)
cp -a /tmp/new/api/. api/
cp -a /tmp/new/gateway/. gateway/
cp /tmp/new/DEPLOY.md /tmp/new/A4_WEBRTC_API.md /tmp/new/PLAN_product_feedback.md .
grep -c "class Products" api/stream.py; grep -c "def to_i420" gateway/gateway.py
#   기대: 1 / 1
docker build -t a4-api api/ --no-cache
docker build -t a4-gateway gateway/ --no-cache

# 3. 사진 폴더 — API 컨테이너가 쓰므로 미리 만든다
mkdir -p product_frames

# 4. DB 덤프 — 여기서부터 서비스 중단
docker exec a4-db pg_dump -U a4 a4 > archive_frame_mode/a4_db.sql && ls -l archive_frame_mode/a4_db.sql
docker rm -f a4-gateway a4-api

# 5. 비밀번호를 읽고 §5 ⑤의 docker run(product_frames 마운트가 더해졌다) → startup·db_ready, 이어서 §5 ⑦
export A4_DB_PASSWORD=$(docker inspect a4-db --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^POSTGRES_PASSWORD=//p')
[ -n "$A4_DB_PASSWORD" ] && echo "password set" || echo "EMPTY — 여기서 멈춘다"

# 6. 새 테이블·마운트 확인
docker exec a4-db psql -U a4 -d a4 -c '\dt'
#   기대: alerts, decisions, feedback_log, frames, products, results, sessions, stations
docker inspect a4-api --format '{{range .Mounts}}{{.Destination}} {{end}}'
#   기대: /app/product_frames 가 들어 있다
```

- 7. §5 ⑧ 확인에 더해: 앱(또는 `webrtc_client.py --mode manual`)으로 제품을 비췄다 치우면 `product_end`가 오는지, `GET :8080/products?station=ST-02&limit=1`에 그 제품이 보이는지, `product_frames/<오늘>/`에 사진이 쌓이는지, `GET :8080/storage`가 200인지.
- 8. `A4_WEBRTC_API.md` 머리말을 배포 날짜와 확인 결과로 고치고, 앱 개발자에게 알린다.
- **되돌리기**: `archive_frame_mode/`의 `api`·`gateway`를 제자리로 복사해 다시 빌드하고, 옛 명령(`product_frames` 마운트 없이)으로 띄운다. 새 테이블은 옛 코드가 쓰지 않으므로 그대로 둬도 된다.

### 처음 설치 — 이상탐지를 내리고 PD·QI로 다시 올리기 (①~⑧)

| 단계 | 하는 일 | 기존 서비스 |
|---|---|---|
| ① 준비 | 기존 코드 보관, 새 코드로 교체, 모델·테스트 자료 배치 | 계속 돈다 |
| ② 빌드 | 두 이미지 빌드 | 계속 돈다 (빌드가 실패하면 여기서 멈추고 기존 서비스 유지) |
| ③ 내리기 | DB 덤프, 컨테이너 삭제, 이상탐지 데이터·기록 보관 | **여기서 중단** |
| ④ DB | 새 `a4-db` (새 비밀번호) | |
| ⑤ API | `a4-api` 기동 | |
| ⑥ 스테이션 | ST-01, ST-02 등록 | |
| ⑦ 게이트웨이 | `a4-gateway` 기동 | 재개 |
| ⑧ 확인 | 동작·예외·연결 테스트 | |

**전제**

- 호스트: CUDA 13을 지원하는 NVIDIA 드라이버, NVIDIA Container Toolkit (`docker run --gpus`).
- 빌드에 pip(PyPI)와 apt(Ubuntu/Debian 저장소) 접근이 필요하다. `postgres:17`, `pytorch/pytorch:2.10.0-cuda13.0-cudnn9-devel`, `python:3.12-slim` 이미지를 받을 수 있어야 한다. 이전과 달리 Hugging Face 접근(DINOv2 가중치)은 필요 없다.
- 서버 시간대와 무관하게 아래 `date`는 `TZ=Asia/Seoul`로 쓴다.

### ① 준비 (기존 서비스는 계속 돈다)

```bash
cd /home/admin/odServer   # 프로젝트 폴더 ($PWD)
A=archive_a4_$(TZ=Asia/Seoul date +%Y%m%d); mkdir -p $A; echo "보관 폴더: $A"

# 1. 기존 코드 보관 (되돌릴 때 쓴다)
mkdir -p $A/code && cp -a api gateway $A/code/ && ls $A/code

# 2. 실행 중인 컨테이너가 쓰지 않는 이상탐지 자료를 먼저 보관 폴더로 옮긴다 (지우지 않는다)
#    db·logs·frames·results·checkpoints는 컨테이너가 붙여 쓰고 있으므로 ③에서 옮긴다
for d in raw videos data a4_check a4_check_cmp checkpoints_cmp stream_out webrtc_out trunc.jpg bomb.png; do
  [ -e "$d" ] && mv "$d" "$A/" && echo "보관: $d"
done

# 3. 이상탐지 코드 삭제
rm -r api/corelib
rm api/a4_common.py api/prepare_a4.py api/build_a4.py api/smoke_a4.py api/bench_a4.py

# 4. 새 코드 복사 — 전달받은 묶음을 풀어 api/·gateway/·문서를 덮어쓴다 (예: 묶음을 /tmp/new에 풀었을 때)
cp -a /tmp/new/api/. api/
cp -a /tmp/new/gateway/. gateway/
cp /tmp/new/DEPLOY.md /tmp/new/A4_WEBRTC_API.md /tmp/new/A4_PLAN.md .
ls -A api; ls gateway
#   기대: api 8개 (.dockerignore Dockerfile api.py db.py detect.py requirements.txt stream.py stream_client.py)
#         gateway 5개 (Dockerfile README.md gateway.py requirements.txt webrtc_client.py)
grep -c ultralytics api/requirements.txt; grep -c corelib api/Dockerfile
#   기대: 1 / 0

# 5. 모델 — 파일명은 api/detect.py의 PD_MODEL, QI_MODEL과 같아야 한다
mkdir -p models && cp <모델 위치>/yolo_product_detection_model_20261020111232.pt <모델 위치>/yolo_quality_inspection_model_20261021091500.pt models/
ls -l models

# 6. 테스트 자료 — 제품 사진 몇 장(예: QI val 이미지)과 제품을 찍은 영상
mkdir -p samples videos && cp <제품 사진>.jpg samples/ && cp <제품 영상>.mp4 videos/
```

### ② 빌드 (기존 서비스는 계속 돈다)

```bash
docker build -t a4-api api/ --no-cache
docker build -t a4-gateway gateway/ --no-cache
#   a4-api: ultralytics 설치가 torch를 다시 받지 않아야 한다 (베이스 이미지의 torch 사용). 빌드 로그에 "Downloading torch-"가 없는지 본다
docker run --rm a4-api python -c "import ultralytics, cv2, fastapi, psycopg; print(ultralytics.__version__, cv2.__version__)"
#   기대: 8.4.117 4.x — libGL 관련 ImportError가 나면 Dockerfile의 libgl1 설치가 빠진 것
docker run --rm a4-api sh -c 'date; ls /usr/share/fonts/truetype/nanum/NanumGothic.ttf; ls /app'
#   기대: KST 시각, 폰트 파일 경로, /app에 api.py db.py detect.py requirements.txt stream.py stream_client.py (corelib 없음)
```

### ③ 내리기 — 여기서부터 서비스 중단

```bash
# 1. 기존 DB 덤프 (컨테이너를 지우기 전에)
docker exec a4-db pg_dump -U a4 a4 > $A/a4_db.sql && ls -l $A/a4_db.sql
#   새 셸이면 A를 다시 정한다: A=archive_a4_<①의 날짜>

# 2. 컨테이너 삭제 (게이트웨이 → API → DB). 없는 컨테이너는 "No such container" 경고만 난다
docker rm -f a4-gateway a4-api a4-api-1 a4-db
docker ps -a --format '{{.Names}}' | grep '^a4-'
#   기대: 출력 없음

# 3. 컨테이너가 쓰던 이상탐지 기록을 보관 폴더로 옮긴다 (지우지 않는다)
for d in db logs frames results checkpoints; do
  [ -e "$d" ] && mv "$d" "$A/" && echo "보관: $d"
done
ls; ls $A
#   기대: 최상위에 api gateway models samples videos 와 문서, 보관 폴더만. db/는 반드시 옮겨져 있어야 한다 —
#         남아 있으면 새 a4-db가 옛 데이터베이스(옛 비밀번호·옛 스키마)를 그대로 읽는다
```

- `db/`는 PostgreSQL 사용자 소유라도 같은 디스크 안 이동(`mv`)은 프로젝트 폴더 쓰기 권한만 있으면 된다. `Permission denied`면 `sudo mv`.
- 옛 이미지 `a4-anomaly`와 가중치 볼륨 `hf-cache`(약 1.2GB)는 **⑧ 확인이 끝날 때까지 지우지 않는다** — 되돌릴 때 필요하다 (§6.6).
- 네트워크 `a4-net`은 그대로 쓴다.

### ④ DB (새 비밀번호)

```bash
# 비밀번호는 셸 기록에 남지 않게 입력받는다. 영문·숫자로 정한다 — @ : / 같은 문자는 DATABASE_URL을 깨뜨린다
read -rsp "새 DB 비밀번호: " A4_DB_PASSWORD; echo; export A4_DB_PASSWORD
[ -n "$A4_DB_PASSWORD" ] && echo "password set" || echo "EMPTY — 여기서 멈춘다"
docker network inspect a4-net >/dev/null 2>&1 || docker network create a4-net
docker run -d --name a4-db --network a4-net --restart unless-stopped \
  -e POSTGRES_USER=a4 -e POSTGRES_PASSWORD="$A4_DB_PASSWORD" -e POSTGRES_DB=a4 -e TZ=Asia/Seoul \
  -v $PWD/db:/var/lib/postgresql/data \
  postgres:17
docker logs a4-db 2>&1 | tail -n 3
#   기대: "database system is ready to accept connections"
```

- 새 셸에서 비밀번호가 필요하면 `a4-db` 컨테이너 설정에서 다시 읽는다 (화면에 찍지 않는다):
  `export A4_DB_PASSWORD=$(docker inspect a4-db --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^POSTGRES_PASSWORD=//p')`

### ⑤ API

```bash
[ -n "$A4_DB_PASSWORD" ] && echo "password set" || echo "EMPTY — ④의 read 줄부터"
#   비어 있으면 DB 접속이 PoolTimeout으로 실패해 재시작만 반복한다 (startup만 반복, db_ready 없음)
docker run -d --name a4-api --gpus '"device=0"' -p 8000:8000 --restart unless-stopped \
  --network a4-net \
  -e DATABASE_URL="postgresql://a4:$A4_DB_PASSWORD@a4-db:5432/a4" \
  -v $PWD/models:/app/models:ro \
  -v $PWD/logs/a4-api:/app/logs \
  -v $PWD/frames:/app/frames \
  -v $PWD/product_frames:/app/product_frames \
  -v $PWD/results:/app/results \
  --log-opt max-size=50m --log-opt max-file=3 \
  a4-api
timeout 180 sh -c 'until curl -sf -o /dev/null localhost:8000/docs; do sleep 2; done' \
  && echo "API ready" || docker logs --tail 30 a4-api
docker logs a4-api 2>&1 | grep -E '"event": "(startup|db_ready)"' | tail -n 2
#   기대: startup (checkpoint "yolo_product_detection_model_…pt@…|yolo_quality_inspection_model_…pt@…",
#         pd_classes 3개, qi_classes 18개, pd_conf 0.25, qi_conf 0.25) 다음 db_ready
docker exec a4-db psql -U a4 -d a4 -c '\dt'
#   기대: alerts, decisions, results, sessions, stations
docker exec a4-db psql -U a4 -d a4 -c '\d results' | grep -E 'detections|frame_size|mask_path|is_anomalous'
#   기대: detections, frame_size 두 줄만 (mask_path·is_anomalous가 보이면 옛 db/를 읽고 있는 것 — ③-3 확인)
```

- 기동 시 PD·QI를 한 번씩 돌린다(예열). **GPU가 안 보이면(`--gpus` 누락 등) 여기서 기동이 멈추고** `docker logs`에 CUDA 장치 오류가 남는다 — CPU로 조용히 돌지 않게 했다.
- `FileNotFoundError: models/…pt`면 ①-4의 파일명이나 `-v $PWD/models` 마운트를 확인한다.

### ⑥ 스테이션 등록

```bash
#   ST-01: 서버 테스트용 (⑧이 쓴다). ST-02: 모바일 앱용 (A4_WEBRTC_API.md §2.1). 알림이 섞이지 않게 나눈다
docker exec a4-db psql -U a4 -d a4 -c "INSERT INTO stations (station_id, name) VALUES ('ST-01', '1번 스테이션'), ('ST-02', '2번 스테이션');"
docker exec a4-db psql -U a4 -d a4 -c "SELECT station_id, name FROM stations ORDER BY station_id;"
#   기대: ST-01, ST-02
```

### ⑦ 게이트웨이

```bash
docker run -d --name a4-gateway --network host --restart unless-stopped \
  -v $PWD/logs/a4-gateway:/app/logs \
  --log-opt max-size=50m --log-opt max-file=3 \
  a4-gateway
until curl -sf -o /dev/null localhost:8080/docs; do sleep 1; done; echo "gateway ready"
```

- 방화벽은 포트가 그대로라 바꿀 것이 없다. 현재 상태 확인은 §7.

### ⑧ 확인

**API — 사진 1장, 속도**

```bash
until curl -sf -o /dev/null localhost:8000/docs; do sleep 1; done; echo "API ready"
D=$(TZ=Asia/Seoul date +%F)
#   파일명에 공백이 있을 수 있어 $IMG·$V 등은 항상 따옴표로 감싼다 — 없으면 셸이 경로를 나눠 curl이 요청 없이 실패하고(-s라 조용히)
#   python이 빈 입력을 받아 JSONDecodeError가 난다 (2026-11-03 gpudev, "samples/20260831 134401-002.jpg")
IMG=$(ls samples/*.jpg | head -1); echo "$IMG"
curl -s -F "file=@$IMG" localhost:8000/predict | python3 -c "
import sys, json; r = json.load(sys.stdin)
print(len(r['products']), r['decision'], r['defect_label'], [(d['label'], round(d['conf'], 2)) for d in r['detections']], round(r['score'], 3), r['threshold'])"
#   기대: 제품 상자 개수 ≥ 1, decision, defect_label, 제품 상자 안의 결함 목록, 점수, 0.25 (가모델이라 결함 결과 자체는 맞지 않을 수 있다)
#   KeyError가 나면 422(제품 없음)다 — PD가 그 사진에서 제품을 못 찾은 것이니 다른 사진으로 바꾼다
for i in 1 2 3; do curl -s -o /dev/null -F "file=@$IMG" localhost:8000/predict; done
grep '"event": "predict"' logs/a4-api/$D.jsonl | tail -n 3 | python3 -c "
import sys, json
for l in sys.stdin: r = json.loads(l); print(r['image_size'], r['ms'])"
#   ms.pd = 프레임 1장 PD 시간, ms.qi = 사진 1장 QI 시간 (타일 수에 비례: 1080p 8타일, 4K 32타일).
#   /ws는 프레임마다 decode + pd + qi를 차례로 한다 — 합이 100ms를 넘으면 초당 10장을 처리하지 못한다 (아래 처리 속도)
```

**API — 예외·동시 요청**

```bash
head -c $(( $(stat -c%s "$IMG") / 2 )) "$IMG" > trunc.jpg
curl -s -o /dev/null -w "%{http_code}\n" -F file=@trunc.jpg localhost:8000/predict
#   기대: 400
docker exec a4-api python -c "from PIL import Image; Image.new('1', (14000, 14000)).save('/tmp/bomb.png'); Image.new('RGB', (1920, 1080)).save('/tmp/black.jpg')"
docker cp a4-api:/tmp/bomb.png bomb.png; docker cp a4-api:/tmp/black.jpg black.jpg
curl -s -w " %{http_code}\n" -F file=@bomb.png localhost:8000/predict
#   기대: {"detail":"이미지 해상도가 너무 큽니다."} 400
curl -s -w " %{http_code}\n" -F file=@black.jpg localhost:8000/predict
#   기대: {"detail":"제품을 찾지 못했습니다."} 422 (PD가 검은 화면에서 제품을 찾으면 200 — 가모델 확인 사항으로 기록)
for i in 1 2 3 4 5; do curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" -F "file=@$IMG" localhost:8000/predict & done; wait
#   기대: 5건 모두 200
# /ws 깨진 프레임: 이미지 아님 → 해상도 초과 → 검은 화면
docker exec -i a4-api python - <<'EOF'
import asyncio, io, websockets
from PIL import Image
buf = io.BytesIO(); Image.new("1", (14000, 14000)).save(buf, "PNG"); bomb = buf.getvalue()
buf = io.BytesIO(); Image.new("RGB", (640, 480)).save(buf, "JPEG"); black = buf.getvalue()
async def main():
    async with websockets.connect("ws://localhost:8000/ws") as ws:
        print(await ws.recv())
        for frame in (b"not an image", bomb, black):
            await ws.send(frame); await asyncio.sleep(0.5)
        print(await asyncio.wait_for(ws.recv(), 10))
asyncio.run(main())
EOF
#   기대: {"state": "searching"} 다음 {"state": "searching", "reason": "no_product"}
# 스테이션 검증
docker exec -i a4-api python - <<'EOF'
import asyncio, websockets
async def main():
    for q in ("?station=NOPE", "?station=", "", "?station=ST-01"):
        try:
            async with websockets.connect("ws://localhost:8000/ws" + q) as ws:
                print(repr(q), await ws.recv())
        except websockets.ConnectionClosed as e:
            print(repr(q), "closed", e.rcvd.code if e.rcvd else None)
asyncio.run(main())
EOF
#   기대: '?station=NOPE' closed 4403 / '?station=' closed 4403 / '' {"state": "searching"} / '?station=ST-01' {"state": "searching"}
tail -n 20 logs/a4-api/$D.jsonl
#   기대: predict 400(unreadable_image, too_large)·422(product_not_found)·200(decision 포함), ws_open → ws_close(skipped_frames 2), ws_rejected ×2. ts가 +09:00
```

**API — `/ws` 연속 프레임, 저장, 조회**

```bash
# 사진마다 10장(제품 비춤) + 검은 프레임 30장(치움)을 10fps로 보낸다
docker run --rm --network host -v $PWD/samples:/samples:ro -v $PWD/stream_out/photos:/out \
  a4-api sh -c 'ls /samples/*.jpg | head -3 | tr "\n" "\0" | xargs -0 python stream_client.py --out /out'
#   기대: searching → inspect … → searching (no_product) → … 사진마다. 같은 사진 10장이라 inspect가 같은 판정.
#         NG·REVIEW 사진은 1초에 1장꼴로 result_id (사진 10장 = 1초 → 1~2장 저장). OK는 "inspect OK ×N" 한 줄.
#         끝에 "요약: inspect N (OK a / REVIEW b / NG c), 저장 m". 오버레이는 stream_out/photos/NNN_<decision>_<result_id>.jpg
grep inspect_saved logs/a4-api/$D.jsonl | tail -n 3
ls frames/$D/ results/$D/
#   기대: 저장마다 frames/$D/<result_id>.jpg, results/$D/<result_id>/overlay.jpg
grep '"event": "ws_close"' logs/a4-api/$D.jsonl | tail -n 1
#   기대: product_frames = 요약의 inspect 수, saved = 요약의 저장 수 = 그 세션의 frames 파일 수, error null
RID=$(grep inspect_saved logs/a4-api/$D.jsonl | tail -n 1 | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['result_id'])"); echo $RID
curl -s localhost:8000/results/$RID; echo
#   기대: "status": "done", "scores": [score], detections·frame_size·overlay_url 있음, "alert": null
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" localhost:8000/results/$RID/overlay
curl -s -o /dev/null -w "%{http_code}\n" localhost:8000/results/nope
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"final": "OK", "method": "nod"}' localhost:8000/results/$RID/decision
#   기대: 200 image/jpeg / 404 / 404 (decision은 없어진 경로)
#   오버레이를 열어 작은 결함이 빨간 테두리·글자에 가려지지 않았는지 눈으로 본다 (테두리·글자는 상자 바깥)
#   결함 사진이 없어 저장이 하나도 없으면 RID가 비어 있다 — 결함이 나오는 사진을 samples/에 넣는다
```

**API — 처리 속도** (`PLAN_realtime_inspect.md` §9 R1)

```bash
docker run --rm --network host -v $PWD/videos:/videos:ro a4-api sh -c 'python stream_client.py /videos/*'
grep '"event": "ws_close"' logs/a4-api/$D.jsonl | tail -n 1 | python3 -c "
import sys, json; c = json.loads(sys.stdin.read())
print('fps', round(c['processed'] / c['duration_s'], 1), '밀림', c['frames'] - c['processed'] - c['skipped_frames'], 'ms_avg', c['ms_avg'])"
#   기록: 영상 해상도별(4K 테스트 영상, 가능하면 1080p 영상) fps·밀림·ms_avg.
#   ms_avg.decode + pd + qi가 100ms를 넘으면 제품을 비추는 동안 초당 10장을 처리하지 못한다 — 1080p에서 그렇다면 §8 처리 속도
#   inspect가 한 번도 안 나오면 PD가 영상에서 제품을 못 찾는 것이다
```

**게이트웨이 — 영상을 앱처럼 WebRTC로**

```bash
for v in videos/*; do
  docker run --rm --network host -v $PWD/videos:/videos:ro -v $PWD/webrtc_out:/out \
    a4-gateway python webrtc_client.py "/videos/${v##*/}" --station ST-01 --out "/out/${v##*/}"
done
#   기대: 연결 상태 connected → searching / inspect OK ×N / inspect seq … NG|REVIEW … result_id … → 영상 끝 → 요약
grep '"a4_message"' logs/a4-gateway/$D.jsonl | python3 -c "
import sys, json
m = [json.loads(l)['message'] for l in sys.stdin if json.loads(l)['message']['state'] == 'inspect']
print(sorted(m[-1]) if m else '결함 inspect 없음 — OK만 나왔다 (a4_message에는 NG·REVIEW만 남는다)')"
#   기대: ['decision', 'defect_label', 'detections', 'frame_size', 'overlay_url', 'products', 'result_id', 'score', 'seq', 'state', 'threshold']
grep relay_summary logs/a4-gateway/$D.jsonl | tail -n 1
grep ws_close logs/a4-api/$D.jsonl | grep '"source": "gateway"' | tail -n 1
#   기대: relay_summary.inspect = ws_close.product_frames, relay_summary.peer = ws_close.client, station ST-01
RID=$(docker exec a4-db psql -U a4 -d a4 -tAc "SELECT result_id FROM results WHERE status = 'done' AND result_id NOT LIKE 'direct_%' ORDER BY result_at DESC LIMIT 1;"); echo $RID
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/results/$RID
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" localhost:8080/results/$RID/overlay
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"final": "OK", "method": "nod"}' localhost:8080/results/$RID/decision
#   기대: 200 / 200 image/jpeg / 404 (게이트웨이도 decision 프록시가 없다)
#   이 SELECT는 제품 단위 때의 행도 고를 수 있다 — 오늘 게이트웨이 경유로 저장된 결함 프레임이 없으면 옛 결과가 나온다
```

**게이트웨이 — 거부·장애**

```bash
V=$(ls videos | head -1); echo "영상: $V"
curl -s -o /dev/null -w "%{http_code}\n" -H 'Content-Type: application/json' -d '{"sdp": "x", "type": "offer"}' localhost:8080/offer
#   기대: 422
docker run --rm --network host -v $PWD/videos:/videos:ro a4-gateway python webrtc_client.py "/videos/$V" --station NOPE; echo "exit $?"
#   기대: /offer 실패: 403 …, exit 1
docker stop a4-api
docker run --rm --network host -v $PWD/videos:/videos:ro a4-gateway python webrtc_client.py "/videos/$V" --station ST-01; echo "exit $?"
#   기대: /offer 실패: 503 …, exit 1
docker start a4-api
until curl -sf -o /dev/null localhost:8000/docs; do sleep 1; done; echo "API ready"
```

**확인이 끝나면** 테스트 파일을 지운다.

```bash
rm -f trunc.jpg bomb.png black.jpg
```

- 테스트 결과(`stream_out/`, `webrtc_out/`)도 필요 없으면 지운다.
- ST-01 결과(저장된 결함 프레임)는 DB·`frames/`·`results/`에 남는다. 앱(ST-02) 기록과는 `station_id`로 나뉜다.

---

## 6. 운영

### 6.1 코드나 설정값을 바꿨을 때

코드는 빌드할 때 이미지에 복사된다. `docker restart`는 옛 이미지를 계속 쓰므로 **재빌드 후 컨테이너를 새로 만든다.** `a4-db`는 그대로 둔다.

```bash
docker build -t a4-api api/ --no-cache
export A4_DB_PASSWORD=$(docker inspect a4-db --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^POSTGRES_PASSWORD=//p')
[ -n "$A4_DB_PASSWORD" ] && echo "password set" || echo "EMPTY — 여기서 멈춘다"
docker rm -f a4-api
# §5 ⑤의 docker run 명령 그대로, 이어서 startup·db_ready 확인
```

- 게이트웨이를 바꿨으면 `docker build -t a4-gateway gateway/ --no-cache && docker rm -f a4-gateway` 후 §5 ⑦.
- **응답 필드나 `/ws` 연결 방식을 바꾸면 API와 게이트웨이를 같이 재배포한다.**

### 6.2 모델 교체

- **파일명이 같으면**: `models/`의 파일을 덮어쓰고 `docker restart a4-api`. 모델은 기동 시 한 번만 읽는다. `startup`의 checkpoint(파일 수정 시각 포함)가 바뀌었는지 확인한다.
- **파일명이 다르면**: `api/detect.py`의 `PD_MODEL`·`QI_MODEL`을 고치고 §6.1.
- QI의 클래스 이름은 모델에서 읽는다. 클래스가 바뀌면 `defect_label`도 따라 바뀐다 — 앱이 이름을 하드코딩하지 않는지 확인한다.
- 모델을 바꾸면 기준값(`QI_CONF`)도 다시 본다. DB는 결과마다 `checkpoint`가 남아 모델별로 나눠 볼 수 있다.

### 6.3 기준값 바꾸기

`api/detect.py`의 `QI_CONF`를 고치고 §6.1. REVIEW 구간(×0.9~×1.1)과 QI 추론 하한이 함께 움직인다. 바꾼 뒤 §5 ⑧의 `/predict`로 사진 몇 장의 `score`를 본다.

### 6.4 GPU 여러 장

GPU마다 API 컨테이너를 하나씩 띄운다. `frames/`·`product_frames/`·`results/`·`models/`는 공유하고 `logs/`는 따로 붙인다. DB는 같은 `a4-db`.

```bash
docker rm -f a4-api-1
docker run -d --name a4-api-1 --gpus '"device=1"' -p 8001:8000 --restart unless-stopped \
  --network a4-net \
  -e DATABASE_URL="postgresql://a4:$A4_DB_PASSWORD@a4-db:5432/a4" \
  -v $PWD/models:/app/models:ro \
  -v $PWD/logs/a4-api-1:/app/logs \
  -v $PWD/frames:/app/frames \
  -v $PWD/product_frames:/app/product_frames \
  -v $PWD/results:/app/results \
  --log-opt max-size=50m --log-opt max-file=3 \
  a4-api
```

- 모델을 바꾸면 모든 API 컨테이너를 재시작한다. 요청을 나누는 로드밸런서는 범위 밖이다.

### 6.5 백업·디스크

- DB: `docker exec a4-db pg_dump -U a4 a4 > a4_$(TZ=Asia/Seoul date +%F).sql`. `db/`를 직접 복사하지 않는다.
- 디스크: `GET /storage`(앱 M-06) 또는 `du -sh logs product_frames frames results db`. 자동 삭제가 없다 (§4.2).

### 6.6 되돌리기와 보관물 정리

- §5 ⑧까지 확인되기 전에는 옛 이미지 `a4-anomaly`와 볼륨 `hf-cache`를 남겨 둔다. 되돌릴 때: 새 컨테이너 3개를 지우고(`docker rm -f a4-gateway a4-api a4-db`), 새 `db/`를 다른 이름으로 옮긴 뒤, 보관 폴더의 `db/`·`checkpoints/`·`logs/` 등과 `code/`를 제자리로 되돌려 `A4_PLAN.md`(보관) §11.2의 ③-1·④·⑥으로 띄운다. 옛 게이트웨이 이미지는 같은 태그로 덮였으므로 `code/gateway`에서 다시 빌드한다.
- 확인이 끝나 되돌릴 일이 없으면:
  ```bash
  docker image rm a4-anomaly
  docker volume rm hf-cache       # DINOv2 가중치 약 1.2GB
  docker image prune -f           # 태그가 떨어진 옛 게이트웨이 이미지 등
  ```
- 보관 폴더(`archive_a4_<날짜>/`)는 언제 지울지 따로 정한다. 이상탐지 단계의 프레임·DB에는 사람의 최종 판정이 들어 있다.

---

## 7. 방화벽·보안

**방화벽** — 이번 재배포는 포트(TCP 8000, 8080, UDP 임시 포트 범위)를 바꾸지 않으므로 규칙을 건드릴 필요가 없다. 다만 기록이 서로 맞지 않으니 현재 상태를 한 번 확인한다.

```bash
systemctl is-active firewalld ufw
firewall-cmd --list-ports
firewall-cmd --list-rich-rules
cat /proc/sys/net/ipv4/ip_local_port_range
```

- 이전 문서들에는 두 가지가 함께 적혀 있었다: 사내 Wi-Fi 대역(`192.168.1.0/24`)에만 여는 규칙(`gateway/README.md`, 앱 규격)과, 그 규칙을 지우고 **출처 제한 없이** 여는 명령(`A4_PLAN.md` §11.2 ⑥).
  - `--list-ports`에 `8080/tcp 32768-60999/udp`가 있으면 출처 제한 없이 열려 있는 것이다.
  - `--list-rich-rules`에 `192.168.1.0/24` 규칙이 있으면 대역 제한이다.
- 어느 쪽으로 둘지는 운영에서 정한다. API에 인증이 없으므로, 앱이 있는 대역으로 제한하는 편이 안전하다. 대역 제한으로 되돌리는 명령은 `gateway/README.md` "방화벽"에 있다.
- `:8000`은 Docker 포트 매핑(`-p 8000:8000`)이라 Docker가 방화벽 규칙을 직접 넣는다. firewalld 설정과 별개로 열려 있을 수 있으니, 앱과 같은 망의 기기에서 `http://<서버 IP>:8000/docs`가 열리는지로 확인한다.

**그 밖의 보안 사항**

- **인증·HTTPS 없음.** `/predict`, `/docs`, `/results/*`, `/ws`가 열린 망 누구에게나 응답한다.
- **DB 비밀번호**: 새 DB에서 새로 정했다 (§5 ④). 이전 문서에 **옛 비밀번호가 평문으로 적혀 있었다** — 보관된 `A4_PLAN.md`에서는 지웠지만, 그 문서를 다른 곳(공유 폴더, 프로젝트 자료, 메신저 등)에 올렸다면 거기서도 지운다. 같은 비밀번호를 다른 곳에 쓰고 있었다면 바꾼다.
- **사용 통계 전송 끔**: Ultralytics는 기본값으로 사용 통계(추론 포함)를 외부로 보낸다. `api.py`가 기동 시 `settings.update({"sync": False})`로 끈다.
- 헤드마운트 영상에는 작업자나 작업장이 찍힐 수 있다. `frames/`를 보관해도 되는지는 운영 측에서 확인한다.

---

## 8. 알려진 제약과 확인하지 않은 것

**확인한 것 — 제품 단위·피드백 (작성 환경, 2026-11-06)** — 이번에는 실제 FastAPI·websockets·aiortc·psycopg(PostgreSQL 16)를 설치해 돌렸다. PD·QI만 색으로 상자를 찾는 가짜 YOLO다. `/ws`·HTTP 42건 + WebRTC 게이트웨이 종단:

- 제품 구간(1초 무소식·프레임이 끊긴 뒤 tick·연결 끊김), 확정(NG 3장 → FAIL/NG, REVIEW만 → FAIL/REVIEW), 면 진행(3장째부터 `faces_seen`, 세 면 → `complete`), `product_end` 필드·순서(앞 제품 끝이 다음 제품 첫 `inspect`보다 먼저), 피드백 초기값(manual `NOT_ENTERED`·auto `NONE`).
- 저장: 제품 프레임마다 사진·`frames` 행, JPEG 입력은 받은 바이트 그대로, I420 입력은 JPEG로, 결함 프레임 1초에 1장 `results`·오버레이, `best_result_id`, 끊김 제품 `disconnect` 기록.
- API: 피드백 입력·같은 값 무기록·수정·변경 기록·`NONE` 입력 422·없는 제품 404, 목록(최신 순·`before` 페이지·feedback/decision/날짜 필터·잘못된 필터 422), 상세, 프레임 목록·사진·`overlay=1`·없는 프레임 404, `/storage`, `/ws?mode=bad` 4422.
- 게이트웨이: `webrtc_client.py --mode manual`로 동영상(10fps, 90장) → 제품 2개(FAIL·PASS) `product_end`, 제품 프레임 40장 모두 `inspect`(간격 제한 삭제로 버린 장 없음), `/products*`·`PUT`·`/storage` 중계, 잘못된 `feedback_mode` 422.

**확인하지 않은 것 (2026-11-06)**: 실제 YOLO·GPU, 실서버(gpudev) 배포, 실제 모바일·HMD 앱 연결(앱의 안드로이드 코드는 이 환경에서 빌드하지 못했다), I420 전달·JPEG 품질 90에서의 처리 속도와 디스크 증가량.

**확인한 것 — 프레임 단위 (작성 환경, 2026-11-03)** — torch·ultralytics·FastAPI·websockets·psycopg·aiortc를 설치할 수 없어 PD·QI를 색으로 상자를 찾는 가짜 모델로, FastAPI·websockets·aiortc·httpx를 대역으로 바꾸고 실제 `api.py`·`stream.py`·`detect.py`·`db.py`·`stream_client.py`·`gateway.py`·`webrtc_client.py`를 돌렸다. SQL은 psql을 거쳐 실제 PostgreSQL 16에서 실행했다 (드라이버만 대역). 125개 확인 통과.

- 판정: `judge` OK/REVIEW/NG 경계, 제품 상자 밖(배경) 결함 제외(경계 포함·제품 여러 개·순서 유지), 하한 미만 결함 → OK, 타일 경계 결함의 NMS 정리.
- `/ws`: 첫 `searching`(reason 없음), `searching`은 바뀔 때만, 제품 프레임마다 `inspect`(키가 `A4_WEBRTC_API.md` §3.3과 정확히 같음, 이미지 없음), `seq` 증가, 저장 간격(0.1초 간격 결함 3초 → 3장, 저장 사이 ≥1초), 저장물(원본 = 보낸 바이트, 오버레이, DB 행 `done`·`scores [score]`·`attempt = seq`), 조회 200·`alert null`·`/overlay` JPEG·없는 ID 404, 저장 중 끊김 → 저장 완료·`saved` = 파일 수, 저장 없이 끊김, 앱 쪽 끊김(전송 실패) → 오류 없이 종료, 깨진 프레임 건너뜀, 미등록·빈 스테이션 4403, DB 장애 4503·장애 중 `inspect` 계속·`db_error`.
- `ws_close` 숫자: `frames` = `processed` + `skipped_frames` + 밀림, `product_frames` = `inspect` 수, `defect_frames`, `saved`, `ms_avg`.
- 오버레이: 4K 실측 상자(14×13px)에서 상자 안 210픽셀이 모두 원본 그대로 (옛 그리기는 204픽셀이 빨강). 한글 폰트(CJK)에서도 같고, 프레임 위 끝 상자는 글자가 상자 아래로 간다.
- `/predict` 200(`decision`·`defect_label`, 배경 결함 제외)·400·422, 기동 시 통계 전송 끔·PD·QI 예열, `decision` 라우트 없음.
- `stream_client.py`: 같은 프로세스의 `/ws`에 붙여 출력(OK 묶음)·요약(= 서버 `ws_close`)·`--out` 오버레이 받기. 오버레이 404 → 0.5초 뒤 다시 받기.
- 게이트웨이: 메시지를 그대로 데이터 채널로, OK `inspect`는 `a4_message`에 안 남기고 `relay_summary`로 개수, `/ws` 끊김 시 WebRTC 닫기와 요약, `decision` 프록시 없음, 빈 `station_id` 검증 오류. `webrtc_client.py` 출력·요약.

**확인한 것 — 제품 단위 (2026-10-23·29, 실서버 2026-11-03)** — 지금 코드에 남아 있는 부분: 타일 격자·좌표 복원·NMS, 한글 결함 이름, 스테이션 검증, 게이트웨이 연결·거부·장애 닫기·결과 조회 프록시. 실서버(gpudev)에서 `DEPLOY.md` 옛 §5 ⑧ 전체를 확인했다 (`PLAN_realtime_inspect.md` §2).

**확인하지 않은 것 (§5.A·§5 ⑧에서 실서버로 확인한다)**

- 실제 YOLO로 프레임 단위 판정, 처리 속도(`ws_close`의 fps·`ms_avg`, 특히 1080p).
- 실제 FastAPI·websockets·psycopg·aiortc 위에서의 동작 (작성 환경은 대역), 게이트웨이 경유 전체, 실제 모바일 앱 연결, HMD 영상.
- 실서버 폰트(NanumGothic)로 그린 오버레이 — 글자 위치는 실제 글자 상자로 재므로 폰트가 달라도 상자를 덮지 않아야 한다.

**알려진 제약**

- **처리 속도**: 프레임마다 디코딩 → PD → QI가 차례로 돈다. 4K 사진 실측 합계 약 175ms(decode 100 + PD 10 + QI 63) → 제품을 비추는 동안 약 5.7fps. 1080p는 측정 전. 10fps에 못 미치면 처리가 밀린 프레임을 건너뛴다 (`inspect`가 덜 온다). 1080p에서도 부족하면 (a) 다음 프레임 PD를 이전 프레임 QI와 겹쳐 돌리기, (b) QI를 PD 상자와 겹치는 타일에만 돌리기 순으로 검토한다 (`PLAN_realtime_inspect.md` §9 R1).
- **가모델**: 판정·결함 종류가 자주 틀린다. QI val(122장, conf 0.1)에서 결함이 없는 24장 중 14장에 검출이 나왔다. 프레임마다 판정하므로 오탐이 앱 화면에 그대로 보이고, 결함 프레임 저장(1초에 1장)도 계속될 수 있다 (§4.2 디스크). 제품 상자 제한으로 배경 오탐은 줄지만 제품 위 오탐은 남는다.
- **제품 상자 제한**: PD 상자가 제품을 덜 덮으면 가장자리 결함이 버려진다. 실제 영상에서 버려진 결함을 보고 필요하면 "중심" 대신 "겹침"으로 바꾼다 (`PLAN_realtime_inspect.md` §9 R5).
- **해상도**: QI는 4K 원본의 640 타일로 학습했다. 1080p 운영 프레임에서는 결함이 학습 때의 절반 크기로 보인다 (§3.2).
- **PD 놓침**: PD가 제품을 못 찾은 프레임은 QI 없이 `searching`이다. 모양이 크게 바뀐 불량품을 PD가 놓칠 수 있다 — 제품을 비추는데 `inspect`가 안 나오면 의심한다.
- **흔들린 프레임**: 선명도 조건이 없어 흐린 프레임도 판정한다. 빨리 돌리면 작은 결함을 놓친다.
- **밀린 메시지**: 데이터 채널은 순서 보장·재전송이라 망이 느리면 오래된 `inspect`가 늦게 도착할 수 있다. 실기기에서 확인한다 (`PLAN_realtime_inspect.md` §9 R2).
- **같은 네트워크 전제**: STUN/TURN이 없다. ICE restart 없음 (aiortc). 근거는 `A4_PLAN.md`(보관) §7.5.
- **`-devel` 베이스 이미지**: 크다. CUDA 확장을 빌드하지 않으므로 같은 버전의 `-runtime`으로도 될 것으로 보이지만 확인하지 않았다.
- **ultralytics 외 패키지 버전은 고정하지 않았다** (fastapi, psycopg 등). 실서버에서 빌드가 확인되면 `pip freeze`로 고정하는 것을 권한다.
