package kr.co.snail.tacitq.hmd.hud

import kr.co.snail.tacitq.hmd.protocol.Downlink

/**
 * 실시간 결함 표시 유지 — 안드로이드 없이 시험하는 순수 로직. 시각(now)은 밖에서 넣는다([ShowGate] 와 같은 방식).
 *
 *  - 결함은 프레임마다 잡혔다 안 잡혔다 하므로 마지막 결함 LIVE 뒤 [holdMs] 동안 표시를 유지한다(깜빡임 억제).
 *    모바일 Profile.markHoldMs 와 같은 값이어야 한다.
 *  - d=null(제품을 내림·서버 끊김)·BLE 끊김이면 즉시 지운다([clear]).
 *  - 가운데 큰 글자는 조준 화면(H-01)에서만 LIVE(유지 중) > HINT > 화면 기본값. 그 밖의 화면(대기·판정·H-09 등)은 기본값.
 */
class LiveHold(private val holdMs: Long = 500) {
    private var live: Downlink.Live? = null
    private var at = 0L

    fun onLive(m: Downlink.Live, now: Long) {
        if (m.d == null) { clear(); return }
        live = m
        at = now
    }

    fun clear() { live = null }

    /** 지금 보일 LIVE — 유지 시간이 지났으면 null */
    fun current(now: Long): Downlink.Live? = live?.takeIf { now - at < holdMs }

    /** 유지가 끝나는 시각까지 남은 시간(다시 그릴 타이머용). 보일 것이 없으면 null */
    fun remainingMs(now: Long): Long? = current(now)?.let { holdMs - (now - at) }

    /** 가운데 큰 글자와 색 — 조준 화면이면 LIVE > HINT > 기본값 */
    fun big(base: HudSpec, hint: Pair<String, Tone>?, now: Long): Pair<String, Tone> {
        if (base.screen != "H-01") return base.big to base.bigTone
        current(now)?.let { return text(it) }
        hint?.let { return it }
        return base.big to base.bigTone
    }

    companion object {
        fun text(m: Downlink.Live): Pair<String, Tone> {
            val label = m.l?.ifBlank { null }?.let { " · $it" }.orEmpty()
            return if (m.d == "NG") "✕ 불량$label" to Tone.BAD else "? 애매$label" to Tone.WARN
        }

        /**
         * 결함 상자(영상 기준 0~1000) → 화면 위 원(중심 x, 중심 y, 반지름). 미리보기가 화면을 같은 비율(16:9)로
         * 꽉 채운다는 전제. 결함은 수 px 라 상자 그대로는 안 보이므로 반지름은 최소 [minRadius](지름 40dp 이상).
         */
        fun circle(b: List<Int>, viewW: Int, viewH: Int, minRadius: Float): Triple<Float, Float, Float> {
            val x1 = b[0] * viewW / 1000f; val y1 = b[1] * viewH / 1000f
            val x2 = b[2] * viewW / 1000f; val y2 = b[3] * viewH / 1000f
            val r = maxOf(minRadius, maxOf(x2 - x1, y2 - y1) * 0.75f)
            return Triple((x1 + x2) / 2, (y1 + y2) / 2, r)
        }
    }
}
