package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 서버 메시지 해석·판정 매핑 — 설계서 §9, A4_WEBRTC_API.md §3·§7 예시 그대로. */
class AiFlowTest {
    private var now = 1_000L
    private val events = mutableListOf<Event>()
    private val flow = AiFlow(emit = { events += it }, clock = { now })

    private fun msg(json: String) = flow.onMessage(AiMessages.parse(json))
    private val judges get() = events.filterIsInstance<Event.JudgeResult>()

    @Test fun `문서 예시 메시지를 모두 해석한다`() {
        assertEquals(AiMsg.Searching("too_small"), AiMessages.parse("""{"state":"searching","reason":"too_small"}"""))
        assertEquals(AiMsg.Searching(null), AiMessages.parse("""{"state":"searching"}"""))
        assertEquals(AiMsg.Hold(4, 10), AiMessages.parse("""{"state":"hold","collected":4,"inferred":2,"max":10}"""))
        assertEquals(AiMsg.Captured("c311ab4b00e5-01"), AiMessages.parse("""{"state":"captured","result_id":"c311ab4b00e5-01"}"""))
        val r = AiMessages.parse("""{"state":"result","result_id":"c311ab4b00e5-01","decision":"NG","defect_label":"불량 의심",
            "is_anomalous":true,"score":0.1957,"scores":[0.1957,0.1941],"image_threshold":0.1244,"새필드":1,
            "mask_url":"/results/c311ab4b00e5-01/mask","overlay_url":"/results/c311ab4b00e5-01/overlay"}""")
        assertEquals(AiMsg.Result("c311ab4b00e5-01", "NG", "불량 의심", 0.1957), r)
        val a = AiMessages.parse("""{"state":"alert","type":"consecutive_ng","station_id":"ST-02","count":3,"result_ids":["a","b","c"]}""")
        assertEquals(AiMsg.Alert("consecutive_ng", 3, listOf("a", "b", "c")), a)
        assertTrue(AiMessages.parse("깨진 글") is AiMsg.Unknown)
        assertTrue(AiMessages.parse("""{"state":"captured"}""") is AiMsg.Unknown)     // result_id 없음
    }

    @Test fun `OK는 합격 NG와 REVIEW는 불합격 순번은 차례로`() {
        msg("""{"state":"result","result_id":"r1","decision":"OK","defect_label":null}""")
        msg("""{"state":"result","result_id":"r2","decision":"NG"}""")
        msg("""{"state":"result","result_id":"r3","decision":"REVIEW"}""")
        assertEquals(listOf(Verdict.PASS, Verdict.FAIL, Verdict.FAIL), judges.map { it.verdict })
        assertEquals(listOf(1L, 2L, 3L), judges.map { it.seq })
        assertEquals(listOf("r1", "r2", "r3"), judges.map { it.partId })
    }

    @Test fun `지연은 captured 받은 시각부터 result 받은 시각까지`() {
        msg("""{"state":"captured","result_id":"r1"}""")
        now += 230
        msg("""{"state":"result","result_id":"r1","decision":"NG"}""")
        assertEquals(1_000L, judges.single().capturedEndAt)
        assertEquals(1_230L, judges.single().arrivedAt)
        assertEquals(230L, flow.lastDelayMs)
    }

    @Test fun `안내 문구는 마지막 상태를 따른다`() {
        flow.onConnected(); assertEquals("종이 없음", flow.guide)
        msg("""{"state":"searching","reason":"too_small"}"""); assertTrue(flow.guide.startsWith("종이가 작음"))
        msg("""{"state":"hold","collected":3,"inferred":1,"max":10}"""); assertTrue(flow.guide.startsWith("수집 3/10"))
        msg("""{"state":"captured","result_id":"r1"}"""); assertTrue(flow.guide.startsWith("판정 중"))
    }

    @Test fun `끊긴 사이 판정은 조회로 받고 실시간과 겹쳐도 한 번만`() {
        msg("""{"state":"captured","result_id":"r1"}""")
        msg("""{"state":"captured","result_id":"r2"}""")
        assertEquals(listOf("r1", "r2"), flow.pending)

        flow.onPolled(AiResultView("r1", "pending", null, null, null, null))
        assertEquals(listOf("r1", "r2"), flow.pending)                         // 아직 판정 중 — 그대로
        flow.onPolled(AiResultView("r1", "done", "NG", "불량 의심", 0.2, null))
        flow.onPolled(AiResultView("r2", "failed", null, null, null, null))   // 실패 — 버림
        assertEquals(emptyList<String>(), flow.pending)
        assertEquals(listOf("r1"), judges.map { it.partId })

        msg("""{"state":"result","result_id":"r1","decision":"NG"}""")         // 늦게 온 실시간 — 무시
        assertEquals(1, judges.size)
    }

    @Test fun `HMD 진행 안내는 바뀔 때만 보내고 판정 뒤엔 다시 보낸다`() {
        val hints = mutableListOf<String>()
        val f = AiFlow(emit = {}, clock = { now }, onHint = { t, _ -> hints += t })
        f.onConnected()
        f.onMessage(AiMessages.parse("""{"state":"searching","reason":"no_paper"}"""))     // 같은 안내 — 안 보냄
        f.onMessage(AiMessages.parse("""{"state":"searching","reason":"too_small"}"""))
        f.onMessage(AiMessages.parse("""{"state":"hold","collected":1,"max":10}"""))
        f.onMessage(AiMessages.parse("""{"state":"captured","result_id":"r1"}"""))
        f.onMessage(AiMessages.parse("""{"state":"result","result_id":"r1","decision":"NG"}"""))
        f.onMessage(AiMessages.parse("""{"state":"searching","reason":"no_paper"}"""))     // 판정 뒤 — 다시 보냄
        assertEquals(listOf("종이를 비춰 주세요", "더 가까이 — 종이를 크게", "수집 1/10 — 그대로",
                            "판정 중 — 내려놓아도 됩니다", "종이를 비춰 주세요"), hints)
    }

    @Test fun `alert는 반장 알림으로 조회 응답의 alert도`() {
        msg("""{"state":"alert","type":"consecutive_review","station_id":"ST-02","count":3,"result_ids":["a","b","c"]}""")
        val v = AiMessages.parseResult("""{"result_id":"c","station_id":"ST-02","status":"done","decision":"NG",
            "alert":{"state":"alert","type":"consecutive_ng","station_id":"ST-02","count":3,"result_ids":["a","b","c"]}}""")
        flow.onPolled(v)
        val alerts = events.filterIsInstance<Event.PatternAlert>()
        assertEquals(listOf("consecutive_review", "consecutive_ng"), alerts.map { it.level })
        val none = AiMessages.parseResult("""{"result_id":"x","status":"pending","decision":null,"alert":null}""")
        assertNull(none.alert); assertNull(none.decision)
    }
}
