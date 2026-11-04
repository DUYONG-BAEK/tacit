package kr.co.snail.tacitq.hmd

import kr.co.snail.tacitq.hmd.hud.LiveHold
import kr.co.snail.tacitq.hmd.hud.Screens
import kr.co.snail.tacitq.hmd.hud.ShowGate
import kr.co.snail.tacitq.hmd.hud.ShowGate.Verdict
import kr.co.snail.tacitq.hmd.hud.Tone
import kr.co.snail.tacitq.hmd.protocol.Downlink
import kr.co.snail.tacitq.hmd.protocol.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2단계 HMD 쪽 순수 로직 — 쪽지 해석 · 순번 · 3초 무소식 · 화면 매핑 */
class HudLogicTest {

    /** 모바일 HmdRenderer 가 실제로 내보내는 모양(encodeDefaults=true) 그대로 */
    private val failJson = """
        {"cmd":"SHOW","screen":"H-03","seq":7,"ttl_ms":0,"vibrate":3,"gesture_window":"OBJECT",
         "window_id":null,"fields":{"part_id":"A_0249","severity":"CRITICAL",
         "evidence":"찍힘 · 2면(하면) 좌상단 · 4장 중 3장 검출","frame_ref":"","bbox":"0.31,0.22,0.07,0.06"}}
    """.trimIndent()

    @Test fun `모바일 SHOW 를 그대로 해석한다`() {
        val msg = Wire.parse(failJson.encodeToByteArray()) as Downlink.Show
        assertEquals("H-03", msg.wire.screen)
        assertEquals(7L, msg.wire.seq)
        assertEquals("OBJECT", msg.wire.gestureWindow)
        assertEquals("A_0249", msg.wire.fields["part_id"])
    }

    @Test fun `깨진 쪽지와 모르는 cmd 는 예외 없이 Unknown`() {
        assertTrue(Wire.parse("{not json".encodeToByteArray()) is Downlink.Unknown)
        assertTrue(Wire.parse("""{"cmd":"OVERLAY"}""".encodeToByteArray()) is Downlink.Unknown)
        assertEquals(Downlink.Ping(3), Wire.parse("""{"cmd":"PING","seq":3}""".encodeToByteArray()))
    }

    @Test fun `ACK 는 계약 필드명 type seq rendered_at`() {
        assertEquals("""{"type":"ACK","seq":7,"rendered_at":1000}""", Wire.ack(7, 1000).decodeToString())
    }

    @Test fun `새 seq 는 그리고 같은 seq 는 ACK 만 작은 seq 는 버린다`() {
        val g = ShowGate()
        assertEquals(Verdict.RENDER, g.onShow(5, 0))
        assertEquals(Verdict.DUPLICATE, g.onShow(5, 10))   // 모바일 1회 재전송
        assertEquals(Verdict.STALE, g.onShow(4, 20))       // 늦게 도착한 옛 지시
        assertEquals(Verdict.RENDER, g.onShow(6, 30))
    }

    @Test fun `새 연결이면 순번을 초기화한다 - 재시작한 모바일은 1부터 센다`() {
        val g = ShowGate()
        g.onShow(40, 0)
        g.onConnected()
        assertEquals(Verdict.RENDER, g.onShow(1, 10))
    }

    @Test fun `끊긴 지 3초면 무소식 - 그 전엔 아님`() {
        val g = ShowGate()
        g.onConnected()
        g.onDisconnected(1_000)
        assertFalse(g.isSilent(3_999))
        assertTrue(g.isSilent(4_000))
        g.onConnected()
        assertFalse(g.isSilent(10_000))
    }

    @Test fun `PING 을 받아 오다 3초 끊기면 무소식 - PING 을 한 번도 안 받았으면 감시 안 함`() {
        val g = ShowGate()
        g.onConnected()
        g.onShow(1, 0)
        assertFalse("PING 미구현 모바일 — 오탐 금지", g.isSilent(60_000))

        g.onPing(100_000)
        g.onShow(2, 101_000)                               // SHOW 도 소식으로 친다
        assertFalse(g.isSilent(103_999))
        assertTrue(g.isSilent(104_000))
    }

    @Test fun `화면 매핑 - 불합격 합격 REVIEW 대기`() {
        val fail = Screens.from((Wire.parse(failJson.encodeToByteArray()) as Downlink.Show).wire)
        assertEquals("✕ 불합격 · Critical", fail.big)
        assertEquals(Tone.BAD, fail.bigTone)
        assertEquals("A_0249", fail.topLeft)

        val pass = Screens.from(show("H-02", "part_id" to "A_0248", "confidence" to "0.982"))
        assertEquals("✓ 합격", pass.big)
        assertTrue(pass.lines.contains("신뢰도 98.2%"))

        val review = Screens.from(show("H-04", "part_id" to "A_0250", "confidence" to "0.62", "agree_rate" to "0.5"))
        assertEquals(listOf("결함 후보 · 신뢰도 62.0%", "검출 일치율 50.0%"), review.lines)

        val idle = Screens.from(show("H-01", "state" to "IDLE"))
        assertEquals("검사 대기", idle.big)
        assertEquals("대기 화면은 조준 화면(H-01)과 다른 ID", "IDLE", idle.screen)
        val aim = Screens.from(show("H-01", "part_no" to "PRS-4410", "done_cnt" to "12", "frames_target" to "4"))
        assertEquals("H-01", aim.screen)
        assertEquals("● REC · PRS-4410", aim.topLeft)
        assertTrue("처리 건수·프레임 문구 없음", aim.lines.isEmpty() && aim.keys.isEmpty())
        assertEquals(listOf("과검출 / 결함 아님", "미검출 / 더 있음"),
            Screens.from(show("H-05", "option_1" to "AI_FALSE_POSITIVE", "option_2" to "AI_FALSE_NEGATIVE")).options)
    }

    @Test fun `LIVE 쪽지를 해석한다`() {
        assertEquals(Downlink.Live("NG", "칩찍힘", listOf(listOf(574, 905, 578, 911))),
            Wire.parse("""{"cmd":"LIVE","d":"NG","l":"칩찍힘","b":[[574,905,578,911]]}""".encodeToByteArray()))
        assertEquals(Downlink.Live("REVIEW", "이물찍힘", null),
            Wire.parse("""{"cmd":"LIVE","d":"REVIEW","l":"이물찍힘"}""".encodeToByteArray()))   // 결함 원 끔
        assertEquals(Downlink.Live(null), Wire.parse("""{"cmd":"LIVE","d":null}""".encodeToByteArray()))
    }

    @Test fun `LIVE 는 0점5초 유지하고 d null 이나 끊김이면 바로 지운다`() {
        val h = LiveHold()
        h.onLive(Downlink.Live("NG", "칩찍힘"), 1_000)
        assertEquals("NG", h.current(1_499)?.d)
        assertEquals(1L, h.remainingMs(1_499))
        assertEquals(null, h.current(1_500))
        h.onLive(Downlink.Live("NG"), 2_000); h.onLive(Downlink.Live(null), 2_100)
        assertEquals(null, h.current(2_100))
        h.onLive(Downlink.Live("REVIEW"), 3_000); h.clear()
        assertEquals(null, h.current(3_000))
    }

    @Test fun `큰 글자는 조준 화면에서만 LIVE 가 HINT 보다 먼저`() {
        val h = LiveHold()
        val aim = Screens.from(show("H-01", "part_no" to "P"))
        val hint = "검사 중" to Tone.OK
        assertEquals(hint, h.big(aim, hint, 0))
        h.onLive(Downlink.Live("NG", "칩찍힘"), 0)
        assertEquals("✕ 불량 · 칩찍힘" to Tone.BAD, h.big(aim, hint, 100))
        assertEquals("유지가 끝나면 HINT 로", hint, h.big(aim, hint, 500))
        h.onLive(Downlink.Live("REVIEW", "이물찍힘"), 1_000)
        assertEquals("? 애매 · 이물찍힘" to Tone.WARN, h.big(aim, hint, 1_100))
        val idle = Screens.idle()
        assertEquals("대기 화면은 LIVE·HINT 를 그리지 않는다", "검사 대기" to Tone.IDLE, h.big(idle, hint, 1_100))
        val manual = Screens.manual(null)
        assertEquals("수동 판정", h.big(manual, hint, 1_100).first)
    }

    @Test fun `결함 원 좌표 - 0~1000 을 화면 비율로 바꾸고 최소 반지름을 지킨다`() {
        val (cx, cy, r) = LiveHold.circle(listOf(574, 905, 578, 911), 1280, 720, minRadius = 20f)
        assertEquals(737.3f, cx, 0.5f)
        assertEquals(653.8f, cy, 0.5f)
        assertEquals("수 px 결함도 지름 40 이상", 20f, r, 0f)
        val big = LiveHold.circle(listOf(0, 0, 100, 100), 1000, 1000, minRadius = 20f)
        assertEquals(75f, big.third, 0f)
    }

    private fun show(screen: String, vararg f: Pair<String, String>) =
        kr.co.snail.tacitq.hmd.protocol.ShowWire(screen = screen, seq = 1, fields = mapOf(*f))
}
