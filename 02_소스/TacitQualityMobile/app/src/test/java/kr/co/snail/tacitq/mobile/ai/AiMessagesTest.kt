package kr.co.snail.tacitq.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 서버 메시지 해석 — 2026-11-03 규격 §3·§4.1 예시 그대로. */
class AiMessagesTest {

    @Test fun `searching 은 reason 이 있든 없든 해석한다`() {
        assertEquals(AiMsg.Searching("no_product"), AiMessages.parse("""{ "state": "searching", "reason": "no_product" }"""))
        assertEquals(AiMsg.Searching(null), AiMessages.parse("""{"state":"searching"}"""))
    }

    @Test fun `inspect NG 예시를 모두 읽는다`() {
        val m = AiMessages.parse("""
            {
              "state": "inspect", "seq": 30, "frame_size": [1920, 1080],
              "decision": "NG", "defect_label": "칩찍힘", "score": 0.72, "threshold": 0.25,
              "products": [ { "label": "상면", "conf": 0.91, "box": [402.0, 130.5, 1510.2, 1002.8] } ],
              "detections": [ { "label": "칩찍힘", "conf": 0.72, "box": [1102.7, 977.6, 1109.7, 984.0] } ],
              "result_id": "c311ab4b00e5-000030",
              "overlay_url": "/results/c311ab4b00e5-000030/overlay"
            }""")
        assertEquals(
            AiMsg.Inspect(
                seq = 30, frameW = 1920, frameH = 1080, decision = "NG", defectLabel = "칩찍힘",
                score = 0.72, threshold = 0.25,
                products = listOf(AiBox("상면", 0.91, listOf(402.0, 130.5, 1510.2, 1002.8))),
                detections = listOf(AiBox("칩찍힘", 0.72, listOf(1102.7, 977.6, 1109.7, 984.0))),
                resultId = "c311ab4b00e5-000030", overlayUrl = "/results/c311ab4b00e5-000030/overlay",
            ),
            m,
        )
    }

    @Test fun `inspect OK 는 결함 없음 result_id 없음`() {
        val m = AiMessages.parse("""{"state":"inspect","seq":29,"frame_size":[1920,1080],"decision":"OK",
            "defect_label":null,"score":0,"threshold":0.25,
            "products":[{"label":"엣지","conf":0.8,"box":[1,2,3,4]}],"detections":[],"result_id":null,"overlay_url":null}""")
        m as AiMsg.Inspect
        assertEquals("OK", m.decision)
        assertNull(m.defectLabel); assertNull(m.resultId); assertNull(m.overlayUrl)
        assertEquals(0.0, m.score, 0.0)
        assertTrue(m.detections.isEmpty())
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), m.products.single().box)
    }

    @Test fun `모르는 필드는 무시하고 모르는 state 와 깨진 글은 Unknown`() {
        val m = AiMessages.parse("""{"state":"inspect","seq":1,"frame_size":[640,480],"decision":"REVIEW","score":0.24,
            "threshold":0.25,"products":[],"detections":[{"label":"이물찍힘","conf":0.24,"box":[1,1,2,2],"새필드":1}],
            "result_id":"x","overlay_url":"/results/x/overlay","새필드":{"a":1}}""")
        assertEquals("REVIEW", (m as AiMsg.Inspect).decision)
        assertTrue(AiMessages.parse("""{"state":"hold","collected":3}""") is AiMsg.Unknown)     // 이전 규격
        assertTrue(AiMessages.parse("깨진 글") is AiMsg.Unknown)
        assertTrue(AiMessages.parse("""{"state":"inspect","seq":1}""") is AiMsg.Unknown)       // frame_size·decision 없음
    }

    @Test fun `결과 조회 응답은 detections 와 frame_size 를 읽는다`() {
        val v = AiMessages.parseResult("""{"result_id":"c311ab4b00e5-000030","station_id":"ST-02","status":"done",
            "decision":"NG","defect_label":"칩찍힘","score":0.72,"scores":[0.72],"threshold":0.25,
            "detections":[{"label":"칩찍힘","conf":0.72,"box":[1102.7,977.6,1109.7,984.0]}],
            "frame_size":[1920,1080],"overlay_url":"/results/c311ab4b00e5-000030/overlay",
            "captured_at":"2026-11-03T15:04:47.691+09:00","result_at":"2026-11-03T15:04:47.691+09:00","alert":null}""")
        assertEquals("c311ab4b00e5-000030", v.resultId)
        assertEquals("NG", v.decision)
        assertEquals(1920 to 1080, v.frameW to v.frameH)
        assertEquals("칩찍힘", v.detections.single().label)
        assertEquals("/results/c311ab4b00e5-000030/overlay", v.overlayUrl)
    }
}
