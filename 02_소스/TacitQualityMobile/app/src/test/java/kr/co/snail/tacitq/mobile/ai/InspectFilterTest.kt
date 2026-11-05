package kr.co.snail.tacitq.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 앱 보수 기준 — PD 신뢰도 하한, QI 기준값 배율로 다시 판정 */
class InspectFilterTest {
    private val product = AiBox("상면", 0.91, listOf(400.0, 100.0, 1500.0, 1000.0))
    private fun det(conf: Double, x: Double = 1100.0) = AiBox("칩찍힘", conf, listOf(x, 900.0, x + 8, 908.0))
    private fun inspect(products: List<AiBox>, dets: List<AiBox>, decision: String = "NG") = AiMsg.Inspect(
        seq = 1, frameW = 1920, frameH = 1080, decision = decision, defectLabel = dets.firstOrNull()?.label,
        score = dets.maxOfOrNull { it.conf } ?: 0.0, threshold = 0.25, products = products, detections = dets,
        resultId = "r-1", overlayUrl = "/results/r-1/overlay",
    )

    @Test fun `PD 신뢰도가 낮은 제품만 있으면 제품 없음`() {
        assertNull(InspectFilter.apply(inspect(listOf(product.copy(conf = 0.4)), emptyList(), "OK"), pdMinConf = 0.5, qiFactor = 1.0))
    }

    @Test fun `QI 기준값을 1점3배로 다시 판정 - 서버 NG 가 REVIEW 나 OK 로`() {
        // 앱 기준 0.325: NG > 0.3575, OK < 0.2925
        val ng = InspectFilter.apply(inspect(listOf(product), listOf(det(0.72))), 0.5, 1.3)!!
        assertEquals("NG", ng.decision)
        val review = InspectFilter.apply(inspect(listOf(product), listOf(det(0.30))), 0.5, 1.3)!!
        assertEquals("REVIEW", review.decision)
        assertEquals(0.325, review.threshold!!, 1e-9)
        val ok = InspectFilter.apply(inspect(listOf(product), listOf(det(0.28))), 0.5, 1.3)!!
        assertEquals("OK", ok.decision)
        assertEquals(emptyList<AiBox>(), ok.detections)
        assertNull("결함 아님이면 저장 프레임을 연결하지 않는다", ok.resultId)
        assertNull(ok.defectLabel)
    }

    @Test fun `버린 제품 상자 밖의 결함은 남기지 않는다`() {
        val weak = AiBox("엣지", 0.3, listOf(1600.0, 100.0, 1900.0, 1000.0))
        val m = inspect(listOf(product, weak), listOf(det(0.9, x = 1700.0), det(0.5)))
        val r = InspectFilter.apply(m, 0.5, 1.0)!!
        assertEquals(listOf(0.5), r.detections.map { it.conf })
        assertEquals(listOf("상면"), r.products.map { it.label })
    }

    @Test fun `1배 0 이면 서버 판정 그대로`() {
        val m = inspect(listOf(product), listOf(det(0.26)), "REVIEW")
        val r = InspectFilter.apply(m, 0.0, 1.0)!!
        assertEquals("REVIEW", r.decision)
        assertEquals(m.detections, r.detections)
        assertEquals("r-1", r.resultId)
    }
}
