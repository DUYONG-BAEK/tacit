package kr.co.snail.tacitq.mobile.hmd

import kr.co.snail.tacitq.mobile.ai.LiveMark
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.core.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-07 HMD 화면 따라 그리기 — HMD Screens·LiveHold 와 같은 글자·색 */
class HudMirrorTest {
    private val aim = Screens.guide("ST-02", 0, Profile())

    @Test fun `조준 화면 - 큰 글자는 LIVE 가 HINT 보다 먼저`() {
        val hint = "검사 중" to "OK"
        assertEquals("검사 중" to HudMirror.Tone.OK, HudMirror.view(aim, hint, null, null).let { it.big to it.bigTone })
        val ng = HudMirror.view(aim, hint, LiveMark("NG", "칩찍힘"), null)
        assertEquals("✕ 불량 · 칩찍힘", ng.big); assertEquals(HudMirror.Tone.BAD, ng.bigTone)
        assertEquals("● REC · ST-02", ng.topLeft)
        assertTrue(ng.marks)
        assertEquals("? 애매 · 이물찍힘", HudMirror.view(aim, hint, LiveMark("REVIEW", "이물찍힘"), null).big)
        assertEquals("지움(d=null)이면 HINT", "검사 중", HudMirror.view(aim, hint, LiveMark.CLEAR, null).big)
    }

    @Test fun `대기 화면은 LIVE HINT 를 그리지 않는다`() {
        val v = HudMirror.view(Screens.idle(), "검사 중" to "OK", LiveMark("NG", "칩찍힘"), null)
        assertEquals("검사 대기", v.big)
        assertEquals(listOf("다음 부품을 준비하세요"), v.lines)
        assertFalse(v.marks)
    }

    @Test fun `OVERLAY 띠는 화면과 함께`() {
        assertEquals("⚠ 서버 지연 · 검사 계속", HudMirror.view(aim, null, null, "서버 지연 · 검사 계속").overlay)
    }

    @Test fun `직전 결과는 오른쪽 위 칸 - 큰 글자(검사 중)를 가리지 않는다`() {
        val v = HudMirror.view(aim, "하면비닐 검사 중" to "INFO", null, null, result = Triple("FAIL", "비닐찍힘", "상면"))
        assertEquals("하면비닐 검사 중", v.big); assertEquals(HudMirror.Tone.STOP, v.bigTone)
        assertEquals("상면 ✕ 비닐찍힘", v.result); assertEquals(HudMirror.Tone.BAD, v.resultTone)
        assertEquals("✕ 불량 · 칩찍힘", HudMirror.view(aim, null, LiveMark("NG", "칩찍힘"), null, result = Triple("PASS", null, "엣지")).big)
        val pass = HudMirror.view(aim, null, null, null, result = Triple("PASS", null, "엣지"))
        assertEquals("엣지 ✓ 정상" to HudMirror.Tone.OK, pass.result to pass.resultTone)
        assertEquals("✕ 불합격", HudMirror.view(aim, null, null, null, result = Triple("FAIL", null, null)).result)
        assertEquals(null, HudMirror.view(aim, null, null, null).result)
        assertEquals("대기 화면에는 없다", null, HudMirror.view(Screens.idle(), null, null, null, result = Triple("PASS", null, "엣지")).result)
        assertEquals(emptyList<String>(), v.lines)
    }
}
