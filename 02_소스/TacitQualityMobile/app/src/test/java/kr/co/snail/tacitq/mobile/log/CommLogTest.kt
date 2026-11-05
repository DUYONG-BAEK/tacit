package kr.co.snail.tacitq.mobile.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommLogTest {

    @Test fun `채널별로 따로 쌓이고 최근 300줄만 남는다`() {
        CommLog.clear(CommLog.Ch.AI); CommLog.clear(CommLog.Ch.HMD)
        repeat(CommLog.KEEP + 5) { CommLog.i(CommLog.Ch.AI, "TacitQ.AiEdge", "줄 $it") }
        CommLog.w(CommLog.Ch.HMD, "TacitQ.BLE", "HMD 끊김")
        val ai = CommLog.lines(CommLog.Ch.AI).value
        assertEquals(CommLog.KEEP, ai.size)
        assertEquals("줄 5", ai.first().text)
        assertEquals(listOf('W'), CommLog.lines(CommLog.Ch.HMD).value.map { it.level })
    }

    @Test fun `복사용 글은 시각 수준 태그 내용`() {
        val s = CommLog.format(listOf(CommLog.Line(0, 'W', "TacitQ.HmdVideo", "영상 연결 실패")))
        assertTrue(s, s.matches(Regex("""\d\d:\d\d:\d\d\.\d{3} W HmdVideo 영상 연결 실패""")))
    }
}
