package kr.co.snail.tacitq.mobile.relay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.model.Verdict
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/**
 * C2 StreamRelay 단위 시험 — 설계서 §6 T2. 장비·망 없이 노트북에서 돈다.
 * 엣지는 가짜 [Transport], 시간은 coroutines-test 가상 시간.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRelayTest {

    /** 보낸 것을 기록하고, 연결 성공·끊김은 시험이 직접 일으키는 가짜 엣지 */
    private class FakeTransport : Transport {
        var listener: Transport.Listener? = null
        var opens = 0
        var closes = 0
        val texts = mutableListOf<String>()
        val frames = mutableListOf<ByteArray>()
        override fun open(url: String, listener: Transport.Listener) { opens++; this.listener = listener }
        override fun sendBinary(bytes: ByteArray): Boolean { frames += bytes; return true }
        override fun sendText(text: String): Boolean { texts += text; return true }
        override val queuedBytes: Long = 0
        override fun close() { closes++ }
    }

    private val hello = Hello(sessionId = "S-T", line = "A", partNo = "PRS-4410", viewTotal = 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun TestScope.relay(t: FakeTransport, events: MutableList<Event>, maxFrames: Int = 900) =
        StreamRelay(t, emit = { events += it }, clock = { testScheduler.currentTime }, maxFrames = maxFrames)
            .also { r -> backgroundScope.launch { r.run("ws://edge/v1/stream", hello) } }

    /** FRAME 바이너리에서 헤더 JSON 의 seq 를 꺼낸다 */
    private fun seqOf(frame: ByteArray): Long {
        val n = ByteBuffer.wrap(frame).int
        val header = Json.parseToJsonElement(String(frame, 4, n)).jsonObject
        return header["seq"]!!.jsonPrimitive.long
    }

    @Test fun `MJPEG 파트를 Content-Length 로 정확히 자르고 HMD 헤더를 읽는다`() {
        // JPEG 본문 안에 경계·줄바꿈처럼 생긴 바이트가 있어도 깨지지 않아야 한다
        val body1 = "AB\r\n--tacitqframe\r\nCD".toByteArray()
        val body2 = byteArrayOf(9, 8, 7)
        val stream = "--tacitqframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${body1.size}\r\nX-Seq: 41\r\nX-Captured-At: 1000\r\n\r\n".toByteArray() +
            body1 + "\r\n--tacitqframe\r\nContent-Length: ${body2.size}\r\nX-Seq: 42\r\n\r\n".toByteArray() + body2 + "\r\n".toByteArray()
        val r = MjpegReader(ByteArrayInputStream(stream))
        val p1 = r.next()!!
        assertArrayEquals(body1, p1.body)
        assertEquals(41L, p1.hmdSeq)
        assertEquals(1000L, p1.capturedAt)
        val p2 = r.next()!!
        assertArrayEquals(body2, p2.body)
        assertEquals(42L, p2.hmdSeq)
        assertNull(r.next())
    }

    @Test fun `HEVC 파트는 코덱과 키프레임을 읽고 그대로 중계한다`() {
        val au = byteArrayOf(0, 0, 0, 1, 0x40, 1, 2, 3)          // Annex-B 시작 부호 + VPS 흉내
        val stream = "--tacitqframe\r\nContent-Type: video/hevc\r\nContent-Length: ${au.size}\r\nX-Seq: 7\r\nX-Key: 1\r\n\r\n".toByteArray() +
            au + "\r\n--tacitqframe\r\nContent-Type: video/hevc\r\nContent-Length: ${au.size}\r\nX-Seq: 8\r\nX-Key: 0\r\n\r\n".toByteArray() + au
        val r = MjpegReader(ByteArrayInputStream(stream))
        val p1 = r.next()!!
        val p2 = r.next()!!
        assertEquals("hevc", p1.codec)
        assertTrue(p1.key)
        assertTrue(!p2.key)

        val bytes = FrameCodec.encode(Frame(1, p1.hmdSeq, 0, 0, p1.body, p1.codec, p1.key))
        val n = ByteBuffer.wrap(bytes).int
        val h = Json.parseToJsonElement(String(bytes, 4, n)).jsonObject
        assertEquals("hevc", h["codec"]!!.jsonPrimitive.content)
        assertEquals("true", h["key"]!!.jsonPrimitive.content)
        assertArrayEquals(au, bytes.copyOfRange(4 + n, bytes.size))   // 손대지 않고 그대로
        assertEquals("jpeg", MjpegReader(ByteArrayInputStream(
            "--x\r\nContent-Type: image/jpeg\r\nContent-Length: 1\r\n\r\nA".toByteArray())).next()!!.codec)
    }

    @Test fun `FRAME 은 길이 헤더 JSON JPEG 원본 순서이고 JPEG 는 그대로다`() {
        val bytes = FrameCodec.encode(Frame(7, 41, 1000, 1050, jpeg))
        val n = ByteBuffer.wrap(bytes).int
        val h = Json.parseToJsonElement(String(bytes, 4, n)).jsonObject
        assertEquals(7L, h["seq"]!!.jsonPrimitive.long)
        assertEquals(41L, h["hmd_seq"]!!.jsonPrimitive.long)
        assertEquals(1050L, h["received_at"]!!.jsonPrimitive.long)
        assertEquals(jpeg.size, h["bytes"]!!.jsonPrimitive.int)
        assertArrayEquals(jpeg, bytes.copyOfRange(4 + n, bytes.size))
    }

    @Test fun `엣지 메시지 해석 - 모르는 verdict 는 안전측 REVIEW`() {
        val j = EdgeMessages.parse("""{"type":"JUDGE","seq":3,"part_id":"A_0003","verdict":"???","frame_seq_end":9}""")
        assertTrue(j is EdgeMsg.Judge)
        assertEquals(Verdict.REVIEW, (j as EdgeMsg.Judge).toEvent(0, 0).verdict)
        assertTrue(EdgeMessages.parse("not json") is EdgeMsg.Unknown)
        assertEquals(14.2, (EdgeMessages.parse("""{"type":"HB","fps":14.2}""") as EdgeMsg.Hb).fps, 0.0)
        assertEquals(EdgeMsg.Tray("A_0001", "NG"), EdgeMessages.parse("""{"type":"TRAY","part_id":"A_0001","kind":"NG"}"""))
    }

    @Test fun `붙으면 HELLO 를 보내고 프레임을 순서대로 중계한다`() = runTest {
        val t = FakeTransport()
        val events = mutableListOf<Event>()
        val r = relay(t, events)
        runCurrent()
        assertEquals(1, t.opens)                       // 시작하자마자 연결 시도
        t.listener!!.onOpen()
        assertTrue(t.texts.single().contains("\"type\":\"HELLO\""))
        assertTrue(t.texts.single().contains("\"session_id\":\"S-T\""))

        repeat(5) { r.offer(100L + it, 0, jpeg) }
        advanceTimeBy(100)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), t.frames.map(::seqOf))
        assertTrue(events.isEmpty())
    }

    @Test fun `판정 지연은 폰 시계로 - 마지막 프레임 수신부터 판정 도착까지`() = runTest {
        val t = FakeTransport()
        val events = mutableListOf<Event>()
        val r = relay(t, events)
        runCurrent()
        t.listener!!.onOpen()
        repeat(3) { r.offer(it.toLong(), 0, jpeg); advanceTimeBy(70) }   // seq 3 은 t=140 에 받음, 지금 t=210
        advanceTimeBy(190)                                              // t=400 에 판정 도착
        t.listener!!.onText("""{"type":"JUDGE","seq":1,"part_id":"A_0001","verdict":"FAIL","severity":"CRITICAL",
            "confidence":0.93,"agree_rate":0.75,"evidence":"찍힘","frame_ref":null,"bbox":[0.1,0.2,0.05,0.05],
            "frame_seq_start":1,"frame_seq_end":3}""")
        val e = events.single() as Event.JudgeResult
        assertEquals(Verdict.FAIL, e.verdict)
        assertEquals("A_0001", e.partId)
        assertEquals(140L, e.capturedEndAt)
        assertEquals(400L, e.arrivedAt)
        assertEquals(listOf(0.1, 0.2, 0.05, 0.05), e.bbox)
    }

    @Test fun `엣지 3초 무소식이면 Unreachable 한 번 - 5초 주기로 다시 붙고 복구되면 쌓인 프레임을 순서대로`() = runTest {
        val t = FakeTransport()
        val events = mutableListOf<Event>()
        val r = relay(t, events)
        runCurrent()
        t.listener!!.onOpen()

        advanceTimeBy(2_600)
        assertTrue("3초 전엔 멀쩡", events.isEmpty())
        advanceTimeBy(600)                                 // HB 없이 3초 넘김
        assertEquals(listOf<Event>(Event.EdgeUnreachable("엣지 3000ms 무응답")), events)
        assertEquals(1, t.closes)

        repeat(4) { r.offer(it.toLong(), 0, jpeg) }       // 끊긴 동안 큐에 쌓인다
        advanceTimeBy(1_000)
        assertTrue(t.frames.isEmpty())
        assertEquals(1, events.size)                       // Unreachable 은 한 번만

        advanceTimeBy(2_000)                               // 첫 시도(t=0)부터 5초 → 재시도
        assertEquals(2, t.opens)
        t.listener!!.onOpen()
        advanceTimeBy(100)
        assertEquals(Event.EdgeRecovered, events.last())
        assertEquals(listOf(1L, 2L, 3L, 4L), t.frames.map(::seqOf))
    }

    @Test fun `큐가 차면 새 프레임을 버리되 순번은 소비하고 90퍼센트에서 Overflow 를 한 번 알린다`() = runTest {
        val t = FakeTransport()
        val events = mutableListOf<Event>()
        val r = relay(t, events, maxFrames = 10)
        runCurrent()                                       // 연결 안 됨 → 전부 큐에

        repeat(12) { r.offer(it.toLong(), 0, jpeg) }
        assertEquals(1, events.count { it == Event.RelayOverflow })
        advanceTimeBy(600)
        assertEquals(10, r.status.value.queued)
        assertEquals(2L, r.status.value.dropped)

        t.listener!!.onOpen()
        advanceTimeBy(100)                                 // 큐가 비워진다
        r.offer(99, 0, jpeg)                               // 자리가 생긴 뒤 들어온 프레임
        advanceTimeBy(100)
        val seqs = t.frames.map(::seqOf)
        assertEquals((1L..10L).toList(), seqs.take(10))
        assertEquals(13L, seqs.last())                     // 11·12 는 버려짐 → 엣지가 누락 2 로 센다
    }
}
