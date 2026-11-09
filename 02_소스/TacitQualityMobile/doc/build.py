"""
build.py — sections/*.html 을 TacitQualityMobile_작업일지.html 의 각 <section id="..."> 에 삽입
사용: python doc/build.py  (또는 doc 폴더에서 python build.py)
"""
import re
import os

# (섹션ID, 사이드바 제목, 상태클래스) — 위에서 아래 순서가 화면 순서
# 상태클래스: s-plan / s-wip / s-dev / s-confirmed / s-waiting / s-in-progress / s-analysis
SECTIONS = [
    ("overview",  "프로젝트 개요 · 역할",   "s-confirmed"),
    ("env-setup", "개발 환경 · 첫 구동",    "s-confirmed"),
    ("git-init",  "버전관리(Git) 구축",     "s-confirmed"),
    ("next-plan", "다음 계획 — 상태 기계",  "s-plan"),
    ("state-machine", "상태 기계(C3) 구현", "s-confirmed"),
    ("local-store", "C5 LocalStore 구현", "s-confirmed"),
    ("hmd-renderer", "C4 HmdRenderer 구현", "s-confirmed"),
    ("service-engine", "서비스 디스패처 — 부품 연결", "s-confirmed"),
    ("local-server", "로컬 개발 서버 — 6화면 API", "s-confirmed"),
    ("hmd-app-stage1", "HMD 앱 1단계 — 영상 송출", "s-confirmed"),
    ("hmd-app-stage2", "HMD 앱 2단계 — BLE SHOW 수신", "s-confirmed"),
    ("edge-relay-e1", "C2 영상 중계 + 모의 엣지", "s-confirmed"),
    ("c1-ble-link", "C1 BleLink — 폰→HMD 종단 시험", "s-confirmed"),
    ("verdict-3cases", "판정 단순화 — 3가지 경우", "s-dev"),
    ("e2-hevc", "E2 — H.265 1080p 전환", "s-confirmed"),
    ("ai-mock", "모의 AI 엣지 — WebRTC API 흉내", "s-dev"),
    ("mobile-live-view", "폰 앱 영상 모니터링 (M-07)", "s-confirmed"),
    ("ai-edge-webrtc", "실제 AI 서버 연동 (WebRTC)", "s-confirmed"),
    ("auto-connect", "연결 자동화 — 주소 · 앱 실행", "s-confirmed"),
    ("hmd-screen-1018", "현장 피드백 — 화면 전환 · 버튼 · 순서", "s-confirmed"),
    ("frame-inspect", "AI 서버 11-03 규격 — 프레임 단위 판정", "s-confirmed"),
    ("product-feedback", "제품 단위 판정 서버 이전 · 피드백 · 사진 저장", "s-dev"),
    ("measure-1109", "실서버 실측 · 송출 간격 · 면 기준 5장", "s-dev"),
]

BASE_DIR  = os.path.dirname(os.path.abspath(__file__))
MAIN_HTML = os.path.join(BASE_DIR, "TacitQualityMobile_작업일지.html")
SECT_DIR  = os.path.join(BASE_DIR, "sections")

def build():
    with open(MAIN_HTML, encoding="utf-8") as f:
        html = f.read()

    # 1) 섹션 내용 삽입
    for sec_id, _, _ in SECTIONS:
        sect_file = os.path.join(SECT_DIR, f"{sec_id}.html")
        if not os.path.exists(sect_file):
            print(f"  [SKIP] {sec_id}.html 없음")
            continue
        with open(sect_file, encoding="utf-8") as f:
            content = f.read().strip()
        pattern = rf'(<section id="{re.escape(sec_id)}">)(.*?)(</section>)'
        # 함수 치환 — content 안의 백슬래시(예: 윈도우 경로)가 이스케이프로 해석되지 않게 한다
        new_html, n = re.subn(
            pattern,
            lambda m: m.group(1) + "\n" + content + "\n" + m.group(3),
            html, flags=re.DOTALL
        )
        if n:
            html = new_html
            print(f"  [OK]   {sec_id}")
        else:
            print(f"  [WARN] <section id=\"{sec_id}\"> 태그를 찾지 못했습니다.")

    # 2) 사이드바 nav 자동 생성 (상태 점 색상 클래스 포함)
    nav_lines = []
    for sec_id, title, s_class in SECTIONS:
        nav_lines.append(
            f'    <a class="nav-item {s_class}" href="#{sec_id}">{title}</a>'
        )
    nav_html = "\n".join(nav_lines)
    html = re.sub(
        r'(<nav id="sidebar-nav">)(.*?)(</nav>)',
        rf'\1\n{nav_html}\n  \3',
        html, flags=re.DOTALL
    )

    with open(MAIN_HTML, "w", encoding="utf-8") as f:
        f.write(html)
    print("\nbuild 완료 ->", MAIN_HTML)

if __name__ == "__main__":
    build()
