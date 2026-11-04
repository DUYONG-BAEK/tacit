package kr.co.snail.tacitq.mobile.ai

/**
 * 재연결 정책 — API §5.3 + 시도 마감. 안드로이드·WebRTC 를 모른다(단위 시험 대상). 시각은 밖에서 넣는다.
 *
 *  - 간격: 연속 실패 횟수에 따라 1 → 2 → 4 → 5초, 이후 5초. 다음 시도 시각 = 실패한 시각 + 간격.
 *  - 시도 중(offer 를 보내고 CONNECTED 가 오기 전)에는 간격이 지나도 새 시도를 하지 않는다.
 *    한 번의 시도는 [attemptTimeoutMs] 안에 CONNECTED 가 되어야 하고, 넘으면 실패로 센다.
 *  - CONNECTED 가 되면 실패 횟수를 0 으로. 설정 오류(422·403)면 더 시도하지 않는다.
 */
class Reconnect(private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS) {
    enum class State { IDLE, ATTEMPTING, CONNECTED, STOPPED }

    var state = State.IDLE; private set
    var failures = 0; private set
    private var nextAt = Long.MIN_VALUE        // 처음 시도는 바로
    private var attemptAt = 0L

    /** 새 시도를 할 때인가 */
    fun due(now: Long) = state == State.IDLE && now >= nextAt

    fun onAttempt(now: Long) { state = State.ATTEMPTING; attemptAt = now }

    /** 시도가 마감을 넘겼는가 — 넘었으면 부른 쪽이 연결을 닫고 [onFailure] 한다 */
    fun expired(now: Long) = state == State.ATTEMPTING && now - attemptAt >= attemptTimeoutMs

    fun onConnected() { state = State.CONNECTED; failures = 0 }

    /** 시도 실패 또는 연결 끊김. 시도 중·연결됨이 아닐 때 온 것(같은 실패의 중복 보고)은 세지 않는다. */
    fun onFailure(now: Long) {
        if (state != State.ATTEMPTING && state != State.CONNECTED) return
        failures++
        nextAt = now + delayMs(failures)
        state = State.IDLE
    }

    fun onConfigError() { state = State.STOPPED }

    companion object {
        const val ATTEMPT_TIMEOUT_MS = 10_000L
        private val DELAYS = longArrayOf(1_000, 2_000, 4_000, 5_000)
        fun delayMs(failures: Int): Long = DELAYS[(failures.coerceAtLeast(1) - 1).coerceAtMost(DELAYS.size - 1)]
    }
}
