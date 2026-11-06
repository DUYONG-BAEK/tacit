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

    @Test fun `inspect 제품 단위 필드 - 없으면 기본값`() {
        val m = AiMessages.parse("""{"state":"inspect","seq":3,"frame_size":[1920,1440],"decision":"OK","defect_label":null,
            "score":0,"threshold":0.4,"products":[],"detections":[],"result_id":null,"overlay_url":null,
            "product_id":"abc-p0002","faces":{"상면":4,"엣지":1,"하면비닐":0},"faces_seen":["상면"],"complete":false,"rx_at":1762390000123}""") as AiMsg.Inspect
        assertEquals("abc-p0002", m.productId)
        assertEquals(mapOf("상면" to 4, "엣지" to 1, "하면비닐" to 0), m.faces)
        assertEquals(listOf("상면"), m.facesSeen)
        assertEquals(1762390000123L, m.rxAt)
        val old = AiMessages.parse("""{"state":"inspect","seq":3,"frame_size":[1920,1080],"decision":"OK","defect_label":null,
            "score":0,"threshold":0.4,"products":[],"detections":[],"result_id":null,"overlay_url":null}""") as AiMsg.Inspect
        assertEquals(null, old.productId); assertEquals(emptyMap<String, Int>(), old.faces); assertEquals(null, old.rxAt)
    }

    @Test fun `product_end 를 읽는다`() {
        val m = AiMessages.parse("""{"state":"product_end","product_id":"c311-p0007","started_at":"2026-11-06T10:12:03.120+09:00",
            "ended_at":"2026-11-06T10:12:09.870+09:00","end_reason":"gap","decision":"FAIL","ai_decision":"NG","defect_label":"칩찍힘",
            "frames":61,"defect_frames":7,"faces":{"상면":30,"엣지":22,"하면비닐":9},"faces_seen":["상면","엣지","하면비닐"],
            "complete":true,"best_result_id":"c311-000123","result_ids":["c311-000123","c311-000131"],"threshold":0.4,
            "feedback_mode":"manual","feedback":"NOT_ENTERED"}""") as AiMsg.ProductEnd
        val p = m.product
        assertEquals("c311-p0007", p.productId); assertEquals("FAIL", p.decision); assertEquals(false, p.pass)
        assertEquals("NG", p.aiDecision); assertEquals(61, p.frames); assertEquals(7, p.defectFrames)
        assertEquals(true, p.complete); assertEquals("c311-000123", p.bestResultId)
        assertEquals(listOf("c311-000123", "c311-000131"), p.resultIds)
        assertEquals("manual", p.feedbackMode); assertEquals("NOT_ENTERED", p.feedback)
    }

    @Test fun `제품 목록·상세·프레임·저장소 응답`() {
        val page = AiMessages.parseProducts("""{"items":[{"product_id":"a-p0002","station_id":"ST-02","started_at":"x","ended_at":null,
            "decision":null,"ai_decision":null,"defect_label":null,"frames":0,"defect_frames":0,"complete":false,"best_result_id":null,
            "feedback_mode":"auto","feedback":"NONE","feedback_at":null}],"next_before":"a-p0002"}""")
        assertEquals("a-p0002", page.nextBefore); assertEquals(null, page.items.single().decision); assertEquals("NONE", page.items.single().feedback)
        val d = AiMessages.parseProduct("""{"product_id":"a-p0001","decision":"PASS","feedback":"WRONG","feedback_log":[
            {"before":"NOT_ENTERED","after":"UNSURE","source":"live","device":"SM-1","changed_at":"t1"},
            {"before":"UNSURE","after":"WRONG","source":"history","device":null,"changed_at":"t2"}]}""")
        assertEquals(true, d.pass)
        assertEquals(listOf("UNSURE", "WRONG"), d.feedbackLog.map { it.after }); assertEquals(null, d.feedbackLog[1].device)
        val f = AiMessages.parseFrames("""{"items":[{"seq":12,"ts":"t","decision":"NG","score":0.8,"defect_label":"칩찍힘",
            "products":[],"detections":[{"label":"칩찍힘","conf":0.8,"box":[1,2,3,4]}],"frame_size":[1920,1440],"result_id":"r","has_image":true}],"total":61}""")
        assertEquals(61, f.total); assertEquals(12L, f.items.single().seq); assertEquals(1440, f.items.single().frameH); assertTrue(f.items.single().hasImage)
        val s = AiMessages.parseStorage("""{"measured_at":null,"disk_total_bytes":2000,"disk_used_bytes":500,"disk_free_bytes":1500,
            "data_bytes":null,"per_day_bytes":[]}""")
        assertEquals(1500L, s.diskFree); assertTrue(s.data.isEmpty())
        val s2 = AiMessages.parseStorage("""{"measured_at":"t","disk_total_bytes":1,"disk_used_bytes":1,"disk_free_bytes":0,
            "data_bytes":{"product_frames":312122,"logs":7},"per_day_bytes":[{"date":"2026-11-06","bytes":338031}]}""")
        assertEquals(312122L, s2.data["product_frames"]); assertEquals(listOf("2026-11-06" to 338031L), s2.perDay)
    }
}
