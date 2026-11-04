package kr.co.snail.tacitq.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** AI 서버 메시지 → 안내 문구·HMD 안내. 1단계 범위(구간·이력은 2단계). */
class AiFlowTest {
    private val hints = mutableListOf<String>()
    private val flow = AiFlow(emit = {}, onHint = { t, _ -> hints += t })

    private fun msg(json: String) = flow.onMessage(AiMessages.parse(json))

    private val ng = """{"state":"inspect","seq":30,"frame_size":[1920,1080],"decision":"NG","defect_label":"칩찍힘",
        "score":0.72,"threshold":0.25,"products":[],"detections":[{"label":"칩찍힘","conf":0.72,"box":[1,1,2,2]}],
        "result_id":null,"overlay_url":null}"""

    @Test fun `안내 문구는 마지막 상태를 따른다`() {
        flow.onConnected(); assertEquals("제품 없음", flow.guide)
        msg(ng); assertEquals("검사 중 · NG 칩찍힘 0.72", flow.guide)
        msg("""{"state":"searching","reason":"no_product"}"""); assertEquals("제품 없음", flow.guide)
        flow.onDisconnected(); assertEquals("연결 끊김", flow.guide)
    }

    @Test fun `HMD 안내는 바뀔 때만 보내고 끊긴 뒤엔 다시 보낸다`() {
        flow.onConnected()
        msg("""{"state":"searching","reason":"no_product"}""")      // 같은 안내 — 안 보냄
        msg(ng); msg(ng)                                             // 프레임마다 와도 한 번
        msg("""{"state":"searching","reason":"no_product"}""")
        flow.onDisconnected()
        flow.onConnected()                                           // 재연결 — 다시 보냄
        assertEquals(listOf("제품을 비춰 주세요", "검사 중", "제품을 비춰 주세요", "제품을 비춰 주세요"), hints)
    }
}
