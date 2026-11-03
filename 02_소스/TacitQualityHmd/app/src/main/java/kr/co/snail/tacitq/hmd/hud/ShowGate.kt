package kr.co.snail.tacitq.hmd.hud

/**
 * 쪽지 문지기 — 안드로이드 없이 시험하는 순수 로직. 시각(now)은 밖에서 넣는다.
 *
 *  1. **순번(seq)** — BLE 는 순서를 보장하지 않는다. 마지막에 그린 seq 보다 작으면 버리고,
 *     같으면(모바일의 1회 재전송) 다시 그리지 않고 ACK 만 한다.
 *  2. **3초 무소식 → H-09** (R16) — 연결이 끊기거나, PING 을 받아 오다가 끊기면 HMD 가 스스로 수동 판정 화면.
 *     PING 을 아직 한 번도 못 받았으면 PING 기준 감시는 하지 않는다(모바일 PING 미구현 단계에서 오탐 방지).
 */
class ShowGate(private val silenceMs: Long = 3_000) {

    enum class Verdict { RENDER, DUPLICATE, STALE }

    var lastSeq: Long = 0
        private set
    private var lastPingAt: Long? = null
    private var linkLostAt: Long? = null

    fun onShow(seq: Long, now: Long): Verdict {
        heard(now)
        return when {
            seq > lastSeq -> { lastSeq = seq; Verdict.RENDER }
            seq == lastSeq -> Verdict.DUPLICATE
            else -> Verdict.STALE
        }
    }

    fun onPing(now: Long) {
        lastPingAt = now
        linkLostAt = null
    }

    /** 새 연결 — 재시작한 모바일은 seq 를 1부터 다시 세므로 순번 기준을 초기화한다. */
    fun onConnected() {
        lastSeq = 0
        lastPingAt = null
        linkLostAt = null
    }

    fun onDisconnected(now: Long) {
        linkLostAt = now
        lastPingAt = null
    }

    fun isSilent(now: Long): Boolean {
        val lost = linkLostAt?.let { now - it >= silenceMs } ?: false
        val quiet = lastPingAt?.let { now - it >= silenceMs } ?: false
        return lost || quiet
    }

    private fun heard(now: Long) {
        if (lastPingAt != null) lastPingAt = now
    }
}
