TacitQ 품질검사 지원 시스템 소스 (2026-11-05 기준)
(주)스네일, 대외비

[구성]
HMD(RealWear Navigator 520) 카메라 영상을 모바일(Android)이 받아 AI 판정 서버로 보내고,
서버가 프레임마다 돌려주는 판정을 HMD 화면(BLE)과 폰 화면에 실시간으로 표시하고 제품 단위로 기록합니다.
 - HMD에서 모바일: MJPEG 4:3 1920x1440 (HTTP, 폰 핫스팟). 화각은 HMD 기본 카메라 앱과 같게(줌 최소)
 - 모바일에서 AI 서버: WebRTC 영상 + 데이터 채널(JSON), 개발 서버 192.168.1.11:8080, 스테이션 ST-02
   서버 규격: 「제품 불량 판정 — 모바일 앱 연동 API」 2026-11-03 (프레임 단위 판정: searching / inspect)
 - 모바일에서 HMD: BLE (화면 지시 SHOW, 진행 안내 HINT, 실시간 결함 LIVE, HMD 영상 주소·카메라 상태 HELLO)
 - 판정: OK는 합격, NG와 REVIEW는 불합격. 제품을 비추는 동안 결함이 5장 중 2장 이상 잡히면 확정(진동 1회),
   제품을 내리고 1초 지나면 이력 1건
 - PD(제품 탐지)·QI(불량 탐지) 임곗값은 AI 서버가 정합니다. 앱은 서버 판정을 그대로 씁니다
   (바꾸려면 서버 관리자에게 요청)

[폴더]
01_문서
 - 설계서.html: 9장이 AI 서버(WebRTC, 프레임 단위 판정) 연동
 - 작업일지.html: 단계별 작업과 시험 결과 (마지막 항목: 2026-11-03 규격 대응)
 - TacitQ_*.pptx: 10월 현황 자료 (11-03 규격 변경 전 내용)
   두 html 은 02_소스/TacitQualityMobile/doc 의 build.py 로 만든 사본입니다 (sections/*.html 을 고친 뒤 build)

02_소스
 - TacitQualityMobile: 모바일 앱 (Kotlin, Jetpack Compose, Room, WebRTC)
     AI 서버 연동: app/src/main/java/kr/co/snail/tacitq/mobile/ai/
     검사 서비스: service/InspectionService.kt, 상태 기계: core/StateMachine.kt, 통신 로그: log/CommLog.kt
 - TacitQualityHmd: HMD 앱 (Kotlin, CameraX, BLE GATT 서버)
     화면: hud/, BLE: link/BleServer.kt, 카메라: MainActivity.kt

[Git — 최신 코드 받기 (Windows PowerShell)]
작업 브랜치는 claude/zealous-dirac-xntfgb 입니다. main 에는 올리지 않습니다.

1. 브랜치 바꾸기 (처음 한 번만)
   cd C:\dev\tacit
   git fetch origin
   git switch claude/zealous-dirac-xntfgb
   (git switch 가 없는 옛 Git 이면: git checkout claude/zealous-dirac-xntfgb)
   사본 폴더 만들기 (Android Studio 로 여는 폴더 — 아직 없을 때만)
   Copy-Item -Recurse "C:\dev\tacit\02_소스\TacitQualityMobile" C:\dev\TacitQualityMobile
   Copy-Item -Recurse "C:\dev\tacit\02_소스\TacitQualityHmd" C:\dev\TacitQualityHmd
   - 사본 폴더가 이미 있으면 Copy-Item 은 그 안에 한 겹 더 만듭니다(C:\dev\TacitQualityMobile\TacitQualityMobile).
     이미 있으면 이 단계는 건너뛰고 3번 robocopy 로 덮어쓰세요.

2. 최신 코드 받기 (매번)
   cd C:\dev\tacit
   git pull
   - "Fast-forward"와 바뀐 파일 목록이 나오면 성공입니다.
   - "Already up to date."가 나오면 받을 것이 없다는 뜻입니다. push 가 됐는지, 브랜치가 맞는지 확인하세요:
     git branch --show-current      (claude/zealous-dirac-xntfgb 가 나와야 함)
     git log --oneline -1           (가장 최근 커밋 한 줄)
   - 브랜치를 직접 지정해 받을 때: git pull origin claude/zealous-dirac-xntfgb

3. 사본 폴더에 덮어쓰기 (Android Studio 로 여는 폴더)
   robocopy "C:\dev\tacit\02_소스\TacitQualityMobile" C:\dev\TacitQualityMobile /MIR /XD build .gradle .idea /XF local.properties keystore.properties
   robocopy "C:\dev\tacit\02_소스\TacitQualityHmd" C:\dev\TacitQualityHmd /MIR /XD build .gradle .idea /XF local.properties keystore.properties
   - /MIR 는 사본을 원본과 똑같이 맞춥니다(사본에만 있는 파일은 지워짐). 사본에서 고친 것은 먼저 원본으로 옮기세요.
   - /XD /XF 로 빌드 결과·IDE 설정·로컬 설정 파일은 그대로 둡니다.
   - robocopy 는 종료 코드 0~7 이 정상입니다(1 = 파일을 복사함).

4. Android Studio 에서 두 앱을 다시 빌드·설치합니다 (모바일·HMD 둘 다).

[빌드]
 - Android Studio로 TacitQualityMobile, TacitQualityHmd 폴더를 각각 열어 빌드합니다.
 - 릴리스 서명 키는 포함하지 않았습니다 (keystore.properties.sample 참고).
 - HMD 에는 TacitQ HMD 앱을 하나만 둡니다. 디버그(kr.co.snail.tacitq.hmd.debug)와 릴리스(kr.co.snail.tacitq.hmd)가
   같이 깔리면 BLE 광고·카메라를 서로 다툽니다.
     adb shell pm list packages tacitq
     adb uninstall kr.co.snail.tacitq.hmd

[사용 순서]
 1. HMD 전원을 켭니다 (품질검사 앱이 뒤에서 자동 대기).
 2. 폰 핫스팟을 켜고, 폰은 사무실 Wi-Fi 에도 연결합니다.
 3. 폰 앱 검사 메뉴에서 검사 시작을 누릅니다.
    HMD 앱이 자동으로 화면에 나타나고, 폰은 영상 메뉴(M-07)로 이동합니다.
    M-07 에는 HMD 와 같은 화면(판정 글자·결함 원)이 보입니다.
 - 새 HMD 는 처음 1회 권한이 필요합니다 (앱을 새로 깔았을 때도):
   adb shell pm grant kr.co.snail.tacitq.hmd.debug android.permission.CAMERA
   adb shell appops set kr.co.snail.tacitq.hmd.debug SYSTEM_ALERT_WINDOW allow
   (카메라 권한이 없으면 폰은 HMD 영상 서버에 붙지만 프레임이 0장입니다 — 2026-11-05 실제 원인)

[연결이 안 될 때 — 통신 로그]
 - 폰 앱 상단 바 오른쪽 "로그" 버튼 → 모바일 ↔ AI 서버 / 모바일 ↔ HMD 탭
 - 5초마다 "HMD 영상 n장 받음 · 서버로 n장 보냄 · 서버 메시지 수"가 남고, HMD 는 카메라 상태
   (정상 / 꺼짐 — 앱이 화면 뒤 / 카메라 열기 대기 / 시작 실패 등)를 알려 줍니다.
 - "복사" 버튼으로 로그를 복사해 전달할 수 있습니다.
