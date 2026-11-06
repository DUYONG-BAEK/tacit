package kr.co.snail.tacitq.mobile.hmd

import kr.co.snail.tacitq.mobile.ai.FacesMark
import kr.co.snail.tacitq.mobile.ai.LiveMark
import kr.co.snail.tacitq.mobile.core.ShowCommand

/**
 * HMD 화면 따라 그리기(M-07) — 폰이 HMD 에 보낸 것(SHOW 화면 · HINT 안내 · LIVE 결함 · OVERLAY 띠)으로
 * HMD 가 그리는 글자·색을 똑같이 만든다. 안드로이드를 모르는 순수 함수.
 *
 * HMD 앱 `hud/Screens.kt`(idle·aim) · `hud/LiveHold.kt`(큰 글자 우선순위·LIVE·RESULT 문구·면 칩 줄) · `hud/Screens.kt` Tone 과
 * **같아야 한다** — 한쪽을 바꾸면 다른 쪽도 고친다. 지금 폰이 보내는 화면은 조준(H-01)·대기·OVERLAY 뿐이다.
 */
object HudMirror {

    /** HMD Tone 팔레트(ARGB) */
    enum class Tone(val argb: Long) {
        OK(0xFF5FB98F), BAD(0xFFFF6B61), WARN(0xFFF0A95C), IDLE(0xFF8FA2B5), STOP(0xFFC9D4E0),
    }

    data class View(
        val topLeft: String = "",
        val topLeftTone: Tone = Tone.STOP,
        val big: String = "",
        val bigTone: Tone = Tone.STOP,
        val lines: List<String> = emptyList(),
        val linesTone: Tone = Tone.STOP,
        val overlay: String? = null,        // 예외 띠 "⚠ …" (null = 숨김)
        val marks: Boolean = false,         // 결함 원을 그릴 화면인가(조준 화면에서만)
    )

    /** 제품 결과 표시 시간 — HMD LiveHold 의 RESULT 유지와 같은 값 */
    const val RESULT_SHOW_MS = 2_000L

    /**
     * @param screen 마지막으로 보낸 화면 지시(OVERLAY 제외) · [hint] (text, tone) · [live] 유지 중인 결함(null = 없음)
     * @param overlay 켜진 OVERLAY 띠 글 · [faces] 면 진행(null·빈 값 = 숨김)
     * @param result 표시 중인 제품 결과 (PASS · FAIL, 결함 종류) — 보낸 뒤 [RESULT_SHOW_MS] 안일 때만 넘긴다
     */
    fun view(
        screen: ShowCommand?, hint: Pair<String, String>?, live: LiveMark?, overlay: String?,
        faces: FacesMark? = null, result: Pair<String, String?>? = null,
    ): View {
        val o = overlay?.let { "⚠ $it" }
        if (screen == null) return View(overlay = o)
        if (screen.screen == "H-01" && screen.fields["state"] == "IDLE") return View(
            topLeft = "● 대기", topLeftTone = Tone.IDLE, big = "검사 대기", bigTone = Tone.IDLE,
            lines = listOf("다음 부품을 준비하세요"), overlay = o,
        )
        if (screen.screen != "H-01") return View(big = "HMD 화면 ${screen.screen}", overlay = o)
        // 조준 — 큰 글자는 RESULT(표시 중) > LIVE(유지 중) > HINT > 없음 (HMD LiveHold.big)
        val (big, tone) = result?.let { (d, l) -> resultText(d, l) }
            ?: live?.takeIf { it.decision != null }?.let(::liveText)
            ?: hint?.let { (t, tn) -> t to hintTone(tn) }
            ?: ("" to Tone.STOP)
        val facesLine = faces?.takeIf { it.all.isNotEmpty() }?.let { facesText(it.seen, it.all, it.complete) }
        return View(
            topLeft = listOfNotNull("● REC", screen.fields["part_no"]?.ifBlank { null }).joinToString(" · "),
            topLeftTone = Tone.BAD, big = big, bigTone = tone, overlay = o, marks = true,
            lines = listOfNotNull(facesLine), linesTone = if (faces?.complete == true) Tone.OK else Tone.STOP,
        )
    }

    /** 제품 결과 문구 — HMD LiveHold.resultText 와 같다 */
    fun resultText(decision: String, label: String?): Pair<String, Tone> =
        if (decision == "PASS") "✓ 합격" to Tone.OK
        else ("✕ 불합격" + (label?.ifBlank { null }?.let { " · $it" }.orEmpty())) to Tone.BAD

    /** 면 칩 줄 — `■ 상면   □ 엣지   □ 하면비닐` (+ `   ✓ 검사 완료`). HMD LiveHold.facesText 와 같다 */
    fun facesText(seen: List<String>, all: List<String>, complete: Boolean): String =
        all.joinToString("   ") { (if (it in seen) "■ " else "□ ") + it } + if (complete) "   ✓ 검사 완료" else ""

    fun liveText(m: LiveMark): Pair<String, Tone> {
        val label = m.label?.ifBlank { null }?.let { " · $it" }.orEmpty()
        return if (m.decision == "NG") "✕ 불량$label" to Tone.BAD else "? 애매$label" to Tone.WARN
    }

    private fun hintTone(t: String) = when (t) { "OK" -> Tone.OK; "WARN" -> Tone.WARN; else -> Tone.IDLE }
}
