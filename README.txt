TacitQ 품질검사 지원 시스템 최종 소스 (2026-10-30 기준)
(주)스네일, 대외비

[구성]
HMD(RealWear Navigator 520) 카메라 영상을 모바일(Android)이 받아 AI 판정 서버로 보내고,
AI 판정 결과를 BLE로 HMD 화면에 표시합니다.
 - HMD에서 모바일: MJPEG 1920x1080 (HTTP, 폰 핫스팟)
 - 모바일에서 AI 서버: WebRTC 영상 + 데이터 채널(JSON), 사무실 AI 서버 192.168.1.11:8080, 스테이션 ST-02
 - 모바일에서 HMD: BLE (화면 지시, 진행 안내, HMD 영상 주소 자동 수신)
 - 판정 표시: OK는 합격, NG와 REVIEW는 불합격

[폴더]
01_문서
 - 설계서.html: 9장이 AI 서버(WebRTC) 연동
 - 작업일지.html: 단계별 작업과 시험 결과
 - TacitQ_개발현황.pptx: 전체 구조, 처리 흐름
 - TacitQ_AI서버연동_현황.pptx: AI 서버 연동 결과 (10/10)
 - TacitQ_연결자동화_현황.pptx: 연결 자동화, HMD 화면 개선 (10/18)

02_소스
 - TacitQualityMobile: 모바일 앱 (Kotlin, Jetpack Compose, Room, WebRTC)
     AI 서버 연동 코드: app/src/main/java/kr/co/snail/tacitq/mobile/ai/
     검사 서비스: service/InspectionService.kt, 상태 기계: core/StateMachine.kt
 - TacitQualityHmd: HMD 앱 (Kotlin, CameraX, BLE GATT 서버)
     화면: hud/, BLE: link/BleServer.kt
 - TacitQEdgeServer: PC용 모의 서버 (Python)
     edge/ai_mock.py: 실제 AI 서버와 같은 API의 모의 서버 (사내망 밖 개발용)
     실행: pip install -r requirements.txt 후 python -m edge.ai_mock --port 8080
 - TacitQMockServer: 모의 백엔드 (Ktor, PostgreSQL Docker), 화면 6종 조회 API
     docker-compose.yml의 DB 비밀번호는 로컬 개발용 기본값입니다.

[빌드]
 - Android Studio로 TacitQualityMobile, TacitQualityHmd 폴더를 각각 열어 빌드합니다.
 - 릴리스 서명 키는 포함하지 않았습니다 (keystore.properties.sample 참고).

[사용 순서]
 1. HMD 전원을 켭니다 (품질검사 앱이 뒤에서 자동 대기).
 2. 폰 핫스팟을 켜고, 폰은 사무실 Wi-Fi(21snail5)에도 연결합니다.
 3. 폰 앱 검사 메뉴에서 검사 시작을 누릅니다.
    HMD 앱이 자동으로 화면에 나타나고, 폰은 영상 메뉴로 이동합니다.
 - 새 HMD는 처음 1회 "다른 앱 위에 표시" 허용이 필요합니다:
   adb shell appops set kr.co.snail.tacitq.hmd.debug SYSTEM_ALERT_WINDOW allow

[참고]
 - 이력 메뉴 실시간 표시(10/19 수정)가 포함되어 있습니다. 아직 커밋 전 작업분이며,
   새 판정이 바로 추가되는 동작은 실기기에서 추가 확인이 필요합니다.
 - 사무실 밖에서는 실제 AI 서버에 접속되지 않습니다. 이때는 노트북 모의 AI 서버로 자동 전환됩니다.
