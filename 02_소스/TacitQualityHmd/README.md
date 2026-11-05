# TacitQualityHmd — HMD 앱 (RealWear Navigator 520)

품질검사 지원 시스템의 **HMD 앱** — 카메라 영상 송출 · 폰이 BLE 로 보낸 화면 표시. 안드로이드/Kotlin + CameraX.
2026-11-05 기준 (AI 서버 2026-11-03 규격 — 프레임 단위 판정 대응).

## 하는 일

| 기능 | 내용 |
|---|---|
| 영상 송출 | CameraX(Preview + ImageAnalysis) → JPEG → **MJPEG** `http://<HMD-IP>:8080` (`MjpegServer.kt`, `YuvToJpeg.kt`) |
| 화각 | 분석 영상 **4:3(1920×1440 목표, `--es res 720` → 960×720)**, 미리보기 `FIT_CENTER`(좌우 검은 띠), 줌 최소 — 기본 카메라 앱과 같은 화각. 16:9 는 4:3 센서 위아래를 잘라 약 1.33배 확대돼 보였다 |
| 화면 지시 | BLE `SHOW` → HUD(`hud/Screens.kt`) · 순번 검사(`hud/ShowGate.kt`) · ACK. 대기 화면은 화면 ID `IDLE`, 조준 화면 `H-01` |
| 진행 안내 | BLE `HINT` → 조준 화면 가운데 글자 (제품을 비춰 주세요 · 검사 중) |
| 실시간 결함 | BLE `LIVE` `{"cmd":"LIVE","d":"NG","l":"칩찍힘","b":[[x1,y1,x2,y2]]}` → 판정 글자(✕ 불량 빨강 · ? 애매 노랑) + 결함 원(`hud/MarkerView.kt`). 마지막 결함 뒤 0.5초 유지, `d:null`·BLE 끊김이면 즉시 지움(`hud/LiveHold.kt`). 큰 글자 우선순위 LIVE > HINT > 기본 |
| HELLO | 폰이 UP 특성을 읽으면 영상 주소와 **카메라 상태**(정상 · 꺼짐 — 앱이 화면 뒤 · 카메라 열기 대기 · 시작 실패 등)를 알려 준다 — 폰 통신 로그에 남음. 같은 상태가 HMD 상태줄에도 `⚠ 카메라 …` |
| 3초 무소식 | 폰이 끊기면 H-09(수동 판정)를 스스로 띄운다 |

폰 앱의 M-07 은 이 HUD 를 똑같이 따라 그린다(모바일 `hmd/HudMirror.kt`). **`hud/Screens.kt`(idle·aim)·`hud/LiveHold.kt` 를 바꾸면 모바일 HudMirror 도 같이 고친다.**

## BLE 주소

HMD = GATT 서버(광고), 모바일 = 클라이언트. 모바일 `hmd/GattBleLink.kt` 와 같은 값.

| 이름 | UUID | 용도 |
|---|---|---|
| SERVICE | `9e7a1000-3c5b-4f0e-8d21-5a7c0d4e6b01` | 광고에 포함 |
| DOWN | `9e7a1001-…-5a7c0d4e6b01` | 모바일→HMD SHOW·PING·HINT·LIVE (write / write-no-response) |
| UP | `9e7a1002-…-5a7c0d4e6b01` | HMD→모바일 ACK (notify) · HELLO (read) |

## 빌드 · 설치 (HMD는 Play 스토어 없음 → adb)
```bash
./gradlew.bat assembleDebug
./gradlew.bat testDebugUnitTest
adb -s <HMD> install -r app/build/outputs/apk/debug/TacitQHmd-*.apk
adb -s <HMD> shell pm grant kr.co.snail.tacitq.hmd.debug android.permission.CAMERA
adb -s <HMD> shell appops set kr.co.snail.tacitq.hmd.debug SYSTEM_ALERT_WINDOW allow
adb -s <HMD> shell am start -n kr.co.snail.tacitq.hmd.debug/kr.co.snail.tacitq.hmd.MainActivity
```
- **카메라 권한이 없으면** 폰은 영상 서버에 붙지만 프레임이 0장이다(2026-11-05 실제 원인). 폰 통신 로그 HMD 탭에 카메라 상태가 나온다.
- **"다른 앱 위에 표시"** 허용이 있어야 폰이 검사를 시작할 때 이 앱을 앞으로 불러온다(카메라는 앱이 앞에 있을 때만 돈다).
- 디버그(`kr.co.snail.tacitq.hmd.debug`)·릴리스(`kr.co.snail.tacitq.hmd`)를 같이 깔지 않는다 — BLE 광고·카메라를 다툰다.
  `adb shell pm list packages tacitq` 로 확인, `adb uninstall <패키지>` 로 지운다.

## ⚠️ RealWear 520 카메라 주의
구형 카메라 HAL 이라 CameraX 가 까다롭다:
- **ImageAnalysis 단독 바인딩 시 카메라가 안 열린다.** Preview 를 반드시 함께 바인딩.
- Preview 는 **TextureView(COMPATIBLE) 모드**로. (기본 SurfaceView 는 표면 생성이 안 돼 카메라 미시작.)
- **화면이 실제 렌더링(디스플레이 깨어남)돼야** 카메라가 열린다. 앱이 뒤로 가면 카메라를 놓고, 앞으로 오면 다시 묶는다.

## BLE 없이 화면 확인 (디버그 빌드)
```powershell
$j = '{"cmd":"LIVE","d":"NG","l":"칩찍힘","b":[[480,480,520,520]]}'
$b = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($j))
adb -s <HMD> shell am broadcast -a kr.co.snail.tacitq.hmd.SHOW --es b64 $b
```
(SHOW 도 같은 방법. 조준 화면에서만 LIVE 가 그려진다 — 먼저 `{"cmd":"SHOW","screen":"H-01","seq":1,"fields":{"part_no":"P"}}`)

> ㈜스네일 · 대외비 — 외부 공개 금지
