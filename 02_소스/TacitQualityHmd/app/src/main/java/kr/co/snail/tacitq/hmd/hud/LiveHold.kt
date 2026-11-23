package kr.co.snail.tacitq.hmd.hud

import kr.co.snail.tacitq.hmd.protocol.Downlink

/**
 * 실시간 결함 표시 유지 — 안드로이드 없이 시험하는 순수 로직. 시각(now)은 밖에서 넣는다([ShowGate] 와 같은 방식).
 *
 *  - 결함은 프레임마다 잡혔다 안 잡혔다 하므로 마지막 결함 LIVE 뒤 [holdMs] 동안 표시를 유지한다(깜빡임 억제).
 *    모바일 Profile.markHoldMs 와 같은 값이어야 한다.
 *  - d=null(제품을 내림·서버 끊김)·BLE 끊김이면 즉시 지운다([clear]).
 *  - 직전 검사 결과(RESULT)는 다음 결과가 올 때까지 둔다(BLE 끊김이면 지운다). 조준 화면 오른쪽 위 칸([result]).
 *  - 가운데 큰 글자는 조준 화면(H-01)에서만 LIVE(유지 중) > HINT > 화면 기본값.
 *    그 밖의 화면(대기·판정·H-09 등)은 기본값. 모바일 `hmd/HudMirror.kt` 와 같은 규칙이어야 한다.
 */
class LiveHold(private val holdMs: Long = 500) {
    private var live: Downlink.Live? = null
    private var at = 0L
    private var last: Downlink.Result? = null

    fun onResult(r: Downlink.Result) { last = r }

    /** 직전 검사 결과 칸의 글자와 색 — 조준 화면이 아니거나 아직 결과가 없으면 null */
    fun result(base: HudSpec): Pair<String, Tone>? =
        last?.takeIf { base.screen == "H-01" }?.let { resultText(it.d, it.l, it.f) }

    fun onLive(m: Downlink.Live, now: Long) {
        if (m.d == null) { live = null; return }          // 결함 표시만 지운다 — 직전 검사 결과는 그대로
        live = m
        at = now
    }

    /** BLE 끊김 — 결함 표시와 직전 검사 결과를 모두 지운다(R1) */
    fun clear() { live = null; last = null }

    /** 지금 보일 LIVE — 유지 시간이 지났으면 null */
    fun current(now: Long): Downlink.Live? = live?.takeIf { now - at < holdMs }

    /** LIVE 유지가 끝나는 시각까지 남은 시간(다시 그릴 타이머용). 보일 것이 없으면 null */
    fun remainingMs(now: Long): Long? = current(now)?.let { holdMs - (now - at) }

    /** 가운데 큰 글자와 색 — 조준 화면이면 LIVE > HINT > 기본값 */
    fun big(base: HudSpec, hint: Pair<String, Tone>?, now: Long): Pair<String, Tone> {
        if (base.screen != "H-01") return base.big to base.bigTone
        current(now)?.let { return text(it) }
        hint?.let { return it }
        return base.big to base.bigTone
    }

    companion object {
        /** 직전 검사 결과 문구 — `상면 ✓ 정상` · `상면 ✕ 비닐찍힘` (결함 종류가 없으면 `✕ 불합격`). 모바일 HudMirror.resultText 와 같다 */
        fun resultText(d: String, l: String?, f: String?): Pair<String, Tone> {
            val face = f?.ifBlank { null }?.let { "$it " }.orEmpty()
            return if (d == "PASS") "$face✓ 정상" to Tone.OK
            else "$face✕ ${l?.ifBlank { null } ?: "불합격"}" to Tone.BAD
        }

        fun text(m: Downlink.Live): Pair<String, Tone> {
            val label = m.l?.ifBlank { null }?.let { " · $it" }.orEmpty()
            return if (m.d == "NG") "✕ 불량$label" to Tone.BAD else "? 애매$label" to Tone.WARN
        }

        /**
         * 결함 상자(영상 기준 0~1000) → 화면 위 원(중심 x, 중심 y, 반지름). 미리보기는 영상([contentAspect] = 가로/세로)을
         * 자르지 않고 화면 가운데에 맞춘다(FIT_CENTER — 남는 쪽은 검은 띠). 원도 같은 맞춤으로 그린다.
         * 결함은 수 px 라 상자 그대로는 안 보이므로 반지름은 최소 [minRadius](지름 40dp 이상).
         */
        fun circle(b: List<Int>, viewW: Int, viewH: Int, minRadius: Float, contentAspect: Float): Triple<Float, Float, Float> {
            val w = minOf(viewW.toFloat(), viewH * contentAspect); val h = w / contentAspect
            val left = (viewW - w) / 2; val top = (viewH - h) / 2
            val x1 = left + b[0] * w / 1000f; val y1 = top + b[1] * h / 1000f
            val x2 = left + b[2] * w / 1000f; val y2 = top + b[3] * h / 1000f
            val r = maxOf(minRadius, maxOf(x2 - x1, y2 - y1) * 0.75f)
            return Triple((x1 + x2) / 2, (y1 + y2) / 2, r)
        }
    }
}
