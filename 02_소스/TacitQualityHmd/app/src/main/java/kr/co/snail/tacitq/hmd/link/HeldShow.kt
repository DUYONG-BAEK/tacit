package kr.co.snail.tacitq.hmd.link

import kr.co.snail.tacitq.hmd.protocol.Downlink
import kr.co.snail.tacitq.hmd.protocol.Wire

/**
 * 화면(MainActivity)이 뜨기 전에 온 화면 지시(SHOW) 보관 — [LinkService] 가 쓴다. 순수 로직(시험 대상).
 *
 * 폰은 SHOW 를 보내고 200ms 안에 ACK 가 없으면 BLE 를 끊고 다시 붙는다(HmdRenderer). 서비스가 화면을 띄우는 1~2초 동안
 * 그러지 않게 서비스가 먼저 ACK 하고, 마지막 화면 지시와 마지막 예외 띠(OVERLAY)만 들고 있다가 화면이 뜨면 넘긴다.
 * 넘긴 SHOW 는 화면이 다시 ACK 하지만 폰은 이미 끝난 순번의 ACK 를 무시한다.
 * PING·HINT·LIVE·FACES·RESULT 는 최신 것만 의미가 있고 계속 다시 오므로 보관하지 않는다.
 */
class HeldShow {
    private var screen: Pair<Long, ByteArray>? = null
    private var overlay: Pair<Long, ByteArray>? = null

    /** 쪽지 한 장. SHOW 면 보관하고 ACK 할 seq 를, 아니면 null 을 돌려준다. */
    fun offer(bytes: ByteArray): Long? {
        val w = (Wire.parse(bytes) as? Downlink.Show)?.wire ?: return null
        val slot = if (w.screen == "OVERLAY") overlay else screen
        if (slot == null || w.seq > slot.first) {
            if (w.screen == "OVERLAY") overlay = w.seq to bytes else screen = w.seq to bytes
        }
        return w.seq
    }

    /** 화면이 떴을 때 넘길 쪽지 — 순번 순서대로(화면의 [kr.co.snail.tacitq.hmd.hud.ShowGate] 가 작은 순번을 버리므로). 꺼내면 비운다. */
    fun take(): List<ByteArray> {
        val out = listOfNotNull(screen, overlay).sortedBy { it.first }.map { it.second }
        clear()
        return out
    }

    /** 폰이 끊기면 버린다 — 다시 붙으면 폰이 지금 화면을 다시 보낸다 */
    fun clear() {
        screen = null
        overlay = null
    }
}
