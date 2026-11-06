package kr.co.snail.tacitq.mobile.ai

/**
 * 작업자 피드백 — 서버 `products.feedback` 값(PLAN_product_feedback.md D6).
 * 사람이 누르는 값은 [INPUTS] 셋(맞음 · 틀림 · 확인필요)뿐이다. 미입력·피드백 없음은 서버가 정하는 초기값이다.
 */
enum class Feedback(val code: String, val label: String) {
    CORRECT("CORRECT", "맞음"),
    WRONG("WRONG", "틀림"),
    UNSURE("UNSURE", "확인필요"),
    NOT_ENTERED("NOT_ENTERED", "미입력"),      // 수동 모드 제품의 초기값
    NONE("NONE", "피드백 없음");               // 자동 모드 제품의 초기값

    companion object {
        /** 버튼 3개 — M-07 현장 카드 · M-05 이력 */
        val INPUTS = listOf(CORRECT, WRONG, UNSURE)

        fun of(code: String?): Feedback? = entries.firstOrNull { it.code == code }
    }
}

/** 피드백 모드 — 연결 때 `/offer` 의 feedback_mode. 수동이면 제품마다 버튼 3개, 자동이면 피드백을 받지 않는다. */
object FeedbackMode {
    const val MANUAL = "manual"
    const val AUTO = "auto"
}
