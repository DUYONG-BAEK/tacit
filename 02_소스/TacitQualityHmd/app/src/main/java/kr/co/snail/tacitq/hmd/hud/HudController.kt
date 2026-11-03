package kr.co.snail.tacitq.hmd.hud

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.snail.tacitq.hmd.databinding.ActivityMainBinding
import kr.co.snail.tacitq.hmd.protocol.Downlink
import kr.co.snail.tacitq.hmd.protocol.Wire

/**
 * 쪽지 → 화면. 메인 스레드 전용.
 *
 *  - SHOW: [ShowGate] 로 순번 검사 → 그리기 → ACK([sendUp]). 같은 seq(재전송)는 ACK 만.
 *  - ttl_ms > 0: 시간이 지나도 더 새 쪽지가 없으면 대기 화면으로 되돌린다(H-02 합격 1.5초 소거).
 *  - 3초 무소식: H-09 수동 판정을 스스로 띄운다(R16).
 */
class HudController(
    private val b: ActivityMainBinding,
    private val sendUp: (ByteArray) -> Boolean,
) {
    private val gate = ShowGate()
    private val main = Handler(Looper.getMainLooper())
    private var current: HudSpec? = null
    private var shiftClosed = false              // 모바일이 정상 마감했으면 끊겨도 H-09 를 띄우지 않는다
    private var screenSeq = 0L                    // 지금 화면을 그린 쪽지의 seq (ttl 소거 판단용 — OVERLAY 는 제외)
    private var lastAim: HudSpec? = null          // 마지막 조준 화면 — 판정 화면 ttl 이 끝나면 여기로 돌아간다
    private var hint: Pair<String, Tone>? = null  // AI 서버 진행 안내(HINT) — 조준 화면 가운데에 표시

    var lastInfo: String = "-"
        private set

    init {
        render(Screens.waiting("BLE 준비 중"))
        main.post(::watchdog)
    }

    /** DOWN 특성(또는 디버그 주입)으로 들어온 쪽지 한 장 */
    fun onDown(bytes: ByteArray) {
        val now = System.currentTimeMillis()
        when (val msg = Wire.parse(bytes)) {
            is Downlink.Show -> onShow(msg, now)
            is Downlink.Ping -> gate.onPing(now)
            is Downlink.Hint -> onHint(msg, now)
            is Downlink.Unknown -> Log.w(TAG, "모르는 쪽지: ${msg.reason} (${bytes.size}B)")
        }
    }

    fun onLinkConnected() {
        gate.onConnected()
        if (current?.screen == "WAIT") render(Screens.waiting("BLE 연결됨 · 쪽지 대기"))
    }

    fun onLinkDisconnected() = gate.onDisconnected(System.currentTimeMillis())

    fun onLinkState(label: String) {
        if (current?.screen == "WAIT") render(Screens.waiting(label))
    }

    private fun onShow(msg: Downlink.Show, now: Long) {
        val w = msg.wire
        when (gate.onShow(w.seq, now)) {
            ShowGate.Verdict.STALE -> {
                Log.i(TAG, "오래된 쪽지 무시 seq=${w.seq} < ${gate.lastSeq}")
                return
            }
            ShowGate.Verdict.DUPLICATE -> Log.i(TAG, "재전송 seq=${w.seq} — ACK 만")
            ShowGate.Verdict.RENDER -> if (w.screen == "OVERLAY") {
                overlay(w.fields)                    // 화면은 그대로 두고 띠만 켜고 끈다(R17·R18)
            } else {
                // 모바일이 교대를 마감하며 보내는 대기 화면 — 이 뒤의 끊김은 장애가 아니다
                shiftClosed = w.screen == "H-01" && w.fields["state"] == "IDLE"
                screenSeq = w.seq
                render(Screens.from(w))
                if (w.ttlMs > 0) main.postDelayed({ expire(w.seq) }, w.ttlMs)
            }
        }
        val renderedAt = System.currentTimeMillis()
        val acked = sendUp(Wire.ack(w.seq, renderedAt))
        lastInfo = "seq=${w.seq} ${w.screen} ack=${if (acked) "보냄" else "못보냄"} ${renderedAt - now}ms"
        Log.i(TAG, "SHOW $lastInfo")
    }

    /** ttl 만료(합격 1.5초 · 불합격 2초) → 마지막 조준 화면(카메라)으로 */
    private fun expire(seq: Long) {
        if (screenSeq == seq && current?.screen != "H-09") render(lastAim ?: Screens.idle())
    }

    /** HMD 버튼 — 판정 화면(합격·불합격)이면 ttl 을 기다리지 않고 바로 조준 화면(카메라)으로 */
    fun toCamera() {
        val s = current?.screen ?: return
        if (s == "H-02" || s == "H-03") render(lastAim ?: Screens.idle())
    }

    /** 진행 안내 — 조준 화면이면 바로 바꿔 그리고, 판정 화면이면 기억해 두었다가 돌아올 때 쓴다 */
    private fun onHint(h: Downlink.Hint, now: Long) {
        gate.onPing(now)                                      // 살아 있다는 신호로도 친다
        hint = h.text to when (h.tone) { "OK" -> Tone.OK; "WARN" -> Tone.WARN; else -> Tone.IDLE }
        if (current?.screen == "H-01") current?.let(::render)
    }

    /** 예외 띠 — `{"type":"NET","text":"서버 지연 · 검사 계속","on":"true"}`. 판정 화면을 덮지 않는다. */
    private fun overlay(f: Map<String, String>) {
        val on = f["on"] != "false"
        b.overlayText.text = "⚠ " + (f["text"] ?: f["type"] ?: "")
        b.overlayText.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun watchdog() {
        if (gate.isSilent(System.currentTimeMillis())) {
            if (shiftClosed) {
                if (current?.screen != "WAIT") render(Screens.waiting("교대 마감 · 연결 대기"))
            } else if (current?.screen != "H-09") {
                Log.w(TAG, "3초 무소식 → H-09 수동 판정")
                render(Screens.manual(null))
            }
        }
        main.postDelayed(::watchdog, 500)
    }

    fun stop() = main.removeCallbacksAndMessages(null)

    private fun render(base: HudSpec) {
        if (base.screen == "H-01" && base.big.isEmpty()) lastAim = base     // 안내가 덧씌워지기 전 원본을 기억
        val h = hint
        val spec = if (base.screen == "H-01" && h != null) base.copy(big = h.first, bigTone = h.second) else base
        current = base
        b.hud.setBackgroundColor(spec.bg)
        b.topLeft.set(spec.topLeft, spec.topLeftTone)
        b.topRight.set(spec.topRight, spec.topRightTone)
        b.big.set(spec.big, spec.bigTone)
        b.lines.text = spec.lines.joinToString("\n")
        b.keys.text = spec.keys.joinToString("      ")

        b.options.removeAllViews()
        spec.options.forEach { label ->
            b.options.addView(
                TextView(b.root.context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(Tone.STOP.argb)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setBackgroundColor(0x33FFFFFF)
                    setPadding(dp(18), dp(14), dp(18), dp(14))
                },
                LinearLayout.LayoutParams(dp(216), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(12); marginEnd = dp(12)
                },
            )
        }
    }

    private fun TextView.set(text: String, tone: Tone) {
        this.text = text
        setTextColor(tone.argb)
    }

    private fun dp(v: Int) = (v * b.root.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TacitQ.Hud"
    }
}
