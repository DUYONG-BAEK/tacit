# 품질검사 지원 APP — 모바일 모듈

> **대외비 — 외부 공개 금지**
> 제조암묵지기반 AI모델개발사업 (RS-2026-25590666) · ㈜스네일 · 2026-11-05 기준

HMD(RealWear Navigator 520) 영상을 받아 AI 판정 서버로 보내고, 서버가 프레임마다 돌려주는 판정을
HMD(BLE)와 폰 화면에 실시간으로 표시하며 제품 단위로 기록하는 안드로이드 앱이다.
Kotlin · Jetpack Compose · Room · WebRTC. 설계·작업 기록은 `doc/`(설계서 9장, 작업일지 마지막 항목).

## 동작 요약 (AI 서버 2026-11-03 규격 — 프레임 단위 판정)

```
HMD ─MJPEG─▶ HmdVideoSource ─▶ AiEdgeLink ─WebRTC─▶ AI 서버(:8080, ST-02)
                                     ◀─ 데이터 채널: searching · inspect(프레임 판정·제품·결함 상자·result_id)
AiFlow ─┬─ 결함 프레임 → BLE LIVE → HMD 판정 글자·결함 원 (0.5초 유지)
        ├─ 결함 확정(최근 5장 중 2장) → 진동 3회(제품당 1회)
        ├─ 제품 구간 끝(1초 무소식·끊김) → 상태기계 → 이력 1건
        └─ M-07: HMD 와 같은 화면 · 최근 결함 확대 / M-05: 결함 프레임 다시 보기
```

| 규칙 | 값 (위치) |
|---|---|
| 판정 임곗값 | PD·QI 임곗값은 서버가 정한다. 앱은 서버 판정(`decision`)을 그대로 쓰고 `threshold` 를 하드코딩하지 않는다 |
| 결함 확정 · 구간 끝 · 표시 유지 | 5장 중 2장 · 1초 · 0.5초 (`Profile.confirmWindow·confirmHits·segmentEndMs·markHoldMs`) |
| 재연결 | 1 → 2 → 4 → 5초, 시도 마감 10초 (`ai/Reconnect.kt`) |
| 판정 | OK 합격 · NG·REVIEW 불합격(원판정은 이력에 따로) |

## 구조

```
app/src/main/java/kr/co/snail/tacitq/mobile/
├── ai/                          AI 서버 연동
│   ├── AiMessages.kt            규격 메시지 해석 (searching · inspect · GET /results)
│   ├── AiFlow.kt                제품 구간 · 결함 확정 · LIVE · 진동 · 구간 판정 (순수)
│   ├── Reconnect.kt             재연결 정책 (순수)
│   ├── AiEdgeLink.kt            WebRTC 연결 · 영상 넣기 · 통신 로그
│   ├── AiResultsApi.kt          POST /offer · GET /results/{id} · 오버레이
│   └── DefectCrop.kt · DefectLoader.kt   결함 확대 영역 · 이미지 불러오기
├── core/                        상태기계 (안드로이드 모름) — Event · Effect · Records · Screens · State
├── hmd/
│   ├── GattBleLink.kt           BLE 클라이언트 (SHOW·PING·HINT·LIVE, HELLO 읽기)
│   ├── HmdRenderer.kt           SHOW 순번 · ACK 200ms
│   ├── ShowWire.kt              SHOW · HINT · LIVE 쪽지 규격
│   └── HudMirror.kt             M-07 HMD 화면 따라 그리기 (HMD Screens·LiveHold 와 같은 규칙)
├── relay/                       HMD 영상 수신(HmdVideoSource·MjpegReader) · 개발 설정(DevSettings) · 모의 엣지(StreamRelay)
├── service/                     InspectionService(포그라운드 서비스, 부품 연결) · InspectionEngine(이벤트 한 줄 세우기)
├── store/                       Room v2 — 검사 이력 · 전송 큐
├── log/CommLog.kt               통신 로그 (모바일 ↔ AI 서버 / 모바일 ↔ HMD)
├── ui/
│   ├── AppShell.kt              상단 바("로그" 버튼) · 본문 · 하단 탭
│   └── screen/                  M-01 검사 준비 · M-07 영상 · M-05 이력 · DefectViewer · CommLogView · M-02~M-06
├── model/ · data/               코드 정의 · 화면 예시 데이터
└── vm/AppViewModel.kt
```

## 화면

| 화면 | 내용 |
|---|---|
| M-01 검사 | AI 서버 주소 · 스테이션 · HMD 결함 원 켜기/끄기 · 검사 시작/마감 |
| M-07 영상 | HMD 영상 + HMD 와 같은 글자·결함 원 · 최근 결함 프레임 확대 · 연결 상태 · 마지막 구간 판정 |
| M-05 이력 | 제품 구간 1건씩 · 합격 / 불합격(NG) / 불합격(REVIEW) · 결함 종류 · 결함 이미지 다시 보기 |
| 로그(상단 바) | 통신 로그 — 연결 과정 · 5초 요약 · HMD 카메라 상태, 복사·지우기 |

## 빌드 · 시험

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
adb logcat -s TacitQ.AiEdge:V TacitQ.BLE:V TacitQ.HmdVideo:V TacitQ.Service:V
```

- 최신 코드 받기·사본 폴더 덮어쓰기는 저장소 맨 위 `README.txt`의 [Git] 참고.
- DB 는 개발 중이라 스키마가 바뀌면 이력·전송 큐를 지우고 다시 만든다(`fallbackToDestructiveMigration`). 실사용 전 명시적 마이그레이션으로 바꾼다.
- 릴리즈 빌드 전에 `keystore.properties.sample` → `keystore.properties` 복사 후 키스토어를 만들고,
  실기기에서 릴리즈 빌드를 1회 구동해 R8 난독화 문제를 확인한다.

## 아직 하지 않은 것

| 항목 | 내용 |
|---|---|
| 해상도별 검출률 비교 | 계획서 5단계 — 같은 4K 프레임을 1080p 로 줄여 서버에 보내 비교 |
| 지연 실측 | 계획서 6단계 — HMD → 폰 → 서버 → 폰 → HMD 구간별(0.3초 기준으로 결함 원 유지 여부 결정) |
| 사람 판정 절차 | 서버 규격에서 없어짐 — 트레이·제스처·확인 타이머 코드는 호출만 끊어 둠 |
| 인증 | 로그인 세션 · 역할 기반 접근 제어 |
