"""
design_build.py — sections/*.html 을 TacitQualityMobile_설계서.html 의 각 <section id="..."> 에 삽입
사용: python design_build.py
"""
import re
import os

# (섹션ID, 사이드바 제목) 순서대로 — 위에서 아래 순서가 화면 순서
SECTIONS = [
    ("overview", "1. 개요 · 범위"),
    ("architecture", "2. 구성 · 데이터 경로"),
    ("ai-edge", "3. AI 서버 연동 — 판정 · 메시지 · 화면"),
    ("e2-hevc", "4. E2 — H.265 1080p 전환 (보류)"),
    ("e1-overview", "부록 A. E1 개요 · 범위"),
    ("e1-architecture", "부록 B. E1 구성 · 데이터 경로"),
    ("protocol", "부록 C. E1 전송 규격 (WebSocket)"),
    ("mobile", "부록 D. E1 모바일 C2 StreamRelay"),
    ("edge", "부록 E. E1 모의 엣지 서버 (Python)"),
    ("test-plan", "부록 F. E1 시험 계획 · 완료 기준"),
    ("issues", "부록 G. E1 열린 문제 · 결정 필요"),
]

BASE_DIR   = os.path.dirname(os.path.abspath(__file__))
MAIN_HTML  = os.path.join(BASE_DIR, "TacitQualityMobile_설계서.html")
SECT_DIR   = os.path.join(BASE_DIR, "sections")

def build():
    with open(MAIN_HTML, encoding="utf-8") as f:
        html = f.read()

    # 1) 섹션 내용 삽입
    for sec_id, _ in SECTIONS:
        sect_file = os.path.join(SECT_DIR, f"{sec_id}.html")
        if not os.path.exists(sect_file):
            print(f"  [SKIP] {sec_id}.html 없음")
            continue
        with open(sect_file, encoding="utf-8") as f:
            content = f.read().strip()
        pattern = rf'(<section id="{re.escape(sec_id)}">)(.*?)(</section>)'
        replacement = lambda m, c=content: f'{m.group(1)}\n{c}\n{m.group(3)}'
        new_html, n = re.subn(pattern, replacement, html, flags=re.DOTALL)
        if n:
            html = new_html
            print(f"  [OK]   {sec_id}")
        else:
            print(f"  [WARN] <section id=\"{sec_id}\"> 태그를 찾지 못했습니다.")

    # 2) 사이드바 nav 자동 생성
    nav_lines = []
    for sec_id, title in SECTIONS:
        nav_lines.append(
            f'    <a class="nav-item" href="#{sec_id}">{title}</a>'
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
