# 품질검사 지원 APP — 모바일 모듈

> **대외비 — 외부 공개 금지**
> 제조암묵지기반 AI모델개발사업 (RS-2026-25590666) · ㈜스네일 · 2026.09.14

`10_모바일_프로토타입.html`의 화면 6종(M-01 ~ M-06)을 Kotlin + Jetpack Compose로 구현한
안드로이드 앱 소스입니다. 데이터 모델과 코드 체계는 **05 v1.2 / 06 v1.2** 엑셀과 1:1로 맞췄습니다.

## 설계 전제

검사 루프(P1) 동안 이 앱의 화면은 **열리지 않습니다.** 작업자가 모바일을 꺼내는 시점은
P0(교대 준비) · P3(설비 조치) · P5(수거·재고) · 조회 네 번뿐이고, 그 사이 단말은
주머니 안에서 `RelayService`로만 동작합니다. 이 규칙이 깨지면
"모바일을 계속 보고 있어야 하는 것 아닌가"라는 원래 문제로 돌아갑니다.

## 구조

```
app/src/main/java/kr/co/snail/tacitq/mobile/
├── MainActivity.kt              진입점
├── TacitQApp.kt                 알림 채널
├── model/
│   ├── Codes.kt                 ★ 코드 정의서 06 v1.2와 1:1 (enum 38종)
│   └── Entities.kt              ★ 테이블 레이아웃 05 v1.2와 1:1 (11개 테이블)
├── data/
│   ├── SampleData.kt            화면 검증용 시드 (엑셀 예시 데이터와 같은 규칙)
│   └── QualityRepository.kt     도메인 동작 + 감사 로그 기록
├── vm/AppViewModel.kt           화면 전환 · 토스트 · 중계 상태
├── ui/
│   ├── AppShell.kt              상단 바 · 본문 · 하단 탭 4개
│   ├── theme/Theme.kt           프로토타입 색 토큰
│   ├── components/Widgets.kt    Field · Badge · CodeRadioGroup · NoteBox
│   └── screen/
│       ├── M01ShiftPrep.kt          교대 준비 (P0) — 점검 6항목
│       ├── M02EquipAction.kt        설비 조치 등록 (P3) — 파라미터 전/후
│       ├── M03DefectRegister.kt     수거·등록 (P5) — 실물 대조
│       ├── M04DefectInventory.kt    재고·폐기 (P5) — 승인·연장
│       ├── M05InspectionHistory.kt  이력 조회 — 통합 타임라인
│       └── M06AdminDashboard.kt     관리자 대시보드 — 프레임 지표
└── relay/
    ├── RelayService.kt          HMD ↔ Edge 중계 + 진동 알림
    └── DualQueue.kt             이중 오프라인 큐
```

## 프로토타입 대비 반영한 것 (v1.2 정합)

| 항목 | 프로토타입 | 이 소스 |
|---|---|---|
| M-01 점검 6항목 | 표시만 | `TB_SHIFT_CHECK`로 저장 · 재점검 · FAIL 시 시작 잠금 |
| M-02 조치 선택지 | 4지 | `ActionTaken.active()` 5지 (기타 포함, 메모 필수) |
| M-02 추정 원인 | "금형 면 손상" 문구 | `Cause.MOLD_DAMAGE` 코드로 정식화 |
| M-03 "불일치" | 입력란 없음 | 확정 유형·심각도 라디오가 열림 |
| M-04 "연장" | 동작 없음 | `STORE_EXTEND` 감사 로그 + 연장 횟수 기록 |
| M-04 권한 | 구분 없음 | `canDispose` 검사 · 미달 시 DENIED 로그 |
| M-05 "내보내기" | 동작 없음 | `AuditAction.EXPORT` 기록 (작업자 계정은 거부됨) |
| 트레이 ID | `T-0913-A` | 세트 ID + 개체 ID(`-G`/`-D`) 병행 |
| 드롭률 | 38% | 수신 프레임 기준 실제 계산 (설계 전제 약 90%) |

## 빌드

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/TacitQMobile-0.1.0-debug\(1\)-debug.apk
adb logcat -s TacitQ.Relay:V TacitQ.Queue:V
```

릴리즈 빌드 전에 `keystore.properties.sample` → `keystore.properties` 복사 후 키스토어를
생성하고, **반드시 실기기에서 릴리즈 빌드를 1회 구동**해 R8 난독화 문제를 확인하세요.

## 아직 비어 있는 것

| 위치 | 내용 |
|---|---|
| `RelayService.pump()` | HMD 소켓(Wi-Fi P2P / USB RNDIS)과 Edge 소켓 연결 |
| `DualQueue.hmdPending` | BLE 상태 채널로 HMD 큐 잔량 수신 |
| `M03` 대표 프레임 | `frameRef` 이미지 로드 + bbox 오버레이 |
| 교대 마감 화면 | 설계서 P4.4가 요구하는 두 큐 확인 화면 (6종에 없음 — 신설 검토 필요) |
| 인증 | 로그인 세션 · 역할 기반 접근 제어 |

## 주의

이 소스는 컴파일 검증을 거치지 않았습니다. 의존성 버전과 Compose API 시그니처는
첫 `assembleDebug`에서 확인이 필요합니다.
