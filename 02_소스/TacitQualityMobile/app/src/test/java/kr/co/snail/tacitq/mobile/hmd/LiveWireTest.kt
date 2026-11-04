package kr.co.snail.tacitq.mobile.hmd

import kr.co.snail.tacitq.mobile.ai.AiBox
import kr.co.snail.tacitq.mobile.ai.LiveMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HMD 실시간 결함 쪽지(LIVE) — 0~1000 좌표, 상자 합치기, 480바이트, 결함 원 끄기 */
class LiveWireTest {
    private fun mark(vararg boxes: AiBox, w: Int = 1920, h: Int = 1080) = LiveMark("NG", "칩찍힘", boxes.toList(), w, h)
    private fun box(conf: Double, x1: Double, y1: Double, x2: Double, y2: Double) = AiBox("칩찍힘", conf, listOf(x1, y1, x2, y2))

    @Test fun `frame_size 기준 0~1000 정수로 보낸다`() {
        val s = LiveWire.encode(mark(box(0.72, 1102.7, 977.6, 1109.7, 984.0)), marks = true).decodeToString()
        assertEquals("""{"cmd":"LIVE","d":"NG","l":"칩찍힘","b":[[574,905,578,911]]}""", s)
    }

    @Test fun `지우기는 d null`() {
        assertEquals("""{"cmd":"LIVE","d":null}""", LiveWire.encode(LiveMark.CLEAR, marks = true).decodeToString())
    }

    @Test fun `결함 원을 끄면 b 없이 판정 글자만`() {
        val s = LiveWire.encode(mark(box(0.72, 1.0, 1.0, 2.0, 2.0)), marks = false).decodeToString()
        assertEquals("""{"cmd":"LIVE","d":"NG","l":"칩찍힘"}""", s)
    }

    @Test fun `겹치거나 맞닿은 상자는 합치고 신뢰도 높은 순`() {
        val merged = LiveWire.merge(listOf(
            box(0.30, 0.0, 0.0, 10.0, 10.0),
            box(0.80, 10.0, 5.0, 20.0, 15.0),            // 맞닿음 → 첫 상자와 합침
            box(0.50, 100.0, 100.0, 110.0, 110.0),       // 떨어짐
            box(0.40, 18.0, 14.0, 30.0, 30.0),           // 합친 상자와 겹침 → 다시 합침
        ))
        assertEquals(listOf(0.80, 0.50), merged.map { it.conf })
        assertEquals(listOf(0.0, 0.0, 30.0, 30.0), merged[0].box)
    }

    @Test fun `480바이트를 넘으면 신뢰도 낮은 상자부터 뺀다`() {
        val many = (0 until 60).map { i -> box(1.0 - i / 100.0, i * 30.0, 0.0, i * 30.0 + 5, 5.0) }.toTypedArray()
        val bytes = LiveWire.encode(mark(*many), marks = true)
        assertTrue(bytes.size <= LiveWire.MAX_BYTES)
        val s = bytes.decodeToString()
        assertTrue("가장 확실한 상자는 남는다", s.contains("[[0,0,3,5]"))
    }
}
