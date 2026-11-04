package kr.co.snail.tacitq.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 재연결 정책 — API §5.3 간격(1→2→4→5초) + 시도 마감 10초. */
class ReconnectTest {
    private val r = Reconnect()

    /** now 에 시도했다가 바로 실패 — 다음 시도 가능 시각까지의 간격을 돌려준다 */
    private fun failAt(now: Long): Long {
        assertTrue(r.due(now))
        r.onAttempt(now)
        r.onFailure(now)
        var t = now
        while (!r.due(t)) t += 100
        return t - now
    }

    @Test fun `처음은 바로 시도하고 실패하면 1 2 4 5 5초`() {
        var now = 0L
        val gaps = (1..5).map { failAt(now).also { now += it } }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), gaps)
    }

    @Test fun `시도 중에는 간격이 지나도 새 시도를 하지 않는다`() {
        r.onAttempt(0)
        assertFalse(r.due(5_000))
        assertFalse(r.due(9_999))
    }

    @Test fun `10초 안에 연결되지 않으면 실패로 세고 다음 간격`() {
        r.onAttempt(0)
        assertFalse(r.expired(9_999))
        assertTrue(r.expired(10_000))
        r.onFailure(10_000)
        assertEquals(1, r.failures)
        assertFalse(r.due(10_999)); assertTrue(r.due(11_000))
    }

    @Test fun `연결 전에 온 실패도 센다`() {
        r.onAttempt(0); r.onFailure(300)        // CONNECTED 전 FAILED
        r.onAttempt(1_300); r.onFailure(1_500)
        assertEquals(2, r.failures)
        assertTrue(r.due(3_500))
    }

    @Test fun `같은 실패를 두 번 보고해도 한 번만 센다`() {
        r.onAttempt(0); r.onFailure(100); r.onFailure(100)
        assertEquals(1, r.failures)
    }

    @Test fun `연결되면 0으로 돌아가고 끊기면 다시 1초`() {
        failAt(0); failAt(1_000)
        assertEquals(2, r.failures)
        r.onAttempt(3_000); r.onConnected()
        assertEquals(0, r.failures)
        assertFalse(r.due(99_000))              // 연결 중에는 시도하지 않는다
        r.onFailure(100_000)                    // 끊김
        assertEquals(1, r.failures)
        assertTrue(r.due(101_000))
    }

    @Test fun `설정 오류면 더 시도하지 않는다`() {
        r.onAttempt(0); r.onConfigError(); r.onFailure(0)
        assertFalse(r.due(1_000_000))
        assertEquals(Reconnect.State.STOPPED, r.state)
    }
}
