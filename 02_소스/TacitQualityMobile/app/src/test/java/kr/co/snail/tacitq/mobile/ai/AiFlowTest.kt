package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 서버 메시지 → 실시간 표시 · 검사 중 안내 · 면 검사 결과(2026-11-23). 면 검사·판정은 서버가 하므로 앱은
 * product_end 를 그대로 넘기는지만 본다. 시각은 가짜 시계.
 */
class AiFlowTest {
    private var now = 10_000L
    private val events = mutableListOf<Event>()
    private val hints = mutableListOf<String>()
    private val tones = mutableListOf<String>()
    private val lives = mutableListOf<LiveMark>()
    private val products = mutableListOf<AiProduct>()
    private val flow = AiFlow(
        emit = { events += it }, clock = { now },
        onHint = { t, tone -> hints += t; tones += tone }, onLive = { lives += it }, onProduct = { products += it },
    )
    private val judges get() = events.filterIsInstance<Event.JudgeResult>()

    private var seq = 0L
    private fun inspect(decision: String, label: String? = null, score: Double = 0.0, resultId: String? = null,
                        face: String = "상면", count: Int = 1) {
        val det = if (decision == "OK") "[]" else """[{"label":"$label","conf":$score,"box":[100,200,110,210]}]"""
        flow.onMessage(AiMessages.parse("""{"state":"inspect","seq":${++seq},"frame_size":[1920,1080],"decision":"$decision",
            "defect_label":${label?.let { "\"$it\"" } ?: "null"},"score":$score,"threshold":0.25,"products":[],
            "detections":$det,"result_id":${resultId?.let { "\"$it\"" } ?: "null"},"overlay_url":null,
            "product_id":"s1-p0001","face":"$face","count":$count,"rx_at":null}"""))
    }
    private fun searching() = flow.onMessage(AiMessages.parse("""{"state":"searching","reason":"no_product"}"""))
    private fun productEnd(id: String, decision: String, ai: String, label: String?, ids: List<String> = emptyList()) =
        flow.onMessage(AiMessages.parse("""{"state":"product_end","product_id":"$id","started_at":"2026-11-06T10:00:00.000+09:00",
            "ended_at":"2026-11-06T10:00:05.000+09:00","end_reason":"done","decision":"$decision","ai_decision":"$ai",
            "defect_label":${label?.let { "\"$it\"" } ?: "null"},"frames":40,"defect_frames":7,"face":"상면","best_seq":12,
            "best_result_id":${ids.firstOrNull()?.let { "\"$it\"" } ?: "null"},
            "result_ids":[${ids.joinToString(",") { "\"$it\"" }}],"threshold":0.4,"feedback_mode":"manual","feedback":"NOT_ENTERED"}"""))
    private fun step(ms: Long = 100) { now += ms }

    @Test fun `product_end 는 서버 판정 그대로 넘긴다`() {
        inspect("NG", "칩찍힘", 0.72, "r-1")
        productEnd("s1-p0001", "FAIL", "NG", "칩찍힘", listOf("r-1", "r-2"))
        val p = products.single()
        assertEquals("s1-p0001", p.productId); assertEquals("FAIL", p.decision); assertEquals("NOT_ENTERED", p.feedback)
        val j = judges.single()
        assertEquals(Verdict.FAIL, j.verdict); assertEquals("NG", j.aiDecision); assertEquals("칩찍힘", j.defectLabel)
        assertEquals("s1-p0001", j.partId); assertEquals(listOf("r-1", "r-2"), j.resultIds); assertEquals("r-1", j.frameRef)
        assertEquals(40, j.frames); assertEquals(7, j.defectFrames)
        assertEquals(SegmentSummary(pass = false, aiDecision = "NG", defectLabel = "칩찍힘", defectFrames = 7, frames = 40, face = "상면"), flow.lastSegment)
        assertEquals("상면", p.face); assertEquals(12L, p.bestSeq)
    }

    @Test fun `합격 제품과 순번`() {
        productEnd("s1-p0001", "PASS", "OK", null)
        productEnd("s1-p0002", "FAIL", "REVIEW", "이물찍힘")
        assertEquals(listOf(Verdict.PASS, Verdict.FAIL), judges.map { it.verdict })
        assertEquals(listOf(1L, 2L), judges.map { it.seq })
        assertEquals("REVIEW", judges[1].aiDecision)
    }

    @Test fun `앱은 구간을 스스로 끝내지 않는다 - 끊겨도 판정 없음`() {
        inspect("NG", "칩찍힘", 0.72); step(5_000); searching()
        flow.onDisconnected()
        assertTrue(judges.isEmpty()); assertTrue(products.isEmpty())
    }

    @Test fun `검사 중 안내는 면 이름 - 면이 바뀔 때만 보낸다`() {
        inspect("OK", face = "상면", count = 1); inspect("OK", face = "상면", count = 2)
        productEnd("s1-p0001", "PASS", "OK", null)                       // 같은 면 다음 검사 — 안내는 그대로
        inspect("OK", face = "상면", count = 1)
        inspect("OK", face = "하면비닐", count = 1)
        assertEquals(listOf("상면 검사 중", "하면비닐 검사 중"), hints)
        assertEquals(listOf("INFO", "INFO"), tones)
    }

    @Test fun `LIVE 는 결함 프레임만 보내고 OK 는 보내지 않는다`() {
        inspect("OK"); step(); inspect("NG", "칩찍힘", 0.72); step(); inspect("OK")
        val m = lives.single()
        assertEquals("NG", m.decision); assertEquals("칩찍힘", m.label)
        assertEquals(1920 to 1080, m.frameW to m.frameH)
        assertEquals(listOf(100.0, 200.0, 110.0, 210.0), m.detections.single().box)
    }

    @Test fun `지우는 LIVE 는 표시 중일 때만 보낸다`() {
        inspect("NG", "칩찍힘", 0.72); step(300); searching()        // 0.3초 — 아직 표시 중
        assertEquals(listOf("NG", null), lives.map { it.decision })
        inspect("NG", "칩찍힘", 0.72); step(600); searching()        // 0.6초 — HMD 가 이미 지웠다
        flow.onDisconnected()                                       // 끊김도 보낼 것 없음
        assertEquals(listOf("NG", null, "NG"), lives.map { it.decision })
    }

    @Test fun `안내 문구와 HMD 안내는 바뀔 때만`() {
        flow.onConnected(); assertEquals("제품 없음", flow.guide)
        searching()                                                 // 같은 안내 — 안 보냄
        inspect("NG", "칩찍힘", 0.72); assertEquals("검사 중 · NG 칩찍힘 0.72", flow.guide)
        inspect("OK")
        searching()
        flow.onDisconnected(); assertEquals("연결 끊김", flow.guide)
        flow.onConnected()                                          // 재연결 — 다시 보냄
        assertEquals(listOf("제품을 비춰 주세요", "상면 검사 중", "제품을 비춰 주세요", "제품을 비춰 주세요"), hints)
    }
}
