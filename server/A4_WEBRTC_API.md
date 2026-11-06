# 제품 불량 판정 — 모바일 앱 연동 API

기준: **2026-11-06 규격 (제품 단위 판정·작업자 피드백).** 서버·앱 코드는 이 규격으로 구현돼 있다 (`PLAN_product_feedback.md`, 작성 환경에서 가짜 모델로 확인). 개발 서버(gpudev)는 2026-11-03 규격(프레임 단위)으로 배포·확인돼 있고, 이번 규격은 **배포 전**이다 — 배포되면 이 줄을 배포 날짜와 확인 결과로 고친다 (`DEPLOY.md` §5.B).

개발 서버 주소: `<서버>` = `<개발 서버 IP>` (서버 관리자에게 받는다). WebRTC `http://<서버>:8080`, WebSocket `ws://<서버>:8000/ws`.

> **2026-11-06 변경 (제품 단위 판정을 서버로, 작업자 피드백)**: 앱이 하던 제품 구간·결함 확정을 **서버가 한다**. 제품이 끝나면 서버가 **`product_end`** 를 보낸다 (§3.5). 작업자는 수동 모드에서 제품마다 **맞음·틀림·확인필요**를 보내고(§4.4), 이력에서 언제든 바꾼다. 서버는 제품이 보인 프레임 **전부의 사진**을 저장하고 이력·사진·디스크 사용량을 조회하게 한다 (§4.5~§4.8).
>
> | 항목 | 2026-11-03 | 2026-11-06 |
> |---|---|---|
> | 메시지 | `searching` / `inspect` | + **`product_end`** (제품마다 한 번) |
> | 제품 구간·결함 확정·제품 판정 | 앱 | **서버** (규칙은 같다: 1초 무소식이면 끝, 최근 5장 중 2장 결함이면 확정) |
> | `inspect` | | + `product_id`, `faces`, `faces_seen`, `complete`, `rx_at` (§3.3) |
> | 연결 | `station_id` | + `feedback_mode` (`manual` / `auto`, §5.2) |
> | 피드백 | 없음 | `PUT /products/{id}/feedback` — 맞음·틀림·확인필요 (§4.4) |
> | 이력 | 앱 기기 DB | **서버** `GET /products` (§4.5) |
> | 사진 | 결함 프레임 1초에 1장 | 제품 프레임 **전부** + 결함 프레임 1초에 1장 오버레이 (§4.7) |
>
> **2026-11-03 변경 (제품 단위 → 프레임 단위 실시간 판정)**: 작업자는 팔을 뻗어 제품을 든 채 돌려 보며, 멈춰서 기다리지 않는다. 그래서 서버는 제품 하나를 3~10장 모아 한 번 판정하던 방식을 버리고, **제품이 보이는 프레임마다 결함을 찾아 바로 돌려준다.**
>
> | 항목 | 이전 (제품 단위) | 이후 (프레임 단위) |
> |---|---|---|
> | 메시지 | `searching` / `hold` / `captured` / `result` / `alert` | `searching` / **`inspect`** 두 가지 |
> | 판정 단위 | 제품 하나 (3~10장 중앙값) | 프레임 한 장 |
> | 결함 위치 | `result`에 한 번 (대표 프레임) | **제품이 보이는 프레임마다** `inspect`에 |
> | 결함 범위 | 프레임 전체 | 제품(PD) 상자 안의 결함만 |
> | `result_id` | 제품마다 (`captured`에서 받음) | 결함이 잡혀 **저장된 프레임**에만 |
> | 결과 조회 | `pending`/`done`/`failed` 폴링 | 저장된 프레임 조회만 (`done`뿐, 폴링 없음) |
> | 사람의 최종 판정 (`POST …/decision`), 연속 판정 알림 (`alert`) | 있음 | **없음** (현장에서 써 본 뒤 다시 정한다) |
>
> 지울 코드는 §10 맨 앞 "이전 코드에서 지울 것"에 모았다.

앱은 카메라 영상을 서버에 보내고, 서버가 보내는 메시지(JSON)를 받아 화면에 표시한다. 제품 검출, 결함 탐지, 기록은 모두 서버가 한다. 앱이 서버에 보내는 것은 영상과 이미지 조회뿐이다.

전송 방식은 두 가지이고, **메시지는 같다** (§3).

| 전송 | 용도 | 영상 | 메시지 | 서버 주소 |
|---|---|---|---|---|
| **WebRTC** | **운영** | WebRTC 영상 트랙 | 데이터 채널 (JSON) | `http://<서버>:8080` |
| WebSocket | 테스트용 | JPEG 이미지 (바이너리 메시지) | 같은 WebSocket (JSON) | `ws://<서버>:8000/ws` |

판정 방식: 서버는 프레임마다 **PD**(제품 탐지)로 제품이 보이는지 보고(제품 종류·개수 무관, 하나라도 보이면 통과), 제품이 보이는 프레임만 **QI**(불량 탐지)로 결함을 찾는다. **제품 상자 안에 있는 결함만** 결과에 넣고, 그 프레임의 판정(OK/NG/REVIEW)과 결함 위치를 바로 보낸다.

| 구분 | 내용 | 횟수 |
|---|---|---|
| `searching` | 제품이 안 보임 | 바뀔 때만 |
| `inspect` | 제품이 보이는 프레임의 판정·제품 위치·결함 위치·면 진행 | 제품이 보이는 동안 프레임마다 (초당 최대 10번) |
| `product_end` | 끝난 제품의 판정(합격/불합격)·결함 종류·면 | 제품마다 한 번 |
| 피드백 (HTTP) | 맞음·틀림·확인필요 | 수동 모드에서 제품마다, 이력에서 언제든 (§4.4) |
| 조회 (HTTP) | 결함 오버레이, 제품 이력·사진, 디스크 사용량 | 화면에 쓸 때 (§4) |

---

## 1. 엔드포인트 요약

**WebRTC (운영) — `http://<서버>:8080`**

| 메서드 | 경로 | 용도 |
|---|---|---|
| `POST` | `/offer` | WebRTC 신호 교환 (offer → answer). `station_id` 필수 (§5.2) |
| `GET` | `/results/{result_id}` | 저장된 프레임의 판정 조회 (§4.1) |
| `GET` | `/results/{result_id}/overlay` | 저장된 프레임의 오버레이 JPEG (§4.2) |
| `PUT` | `/products/{product_id}/feedback` | 피드백 입력·수정 (§4.4) |
| `GET` | `/products` | 제품 이력 목록 (§4.5) |
| `GET` | `/products/{product_id}` | 제품 상세·피드백 변경 기록 (§4.6) |
| `GET` | `/products/{product_id}/frames` | 제품의 프레임 목록 (§4.7) |
| `GET` | `/products/{product_id}/frames/{seq}.jpg` | 프레임 사진 (§4.7) |
| `GET` | `/storage` | 서버 디스크 사용량 (§4.8) |

**WebSocket (테스트용) — `:8000`**

| 메서드 | 경로 | 용도 |
|---|---|---|
| `WebSocket` | `ws://<서버>:8000/ws?station=<station_id>&mode=<manual\|auto>` | 앱 → 서버 JPEG 프레임, 서버 → 앱 메시지 (§6) |
| `GET`·`PUT` | `http://<서버>:8000/results/...`, `/products...`, `/storage` | 위와 같다. 주소만 `:8000` |

`POST /results/{result_id}/decision`은 없어졌다 (`404`).

---

## 2. 식별자

### 2.1 `station_id` — 스테이션 (작업 위치)

- 앱이 연결할 때 보내는 작업 위치 ID다. 앱 설정값으로 두고 기기마다 맞게 넣는다.
- **이 앱에 배정된 값은 `ST-02`다.** 다른 값이 필요하면 서버 관리자에게 등록을 요청한다.
- **서버에 등록된 값만 쓸 수 있다** (등록은 서버 관리자). 미등록이면 WebRTC `403`, WebSocket close `4403`. 빈 문자열은 WebRTC `422`, WebSocket `4403`.
- 서버는 저장한 프레임을 스테이션별로 기록한다.

### 2.2 `product_id` — 제품 하나

- 서버가 제품 구간을 시작할 때 붙인다 (첫 제품 프레임). 그 제품의 모든 `inspect`와 `product_end`에 같은 값이 온다.
- 피드백(§4.4)·이력(§4.5~§4.7)의 키다.
- 예: `c311ab4b00e5-p0007`, `direct_c739fabd2f77-p0001`. **받은 값을 그대로 쓴다.** 형식을 해석하지 않는다. 64자 이하.

### 2.3 `result_id` — 저장된 결함 프레임

- 결함이 잡힌 프레임(NG·REVIEW) 중 서버가 저장한 것에만 붙는다. 서버는 결함 프레임을 **연결마다 1초에 최대 1장** 저장한다 — 그 외 프레임의 `inspect`에는 `result_id`가 `null`이다.
- 이미지 조회(§4)의 키다.
- 예: `c311ab4b00e5-000123` (WebRTC), `direct_c739fabd2f77-000045` (WebSocket).
- **받은 값을 그대로 쓴다.** 형식을 해석하거나 만들어 쓰지 않는다. 가변 길이 문자열, 64자 이하.

---

## 3. 서버 → 앱 메시지 (두 전송 공통)

모든 메시지는 JSON 문자열이고 `state` 필드로 구분한다. 메시지는 서버 → 앱 단방향이다. **모르는 필드는 무시한다** — 서버가 필드를 추가할 수 있다.

### 3.1 흐름

```
searching ──(제품이 보임)──▶ inspect ▶ inspect … ──(제품이 안 보임)──▶ searching ──(1초)──▶ product_end
```

- 제품이 보이는 동안은 프레임마다 `inspect`가 온다. 결함이 없으면 `decision: "OK"`, `detections: []`.
- 제품이 안 보이는 프레임이 오면 `searching`이 한 번 온다. 다시 보이면 `inspect`가 이어진다.
- **제품 끝**: 마지막 제품 프레임 뒤 1초 동안 제품 프레임이 없으면 서버가 그 제품을 끝내고 `product_end`를 한 번 보낸다. 1초 안에 다시 보이면 같은 제품이다 (`searching` 한 장은 제품을 끝내지 않는다). 다음 제품의 첫 `inspect`보다 앞 제품의 `product_end`가 먼저 온다.
- 서버는 처리가 밀리면 가장 최근 프레임만 처리하고 나머지는 건너뛴다. 그래서 `inspect`는 초당 10번보다 적게 올 수 있다 (`seq`가 건너뛴다).

예시 (프레임 0.1초 간격, 시각은 예시):

```
[앱] 연결 (POST /offer) ── 이후 영상만 계속 보냄
  0.25s ← searching (no_product)               제품 없음
  3.10s ← inspect seq 29  OK                    제품 보임, 결함 없음
  3.20s ← inspect seq 30  NG 칩찍힘 0.72  result_id …-000030   결함 — 저장됨
  3.30s ← inspect seq 31  NG 칩찍힘 0.68  result_id null       결함 — 1초 안이라 저장 안 함
  3.40s ← inspect seq 32  OK
  …
  4.30s ← inspect seq 41  REVIEW 이물찍힘 0.24  result_id …-000041   1초 지나 다시 저장
  5.00s ← searching (no_product)               제품을 내림
  6.00s ← product_end  FAIL (NG 칩찍힘)        1초 무소식 — 서버가 제품을 끝냄
```

### 3.2 `searching` — 제품이 보이지 않음

```json
{ "state": "searching", "reason": "no_product" }
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `reason` | string, **선택** | `no_product`: 제품이 안 보임 |

- **바뀔 때만** 온다 (`inspect` 뒤에 제품이 안 보이게 되었을 때 한 번). 앱은 마지막 상태를 기억한다.
- WebSocket 연결 직후 첫 메시지는 `reason` 없이 온다 (§6.1).

### 3.3 `inspect` — 프레임 한 장의 판정

```json
{
  "state": "inspect",
  "seq": 30,
  "frame_size": [1920, 1080],
  "decision": "NG",
  "defect_label": "칩찍힘",
  "score": 0.72,
  "threshold": 0.25,
  "products": [
    { "label": "상면", "conf": 0.91, "box": [402.0, 130.5, 1510.2, 1002.8] }
  ],
  "detections": [
    { "label": "칩찍힘", "conf": 0.72, "box": [1102.7, 977.6, 1109.7, 984.0] }
  ],
  "result_id": "c311ab4b00e5-000030",
  "overlay_url": "/results/c311ab4b00e5-000030/overlay"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `seq` | int | 이 연결에서 서버가 받은 프레임 순번 (건너뛴 프레임도 번호를 차지한다). 늘어나기만 한다 |
| `frame_size` | int[2] | 이 프레임의 `[가로, 세로]` (서버가 받은 프레임 크기). `box` 좌표의 기준 |
| `decision` | string | 이 프레임의 판정. `OK` / `NG` / `REVIEW`(애매 — §3.4) |
| `defect_label` | string 또는 null | 가장 확실한 결함 종류 (`detections[0].label`). OK에 `null` |
| `score` | float | 이 프레임 결함의 최대 신뢰도. 결함이 없으면 0 |
| `threshold` | float | 판정 기준값(결함 신뢰도). 모델 교체 때 바뀌므로 **앱에 하드코딩하지 않는다** |
| `products` | object[] | PD가 찾은 제품 상자 (1개 이상), 신뢰도 높은 순 |
| `products[].label` | string | 제품 부위 (`엣지` / `상면` / `하면비닐`) |
| `detections` | object[] | 제품 상자 안의 결함, 신뢰도 높은 순. **OK면 항상 빈 배열, NG·REVIEW면 항상 1개 이상** |
| `detections[].label` | string | 결함 종류 |
| `products[].conf`, `detections[].conf` | float | 신뢰도 0~1 |
| `products[].box`, `detections[].box` | float[4] | `[x1, y1, x2, y2]` 픽셀 좌표. 기준은 `frame_size` |
| `result_id` | string 또는 null | 이 프레임의 결함 오버레이를 서버가 저장했으면 그 ID (§2.3), 아니면 `null`. OK 프레임은 항상 `null` |
| `overlay_url` | string 또는 null | 저장했으면 오버레이 JPEG 경로 (§4.2), 아니면 `null`. 서버 주소 뒤에 붙인다 |
| `product_id` | string | 이 프레임이 속한 제품 (§2.2) |
| `faces` | object | 이 제품에서 지금까지 면(PD 라벨)별로 잡힌 프레임 수. 예 `{"상면": 12, "엣지": 3, "하면비닐": 0}`. 키는 모델이 정한다 — **앱에 하드코딩하지 않는다** |
| `faces_seen` | string[] | 3장 이상 잡힌 면 (그 면을 본 것으로 친다) |
| `complete` | bool | 모든 면을 봤는지. 제품을 끝내지는 않는다 |
| `rx_at` | int 또는 null | 게이트웨이가 이 프레임을 받은 시각 (epoch ms). 지연 측정용. WebSocket 직접 연결이면 `null` |

- `decision`은 서버가 `score`와 `threshold`로 정한다: `score > threshold × 1.1` → NG, `score < threshold × 0.9` → OK, 그 사이 → REVIEW. 앱은 `decision`만 본다.
- 서버는 `threshold × 0.9` 미만의 결함은 받지 않는다. 그래서 결함이 하나라도 있으면 REVIEW 또는 NG이고, 없으면 OK다.
- 메시지에 이미지는 들어 있지 않다 (두 전송 모두).
- 결함이 타일(640px) 경계에 걸치면 같은 결함에 상자가 겹쳐 두세 개 올 수 있다.

### 3.4 실시간 표시 (권장)

- **결함 상자 표시**: `inspect`의 `detections`를 카메라 프리뷰 위에 그린다. 좌표는 프리뷰 크기에 맞춰 같은 비율로 바꾼다: `화면 x = box x × 프리뷰 폭 / frame_size[0]`, `화면 y = box y × 프리뷰 높이 / frame_size[1]`. 앱 카메라 해상도와 `frame_size`가 다를 수 있으므로(WebRTC가 해상도를 낮출 수 있다) 매번 `frame_size`로 나눈다.
- **결함은 아주 작다.** 2026-11-03 실측(3840×2160)에서 결함 상자는 13×13px ~ 91×72px였다. 프리뷰 크기로 줄이면 몇 픽셀이라 상자 테두리만으로는 보이지 않는다. 결함 위치에 **눈에 띄는 크기의 표시**(예: 지름 40dp 이상의 빨간 원)를 그린다.
- **상자 유지**: 결함은 프레임마다 잡혔다 안 잡혔다 할 수 있다. 마지막으로 결함이 온 뒤 **약 0.5초 동안** 그 표시를 유지한다 — 깜빡임을 줄인다. `searching`이 오면 바로 지운다.
- **지연**: 상자는 서버가 받은 프레임 기준이라 프리뷰보다 늦다 (WebRTC 전송·서버 처리, 측정 전 추정 0.1~0.3초). 제품을 빨리 돌리면 표시가 실제 결함 위치에서 밀려 보인다. 서버 프레임과 프리뷰 프레임을 짝지을 수단은 없다.
- **판정 문구**: NG는 빨강, REVIEW는 노랑(애매), OK는 표시하지 않거나 초록처럼 구분한다. `defect_label`을 함께 보여준다. REVIEW는 서버가 확신하지 못한 결함이라는 뜻이고, 사람의 확인 절차는 없다.
- `products`는 제품이 인식되고 있다는 피드백(예: 제품 테두리)에 쓸 수 있다. 쓰지 않아도 된다.
- **면 진행**: `faces_seen`·`complete`로 본 면을 표시한다 (예: `■ 상면  □ 엣지  □ 하면비닐`, 모두 보면 "검사 완료"). 바뀔 때만 다시 그리면 된다.

### 3.5 `product_end` — 제품 하나의 판정

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
  "threshold": 0.4,
  "feedback_mode": "manual",
  "feedback": "NOT_ENTERED"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `decision` | string | 제품 판정. `PASS`(합격) / `FAIL`(불합격). **앱은 이 값만 보고 합격·불합격을 표시한다** |
| `ai_decision` | string | 확정 결과 `OK` / `NG` / `REVIEW`. 합격이면 `OK`. REVIEW 확정도 불합격이다 |
| `defect_label` | string 또는 null | 확정을 만든 결함 종류. 합격이면 `null` |
| `frames`, `defect_frames` | int | 이 제품의 `inspect` 수, 그중 NG·REVIEW 수 |
| `faces`, `faces_seen`, `complete` | | 끝날 때의 면 진행 (§3.3) |
| `best_result_id` | string 또는 null | 점수가 가장 높은 저장 결함 프레임. 확대 보기(§4.2)에 쓴다. 합격이면 대개 `null` |
| `result_ids` | string[] | 이 제품의 저장 결함 프레임, 점수 높은 순 |
| `end_reason` | string | `gap`(1초 무소식) — `disconnect`(연결 끊김)는 메시지로 오지 않는다 (§7) |
| `feedback_mode`, `feedback` | string | 이 연결의 모드와 피드백 초기값: `manual` → `NOT_ENTERED`(미입력), `auto` → `NONE`(피드백 없음) |

- 판정 규칙(서버): 제품 안 최근 5장 중 2장 이상이 NG·REVIEW면 결함 확정 — 그 안에 NG가 있으면 NG, 없으면 REVIEW, 나중에 NG로 올라갈 수 있다. 확정이 없으면 PASS. 값(1초·5장·2장·3장)은 서버 설정이며 바뀔 수 있다.
- **수동 모드**면 결과와 함께 피드백 버튼 3개(맞음·틀림·확인필요)를 띄운다 (§4.4). 누르기 전에 다음 `product_end`가 오면 새 제품으로 넘어간다 — 이전 제품은 서버에 미입력으로 남고 이력에서 고친다.

---

## 4. 조회·피드백 (HTTP, 두 전송 공통)

서버 주소는 WebRTC면 `http://<서버>:8080`, WebSocket이면 `http://<서버>:8000`이다. 경로와 응답은 같다. §4.1~§4.2는 `result_id`가 붙은 프레임(저장된 결함 프레임), §4.4~§4.7은 제품(`product_id`), §4.8은 서버 전체다.

### 4.1 `GET /results/{result_id}` — 저장된 프레임의 판정

```json
{
  "result_id": "c311ab4b00e5-000030",
  "station_id": "ST-02",
  "status": "done",
  "decision": "NG",
  "defect_label": "칩찍힘",
  "score": 0.72,
  "scores": [0.72],
  "threshold": 0.25,
  "detections": [
    { "label": "칩찍힘", "conf": 0.72, "box": [1102.7, 977.6, 1109.7, 984.0] }
  ],
  "frame_size": [1920, 1080],
  "overlay_url": "/results/c311ab4b00e5-000030/overlay",
  "captured_at": "2026-11-03T15:04:47.691+09:00",
  "result_at": "2026-11-03T15:04:47.691+09:00",
  "alert": null
}
```

- 값은 그 프레임의 `inspect`와 같다. `status`는 항상 `done`, `scores`는 `[score]`, `alert`는 항상 `null`이다 (이전 규격과 응답 모양을 맞추려고 남긴 필드). `products`는 들어 있지 않다.
- 실시간 표시에는 필요 없다. 기록 화면 등에서 다시 볼 때 쓴다.

### 4.2 `GET /results/{result_id}/overlay` — 저장된 프레임의 오버레이

`inspect`의 `overlay_url`을 서버 주소 뒤에 붙여 요청한다. 서버에 계속 보관된다.

```
GET http://<서버>:8080/results/c311ab4b00e5-000030/overlay
```

| 경로 | Content-Type | 내용 |
|---|---|---|
| `/overlay` | `image/jpeg` | 그 프레임 위에 결함 상자와 `종류 신뢰도`를 빨간색으로 표시. 크기는 `frame_size`와 같다 |

- 서버는 `inspect`를 보낸 뒤에 이미지를 저장한다. **`inspect`를 받자마자 요청하면 `404`일 수 있다** — 0.5초 뒤 한 번 더 요청한다.
- 오버레이의 빨간 테두리와 `종류 신뢰도` 글자는 결함 상자 **바깥쪽**에 그려져 결함 자체를 가리지 않는다 (글자는 상자 위, 프레임 위 끝이면 상자 아래).

#### 결함 확대 보기 (권장)

실시간 표시(§3.4)만으로는 결함이 작아 무엇인지 알아보기 어렵다. 저장된 결함 프레임의 오버레이를 잘라 크게 보여주면 작업자가 AI가 어디를 결함으로 봤는지 확인할 수 있다. 공장 검사 장비(PCB 자동광학검사 확인 화면 등)에서 흔한 구성대로 **전체 이미지 + 결함별 확대 이미지 + 손가락 확대·이동**을 둔다.

- **두는 위치**: 예를 들어 프리뷰 한쪽 구석의 작은 창에 "가장 최근에 저장된 결함 프레임"의 확대 이미지를 띄운다. 새 `result_id`가 오면 바꾼다. 전체 화면으로 열 수 있게 한다.
- **전체 이미지 (위치 파악용)**: 오버레이 전체를 화면에 맞추고, 결함마다 눈에 띄는 표시(빨간 원 등)를 덧그린다. 좌표 변환은 §3.4와 같다.
- **결함별 확대 이미지 (판단용)**: 결함마다 하나씩, 결함을 가운데 둔 정사각형으로 오버레이를 잘라 크게 보여준다. 오버레이 크기가 `frame_size`와 같으므로 `box`로 그대로 자르면 된다. 신뢰도 높은 순으로 넘겨 보게 한다.
- **손가락 확대·이동**: 두 이미지 모두 핀치 확대와 끌어서 이동을 지원한다.

자르는 규칙 (권장):

- 서로 겹치거나 맞닿은 상자는 합쳐 결함 하나로 본다 (타일 경계에서 겹쳐 오는 상자).
- 정사각형 한 변 = 프레임 짧은 변의 20%(1080p면 216px, 4K면 432px)와 상자 긴 변의 3배 중 큰 값.
- 정사각형이 프레임 밖으로 나가면 안쪽으로 밀어 넣는다.
- 20%·3배는 2026-11-03 실측 결함 크기에 맞춘 출발값이다. 정해진 업계 기준값은 없으므로 실제 화면에서 보고 조정한다.

예: 4K 프레임의 13×13px 결함 → 432×432px을 잘라낸다 → 폭 1080px 화면에 약 2.5배 확대되어 결함이 약 32px로 보인다.

### 4.3 에러

| 코드 | 본문 | 경우 |
|---|---|---|
| `404` | `{"detail": "결과가 없습니다."}` | 없는 `result_id` |
| `404` | `{"detail": "이미지가 없습니다."}` | 오버레이: 아직 저장 중(0.5초 뒤 다시), 결과가 없음, 저장 실패 |
| `404` | `{"detail": "제품이 없습니다."}` | 없는 `product_id` (§4.4~§4.7) |
| `404` | `{"detail": "Not Found"}` | 없는 경로 (예: 예전의 `/mask`, `/decision`) |
| `422` | FastAPI 검증 에러 | 잘못된 값 (§4.4 `value`·`source`, §4.5 필터) |
| `503` | `{"detail": "판정 서버에 연결할 수 없습니다."}` | (`:8080`만) 판정 서버 장애. 1초 뒤 다시 요청 |

### 4.4 `PUT /products/{product_id}/feedback` — 피드백 입력·수정

```json
{ "value": "CORRECT", "source": "live", "device": "SM-S931N-1a2b3c" }
```

| 필드 | 설명 |
|---|---|
| `value` | `CORRECT`(맞음 — AI 판정이 맞다) / `WRONG`(틀림) / `UNSURE`(확인필요 — 애매해서 판단하지 못함). 이 셋만 받는다 |
| `source` | `live`(현장 결과 화면) / `history`(이력 화면) |
| `device` | 선택. 앱이 정하는 기기 이름 (변경 기록용, 인증 아님). 128자 이하 |

- 응답 `200`: 바뀐 제품 (§4.6과 같은 모양, 변경 기록 포함). 같은 값을 다시 보내도 `200`이고 변경 기록은 늘지 않는다.
- 처음 입력과 수정을 구분하지 않는다. 모드와 상관없이 **누구나 어느 제품이든** 바꿀 수 있다 (인증 없음). 바꿀 때마다 서버가 변경 기록을 남긴다.
- 피드백 값 5가지: `CORRECT`·`WRONG`·`UNSURE`(사람이 넣음), `NOT_ENTERED`(수동 모드 초기값 — 미입력), `NONE`(자동 모드 초기값 — 피드백 없음). 학습 라벨은 맞음 → 판정 그대로, 틀림 → 반대, 나머지 → 제외.

### 4.5 `GET /products` — 이력 목록

```
GET /products?station=ST-02&feedback=NOT_ENTERED,UNSURE&decision=FAIL&from=2026-11-06&to=2026-11-06&limit=50&before=<product_id>
```

| 쿼리 | 설명 |
|---|---|
| `station` | 스테이션 (없으면 전체) |
| `feedback` | 피드백 값, 쉼표로 여러 개 (§4.4의 5가지) |
| `decision` | `PASS` / `FAIL` |
| `from`, `to` | 시작 날짜 범위 `YYYY-MM-DD` (둘 다 포함, 서버 시간대 KST) |
| `limit` | 1~200, 기본 50 |
| `before` | 이전 응답의 `next_before` — 다음 페이지 |

응답:

```json
{
  "items": [
    { "product_id": "c311ab4b00e5-p0007", "station_id": "ST-02", "started_at": "…", "ended_at": "…",
      "decision": "FAIL", "ai_decision": "NG", "defect_label": "칩찍힘", "frames": 61, "defect_frames": 7,
      "complete": true, "best_result_id": "c311ab4b00e5-000123", "feedback_mode": "manual",
      "feedback": "NOT_ENTERED", "feedback_at": null }
  ],
  "next_before": "c311ab4b00e5-p0006"
}
```

- 최신 순. `next_before`가 `null`이면 마지막 페이지다.
- `decision`·`ended_at`이 `null`인 제품은 서버가 기록 중 멈춘 제품이다 (재시작 등).

### 4.6 `GET /products/{product_id}` — 제품 상세

§4.5 항목 + `faces`, `faces_seen`, `result_ids`, `threshold`, `checkpoint`(모델), `end_reason`, `feedback_log`:

```json
"feedback_log": [
  { "before": "NOT_ENTERED", "after": "UNSURE", "source": "live", "device": "SM-S931N-1a2b3c", "changed_at": "…" },
  { "before": "UNSURE", "after": "WRONG", "source": "history", "device": null, "changed_at": "…" }
]
```

### 4.7 제품의 프레임과 사진

```
GET /products/{product_id}/frames?offset=0&limit=100        (limit 1~500)
GET /products/{product_id}/frames/{seq}.jpg[?overlay=1]
```

- 목록 응답: `{"items": [{"seq", "ts", "decision", "score", "defect_label", "products", "detections", "frame_size", "result_id", "has_image"}], "total": 61}` (seq 순).
- 사진: 서버가 저장한 그 프레임 JPEG. `overlay=1`이면 결함 상자를 그려서 준다 (결함이 없으면 그대로). `has_image`가 `false`(저장 실패)면 `404`.
- 서버는 제품이 보인 프레임 **전부**를 저장하고 지우지 않는다. 사진을 받는 데 시간이 걸리므로 목록을 먼저 띄우고 사진은 넘길 때 받는다.

### 4.8 `GET /storage` — 서버 디스크 사용량

```json
{
  "measured_at": "2026-11-06T10:15:00.000+09:00",
  "disk_total_bytes": 2000000000000, "disk_used_bytes": 812000000000, "disk_free_bytes": 1188000000000,
  "data_bytes": { "product_frames": 640000000000, "results": 21000000000, "frames": 3000000000, "logs": 900000000 },
  "per_day_bytes": [ { "date": "2026-11-06", "bytes": 61000000000 } ]
}
```

- `disk_*`는 요청 때 잰 값, `data_bytes`·`per_day_bytes`는 서버가 5분마다 계산해 둔 값이다 (`measured_at`). 서버 기동 직후에는 `data_bytes`가 `null`일 수 있다.
- `data_bytes`: `product_frames`(제품 프레임 사진), `results`(결함 오버레이), `frames`(옛 결함 원본), `logs`. 서버는 자동으로 지우지 않으므로 남은 공간을 이 값으로 지켜본다.

---

## 5. WebRTC 전송 (운영)

### 5.1 연결 절차

1. `RTCPeerConnection`을 만든다 (ICE 서버 설정 불필요 — 같은 망 전제).
2. 카메라 **영상 트랙**을 추가한다. 오디오는 쓰지 않는다.
3. **데이터 채널을 하나 연다** (이름 무관). offer를 만들기 **전에** 열어야 SDP에 포함된다. 메시지는 이 채널로만 온다.
4. offer를 만들고 `setLocalDescription` 한 뒤, **ICE 후보 수집이 끝날 때까지 기다린다** (`iceGatheringState == "complete"`). 서버는 trickle ICE를 받지 않는다.
5. `POST /offer`에 `station_id`와 함께 보내고 받은 answer를 `setRemoteDescription` 한다.
6. 연결되면 데이터 채널로 메시지가 오기 시작한다.

```
pc = RTCPeerConnection()
pc.addTrack(cameraVideoTrack)
channel = pc.createDataChannel("a4")
channel.onmessage = (msg) -> handle(JSON.parse(msg.data))
pc.onconnectionstatechange = () -> handleConnectionState(pc.connectionState)   // §5.3

offer = pc.createOffer()
pc.setLocalDescription(offer)
wait until pc.iceGatheringState == "complete"

response = POST http://<서버>:8080/offer
           { sdp: pc.localDescription.sdp, type: "offer", station_id: settings.stationId,
             feedback_mode: settings.feedbackMode }
if response.status != 200: handleOfferError(response)   // §5.2
pc.setRemoteDescription(response.body)
```

**연결 하나 = 세션 하나.** 연결을 유지한 채 제품을 계속 바꿔 가며 비춘다.

### 5.2 `POST /offer`

요청 (`Content-Type: application/json`):

```json
{
  "sdp": "v=0\r\no=- ... (ICE 후보 포함)",
  "type": "offer",
  "station_id": "ST-02",
  "feedback_mode": "manual"
}
```

- `feedback_mode`: `manual`(수동 — 제품마다 피드백) / `auto`(자동 — 피드백 건너뜀). 없으면 `auto`. 모드를 바꾸려면 새로 연결한다.

응답 `200`:

```json
{ "sdp": "v=0\r\no=- ...", "type": "answer" }
```

| 코드 | 본문 | 경우 | 앱 처리 |
|---|---|---|---|
| `422` | FastAPI 검증 에러 | `sdp`·`type`·`station_id`가 없거나 `station_id`가 빈 값, `feedback_mode`가 manual·auto가 아님 | 설정 오류 — 재시도하지 않는다 |
| `403` | `{"detail": "등록되지 않은 스테이션입니다."}` | `station_id` 미등록 | 설정 오류 — 관리자에게 등록 요청 |
| `503` | `{"detail": "판정 서버에 연결할 수 없습니다."}` | 판정 서버 장애 | 재연결 간격(§5.3)에 따라 다시 `POST /offer` |

### 5.3 장애 감지와 재연결

- 서버 쪽 장애가 나면 서버가 WebRTC 연결을 닫는다. 앱은 **연결 상태로 장애를 감지한다.** 생존 신호(heartbeat)는 없다 — 제품을 비추지 않는 동안 메시지가 오지 않는 것은 정상이다.
- Wi-Fi 단절처럼 닫힘 신호가 오지 않으면 `failed`까지 15~30초가 걸리므로, `disconnected`부터 장애로 처리하는 것을 권장한다.
- 재연결은 **새 `POST /offer`** 로 한다. ICE restart는 지원하지 않는다.
- **재연결 간격**: 첫 재시도는 1초 뒤. 연속으로 실패하면 1초 → 2초 → 4초 → 5초로 늘리고 이후 5초마다 재시도한다. 연결에 성공하면 1초로 되돌린다. `422`·`403`은 재시도하지 않는다 (§5.2). WebSocket(§6.2)도 같은 간격을 쓴다.
- 끊기는 동안에는 판정이 없다. 재연결하면 제품을 계속 비추면 된다 (§7).

### 5.4 첫 메시지

- 연결 직후 `searching` 메시지는 오지 않는다. **연결 성공을 `searching` 상태로 간주**하고 첫 메시지를 기다리지 않는다.
- 첫 프레임이 처리되면 `searching`(`reason` 포함) 또는 `inspect`가 온다.

### 5.5 해상도·전송 설정 (권장)

- 해상도는 1080p 이상을 권장한다. 팔을 뻗어 들면 제품이 화면에서 작아지고 결함은 더 작아진다. QI는 프레임을 640px 타일로 나눠 보므로 해상도가 낮을수록 작은 결함을 놓친다.
- 송신 설정 `degradationPreference`를 `maintain-resolution`으로 둔다 — 대역폭이 부족할 때 해상도 대신 프레임 속도를 낮춘다. 서버는 초당 10프레임까지만 쓴다.

---

## 6. WebSocket 전송 (테스트용)

앱의 메시지 처리 코드를 WebRTC 없이 시험할 때 쓴다. 메시지 처리 코드는 WebRTC와 공용으로 쓴다.

### 6.1 연결과 프레임 전송

```
ws://<서버>:8000/ws?station=ST-02&mode=manual
```

- `station`은 항상 넣는다. `mode`는 `manual` / `auto` (없으면 `auto`).
- 연결 직후 `{"state": "searching"}`이 온다. **이 첫 메시지를 받은 때를 연결 성공으로 본다.** 서버는 연결을 수락한 뒤 스테이션을 검사해 close 코드로 닫으므로, 거부될 때도 `onOpen`이 먼저 불리고 바로 `onClose(4403)`이 온다.
- 카메라 프레임을 **JPEG 바이너리 메시지**로 보낸다. 해상도는 정하지 않는다 (촬영 조건은 §8).
- **초당 10프레임 이하**로 보내고, **이전 프레임 전송이 끝나기 전의 새 프레임은 버린다** (쌓아 두면 화면과 판정 사이가 벌어진다).
- 깨진 프레임은 서버가 건너뛴다. 연결은 유지된다.

### 6.2 거부·장애

| close 코드 | 경우 | 앱 처리 |
|---|---|---|
| `4403` | `station`이 미등록이거나 빈 값 | 설정 오류 — 재시도하지 않는다 |
| `4422` | `mode`가 manual·auto가 아님 | 설정 오류 — 재시도하지 않는다 |
| `4503` | 판정 서버 장애 | 재연결 간격(§5.3)에 따라 다시 연결 |
| 그 밖의 끊김 | 서버 재시작, 네트워크 단절 | 재연결 간격(§5.3)에 따라 다시 연결 (새 세션) |

### 6.3 WebRTC와의 차이

| 항목 | WebRTC | WebSocket |
|---|---|---|
| 첫 `searching` | 오지 않음 (§5.4) | 옴 |
| 조회·이미지 주소 | `:8080` | `:8000` |
| 연결 거부 | HTTP `422`·`403`·`503` | close `4403`·`4503` |
| 프레임 속도 | 서버가 조절 (초당 10장) | 앱이 초당 10프레임 이하로 지킴 |

같은 제품이라도 두 전송의 점수·판정은 다를 수 있다 (영상 압축 차이).

---

## 7. 연결이 끊겼을 때 (두 전송 공통)

- 끊긴 동안의 프레임은 판정되지 않는다. 다시 보낼 것은 없다 — 재연결 후 제품을 계속 비춘다.
- 끊길 때 진행 중이던 제품은 서버가 그때까지의 프레임으로 판정해 `end_reason: "disconnect"`로 기록한다. 메시지로는 오지 않으므로, 재연결 뒤 `GET /products?station=…&limit=1`로 받아 결과를 보여 줄 수 있다.
- 끊기기 전에 받은 `result_id`의 이미지는 서버에 남아 있어 재연결 뒤에도 조회할 수 있다 (§4).
- 재연결하면 `seq`는 다시 작은 수부터 시작한다 (새 세션).

---

## 8. 촬영 조건

- 제품이 화면에 보이면 인식한다 (PD 모델이 엣지·상면·하면비닐 중 하나라도 찾으면 된다). 크기·위치 조건은 없다.
- **멈출 필요는 없지만, 천천히 돌릴수록 좋다.** 빨리 돌리면 프레임이 흐려져 작은 결함을 놓치고, 실시간 표시가 실제 위치에서 밀려 보인다 (§3.4).
- 결함은 **제품 상자 안의 것만** 잡는다. 제품이 화면 밖으로 일부 나가 PD 상자가 결함 부위를 덮지 못하면 그 결함은 잡히지 않는다.
- 한 화면에 제품이 여러 개면 모든 제품 상자 안의 결함을 함께 보여준다 (제품별로 나누지 않는다).

---

## 9. 제약 사항

- **같은 네트워크 전제.** STUN/TURN이 없어 LTE 등 다른 망에서는 연결되지 않는다. 접속할 수 있는 대역은 서버 방화벽 설정을 따른다 (사내 Wi-Fi `192.168.1.0/24` 기준). 연결이 안 되면 서버 관리자에게 확인한다.
- **USB 연결(`adb reverse`)** 로는 WebRTC가 통하지 않는다 (TCP만 전달). WebRTC는 같은 Wi-Fi에서 시험한다. WebSocket 테스트 전송은 `adb reverse`로도 된다.
- **인증·HTTPS 없음.** `http://`·`ws://` 평문이므로 iOS(ATS), Android(cleartext traffic) 예외 설정이 필요하다.
- **판정 모델은 가모델이다.** 결함 위치·종류가 자주 틀리고, 결함 없는 곳에서도 검출이 나온다. 프레임마다 판정하므로 이런 오탐이 화면에 그대로 보인다. 지금은 연동과 표시 확인용으로 쓴다.
- **서버 DB 장애 중**에는 새 연결이 거부된다 (`503`·`4503`). 이미 연결된 세션의 실시간 메시지는 계속되지만, 그동안 저장된 프레임은 조회에서 `404`가 될 수 있다.

---

## 10. 앱 구현 체크리스트

2026-11-06에 바꿀 것 (앱이 하던 제품 판정 → 서버)
- [ ] 앱의 제품 구간(1초 무소식)·결함 확정(5장 중 2장) 로직을 지우고 `product_end`(§3.5)를 그대로 표시한다
- [ ] `/offer`에 `feedback_mode`, WebSocket은 `?mode=` (§5.2·§6.1)
- [ ] 수동 모드: `product_end`마다 맞음·틀림·확인필요 버튼 → `PUT /products/{id}/feedback` (`source: live`) (§4.4)
- [ ] 이력: `GET /products`(필터·`before` 페이지), 상세 `GET /products/{id}`, 프레임 사진 `…/frames`·`…/frames/{seq}.jpg`, 피드백 수정(`source: history`)과 변경 기록 (§4.5~§4.7)
- [ ] 면 진행: `faces_seen`·`complete` 표시 — 면 이름은 하드코딩하지 않는다 (§3.3·§3.4)
- [ ] 디스크 사용량: `GET /storage` (§4.8)
- [ ] (권장) 재연결 뒤 `GET /products?station=…&limit=1`로 끊긴 사이 끝난 제품을 받는다 (§7)

이전 코드에서 지울 것 (제품 단위 규격)
- [ ] `hold`·`captured`·`result`·`alert` 처리와 그에 따른 안내("계속 비춰 주세요", "내려놓아도 됩니다", "제품을 치워 주세요")
- [ ] `captured`에서 `result_id`를 저장하고 끊긴 뒤 `pending`을 폴링하는 로직
- [ ] `POST /results/{result_id}/decision` (고개 끄덕임·트레이·시간 초과) — 없어졌다 (`404`)
- [ ] 제품 하나에 결과 화면 하나를 띄우는 흐름 — 이제 프레임마다 `inspect`

이전 코드에서 지울 것 (A4 이상탐지 규격, 아직 남아 있다면)
- [ ] `searching.reason`의 `no_paper`·`too_small` 분기와 "더 가까이" 안내 — 이제 `no_product` 하나뿐
- [ ] `is_anomalous`, `image_threshold`, `mask_url`, `/mask` 요청
- [ ] 펴진 종이(1720×2432) 좌표 기준의 이미지 표시 — 이제 카메라 프레임 좌표 (`frame_size`)

연결
- [ ] `station_id`를 앱 설정값으로 두고 연결 때 보낸다 (WebRTC: `/offer` 본문, WebSocket: `?station=`)
- [ ] (WebRTC) 데이터 채널을 offer 생성 **전에** 연다
- [ ] (WebRTC) ICE 수집 완료 후 offer를 보낸다
- [ ] (WebRTC) 1080p 이상, `degradationPreference: maintain-resolution`
- [ ] `422`·`403`(WebSocket `4403`)은 설정 오류로 표시하고 재시도하지 않는다
- [ ] `503`(WebSocket `4503`)·끊김은 재연결 간격(1→2→4→5초, §5.3)에 따라 다시 연결한다
- [ ] (WebSocket) 첫 `searching` 수신을 연결 성공으로 본다 (`onOpen`만으로 판단하지 않는다)
- [ ] (WebRTC) 연결 상태 `disconnected`·`failed`·`closed`를 장애로 보고 새 `POST /offer`로 재연결한다
- [ ] (WebRTC) 연결 성공을 `searching`으로 간주한다 (첫 메시지를 기다리지 않음)
- [ ] (WebSocket) 초당 10프레임 이하, 밀린 프레임은 버린다

메시지·표시
- [ ] 마지막 `state`를 기억한다 (`searching`은 변할 때만 옴), 모르는 필드는 무시한다
- [ ] `inspect`의 `decision`으로 표시 (NG 빨강, REVIEW 노랑, OK 구분), `defect_label` 문구 사용
- [ ] `detections`를 프리뷰 위에 `frame_size` 기준 비율로 바꿔 눈에 띄는 크기의 표시로 그린다 (§3.4)
- [ ] 결함 표시를 마지막 결함 뒤 약 0.5초 유지하고, `searching`이 오면 지운다
- [ ] `threshold`를 하드코딩하지 않는다
- [ ] (권장) `result_id`가 온 프레임의 오버레이로 결함 확대 보기 — 전체 이미지 + 결함별 확대 + 손가락 확대·이동 (§4.2). 오버레이 `404`면 0.5초 뒤 한 번 더
