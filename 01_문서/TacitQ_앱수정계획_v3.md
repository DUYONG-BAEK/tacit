# TacitQ 앱 수정 계획: 프레임 단위 판정 대응 (개정 2판)

- 작성: 2026-11-04 (개정 2)
- 대상: 모바일 앱 `TacitQualityMobile`, HMD 앱 `TacitQualityHmd`
- 근거:
  - 「제품 불량 판정 — 모바일 앱 연동 API」 2026-11-03 규격 (이하 "규격")
  - 외부 사례 검토 (§2)
  - 현재 소스 코드 대조 검토 (2026-11-04, 개정 2에서 다시 대조)
  - 서버 요청서 `서버요청_프레임기준시각.md`: 지금은 보내지 않는다. 6단계 실측 결과가 조건을 충족할 때만 보낸다(7단계)
- 전제: **개발 중이므로 기존 로컬 이력(DB)은 보존하지 않는다.** 스키마가 바뀌면 DB를 지우고 다시 만든다.

경로 줄임:
- `M/` = `02_소스/TacitQualityMobile/app/src/main/java/kr/co/snail/tacitq/mobile/`
- `H/` = `02_소스/TacitQualityHmd/app/src/main/java/kr/co/snail/tacitq/hmd/`
- `MT/` = `02_소스/TacitQualityMobile/app/src/test/java/kr/co/snail/tacitq/mobile/`
- `HT/` = `02_소스/TacitQualityHmd/app/src/test/java/kr/co/snail/tacitq/hmd/`

### 개정 2 내용 (개정 1 대비)

| 구분 | 개정 1 | 개정 2 | 이유 (외부 사례 §2) |
|---|---|---|---|
| 재연결 (R10, 신규) | 간격만 1→2→4→5초로 | **시도 마감 시간(10초)을 간격과 따로 둔다.** offer가 받아들여진 뒤 `CONNECTED`가 올 때까지는 "시도 중"으로 보고 새 시도를 하지 않는다. 연결 전 `FAILED`, 마감 초과도 실패로 센다 | 지금 구조로 간격만 1초로 줄이면 연결 중인 PeerConnection을 다음 `tick()`이 닫는다(`M/ai/AiEdgeLink.kt:132`). gRPC 연결 재시도 규격은 재시도 간격과 시도 마감(`MIN_CONNECT_TIMEOUT`)을 따로 둔다 |
| HMD 대기 화면 (R9) | 조준 화면(H-01)에서만 LIVE | **대기 화면을 화면 ID `IDLE`로 분리**한다. 조준 화면만 `H-01` | HMD의 대기 화면 `idle()`도 `screen = "H-01"`이라(`H/hud/Screens.kt:75`) 대기 화면에 LIVE·HINT가 그려진다. 화면 상태는 서로 겹치지 않는 값으로 나타낸다(UI 상태 모델링) |
| 큰 글자 우선순위 (R9) | `onLive`에서만 처리 | **`render()` 한 곳에서 LIVE > HINT > 기본 화면 순으로 정한다** | `render()`가 HINT를 큰 글자에 바로 덮어쓴다(`H/hud/HudController.kt:126-131`). 우선순위를 한 곳에서 정해야 HINT가 LIVE를 지우지 않는다 (ISA-18.2: 중요한 표시 우선) |
| 시계 차이·BLE 지연 (6단계) | SHOW ACK의 `rendered_at` | **PING에 HMD가 PONG(받은 시각·보낸 시각)으로 답한다. NTP 방식으로 최근 8개 중 왕복이 가장 짧은 표본을 쓴다** | 판정 화면이 없어지면 SHOW가 거의 나가지 않아 ACK 표본이 없다. PING은 1초마다 이미 나간다. NTP 시계 필터는 8개 중 지연이 가장 작은 표본을 고른다 |
| 측정 모드 (R11, 신규) | 정하지 않음 | **측정 중에는 판정 처리(구간·진동·이력·LIVE)를 끄고 "측정 중"을 표시한다** | 1초마다 검은 화면과 실제 영상을 바꾸면 구간 종료 1초와 겹쳐 이력·진동이 생긴다. ISA-18.2의 "점검 중(out of service)" 상태, 합성 트래픽을 처음부터 표시해 실제 지표에서 빼는 관행 |
| 지연 측정 보조 (6단계) | 구간 3개 | **+ WebRTC 통계(인코딩 시간, 네트워크 왕복)로 "폰 → 서버 → 폰"을 쪼갠다. HMD → 폰은 시계 사진으로 한 번 검증한다** | WebRTC 통계로는 전체 지연을 알 수 없고, 구간별 값은 알 수 있다. 전체 값은 시계를 찍는 방식(glass-to-glass)으로 검증하는 것이 일반적이다 |
| 이력 내용 (R6) | `resultIds`, `serverKey` 추가 | **+ 원판정(`OK`/`NG`/`REVIEW`), `defect_label`, `threshold`를 합격/불합격과 따로 저장한다** | 지금은 `StateMachine.kt:114`가 REVIEW를 FAIL로 합치고, 결함 종류를 저장할 칸이 없다. 비전 검사 기록은 합격/불합격과 결함 종류·점수·기준값·영상 참조를 따로 남긴다 |
| DB 변경 | `Migration(1, 2)` + 마이그레이션 시험 | **`version = 2` + `fallbackToDestructiveMigration()`.** 마이그레이션 시험은 하지 않는다 | 개발 중이라 기존 이력을 보존하지 않는다. Room 공식 문서도 버려도 되는 데이터에 쓰는 방법으로 안내한다 |
| `Screens.guide()` | 필드를 지운다 | **함수 모양은 그대로 두고 보내는 필드만 줄인다** | 호출처 6곳과 `MT/hmd/HmdRendererTest.kt:46`을 고치지 않아도 된다 |
| 서버 이름 키 (R6) | `pickServer`에서 알림 | **서버 목록을 만드는 곳(`M/service/InspectionService.kt:143`)부터 키를 넣는다** | 지금 목록은 표시 이름(`"사무실 AI"`, `"모의 AI(PC)"`)만 가진다 |
| 화면 상태 값 | `lastJudge` 문자열 | **`StreamRelay.Status`에 구간 요약 객체 `lastSegment`를 추가한다** | M-01·M-07이 `lastJudge` 문자열을 쪼개 읽는다(`M07LiveView.kt:96-101`). 상태는 문자열이 아닌 구조로 넘긴다 (UI 상태 모델링) |
| LIVE 지우기 (R8) | 지울 때 `d:null` 한 번 | **HMD에 표시 중일 때만 `d:null`을 보낸다** | `lost()`는 재연결 실패마다 불린다. 바뀔 때만 보내기(Sparkplug) 원칙 |
| 5단계 해상도 시험 | 실시간 경로로 1080p·2160p 비교 | **같은 4K 프레임을 녹화해 PC에서 1080p로 줄이고, 둘 다 규격 §6 WebSocket으로 보내 짝지어 비교한다.** 앱 수정 없음 | 실시간 경로의 4K는 HMD CPU JPEG 변환, 폰 디코딩, 소프트웨어 VP8 4K 인코딩을 거친다. 폰에서 초당 10장이 나온다는 근거가 없어 해상도가 아닌 처리 성능을 재게 될 수 있다. 같은 영상으로 짝지어 비교하면 해상도만 바뀐다 |
| 줄 번호 | — | `AiFlow.kt:195` → `:90`, `AiFlow.kt:141` → `:36`·`:68`, `GattBleLink.kt:195-197` → `:276-282` | 코드 대조 결과 |

<details>
<summary>개정 1 내용 (첫 판 대비, 이력)</summary>

| 구분 | 첫 판 | 개정 1 |
|---|---|---|
| 제품 구간 끝 (R2) | `searching`이 오면 끝 | 마지막 `inspect` 뒤 1초 동안 `inspect`가 없으면 끝. `searching`은 화면만 지운다 |
| 결함 원 지연 대응 (R7) | 실측 지연과 `liveMaxDelayMs` 비교 | 켜기/끄기 설정. 7단계를 하면 메시지마다 나이로 판단 |
| 이력 내용 (R6) | `result_id` 목록 | + 서버 이름 키(`office`/`mock`) |
| LIVE 전송 (R8) | 프레임마다 | 결함 프레임과 지우는 순간만, 응답 없는 쓰기 |
| HMD 화면 (R9) | 정하지 않음 | LIVE가 HINT보다 우선, 조준 화면에서만. 처리 건수는 HMD에서 뺀다 |
| 빠진 수정 파일 | — | `StreamRelay`·`InspectionEngineTest`·`GattBleLink`·`M01ShiftPrep`·`DevSettings`·`QmsDatabase`·`H/MainActivity` 추가 |
| 시험 가능 구조 | — | 재연결 간격·HMD 0.5초 유지를 순수 함수/클래스로 분리 |

</details>

---

## 1. 무엇이 바뀌었고 무엇이 깨지나

AI 서버는 제품 하나를 모아 한 번 판정하던 방식(`hold` → `captured` → `result`)을 버렸다. 이제 **제품이 보이는 프레임마다 `inspect`를 보낸다(초당 최대 10번).** 사람의 최종 판정(`decision` API)과 연속 불량 알림(`alert`)도 없어졌다.

지금 앱은 "제품 1개 = 판정 1건 = HMD 판정 화면 1장"을 전제로 짜여 있다. 새 메시지를 그대로 넣으면 다음 문제가 생긴다.

| 지금 코드 | 프레임마다 판정이 오면 |
|---|---|
| 판정마다 HMD 전체화면 H-02/H-03을 1.5~2초 표시 (`M/core/StateMachine.kt:124-137`) | 판정 화면이 카메라를 계속 덮는다 |
| 화면 지시(SHOW)는 ACK를 200ms 기다리는 BLE 쓰기 (`M/hmd/HmdRenderer.kt`) | 초당 10번이면 밀린다 |
| 불합격마다 진동 3회 | 진동이 계속 울린다 |
| 판정마다 이력 1건 저장, 10초 확인 타이머 | 이력이 초당 10건 쌓이고, 확인할 사람 절차는 이미 없다 |
| `result_id`를 부품 ID로 사용 (`M/ai/AiFlow.kt:90`) | 대부분 프레임은 `result_id`가 `null`이다 |
| 끊긴 사이 `pending` 조회 (`M/ai/AiFlow.kt:36`, `:68`, `M/ai/AiEdgeLink.kt:278-296`) | 규격에서 없어졌다 |
| 재연결 5초 고정 (`M/ai/AiEdgeLink.kt:375`) | 규격 §5.3은 1→2→4→5초. 그대로 1초로 줄이면 연결 중인 연결을 끊는다 (R10) |

## 2. 외부 사례와 우리 선택

### 2.1 개정 1까지 반영한 사례

| 사례 | 그들이 한 일 | 우리 상황 | 우리 선택 |
|---|---|---|---|
| 사과 컨베이어 검사 (arXiv 2602.19278) | 프레임 판정이 흔들려 물체를 추적하고, 물체 단위로 모아 판정을 안정시켰다 | 서버가 추적 ID를 주지 않는다. 작업자는 제품을 하나씩 들었다 내린다. 가모델이라 오탐이 많다 | 한 제품을 비추는 동안을 **제품 구간**(=추적 하나)으로 본다. 구간 판정은 **결함 확정 규칙**(최근 5장 중 2장 이상 결함)으로 정해 한 장짜리 오탐을 거른다 |
| 물체 추적기 SORT·ByteTrack·DeepSORT | 검출을 놓쳐도 추적을 바로 지우지 않고 일정 프레임 유지한다 (SORT `max_age`, ByteTrack `track_buffer` 기본 30장 ≈ 30fps에서 1초) | 서버는 제품이 안 보이는 프레임 **한 장**에도 `searching`을 보낸다 | **마지막 `inspect` 뒤 1초 동안 `inspect`가 없을 때만** 구간을 끝낸다(R2) |
| 산업 알람 관리 ISA-18.2 / IEC 62682 | 켜짐 지연·해제 지연으로 깜빡이는 경보를 하나로 묶는다 | 진동이 판정마다 울릴 구조다 | 진동은 **결함이 확정되는 순간, 구간당 1회**(R5). 확정 기준은 구간 판정과 같은 규칙. 중요한 표시가 덜 중요한 표시를 밀어낸다(R9) |
| 깜빡임 억제 일반 기법 (N-of-M, 짧은 유지) | 한두 장 놓쳐도 바로 지우지 않는다 | 결함은 프레임마다 잡혔다 안 잡혔다 한다 | 화면 표시는 마지막 결함 뒤 **0.5초 유지**(규격 §3.4). `searching`이면 즉시 지운다 |
| 산업 HMI의 값·시각·품질(VTQ) 표시 | 값마다 시각을 붙이고, 오래된 값은 따로 표시한다 | `inspect`에는 촬영 시각이 없다 | 지금은 결함 원 **켜기/끄기 설정**(R7). 7단계로 `t`가 생기면 메시지마다 나이를 본다 |
| SCADA·MQTT Sparkplug의 바뀔 때만 보내기 | 값이 바뀔 때만 보내 대역폭을 줄인다. 실시간 데이터는 수신 확인 없이 보낸다 | BLE 쓰기는 응답을 기다리는 방식이고 SHOW·PING과 잠금을 나눠 쓴다 | LIVE는 **결함 프레임과 지우는 순간만**, **응답 없는 쓰기**로 보낸다(R8). 지우는 쪽지는 **표시 중일 때만** 보낸다 |
| DICOM의 영상 참조 | 고유 ID와 "어디서 가져올지"(논리 이름)를 같이 담는다 | 앱이 사무실·모의 서버를 자동으로 오간다 | 이력에 **서버 이름 키**(`office`/`mock`)를 저장하고, 불러올 때 현재 설정 주소로 바꾼다(R6) |
| RealWear UX 지침, 비전 검사 장비 화면 | 짧고, 글자 위주, 대비 높게. 누적 개수는 통계 화면에 둔다 | Navigator 520은 한쪽 눈 앞의 작은 화면이다 | HMD에는 판정 글자 한 줄과 결함 원만. **처리 건수는 HMD에서 빼고** 폰 M-05에 둔다(R9) |
| PCB AOI 검증 스테이션 | 전체 이미지 + 결함별 확대 + 확대·이동 | HMD는 AI 서버 overlay를 직접 받지 못한다 | 확대 보기는 **폰**(M-07 + M-05)에 둔다 |
| Glimpse (SenSys 2015), Edge-Assisted AR (MobiCom 2019) | 서버 결과가 늦게 오니, 기기에서 추적해 표시를 옮겼다 | 결함은 수 px이고 제품은 3D로 돈다 | **추적은 하지 않는다.** 먼저 지연을 실측한다(6단계). 0.3초를 넘으면 그때만 서버에 `rtp_timestamp`를 요청한다(7단계) |

### 2.2 개정 2에서 추가한 사례

| 사례 | 그들이 한 일 | 우리 상황 | 우리 선택 |
|---|---|---|---|
| gRPC 연결 재시도 규격 (`connection-backoff.md`) | 재시도 간격(첫 1초, 1.6배씩, 최대 120초, ±20% 흔들기)과 **한 번의 연결 시도에 주는 최소 시간**(`MIN_CONNECT_TIMEOUT` 20초)을 따로 둔다. 시도의 마감은 "다음 재시도 시각"과 "지금 + 최소 시간" 중 늦은 쪽이다. 서버가 연결을 받아들였다고 확인되면 간격을 처음 값으로 되돌린다. 모바일은 흔들기를 달리 해도 되지만 기준보다 자주 재시도하면 안 된다 | 지금 `tick()`은 `connected`도 `connecting`도 아니면 새 시도를 한다(`M/ai/AiEdgeLink.kt:132`). offer가 200으로 끝난 뒤 `CONNECTED`가 오기 전이 이 상태라, 간격을 1초로 줄이면 연결 중인 PeerConnection을 `closePc()`가 닫는다. 연결 전에 `FAILED`가 오면 `if (connected)` 조건(`:247`) 때문에 실패로 세지 않는다 | 간격은 규격 §5.3(1→2→4→5초)을 따른다. 그 위에 **시도 마감 10초**를 둔다(같은 망이라 gRPC 20초의 절반, 지금 ICE 수집 대기 `GATHER_MS` 5초 + HTTP 시간을 덮는 값). 시도 중에는 새 시도를 하지 않는다. 다음 시도 시각은 **실패한 시각** + 간격이다. 클라이언트가 스테이션당 1대라 흔들기는 넣지 않는다(R10) |
| NTP 시계 필터 (Mills, RFC 5905) | 요청·응답에 네 시각(보낸 시각 t1, 받은 시각 t2, 답한 시각 t3, 받은 시각 t4)을 담아 시계 차이 = ((t2−t1)+(t3−t4))/2, 왕복 = (t4−t1)−(t3−t2)를 낸다. **최근 8개 중 왕복이 가장 짧은 표본**이 가장 정확하다고 보고 그 표본의 시계 차이를 쓴다. 오가는 지연이 대칭이라고 가정한다 | 개정 1은 SHOW ACK의 `rendered_at`으로 시계 차이를 재려 했다. 판정 화면이 없어지면 SHOW는 교대 시작·장애 띠·BLE 재연결 때만 나가 표본이 거의 없다. PING은 1초마다 나가지만 HMD가 답하지 않는다(`H/hud/HudController.kt:46`) | **HMD가 PING에 PONG(t2, t3)으로 답한다.** 폰은 t1·t4를 붙여 계산하고 최근 8개 중 왕복이 가장 짧은 표본을 쓴다. BLE 한 방향 지연은 그 왕복의 절반으로 본다. 6단계 측정에 별도 쪽지를 늘리지 않는다 |
| ISA-18.2 경보 상태 "점검 중(out of service)" | 점검·시험 중인 경보는 운전원에게 알리지 않고, 점검 중인 경보 목록을 따로 보여 줄 수 있어야 한다. 일부 제품은 점검 중 경보를 따로 표시하고 지표(KPI)에서 뺀다 | 6단계 측정은 1초마다 검은 화면과 실제 영상을 바꾼다. 구간 종료 1초와 겹쳐 이력·진동·HMD 결함 표시가 생긴다 | **측정 모드 동안 판정 처리를 "점검 중"으로 둔다**: 구간·진동·이력·LIVE를 처리하지 않고, M-07과 HMD에 "측정 중 — 판정 안 함"을 띄운다. 측정이 끝나면 저절로 돌아온다(R11) |
| 합성 트래픽 표시 (분석 도구의 시험 트래픽 제외, 합성 모니터링) | 시험 트래픽은 처음부터 표시를 붙여 보내고 실제 지표에서 걸러낸다. 받는 쪽에서 구분할 수 없으면 거르기 어렵다 | 서버는 측정용 영상과 실제 영상을 구분하지 못한다. 서버가 저장하는 결함 프레임에 측정 영상이 섞일 수 있다 | 앱은 측정 시작·끝 시각과 스테이션을 작업일지에 남겨 서버 저장 기록에서 거를 수 있게 한다. 측정용 영상(검은 화면)은 결함이 안 나오도록 제품 없이 찍는다 |
| WebRTC 지연 분석 (getStats, glass-to-glass) | 통계 하나로는 전체 지연을 알 수 없다. `totalEncodeTime`/`framesEncoded`로 프레임당 인코딩 시간, `roundTripTime`으로 네트워크 왕복을 알 수 있다. 전체 값은 카메라 앞 시계와 받는 화면을 함께 찍어 확인한다 | 개정 1의 "폰 → 서버 → 폰" 측정은 인코딩·네트워크·서버 처리를 한 값으로만 잰다 | 측정 결과에 **인코딩 시간과 네트워크 왕복을 함께 기록**해, 나머지를 서버 처리 시간으로 본다. HMD → 폰 구간은 **밀리초 시계 앞에 HMD를 두고 시계와 M-07 화면을 한 사진에 찍어** 한 번 검증한다 |
| 비전 검사 기록 관행 (검사 장비·추적성 자료) | 합격/불합격과 별도로 결함 종류, 점수, 기준값, 결함 위치, 영상 참조를 따로 남긴다. 기준값을 나중에 바꿔도 기록을 다시 쓰지 않아도 된다 | 상태기계가 REVIEW를 FAIL로 합치고(`M/core/StateMachine.kt:114`), 이력에는 결함 종류 칸이 없다. 서버 기준값(`threshold`)은 모델 교체 때 바뀐다(규격 §3.3) | 이력에 **원판정(`OK`/`NG`/`REVIEW`), `defect_label`, 최고 `score`, `threshold`, `result_id` 목록, 서버 키**를 합격/불합격과 따로 저장한다(R6). 합격/불합격 규칙은 그대로다(R4) |
| UI 상태 모델링 (봉인 클래스·서로 겹치지 않는 상태) | 화면 상태를 서로 겹치지 않는 값으로 나타내 "있을 수 없는 상태"를 아예 못 만들게 한다. 문자열이나 여러 플래그 조합으로 상태를 나타내면 숨은 버그가 생긴다 | HMD 대기 화면과 조준 화면이 같은 ID `"H-01"`이다(`H/hud/Screens.kt:75`, `:97`). 폰 M-01·M-07은 상태 문자열을 쪼개 읽는다 | HMD 대기 화면 ID를 `IDLE`로 나눈다(BLE 규격은 그대로). 폰 상태는 `lastSegment` 객체로 넘긴다 |
| 작은 물체 검출과 해상도 (SAHI 문서·논문) | 큰 영상을 모델 입력 크기로 줄이면 작은 물체가 몇 px로 작아져 검출되지 않는다. 그래서 타일로 잘라 본다. 비교는 같은 영상으로 한다 | 서버는 640px 타일로 본다. 4K에서 13px 결함이 1080p에서는 약 6px다. 실시간 경로 4K는 HMD CPU JPEG 변환 + 폰 디코딩 + 소프트웨어 VP8 4K 인코딩을 거치는데, 모바일 VP8 4K 실시간 성능 자료는 찾지 못했다(데스크톱 1코어 720p 약 48fps가 공개된 값) | **같은 4K 프레임을 녹화하고 PC에서 1080p로 줄여, 두 벌을 규격 §6 WebSocket으로 보내 짝지어 비교한다.** 해상도 말고는 모두 같다. 2160p가 이기면 그때 실시간 경로의 성능을 따로 확인한다(5단계) |
| Room `fallbackToDestructiveMigration` (Android 공식 문서) | 마이그레이션이 없으면 표를 지우고 다시 만든다. 버려도 되는 데이터라면 개발 중 흔히 쓴다. 지킬 데이터가 생기면 명시적 마이그레이션과 스키마 내보내기·시험으로 바꾼다 | 개발 중이라 로컬 이력을 지켜야 할 이유가 없다 | `version = 2`와 `fallbackToDestructiveMigration()`. 실사용 전에 마이그레이션 정책으로 바꾼다(§7) |

### 2.3 쓰지 않는 것과 이유

- 기기 쪽 물체 추적(ML Kit 등): 제품 상자는 추적되지만 수 px 결함을 따라가지 못한다.
- AOI식 오탐 사전 거름: 오탐 판단은 서버 모델의 몫이다. 앱은 결함 확정 규칙까지만 한다.
- 실행 중 지연 자동 비교: 메시지에 촬영 시각이 없어 할 수 없다. 7단계 전까지는 설정으로 정한다.
- 재연결 흔들기(jitter): 여러 클라이언트가 동시에 몰리는 것을 막는 장치다. 스테이션당 폰 1대라 넣지 않는다.
- 실시간 경로의 해상도 비교: 해상도 말고도 HMD·폰 처리 성능이 함께 바뀌어 결과를 해석할 수 없다.

## 3. 확정 규칙

| # | 항목 | 규칙 |
|---|---|---|
| R1 | 실시간 표시 | 서버 프레임 판정을 그대로 표시한다. NG 빨강, REVIEW 노랑, OK는 표시 없음. 마지막 결함 뒤 0.5초가 지나면 **표시하는 쪽(HMD·M-07)이 스스로** 지운다. `searching`·연결 끊김이면 즉시 지운다. HMD는 BLE가 끊겨도 즉시 지운다 |
| R2 | 제품 구간 | 첫 `inspect`에서 시작한다. **마지막 `inspect` 뒤 1초 동안 `inspect`가 없거나, 연결이 끊기면** 끝난다. `searching`은 구간을 끝내지 않는다(화면만 지운다) |
| R3 | 결함 확정 | 구간 안 최근 5장의 `inspect` 중 2장 이상이 NG/REVIEW이면 확정. 확정 시점의 5장에 NG가 있으면 NG, 없으면 REVIEW. 이후 NG로 다시 확정되면 REVIEW를 NG로 올린다 |
| R4 | 구간 판정(이력) | 확정이 없으면 OK. 있으면 R3의 판정. OK는 합격, NG·REVIEW는 불합격 |
| R5 | 진동 | 구간에서 처음 확정되는 순간 3회. 구간당 1회 |
| R6 | 이력 내용 | 구간 1건마다 **합격/불합격과 따로** 원판정(`OK`/`NG`/`REVIEW`, R4), 확정 시 `defect_label`, 최고 `score`, 마지막 `threshold`, 받은 `inspect` 수, 결함 프레임 수, 구간 안에서 받은 **모든 `result_id`**(점수 높은 순, 첫 번째가 대표), **서버 이름 키**(`office`/`mock`). 확정되지 않은 결함 프레임도 연결해 오탐을 나중에 확인할 수 있게 한다 |
| R7 | 결함 원 표시 | 설정(`liveMarks`)으로 켜고 끈다. 끄면 폰이 LIVE에서 상자(`b`)를 빼고 보내, HMD는 판정 글자만 보인다. 6단계 실측(90% 값)이 0.3초 이하이면 켜고, 넘으면 끈다. 7단계를 하면 HMD가 메시지마다 나이(현재 − `t`)를 계산해 `liveMaxDelayMs`를 넘는 원만 숨긴다 |
| R8 | LIVE 전송 | NG/REVIEW 프레임마다 보낸다. 지울 때(`searching`·연결 끊김)는 **HMD에 표시 중일 때만**(마지막 결함 LIVE 뒤 `markHoldMs` 안) `d:null`을 한 번 보낸다. OK 프레임은 보내지 않는다. 응답 없는 쓰기로 보내고, 앞의 쓰기가 안 끝났으면 최신 1개만 남긴다 |
| R9 | HMD 화면 | LIVE는 조준 화면(`H-01`)에서만 그린다. 대기 화면은 HMD 안에서 `IDLE`로 따로 구분한다. 가운데 큰 글자는 **LIVE(유지 중) > HINT > 화면 기본값** 순서로 `render()` 한 곳에서 정한다. 처리 건수는 HMD에 표시하지 않는다(폰 M-05에 있음) |
| R10 | 재연결 | 간격은 연속 실패 횟수에 따라 1 → 2 → 4 → 5초, 이후 5초(규격 §5.3). 다음 시도 시각 = 실패 시각 + 간격. 한 번의 시도는 시작부터 `CONNECTED`까지 **최대 10초**이고, 그동안 새 시도를 하지 않는다. 실패로 세는 것: offer 실패(503·네트워크), 서버 모두 응답 없음, `CONNECTED` 전 `FAILED`·`CLOSED`, 10초 초과, 연결 뒤 `DISCONNECTED`·`FAILED`·`CLOSED`. `CONNECTED`가 되면 실패 횟수를 0으로 되돌린다. 422·403은 재시도하지 않는다 |
| R11 | 측정 모드 | 측정 중에는 판정 처리를 "점검 중"으로 둔다: 구간·확정·진동·이력·LIVE를 처리하지 않는다. 진행 중이던 구간은 측정 시작 때 끝낸다. M-07과 HMD에 "측정 중 — 판정 안 함"을 표시한다. 측정이 끝나면(정해진 횟수 완료 또는 취소) 저절로 돌아온다 |

- 숫자(5장 중 2장, 0.5초, 1초, 0.3초, 10초)는 출발값이다. `Profile`이나 상수에 두고 현장 시험(§6)으로 조정한다.
- 1초 구간 종료는 물체 추적기의 관례(30fps에서 30장)를 우리 서버 속도(초당 최대 10장)에 맞춘 값이다. 1초 안에 제품을 바꿔 들면 두 제품이 한 건으로 합쳐질 수 있어 §6에서 확인한다.
- 0.5초 유지 값은 HMD(`LiveHold`)와 폰(`Profile.markHoldMs`, M-07, R8의 "표시 중" 판단)에 따로 있다. 두 값을 같게 유지한다.

## 4. 바뀐 뒤의 흐름

```
AI 서버 ─inspect/searching─▶ AiEdgeLink (R10 재연결) ─▶ AiFlow ── 측정 모드면 여기서 멈춤 (R11)
                                            ├─ 결함 프레임·지움(R8) ─▶ LIVE 쪽지 ─BLE(응답 없음)─▶ HMD: 판정 글자·결함 원(R7·R9)
                                            │                                                 (+ 7단계 시 판정 프레임 작은 창)
                                            ├─ 프레임마다 ─▶ 폰 M-07: 결함 원·최근 결함 확대 보기
                                            ├─ 결함 확정(R3) ─▶ 진동 3회 (R5)
                                            └─ 구간 끝(R2: 1초 무소식·끊김) ─▶ Event.JudgeResult ─▶ StateMachine ─▶ 이력 1건 (R6)
                                                                                                       └─▶ M-05 이력에서 결함 프레임 다시 보기

폰 ─PING(1초마다)─▶ HMD ─PONG(t2, t3)─▶ 폰: 시계 차이·BLE 지연 (6단계)
```

---

## 5. 단계별 작업

### 1단계: 메시지 규격과 재연결 (모바일)

| 파일 | 수정 |
|---|---|
| `M/ai/AiMessages.kt` | `AiMsg`를 `Searching(reason)`, `Inspect`, `Unknown`으로 줄인다. `Inspect`에 담을 값: seq, frameSize, decision, defectLabel, score, threshold, products, detections(label, conf, box), resultId, overlayUrl, rtpTimestamp(없으면 null). `Hold`·`Captured`·`Result`·`Alert`, `AiResultView`의 `status`·`alert`, 끊긴 사이 조회용 해석은 지운다. `GET /results/{id}` 응답 해석은 detections·frame_size를 읽도록 바꿔 남긴다(4단계 이력 확대 보기) |
| `M/ai/AiResultsApi.kt` | `decision()`을 지우고, `result()`는 남긴다. `overlay(path): ByteArray?`를 추가한다 |
| `M/ai/Reconnect.kt` (신규, 순수 클래스) | R10을 담는다. 상태: 대기 / 시도 중 / 연결됨 / 중지(설정 오류). 함수: `due(now)`(새 시도를 할 때인가), `onAttempt(now)`, `expired(now)`(시도 10초 초과), `onConnected()`, `onFailure(now)`, `onConfigError()`. 간격 표: 1 → 2 → 4 → 5초, 이후 5초 |
| `M/ai/AiEdgeLink.kt` | ① 끊긴 사이 조회(`flow.pending`, `poll()`, `onConnected`의 조회 시작, `:278-296`)를 지운다. ② 고정 `RECONNECT_MS`와 `lastAttempt` 대신 `Reconnect`를 쓴다. `tick()`은 `policy.due(now)`일 때만 `connect()`를 부르고, 시도 중이며 `policy.expired(now)`이면 `closePc()` 후 실패로 처리한다. ③ `onConnectionChange`(`:240-251`): `CONNECTED` 전에 `FAILED`·`CLOSED`가 오면 실패로 센다(지금은 `if (connected)` 때문에 무시된다). ④ `lost()`에서 `policy.onFailure(now)`, 403·422에서 `policy.onConfigError()`. ⑤ "종이 긴 변 ≥1216px"(`:51`, `:215`), "hold 간격"(`:376`) 주석을 고친다 |
| `MT/ai/AiMessagesTest.kt` (신규) | 규격 §3 예시(`searching`, `inspect` OK/NG, 모르는 필드, 깨진 글)를 해석한다 |
| `MT/ai/ReconnectTest.kt` (신규) | 간격 1·2·4·5·5초. **시도 중에는 간격이 지나도 새 시도 없음.** 10초 초과는 실패로 세고 다음 간격으로. 연결 전 `FAILED`도 실패. `CONNECTED`면 0으로. 설정 오류면 더 시도하지 않음 |

**결과물**: 규격 예시 메시지가 모두 해석되고, 재연결 시험이 통과한다. 실서버에 연결해 `inspect` 수신 로그를 확인한다. 서버를 껐다 켜서 재시도 간격이 로그에 1·2·4·5초로 찍히고, 서버가 다시 뜨면 연결이 한 번에 붙는지 본다.

### 2단계: 판정 흐름과 이력 (모바일)

| 파일 | 수정 |
|---|---|
| `M/core/State.kt` | `Profile`에 `confirmHits = 2`, `confirmWindow = 5`, `markHoldMs = 500`, `segmentEndMs = 1000`, `liveMarks = true`, `liveMaxDelayMs = 300`(7단계에서만 사용)을 추가한다. `autoDismiss`는 지운다 |
| `M/ai/AiFlow.kt` | 새로 쓴다. ① 결함 프레임과 지울 때 `onLive(LiveMark)`를 부른다(R8, 3단계). 지우는 쪽지는 마지막 결함 LIVE 뒤 `markHoldMs` 안일 때만 보낸다. M-07용 최근 프레임도 갱신한다(4단계). ② 제품 구간(R2)과 결함 확정(R3)을 처리하고, 확정되는 순간 `onAlarm()`을 부른다(R5). ③ 구간 끝은 `onTick(now)`에서 "마지막 `inspect` 뒤 `segmentEndMs`"로 판단하고, `onDisconnected()`에서는 바로 끝낸다. 끝나면 `Event.JudgeResult` 하나를 낸다(R4·R6). partId는 `<스테이션>-<구간 시작 ms>`이다. ④ 안내 문구를 "제품을 비춰 주세요"(대기), "검사 중"(`inspect` 수신)으로 바꾼다. ⑤ `onMessage`(WebRTC 콜백 스레드)·`onTick`·`onConnected`·`onDisconnected`(연결 스레드)를 모두 `@Synchronized`로 한다. 확정 판정은 순수 함수로 분리해 시험한다. ⑥ 6단계용 `outOfService` 플래그 자리를 둔다(R11) |
| `M/ai/AiEdgeLink.kt` | 생성자로 `Profile`, `onLive`, `onAlarm`을 받아 `AiFlow`에 넘긴다(`:68`). `tick()`에서 `flow.onTick(now)`를 부른다(0.5초 주기라 구간 끝은 1.0~1.5초 사이에 잡힌다). 서버 목록 항목을 `AiServer(key, label, url)`로 받고, 서버를 고를 때(`pickServer`, `:151-156`) 그 키를 `AiFlow`에 알린다. 상태(`_status`)에 `lastSegment`를 채운다 |
| `M/core/Event.kt` | `JudgeResult`에 `aiDecision: String? = null`, `defectLabel: String? = null`, `threshold: Double? = null`, `resultIds: List<String> = emptyList()`, `frames: Int = 0`, `defectFrames: Int = 0`, `serverKey: String? = null`을 추가한다. **기본값을 줘서** `M/relay/StreamRelay.kt:194`, `MT/hmd/HmdRendererTest.kt:33`, `MT/service/InspectionEngineTest.kt:56`, `MT/core/StateMachineTest.kt:18`이 그대로 빌드되게 한다 |
| `M/core/StateMachine.kt` | `onJudge`가 이제 구간 1건을 받는다. `Persist(Records.episode(e))`와 처리 건수(`doneCount`) 증가만 남기고, H-02/H-03 판정 화면·`Vibrate`·확인 타이머는 뺀다. 합격/불합격 규칙(`:114`)은 그대로 두고, 원판정은 `e.aiDecision`으로 기록에 따로 남긴다. R06·R07(순서·중복)은 유지한다. 트레이·제스처·확인 타이머 규칙(R08~R12)은 호출되지 않게 되지만 이번에는 지우지 않는다 |
| `M/core/Screens.kt` | `guide(partNo, doneCnt, profile)` **함수 모양은 그대로 두고** `fields`에서 `done_cnt`, `view_total`, `frames_target`을 뺀다(R9). 호출처(`StateMachine.kt:30,50,62,77,182`, `InspectionService.kt:182`, `HmdRendererTest.kt:46`)는 고치지 않는다 |
| `M/core/Effect.kt`, `M/core/Records.kt`, `M/model/Codes.kt` | `InspectionRecord`에 `aiDecision`, `defectLabel`, `threshold`, `resultIds`, `frames`, `defectFrames`, `serverKey`를 추가한다. `WorkerAct.NONE("NONE", "확인 절차 없음")`과 `Records.episode(e)`를 만든다 |
| `M/store/QmsEntities.kt`, `M/store/QmsDatabase.kt`, `M/store/LocalStore.kt` | `InspectionEntity`에 `aiDecision: String?`, `defectLabel: String?`, `threshold: Double?`, `resultIds: String?`(쉼표 구분), `serverKey: String?`를 추가한다. `@Database(version = 2)`로 올리고 `build()`에 `.fallbackToDestructiveMigration()`을 넣는다(Room 2.6.1, 개발 중 기존 이력 삭제 허용). `toEntity()`에서 지금 0으로 채우는 `framesReceived`·`framesSelected`에 `frames`·`defectFrames`를 넣는다 |
| `M/relay/StreamRelay.kt` | `Status`에 `lastSegment: SegmentSummary? = null`(합격/불합격, 원판정, `defect_label`, 결함 프레임 수, 받은 프레임 수)을 추가한다. 모의 엣지 경로는 채우지 않는다 |
| `M/relay/DevSettings.kt`, `M/ui/screen/M01ShiftPrep.kt` | 판정 화면 "자동/수동 소거" 선택지(`M01ShiftPrep.kt:52`, `:120-132`, `DevSettings.autoDismiss`)를 **"HMD 결함 원 켜기/끄기"(`liveMarks`)**로 바꾼다(R7). 상태 줄(`:175`)은 `lastSegment`로 "마지막 구간 판정 · 결함 프레임 n장"을 보여 주고, 없으면(모의 엣지) 지금처럼 `lastJudge`를 쓴다. "앙상블 목표 n장"(`:102`)을 지운다 |
| `M/service/InspectionService.kt` | 서버 목록(`:143`)을 `AiServer("office", "사무실 AI", aiUrl)`, `AiServer("mock", "모의 AI(PC)", mockAiUrl)`로 만든다. 교대 시작 때 `Profile(liveMarks = DevSettings.liveMarks)`를 만든다(`:133`). `AiEdgeLink`에 `Profile`, `onLive`(3단계 BLE 전송, 4단계 M-07), `onAlarm`(`vibrate(3)`)을 연결한다(`:144`) |
| `MT/ai/AiFlowTest.kt` | 이전 규격 시험을 지우고 새로 쓴다. 확정 규칙(1장 오탐은 OK, 5장 중 2장은 확정, REVIEW → NG 올림), 진동 구간당 1회, `searching` 한 장 뒤 1초 안에 `inspect`가 다시 오면 같은 구간, 1초 무소식이면 구간 종료, 끊김으로 즉시 종료, `result_id` 목록 순서, 서버 키·원판정·`defect_label`·`threshold` 기록, **표시 중이 아니면 `d:null`을 보내지 않음** |
| `MT/core/StateMachineTest.kt` | 구간 1건 기록, 판정 화면·진동·타이머가 없음, R06·R07 유지, REVIEW 구간은 불합격이면서 원판정 `REVIEW`로 기록. "판정 화면 자동 수동 전환" 시험은 지운다 |
| `MT/service/InspectionEngineTest.kt` | "PASS면 화면을 띄운다"(`:96`), "확인 시간 초과 → TIMEOUT"(`:107`), "트레이 먼저"(`:120`)는 새 `onJudge`에서 실패한다. 구간 1건이 엔진을 거쳐 저장되는 시험으로 바꾼다. 순서·유실 시험(`:71`, `:85`)은 그대로 둔다 |
| `MT/store/LocalStoreTest.kt` | 마이그레이션 시험은 하지 않는다. 새 칸(원판정, `defect_label`, `threshold`, `result_id` 목록, 서버 키, 프레임 수)이 저장 후 그대로 읽히는지 시험을 하나 추가한다 |

**결과물**: 제품 하나를 비췄다 내리면 이력이 1건 생기고, 원판정·결함 종류·결함 프레임·서버가 함께 남는다. 제품을 돌리다 제품 탐지가 한두 장 놓쳐도 1건이다. 결함 확정 때만 진동이 1회 울린다.

### 3단계: HMD 실시간 표시 (BLE `LIVE` 쪽지)

쪽지 규격 (순번·ACK 없음, 최신 것만 의미 있음, 응답 없는 쓰기):

```json
{"cmd":"LIVE","d":"NG","l":"칩찍힘","b":[[573,905,578,911]],"t":null}
{"cmd":"LIVE","d":null}
```

- 보내는 때(R8): NG/REVIEW 프레임마다. 지울 때(`searching`·연결 끊김)는 표시 중일 때만 `d:null` 한 번. OK 프레임은 보내지 않는다.
- `d`: 프레임 판정(`NG`/`REVIEW`). `null`이면 즉시 지운다.
- `l`: `defect_label`
- `b`: 결함 상자. `frame_size` 기준 0~1000 정수로 보낸다. 겹치거나 맞닿은 상자는 합치고, 480B를 넘지 않게 신뢰도 높은 순으로 자른다. `liveMarks`가 꺼져 있으면 넣지 않는다(R7).
- `t`: 판정 프레임의 HMD 촬영 시각. 7단계를 하지 않으면 항상 `null`이다.

| 파일 | 수정 |
|---|---|
| `M/hmd/ShowWire.kt` | `LiveWire.encode()`와 상자 합치기를 추가한다(순수 함수) |
| `M/hmd/GattBleLink.kt` | 응답 없는 쓰기(`WRITE_TYPE_NO_RESPONSE`)용 `writeNoResponse(bytes)`를 추가한다. 기존 `write()`(`:106-124`)와 같은 잠금(`writeLock`)과 쓰기 완료 콜백을 쓴다. 안드로이드는 응답 없는 쓰기도 콜백이 와야 다음 쓰기를 받는다 |
| `M/service/InspectionService.kt` | LIVE는 최신 1개만 남기는 채널(`Channel.CONFLATED`) 하나에서 꺼내 `writeNoResponse`로 보낸다. 지금 HINT처럼 메시지마다 코루틴을 띄우면(`:146`) 잠금 앞에 쌓인다 |
| `H/protocol/Wire.kt` | `Downlink.Live(d, l, b, t)`를 해석한다 |
| `H/hud/Screens.kt` | 대기 화면 `idle()`의 화면 ID를 `"IDLE"`로 바꾼다(`:75`). BLE 쪽지(`H-01` + `state=IDLE`)는 그대로라 모바일은 고치지 않는다. `aim()`(`:91-108`)에서 처리 건수, "프레임 확보 n/m", "화각 점유율", "n면 검사", "↻ 다음 면으로 돌리세요"를 지운다 |
| `H/hud/LiveHold.kt` (신규, 순수 클래스) | 마지막 결함 뒤 0.5초 유지, `d:null`·BLE 끊김이면 즉시 지움. **큰 글자 결정 함수** `big(base, hint, live, now)`: 조준 화면이면 LIVE(유지 중) > HINT > 기본값, 그 밖의 화면이면 기본값. 시각은 밖에서 넣는다(`ShowGate`와 같은 방식) |
| `H/hud/MarkerView.kt` (신규) | 결함 원(지름 40dp 이상, NG 빨강·REVIEW 노랑)을 그린다. 0~1000 좌표 → 화면 좌표 변환은 순수 함수로 둔다 |
| `H/MainActivity.kt` | Preview에 `setTargetAspectRatio(AspectRatio.RATIO_16_9)`를 준다(`:197`). 지금은 CameraX 기본값 4:3이라 16:9 분석 영상(`:204`)과 화각이 다를 수 있다. HMD 화면은 1280×720(`res/layout/activity_main.xml:2`)이라 16:9끼리면 잘림이 없어 좌표 변환이 단순 비율이 된다. 실제 분석 영상 크기(`image.width×height`)를 로그로 남긴다 |
| `res/layout/activity_main.xml` (HMD) | 미리보기 위, HUD 아래에 `MarkerView`를 둔다 |
| `H/hud/HudController.kt` | ① `onLive`: `LiveHold`에 넣고 큰 글자와 `MarkerView`만 다시 그린다(초당 여러 번이라 `render()` 전체를 돌리지 않는다). 수신을 생존 신호로도 친다(`gate.onPing`). ② `render()`(`:126-131`)의 HINT 덮어쓰기를 `LiveHold.big()` 호출로 바꾼다. ③ `onHint`의 `current?.screen == "H-01"` 검사(`:102`)는 대기 화면이 `IDLE`로 바뀌어 그대로 조준 화면에만 적용된다. ④ 0.5초 유지 후 지우기는 HMD 타이머로 스스로 한다(R1). ⑤ `onLinkDisconnected()`에서 LIVE를 즉시 지운다 |
| `HT/HudLogicTest.kt` | LIVE 해석, 좌표 변환, `LiveHold`(0.5초 유지, 즉시 지우기, LIVE > HINT > 기본값, **대기 화면에서는 LIVE·HINT를 그리지 않음**). 화면 매핑 시험(`:79-92`)의 대기 화면 기대값을 화면 ID `IDLE`로 고친다 |
| `MT/hmd/` | LIVE 480B 이하, 상자 합치기, OK 프레임은 보내지 않음, `liveMarks` 끄면 `b` 없음 |

**결과물**: HMD 카메라 화면에 판정 글자와 결함 원이 뜨고, 0.5초 유지되며, 제품을 내리면 바로 지워진다. 대기 화면에는 아무것도 덧그려지지 않는다. 평상시(OK 프레임) BLE 전송은 없다.

### 4단계: 폰 결함 확대 보기 (M-07, M-05)

| 파일 | 수정 |
|---|---|
| `M/ui/screen/DefectViewer.kt` (신규) | `result_id` 목록과 서버 키를 받아 overlay JPEG와 판정(`GET /results/{id}`)을 불러온다(IO 디스패처). 서버 키는 현재 설정 주소로 바꾼다(`office` → `DevSettings.aiUrl`, `mock` → `DevSettings.mockAiUrl`). 전체 이미지(결함마다 빨간 원) + 결함별 정사각 확대 + 넘겨 보기 + 핀치 확대·이동. 확대 영역은 겹친 상자를 합친 뒤, 한 변 = max(프레임 짧은 변의 20%, 상자 긴 변의 3배)로 하고 프레임 안으로 밀어 넣는다(규격 §4.2). overlay가 `404`면 0.5초 뒤 한 번 더 요청하고, 그래도 없으면 "서버에 이미지 없음"을 표시한다. `503`이면 1초 뒤 한 번 더 요청한다(규격 §4.3) |
| `M/ui/screen/M07LiveView.kt` | ① 영상 위에 결함 원(R1, R7)을 그린다. ② 새 `result_id`가 오면 `DefectViewer` 작은 창을 갱신하고, 누르면 전체 화면으로 연다. ③ "종이가 작음" 분기(`:95`)를 지운다. "마지막 판정"(`:96-101`)은 `lastSegment`로 바꾸고, 6단계 뒤에는 실측 지연도 보여 준다 |
| `M/ui/screen/M05InspectionHistory.kt` | 배지를 합격 / 불합격(NG) / 불합격(REVIEW)로 나누고 `defect_label`을 함께 보인다. `resultIds`가 있는 행에 "결함 이미지 n장"을 표시하고, 누르면 `DefectViewer`를 연다 |
| `M/service/InspectionService.kt` | M-07용 최근 프레임 판정과 최근 `result_id`를 `StateFlow`로 내보낸다(`liveFrame`과 같은 방식) |
| `MT/...` | 확대 영역 계산(가장자리 밀어 넣기, 상자 합치기)과 서버 키 → 주소 변환을 시험한다 |

**결과물**: M-07에서 실시간 결함 위치와 최근 결함 확대를, M-05에서 지난 제품의 결함 프레임과 원판정을 볼 수 있다. 모의 서버 시절 기록도 모의 서버가 켜져 있으면 열린다.

### 5단계: 해상도별 검출률 시험 (짝지은 비교, 앱 수정 없음)

규격의 결함 크기 실측은 4K 기준이고 우리는 1080p를 보낸다. 해상도가 결함을 놓치는 원인인지 **해상도만 바꿔서** 확인한다.

| 파일 | 수정 |
|---|---|
| `H/MainActivity.kt` | 시험용으로 `--es res 2160`(3840×2160) 선택지를 추가한다(`:49`). 기본값 1080p는 바꾸지 않는다. 실제로 받은 분석 영상 크기와 초당 장수를 상태줄에 표시한다. 녹화용이라 초당 장수가 낮아도 된다 |
| `02_소스/tools/res_ab.py` (신규, PC 스크립트) | ① 녹화: 같은 Wi-Fi의 PC가 HMD MJPEG(`http://<HMD>:8080`)에서 2160p 프레임을 받아 `X-Seq`와 함께 파일로 저장한다. ② 재생: 저장한 프레임마다 원본(4K)과 PC에서 줄인 1080p(같은 JPEG 품질)를 만들어, 각각 규격 §6 WebSocket(`ws://<서버>:8000/ws?station=ST-02`)으로 초당 10장 이하로 보내고 `inspect`를 기록한다. ③ 집계: 프레임별 결함 검출 여부와, 같은 순서에 R3(5장 중 2장)를 적용한 제품별 확정 여부를 해상도별로 표로 낸다 |

시험 방법:
1. 결함이 있는 제품 5개와 결함이 없는 제품 5개를 고른다.
2. HMD를 `--es res 2160`으로 켜고, 제품마다 평소처럼 들고 돌리는 모습을 10초씩 녹화한다. 거리와 조명은 실제 작업과 같게 한다.
3. 녹화한 같은 프레임을 4K와 1080p로 서버에 보낸다(스크립트 ②).
4. 해상도별 결함 제품 확정률, 정상 제품 오확정률, 프레임별 검출률을 비교한다(스크립트 ③).

판정 기준:
- 결함 제품 확정률이 4K보다 1080p에서 10%p 이상 낮으면, 해상도 상향을 별도 작업으로 진행한다. 그때 실시간 경로(HMD 4K JPEG 변환 속도, 폰 디코딩·VP8 인코딩 초당 장수, 핫스팟 대역폭, `AiEdgeLink`의 최대 비트레이트 `:378`)를 따로 확인한다.
- 차이가 없으면 1080p를 유지한다.
- 같은 제품이라도 WebSocket(JPEG)과 WebRTC(VP8)의 점수는 다를 수 있다(규격 §6.3). 두 해상도가 같은 전송을 쓰므로 비교에는 영향이 없다.

**결과물**: 해상도별 확정률·오탐률 표와 해상도 결론. 결과는 작업일지에 남긴다.

### 6단계: 지연 실측 (서버 변경 없음)

촬영부터 HMD 표시까지의 지연이 0.3초(규격 추정의 상한) 이하인지 확인한다. 구간 3개로 나눠 잰다.

| 구간 | 재는 방법 |
|---|---|
| HMD → 폰 (MJPEG) | HMD 프레임의 `X-Captured-At`(HMD 시계)과 폰이 받은 시각의 차이에서 **PONG으로 구한 시계 차이**를 뺀다. 한 번은 밀리초 시계 앞에 HMD를 두고 시계와 M-07 화면을 한 사진에 찍어 같은 값이 나오는지 확인한다 |
| 폰 → 서버 → 폰 | 측정 모드(R11)에서 제품을 비춘 채, 폰이 서버로 보내는 영상을 검은 화면과 실제 영상으로 1초마다 바꾼다. 실제 영상으로 바꾼 순간부터 첫 `inspect`가 올 때까지를 잰다(검은 화면에는 `searching`이 온다). 20회 반복하고 중앙값과 90% 값을 쓴다. 같은 시간의 WebRTC 통계에서 프레임당 인코딩 시간(`totalEncodeTime`/`framesEncoded`)과 네트워크 왕복(`remote-inbound-rtp`의 `roundTripTime`)을 함께 기록하고, 나머지를 서버 처리 시간으로 본다 |
| 폰 → HMD (BLE) | PING/PONG 왕복 시간의 절반. 최근 8개 중 가장 짧은 왕복의 절반을 하한, 90% 값의 절반을 평소 값으로 기록한다 |

시계 차이 계산 (NTP 방식): PING을 보낸 폰 시각 t1, HMD가 받은 시각 t2, HMD가 PONG을 보낸 시각 t3, 폰이 받은 시각 t4.
- 왕복 = (t4 − t1) − (t3 − t2)
- 시계 차이(HMD − 폰) = ((t2 − t1) + (t3 − t4)) / 2
- 최근 8개 중 왕복이 가장 짧은 표본의 시계 차이를 쓴다.

| 파일 | 수정 |
|---|---|
| `H/protocol/Wire.kt` | `pong(seq, rx, tx)` = `{"type":"PONG","seq":n,"rx":t2,"tx":t3}`를 추가한다 |
| `H/hud/HudController.kt` | `Downlink.Ping`(`:46`)에서 받은 시각을 t2로 두고 `sendUp(Wire.pong(...))`으로 답한다. 측정 모드 표시(R11)를 띠(`overlay`)로 보인다 |
| `M/hmd/ClockSync.kt` (신규, 순수 함수) | PING별 t1을 기억하고 PONG을 받으면 왕복·시계 차이를 계산한다. 최근 8개 중 왕복 최소 표본을 고른다 |
| `M/hmd/GattBleLink.kt` | PING(`:93`) 직전 시각을 t1로 기록한다. `onUp`(`:276-282`)에서 `PONG`을 해석해 `onPong(seq, rx, tx, t4)`로 넘긴다. 기존 ACK의 `onAck(seq)`는 그대로 둔다 |
| `M/ai/LatencyProbe.kt` (신규, 순수 함수) | 구간별 표본을 모아 중앙값과 90% 값을 낸다 |
| `M/ai/AiFlow.kt` | 측정 모드 시작 때 진행 중 구간을 끝내고 `outOfService = true`로 둔다. 그동안 `inspect`·`searching`은 측정 표본으로만 쓰고 구간·진동·이력·LIVE는 처리하지 않는다(R11) |
| `M/ai/AiEdgeLink.kt` | 측정 모드: 1초마다 검은 I420 프레임과 실제 프레임을 바꿔 넣고, 바꾼 시각과 다음 `inspect` 도착 시각을 `LatencyProbe`에 넘긴다. 20회 뒤 저절로 끝난다. 측정 중 `logStats()`(`:225-234`)에서 `totalEncodeTime`, `framesEncoded`, `roundTripTime`도 읽는다 |
| `M/service/InspectionService.kt` | `GattBleLink`의 `onPong`을 `ClockSync`에 잇는다(`:90-96`). HMD 프레임 `capturedAt`과 폰 수신 시각을 `LatencyProbe`에 넘긴다. 측정 중에는 HMD에 OVERLAY "측정 중 — 판정 안 함"을 보낸다 |
| `H/MainActivity.kt` | `capturedAt`을 분석 콜백 시각(`:222`) 대신 센서 시각으로 보정한다: `image.imageInfo.timestamp`와 `SystemClock.elapsedRealtimeNanos()`의 차이만큼 뺀다. 그대로 두면 센서 → 콜백 사이 1~2프레임이 빠진다 |
| `M/ui/screen/M07LiveView.kt` | "지연 측정" 버튼, 측정 중 표시, 구간별 결과(중앙값·90% 값·합계, 인코딩·네트워크·서버 처리 나눔)를 표시한다 |
| `MT/hmd/ClockSyncTest.kt`, `MT/ai/LatencyProbeTest.kt` | 시계 차이 계산, 왕복 최소 표본 선택(지연이 튄 표본 무시), 중앙값·90% 값 |

측정 해석 주의:
- 검은 화면에서 실제 영상으로 바꾸는 순간 VP8이 큰 프레임을 만들거나 프레임을 버려, 평상시보다 지연이 크게 나올 수 있다. 결과표에 이 점을 함께 적는다.
- PONG 시계 차이는 오가는 BLE 지연이 같다고 가정한다. 왕복 최소 표본을 쓰면 이 오차가 가장 작다.
- 측정 시작·끝 시각과 스테이션을 작업일지에 남긴다. 측정 중 서버가 저장한 결함 프레임은 실제 검사 기록이 아니다.

**결과물**: 구간별 지연 표(작업일지에 기록)와 판정.
- 합계 90% 값이 0.3초 이하이면 결함 원 설정(`liveMarks`)을 켠 채 둔다. 7단계는 하지 않고, 서버 요청서도 보내지 않는다.
- 0.3초를 넘으면 `liveMarks`를 끈다(R7). 서버 요청서를 보내고 7단계로 간다.

### 7단계: 판정 프레임 작은 창 (조건부: 6단계에서 0.3초 초과일 때만)

서버 요청서(`rtp_timestamp`)를 보내고, 반영된 뒤 진행한다. 시작 전 실기기로 한 가지를 확인한다. 안드로이드 WebRTC가 앱이 넣은 프레임 시각(`AiEdgeLink.kt:338`의 `VideoFrame` 시각)을 RTP 타임스탬프에 `90 × ms + 고정값`으로 반영하는지다.
- 반영하면 아래대로 진행한다.
- 반영하지 않으면 이 단계는 하지 않는다. HMD는 판정 글자만 보이고, 위치 확인은 폰(4단계)으로 하며, 그 사실을 작업일지에 남긴다.

| 파일 | 수정 |
|---|---|
| `M/ai/AiEdgeLink.kt` | `offer()`가 지금 버리는 HMD `capturedAt`(HMD 시계, `:96`)을 JPEG와 함께 보관한다. `push()` 때 넣은 프레임 시각 → HMD `capturedAt`을 최근 3초분(약 30장) 기록한다 |
| `M/ai/FrameMatcher.kt` (신규, 순수 함수) | `inspect`마다 후보 고정값(= `rtp_timestamp` − 90 × 넣은 시각)을 계산해, 여러 메시지에 공통으로 나오는 값으로 맞춘다. 32비트 넘김을 처리하고, 재연결하면 다시 맞춘다. 맞춘 뒤에는 `rtp_timestamp` → HMD `capturedAt`으로 바꾼다 |
| `M/ai/AiFlow.kt`, `M/hmd/ShowWire.kt` | LIVE 쪽지 `t`에 HMD `capturedAt`을 넣는다. 이때는 `liveMarks`와 관계없이 `b`를 넣는다 |
| `H/MainActivity.kt` | `onFrame()`에서 만든 JPEG를 촬영 시각과 함께 최근 3초분 보관한다(1080p 약 30장, 수 MB) |
| `H/hud/HudController.kt`, `H/hud/LiveHold.kt` | 메시지마다 나이(현재 − `t`, 둘 다 HMD 시계)를 계산한다. 나이가 `liveMaxDelayMs` 이하이면 실시간 원을 그리고, 넘으면 숨긴다(R7). `t`가 있고 NG/REVIEW이면 그 프레임을 화면 한쪽 작은 창에 띄우고 결함 원을 정확한 위치에 그린다(1.5초 유지) |
| `MT/ai/FrameMatcherTest.kt` | 오프셋 맞추기(간격 흔들림, 프레임 건너뜀, 32비트 넘김, 재연결) |

**결과물**: HMD 작은 창의 결함 위치가 실제 결함과 일치하고, 오래된 실시간 원은 자동으로 숨겨진다.

### 8단계: 문서

- `01_문서/설계서.html` 9장(AI 서버 연동)을 새 흐름(§3 규칙, §4 흐름)으로 고친다.
- `작업일지.html`에 단계별 시험 결과, 5단계 해상도 결론, 6단계 지연 결론(측정 시각 포함), §6 현장 확인 결과를 남긴다.

---

## 6. 현장 확인 항목

| 항목 | 확인 방법 | 결과에 따른 조치 |
|---|---|---|
| 제품 구간 분리 (1초) | ① 제품 하나를 천천히 돌려 볼 때 이력이 1건인지 ② 제품을 연속으로 바꿔 들 때 이력 건수와 실제 제품 수 비교 | ①에서 쪼개지면 `segmentEndMs`를 늘린다(1.0 → 1.5초). ②에서 합쳐지면 줄이거나, 작업 안내("제품을 내린 뒤 다음 제품")로 먼저 해결한다 |
| 결함 확정 기준 (5장 중 2장) | 5단계 시험 데이터(R3 적용 결과)로 결함 제품 확정률과 정상 제품 오확정률 확인 | 오확정이 많으면 2장 → 3장, 놓침이 많으면 창 크기 5 → 3으로 조정(`Profile`) |
| 결함 원 지연 기준 (0.3초) | 6단계 실측값과, 제품을 돌릴 때 원이 결함에서 얼마나 벗어나 보이는지 작업자 확인 | 0.3초 이하라도 원이 실제로 많이 벗어나 보이면 `liveMarks`를 끄고 7단계로 간다 |
| 재연결 (R10) | 서버 재시작, Wi-Fi 끊었다 붙이기, 사무실 밖에서 모의 서버로 넘어가기 | 시도 마감 10초 안에 붙지 않는 경우가 잦으면 마감을 늘린다(최대 20초, gRPC 기준값) |
| BLE LIVE 전송량 | HMD 상태줄의 LIVE 수신 간격, SHOW ACK 실패 로그, 결함이 많이 잡히는 제품에서의 PONG 왕복 | SHOW가 밀리거나 PONG 왕복이 늘면 LIVE를 판정·상자가 바뀔 때만 보낸다 |

## 7. 이번에 하지 않는 것

- 트레이·제스처·확인 타이머 규칙(R08~R12)과 HMD H-02~H-08 화면 코드 삭제: 호출만 끊는다. 서버가 사람 판정 절차를 다시 정하면 그때 정리한다.
- 모의 엣지(WebSocket `StreamRelay`) 경로: 새 필드에 기본값을 줘 빌드만 유지한다.
- 로컬 DB 마이그레이션: 개발 중이라 스키마가 바뀌면 지우고 다시 만든다. 실사용 전에 `exportSchema = true`, 명시적 `Migration`, 마이그레이션 시험으로 바꾼다.
- 기기 쪽 물체 추적, 영역별 압축 (§2 이유)
- 실행 중 지연 자동 비교: 메시지 시각이 생기는 7단계 전까지 하지 않는다 (R7)
- 실시간 경로 4K 전송: 5단계에서 4K가 필요하다고 나올 때만 별도 작업으로 한다

## 8. 순서와 완료 기준

```
1단계 ─▶ 2단계 ─┬─▶ 3단계 ─▶ 6단계 ─(0.3초 초과일 때만: 서버 요청 → 반영)─▶ 7단계
               ├─▶ 4단계
               └─▶ 5단계 (HMD --es res 2160 + PC 스크립트, 2단계와 별개로 먼저 해도 됨)
8단계: 각 단계 끝날 때마다
```

서버 변경이 필요한 곳은 7단계뿐이고, 7단계는 6단계 결과에 따라 하지 않을 수 있다. 1~6단계는 지금 서버 그대로 진행한다. 5단계는 HMD 해상도 선택지와 PC 스크립트만 있으면 되므로 앱의 다른 단계와 독립이다.

| 단계 | 단위 시험 | 실기기 확인 |
|---|---|---|
| 1 | 규격 예시 해석, 재연결(간격, 시도 중 새 시도 없음, 10초 마감, 연결 전 실패, 설정 오류 중지) | 실서버 `inspect` 수신, 서버 재시작 때 1·2·4·5초 재시도 후 한 번에 연결 |
| 2 | 확정 규칙, 진동 1회, 구간 끝(1초·끊김, 한 장 놓침은 같은 구간), 원판정·결함 종류·서버 키 기록, 엔진 경유 저장, 새 칸 저장·읽기 | 제품 1개 = 이력 1건 + 원판정·결함 종류·결함 프레임·서버 연결, 진동 1회 |
| 3 | LIVE 480B, 상자 합치기, OK 미전송, 표시 중일 때만 `d:null`, 좌표 변환, `LiveHold`(0.5초 유지·즉시 지움·LIVE > HINT > 기본값·대기 화면 제외) | HMD 판정 글자·결함 원, 대기 화면에 덧그림 없음, 평상시 BLE 전송 없음 |
| 4 | 확대 영역 계산, 서버 키 → 주소 | M-07 확대 보기, M-05 원판정 배지·이력 다시 보기, 핀치 확대·이동 |
| 5 | — | 같은 프레임으로 짝지은 해상도별 확정률·오탐률 표와 결론 |
| 6 | 시계 차이·왕복 최소 표본, 구간별 지연 집계(중앙값·90% 값) | 구간별 지연 표(인코딩·네트워크·서버 처리 나눔), 시계 사진 검증, 측정 중 이력·진동 없음, `liveMarks` 결정 |
| 7 (조건부) | 오프셋 맞추기 | HMD 작은 창 결함 위치 일치, 오래된 원 자동 숨김 |

## 참고 자료 (개정 2에서 추가)

- gRPC Connection Backoff Protocol: https://chromium.googlesource.com/external/github.com/grpc/grpc.git/+show/HEAD/doc/connection-backoff.md
- NTP Clock Filter Algorithm (D. Mills): https://www.eecis.udel.edu/~mills/ntp/html/filter.html
- ISA-18.2 경보 관리 해설 (shelving, suppressed by design, out of service): https://www.processonline.com.au/content/software-it/article/improving-alarm-management-with-isa-18-2-part-2-1141142642
- OPC UA Part 9 경보 상태 (out of service도 기능은 유지): https://reference.opcfoundation.org/Core/Part9/4.8
- WinCC OA 경보 보류·억제 (점검 중 경보를 따로 표시하고 KPI에서 제외): https://www.winccoa.com/documentation/WinCCOA/latest/en_US/alarmshelving/topics/alarm_shelving_overview.html
- 시험 트래픽 제외 (Piano Analytics exclusions): https://analytics-docs.piano.io/en/analytics/v1/exclusions
- Glass-to-glass 지연: https://bloggeek.me/webrtcglossary/glass-to-glass/
- WebRTC 지연 구성 분석: https://discourse.openrobotics.org/t/where-does-latency-in-webrtc-video-streaming-come-from-an-analysis/54566
- WebRTC 지표(totalEncodeTime 등): https://walterfan.github.io/webrtc_note/5.qos/webrtc_metrics.html
- VP8 인코딩 속도 (IETF draft, 데스크톱 720p): https://datatracker.ietf.org/doc/html/draft-alvestrand-rtcweb-vp8-01
- SAHI 슬라이스 추론: https://obss.github.io/sahi/guides/sliced-inference/ , https://arxiv.org/pdf/2202.06934v2
- AI 비전 검사 기록·추적성: https://ifactoryapp.com/ai-vision-camera/ai-vision-inspection-audit-trail-traceability
- UI 상태 모델링: https://proandroiddev.com/modelling-ui-state-on-android-26314a5975b9
- Room `RoomDatabase.Builder` (fallbackToDestructiveMigration): https://developer.android.com/reference/androidx/room/RoomDatabase.Builder
