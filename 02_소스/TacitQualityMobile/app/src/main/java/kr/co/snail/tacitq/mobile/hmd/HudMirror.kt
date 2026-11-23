package kr.co.snail.tacitq.mobile.hmd

import kr.co.snail.tacitq.mobile.ai.LiveMark
import kr.co.snail.tacitq.mobile.core.ShowCommand

/**
 * HMD 화면 따라 그리기(M-07) — 폰이 HMD 에 보낸 것(SHOW 화면 · HINT 안내 · LIVE 결함 · RESULT 직전 결과 · OVERLAY 띠)으로
 * HMD 가 그리는 글자·색을 똑같이 만든다. 안드로이드를 모르는 순수 함수.
 *
 * HMD 앱 `hud/Screens.kt`(idle·aim) · `hud/LiveHold.kt`(큰 글자 우선순위·LIVE·RESULT 문구) · `hud/Screens.kt` Tone 과
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
        val result: String? = null,         // 오른쪽 위 직전 검사 결과 칸(null = 숨김, 조준 화면에서만)
        val resultTone: Tone = Tone.STOP,
    )

    /**
     * @param screen 마지막으로 보낸 화면 지시(OVERLAY 제외) · [hint] (text, tone) · [live] 유지 중인 결함(null = 없음)
     * @param overlay 켜진 OVERLAY 띠 글 · [result] 직전 검사 결과 (PASS · FAIL, 결함 종류, 면) — 다음 결과까지 유지
     */
    fun view(
        screen: ShowCommand?, hint: Pair<String, String>?, live: LiveMark?, overlay: String?,
        result: Triple<String, String?, String?>? = null,
    ): View {
        val o = overlay?.let { "⚠ $it" }
        if (screen == null) return View(overlay = o)
        if (screen.screen == "H-01" && screen.fields["state"] == "IDLE") return View(
            topLeft = "● 대기", topLeftTone = Tone.IDLE, big = "검사 대기", bigTone = Tone.IDLE,
            lines = listOf("다음 부품을 준비하세요"), overlay = o,
        )
        if (screen.screen != "H-01") return View(big = "HMD 화면 ${screen.screen}", overlay = o)
        // 조준 — 큰 글자는 LIVE(유지 중) > HINT > 없음 (HMD LiveHold.big). 직전 결과는 오른쪽 위 칸에 따로
        val (big, tone) = live?.takeIf { it.decision != null }?.let(::liveText)
            ?: hint?.let { (t, tn) -> t to hintTone(tn) }
            ?: ("" to Tone.STOP)
        val r = result?.let { (d, l, f) -> resultText(d, l, f) }
        return View(
            topLeft = listOfNotNull("● REC", screen.fields["part_no"]?.ifBlank { null }).joinToString(" · "),
            topLeftTone = Tone.BAD, big = big, bigTone = tone, overlay = o, marks = true,
            result = r?.first, resultTone = r?.second ?: Tone.STOP,
        )
    }

    /** 직전 검사 결과 문구 — `상면 ✓ 정상` · `상면 ✕ 비닐찍힘` (결함 종류가 없으면 `✕ 불합격`). HMD LiveHold.resultText 와 같다 */
    fun resultText(decision: String, label: String?, face: String?): Pair<String, Tone> {
        val f = face?.ifBlank { null }?.let { "$it " }.orEmpty()
        return if (decision == "PASS") "$f✓ 정상" to Tone.OK
        else "$f✕ ${label?.ifBlank { null } ?: "불합격"}" to Tone.BAD
    }

    fun liveText(m: LiveMark): Pair<String, Tone> {
        val label = m.label?.ifBlank { null }?.let { " · $it" }.orEmpty()
        return if (m.decision == "NG") "✕ 불량$label" to Tone.BAD else "? 애매$label" to Tone.WARN
    }

    /** HINT tone → 글자색. HMD HudController.onHint 와 같다 */
    private fun hintTone(t: String) = when (t) { "OK" -> Tone.OK; "WARN" -> Tone.WARN; "INFO" -> Tone.STOP; else -> Tone.IDLE }
}
