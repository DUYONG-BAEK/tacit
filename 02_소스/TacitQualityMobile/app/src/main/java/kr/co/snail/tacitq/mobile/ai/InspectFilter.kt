package kr.co.snail.tacitq.mobile.ai

/**
 * 앱 쪽 보수 기준 — 서버 판정을 앱에서 한 번 더 엄격하게 거른다(기능·시나리오 시험용). 안드로이드를 모르는 순수 함수.
 *
 * PD(제품 탐지)·QI(불량 탐지) 임곗값은 AI 서버가 정하고 앱은 바꿀 수 없다(API §3.3 — threshold 를 앱에 하드코딩하지 않는다).
 * 가모델이라 제품·결함이 너무 자주 잡혀 시험을 방해하므로, 받은 결과를 다음처럼 더 보수적으로 본다.
 *
 *  - PD: 신뢰도 [pdMinConf] 미만인 제품 상자는 버린다. 남는 제품이 없으면 "제품 없음"(searching 과 같게)으로 본다.
 *  - QI: 서버 기준값(threshold)에 [qiFactor] 를 곱한 값을 앱 기준값으로 쓰고, 서버와 같은 규칙으로 다시 판정한다
 *    (API §3.3: score > 기준×1.1 → NG, score < 기준×0.9 → 결함 아님, 그 사이 → REVIEW).
 *    남은 제품 상자 안(상자 중심 기준)의 결함만 남긴다.
 *  - 둘 다 1.0·0.0 이면 서버 판정 그대로.
 *
 * 앱이 결함 아님으로 본 프레임은 result_id 를 떼어 이력·확대 보기에 연결하지 않는다.
 */
object InspectFilter {

    /** @return 거른 판정. null 이면 제품 없음으로 본다 */
    fun apply(m: AiMsg.Inspect, pdMinConf: Double, qiFactor: Double): AiMsg.Inspect? {
        val products = m.products.filter { it.conf >= pdMinConf }
        if (products.isEmpty()) return null
        val t = m.threshold ?: return m.copy(products = products)          // 기준값이 없으면 다시 판정할 수 없다
        val appT = t * qiFactor
        val dets = m.detections.filter { d -> d.conf >= appT * 0.9 && products.any { inside(d.box, it.box) } }
        val score = dets.maxOfOrNull { it.conf } ?: 0.0
        val decision = when {
            dets.isEmpty() -> "OK"
            score > appT * 1.1 -> "NG"
            else -> "REVIEW"
        }
        val ok = decision == "OK"
        return m.copy(
            products = products, detections = dets, decision = decision,
            defectLabel = dets.firstOrNull()?.label, score = score, threshold = appT,
            resultId = if (ok) null else m.resultId, overlayUrl = if (ok) null else m.overlayUrl,
        )
    }

    private fun inside(d: List<Double>, p: List<Double>): Boolean {
        val cx = (d[0] + d[2]) / 2; val cy = (d[1] + d[3]) / 2
        return cx >= p[0] && cx <= p[2] && cy >= p[1] && cy <= p[3]
    }
}
