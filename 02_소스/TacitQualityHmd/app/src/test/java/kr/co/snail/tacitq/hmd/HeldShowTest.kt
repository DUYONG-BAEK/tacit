package kr.co.snail.tacitq.hmd

import kr.co.snail.tacitq.hmd.link.HeldShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면이 뜨기 전 SHOW 보관 — 서비스가 대신 ACK 하고 화면이 뜨면 순번 순서로 넘긴다 */
class HeldShowTest {

    private fun show(seq: Long, screen: String) =
        """{"cmd":"SHOW","screen":"$screen","seq":$seq,"fields":{}}""".encodeToByteArray()

    private fun seqs(list: List<ByteArray>) = list.map { Regex("\"seq\":(\\d+)").find(it.decodeToString())!!.groupValues[1].toLong() }

    @Test fun `SHOW 는 ACK 할 순번을 주고 다른 쪽지는 null`() {
        val h = HeldShow()
        assertEquals(3L, h.offer(show(3, "H-01")))
        assertNull(h.offer("""{"cmd":"PING","seq":9}""".encodeToByteArray()))
        assertNull(h.offer("""{"cmd":"LIVE","d":"NG"}""".encodeToByteArray()))
        assertNull(h.offer("{not json".encodeToByteArray()))
    }

    @Test fun `마지막 화면 지시와 마지막 예외 띠만 순번 순서로 넘긴다`() {
        val h = HeldShow()
        h.offer(show(1, "H-01"))
        h.offer(show(2, "OVERLAY"))
        h.offer(show(4, "H-01"))
        h.offer(show(3, "OVERLAY"))
        assertEquals(listOf(3L, 4L), seqs(h.take()))
        assertTrue("꺼내면 비운다", h.take().isEmpty())
    }

    @Test fun `늦게 온 작은 순번은 보관한 것을 바꾸지 않는다`() {
        val h = HeldShow()
        h.offer(show(5, "H-01"))
        assertEquals(4L, h.offer(show(4, "H-01")))          // ACK 는 한다(폰 재시도 막기)
        assertEquals(listOf(5L), seqs(h.take()))
    }

    @Test fun `끊기면 버린다`() {
        val h = HeldShow()
        h.offer(show(1, "H-01"))
        h.clear()
        assertTrue(h.take().isEmpty())
    }
}
