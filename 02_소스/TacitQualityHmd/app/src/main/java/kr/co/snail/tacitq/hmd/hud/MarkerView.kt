package kr.co.snail.tacitq.hmd.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kr.co.snail.tacitq.hmd.protocol.Downlink

/** 카메라 미리보기 위 결함 원 — NG 빨강 · REVIEW 노랑. 좌표 변환은 [LiveHold.circle]. 메인 스레드 전용. */
class MarkerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private var live: Downlink.Live? = null

    /** 그릴 결함(null = 지움) — 바뀔 때만 다시 그린다 */
    fun show(m: Downlink.Live?) {
        if (m == live) return
        live = m
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val m = live ?: return
        paint.color = if (m.d == "NG") Tone.BAD.argb else Tone.WARN.argb
        m.b?.forEach { box ->
            if (box.size < 4) return@forEach
            val (cx, cy, r) = LiveHold.circle(box, width, height, minRadius = 20 * density)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}
