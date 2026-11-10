package kr.co.snail.tacitq.hmd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStatsTest {

    @Test fun summaryAfterWindowThenResets() {
        val s = FrameStats(since = 0)
        repeat(150) { s.onSensor(16_700_000, 400, 30, 30) }
        repeat(60) { s.onAnalyzed() }
        repeat(36) { s.onSent(nv21Ns = 20_000_000, jpegNs = 100_000_000, bytes = 400 * 1024) }

        assertNull(s.take(4_999))
        val r = s.take(5_000)!!
        assertEquals(150, r.sensor); assertEquals(60, r.analyzed); assertEquals(36, r.sent)
        assertEquals(20.0, r.nv21Ms, 1e-9); assertEquals(100.0, r.jpegMs, 1e-9); assertEquals(400, r.jpegKb)
        assertEquals(
            "최근 5.0초: 센서 150장(30.0/초) · 분석 60장 · 송출 36장(7.2/초) · NV21 평균 20.0ms · JPEG 평균 100.0ms · 평균 400KB · " +
                "변환 점유 86% · 노출 16.7ms · ISO 400 · AE [30, 30]",
            r.line(),
        )
        assertEquals("센서 30.0/초 · 송출 7.2/초 · NV21 20.0ms · JPEG 100.0ms", r.short())

        val next = s.take(10_000)!!
        assertEquals(0, next.sensor); assertEquals(0, next.sent)
        assertFalse(next.active)
        assertTrue(r.active)
    }

    @Test fun nothingSentGivesZeroAverages() {
        val s = FrameStats(since = 0)
        s.onSensor(null, null, null, null)
        val r = s.take(5_000)!!
        assertEquals(0.0, r.jpegMs, 0.0)
        assertEquals("최근 5.0초: 센서 1장(0.2/초) · 분석 0장 · 송출 0장(0.0/초) · NV21 평균 0.0ms · JPEG 평균 0.0ms · 평균 0KB · " +
            "변환 점유 0% · 노출 - · ISO - · AE -", r.line())
    }
}
