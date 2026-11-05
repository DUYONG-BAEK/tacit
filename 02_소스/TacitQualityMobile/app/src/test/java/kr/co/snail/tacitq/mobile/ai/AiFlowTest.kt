package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 서버 프레임 단위 판정 → 실시간 표시 · 진동 · 제품 구간 판정(R1~R8). 시각은 가짜 시계. */
class AiFlowTest {
    private var now = 10_000L
    private val events = mutableListOf<Event>()
    private val hints = mutableListOf<String>()
    private val lives = mutableListOf<LiveMark>()
    private var alarms = 0
    private val flow = AiFlow(
        // 구간·확정 규칙만 보려고 앱 보수 기준은 끈다(서버 판정 그대로) — 보수 기준은 InspectFilterTest·아래 시험
        emit = { events += it }, stationId = "ST-02", profile = Profile(pdMinConf = 0.0, qiFactor = 1.0), clock = { now },
        onHint = { t, _ -> hints += t }, onLive = { lives += it }, onAlarm = { alarms++ },
    )
    private val judges get() = events.filterIsInstance<Event.JudgeResult>()

    private var seq = 0L
    private fun inspect(decision: String, label: String? = null, score: Double = 0.0, resultId: String? = null) {
        val det = if (decision == "OK") "[]" else """[{"label":"$label","conf":$score,"box":[100,200,110,210]}]"""
        flow.onMessage(AiMessages.parse("""{"state":"inspect","seq":${++seq},"frame_size":[1920,1080],"decision":"$decision",
            "defect_label":${label?.let { "\"$it\"" } ?: "null"},"score":$score,"threshold":0.25,"products":[$PRODUCT],
            "detections":$det,"result_id":${resultId?.let { "\"$it\"" } ?: "null"},"overlay_url":null}"""))
    }
    private val PRODUCT = """{"label":"상면","conf":0.91,"box":[0,0,1920,1080]}"""
    private fun searching() = flow.onMessage(AiMessages.parse("""{"state":"searching","reason":"no_product"}"""))
    private fun step(ms: Long = 100) { now += ms }
    private fun tick(ms: Long) { now += ms; flow.onTick() }

    @Test fun `앱 보수 기준 - 신뢰도 낮은 제품은 제품 없음, 기준값 1점3배면 서버 REVIEW 는 결함 아님`() {
        val f = AiFlow(emit = { events += it }, profile = Profile(), clock = { now }, onLive = { lives += it })
        f.onMessage(AiMessages.parse("""{"state":"inspect","seq":1,"frame_size":[1920,1080],"decision":"OK","score":0,
            "threshold":0.25,"products":[{"label":"엣지","conf":0.3,"box":[0,0,1920,1080]}],"detections":[]}"""))
        assertEquals("제품 없음 (앱 기준 PD 0.5 미만)", f.guide)
        f.onMessage(AiMessages.parse("""{"state":"inspect","seq":2,"frame_size":[1920,1080],"decision":"REVIEW",
            "defect_label":"이물찍힘","score":0.26,"threshold":0.25,"products":[$PRODUCT],
            "detections":[{"label":"이물찍힘","conf":0.26,"box":[100,200,110,210]}],"result_id":"r-9"}"""))
        assertEquals("검사 중 · OK", f.guide)
        assertTrue("앱 기준 결함 아님 — HMD 에 보내지 않음", lives.isEmpty())
        now += 1_000; f.onTick()
        assertEquals("OK", judges.single().aiDecision)
        assertEquals(1, judges.single().frames)
        assertEquals(emptyList<String>(), judges.single().resultIds)
    }

    @Test fun `결함 확정 규칙 - 5장 중 2장 이상 NG가 있으면 NG`() {
        assertNull(AiFlow.confirm(listOf("OK", "NG", "OK", "OK", "OK"), 2))
        assertEquals("REVIEW", AiFlow.confirm(listOf("REVIEW", "OK", "REVIEW"), 2))
        assertEquals("NG", AiFlow.confirm(listOf("REVIEW", "OK", "NG"), 2))
    }

    @Test fun `한 장짜리 오탐은 합격 구간`() {
        inspect("OK"); step(); inspect("NG", "칩찍힘", 0.4); step()
        repeat(4) { inspect("OK"); step() }                         // 결함 1장은 5장 창에서 빠진다
        inspect("NG", "칩찍힘", 0.3); step()                          // 6장 뒤 또 1장 — 창 안에 1장뿐
        tick(1_000)
        val j = judges.single()
        assertEquals(Verdict.PASS, j.verdict)
        assertEquals("OK", j.aiDecision)
        assertEquals(7, j.frames); assertEquals(2, j.defectFrames)
        assertEquals(0, alarms)
    }

    @Test fun `5장 중 2장이면 확정되고 진동은 구간당 1회`() {
        inspect("REVIEW", "이물찍힘", 0.24); step(); inspect("OK"); step()
        inspect("REVIEW", "이물찍힘", 0.25); step()                   // 확정 REVIEW
        assertEquals(1, alarms)
        inspect("NG", "칩찍힘", 0.72); step()                         // NG 로 올림 — 진동은 다시 안 울림
        inspect("NG", "칩찍힘", 0.70); step()
        assertEquals(1, alarms)
        tick(1_000)
        val j = judges.single()
        assertEquals(Verdict.FAIL, j.verdict)
        assertEquals("NG", j.aiDecision)
        assertEquals("칩찍힘", j.defectLabel)
        assertEquals(0.72, j.confidence, 1e-9)
        assertEquals(0.25, j.threshold!!, 1e-9)
    }

    @Test fun `REVIEW 만 확정되면 원판정 REVIEW로 남는다`() {
        inspect("REVIEW", "이물찍힘", 0.24); step(); inspect("REVIEW", "이물찍힘", 0.24)
        tick(1_000)
        assertEquals("REVIEW", judges.single().aiDecision)
        assertEquals(Verdict.FAIL, judges.single().verdict)
    }

    @Test fun `searching 한 장 뒤 1초 안에 inspect 가 다시 오면 같은 구간`() {
        inspect("OK"); step(); searching(); tick(500); inspect("OK"); tick(500)
        assertTrue(judges.isEmpty())
        tick(500)                                                   // 마지막 inspect 뒤 1초
        assertEquals(2, judges.single().frames)
    }

    @Test fun `1초 무소식이면 구간이 끝나고 다음 inspect 는 새 구간`() {
        inspect("OK"); tick(999)
        assertTrue(judges.isEmpty())
        tick(1)
        assertEquals(1, judges.size)
        step(2_000); inspect("OK"); tick(1_000)
        assertEquals(listOf(1L, 2L), judges.map { it.seq })
        assertEquals(listOf("ST-02-10000", "ST-02-13000"), judges.map { it.partId })
    }

    @Test fun `연결이 끊기면 구간이 바로 끝난다`() {
        inspect("OK"); step()
        flow.onDisconnected()
        assertEquals(1, judges.size)
        flow.onDisconnected()                                       // 재연결 실패마다 불려도 한 번
        assertEquals(1, judges.size)
    }

    @Test fun `result_id 는 구간 안 모든 것을 점수 높은 순으로`() {
        inspect("NG", "칩찍힘", 0.5, "r-1"); step()
        inspect("NG", "칩찍힘", 0.9, "r-2"); step()
        inspect("NG", "칩찍힘", 0.7, null); step()                    // 1초 안이라 서버가 저장하지 않은 프레임
        inspect("REVIEW", "이물찍힘", 0.24, "r-3")
        tick(1_000)
        assertEquals(listOf("r-2", "r-1", "r-3"), judges.single().resultIds)
        assertEquals("r-2", judges.single().frameRef)
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
        assertEquals(listOf("제품을 비춰 주세요", "검사 중", "제품을 비춰 주세요", "제품을 비춰 주세요"), hints)
    }

    @Test fun `끝난 구간 요약을 화면용으로 남긴다`() {
        inspect("NG", "칩찍힘", 0.72); step(); inspect("NG", "칩찍힘", 0.7); step(); inspect("OK")
        tick(1_000)
        assertEquals(SegmentSummary(pass = false, aiDecision = "NG", defectLabel = "칩찍힘", defectFrames = 2, frames = 3), flow.lastSegment)
    }
}
