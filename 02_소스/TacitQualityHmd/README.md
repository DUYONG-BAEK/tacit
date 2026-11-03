# TacitQualityHmd — HMD 앱 (RealWear Navigator 520)

품질검사 지원 시스템의 **① HMD 앱** (설계서 기준: 촬영 · 영상 송출 · 화면 렌더 · 제스처).
설계상 "타 팀" 몫이었으나 담당자가 없어 모바일 팀이 함께 개발한다. 안드로이드/Kotlin + CameraX.

## 1단계 — 카메라 → 폰 영상 송출 ✅
HMD 카메라를 잡아 **MJPEG** 로 송출한다. 폰 브라우저가 `http://<HMD-IP>:8080` 을 열면 실시간 영상이 보인다.
(설계의 H.265 저지연은 후속 단계. MJPEG 는 브라우저로 바로 보여 1단계 검증에 적합.)

- `MainActivity.kt` — CameraX(Preview+ImageAnalysis) 기동, 프레임→JPEG, 서버 기동, 접속 주소 표시
- `MjpegServer.kt` — 외부 라이브러리 없는 `multipart/x-mixed-replace` MJPEG 서버
- `YuvToJpeg.kt` — YUV_420_888 → NV21 → JPEG

## 빌드 · 설치 (HMD는 Play 스토어 없음 → adb)
```bash
./gradlew.bat assembleDebug
adb -s <HMD> install -r app/build/outputs/apk/debug/TacitQHmd-*.apk
adb -s <HMD> shell pm grant kr.co.snail.tacitq.hmd.debug android.permission.CAMERA
adb -s <HMD> shell am start -n kr.co.snail.tacitq.hmd.debug/kr.co.snail.tacitq.hmd.MainActivity
```

## ⚠️ RealWear 520 카메라 주의 (중요)
구형 카메라 HAL 이라 CameraX 가 까다롭다:
- **ImageAnalysis 단독 바인딩 시 카메라가 안 열린다.** Preview 를 반드시 함께 바인딩.
- Preview 는 **TextureView(COMPATIBLE) 모드**로. (기본 SurfaceView 는 표면 생성이 안 돼 카메라 미시작.)
- **화면이 실제 렌더링(디스플레이 깨어남)돼야** 카메라가 열린다.

## 2단계 — BLE 로 SHOW 쪽지 수신 → HUD 화면 (코드 완료 · 실장비 검증 대기)
HMD = **GATT 서버(광고)**, 모바일 = 클라이언트. UUID 는 설계서에 없어 여기서 정의 — 모바일 C1 도 같은 값을 쓴다.

| 이름 | UUID | 용도 |
|---|---|---|
| SERVICE | `9e7a1000-3c5b-4f0e-8d21-5a7c0d4e6b01` | 광고에 포함 |
| DOWN | `9e7a1001-…-5a7c0d4e6b01` | 모바일→HMD SHOW·PING (write) |
| UP | `9e7a1002-…-5a7c0d4e6b01` | HMD→모바일 ACK (notify) |

- `protocol/Wire.kt` — `ShowWire`(모바일과 동일) · `AckWire {"type":"ACK","seq","rendered_at"}` · `BleUuids`
- `hud/ShowGate.kt` — 순번(작으면 무시, 같으면 ACK 만, 새 연결이면 초기화) · 3초 무소식 판정
- `hud/Screens.kt` — H-01~H-09 → 공통 HUD 틀(색·문구는 08_HMD_프로토타입 기준)
- `hud/HudController.kt` — 쪽지 → 화면 · ACK · ttl 소거 · 3초 무소식 시 H-09 자체 표시
- `link/BleServer.kt` — GATT 서버 + 광고, 긴 쓰기(prepared write) 조립, 끊기면 재광고
- 단위 시험: `./gradlew.bat testDebugUnitTest` (8건)

### BLE 없이 화면 확인 (디버그 빌드)
```powershell
$j = '{"cmd":"SHOW","screen":"H-03","seq":1,"fields":{"part_id":"A_0249","severity":"CRITICAL","evidence":"찍힘 · 2면 좌상단"}}'
$b = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($j))
adb -s <HMD> shell am broadcast -a kr.co.snail.tacitq.hmd.SHOW --es b64 $b
```
### 실제 BLE 확인
폰에 **nRF Connect** → `9e7a1000…` 기기 연결 → MTU 512 요청 → UP notify 켜기 → DOWN 에 SHOW JSON(텍스트) 쓰기 → HMD 화면 + UP 으로 ACK 수신.

## 다음 단계
- 2단계 실장비 검증 (위 nRF Connect 절차) · 모바일 실제 `BleLink`(C1) 구현
- 3단계: 제스처(끄덕/젓기)·HEARTBEAT·PING·OVERLAY·로컬 링버퍼
- 영상: H.265 저지연 전환

> ㈜스네일 · 대외비 — 외부 공개 금지
