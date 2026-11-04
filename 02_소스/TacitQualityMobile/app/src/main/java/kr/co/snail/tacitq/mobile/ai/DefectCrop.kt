package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.hmd.LiveWire

/** 오버레이에서 잘라낼 정사각형(픽셀, 프레임 안) */
data class CropSquare(val left: Int, val top: Int, val side: Int)

/**
 * 결함 확대 보기(API §4.2 "자르는 규칙") — 안드로이드를 모르는 순수 계산.
 *
 *  - 서로 겹치거나 맞닿은 상자는 합쳐 결함 하나로 본다(타일 경계에서 겹쳐 오는 상자).
 *  - 정사각형 한 변 = max(프레임 짧은 변 × [SHORT_SIDE_RATIO], 상자 긴 변 × [BOX_SCALE]), 프레임 짧은 변을 넘지 않게.
 *  - 정사각형이 프레임 밖으로 나가면 안쪽으로 밀어 넣는다.
 *  - 20%·3배는 2026-11-03 실측 결함 크기에 맞춘 출발값이다. 실제 화면에서 보고 조정한다.
 */
object DefectCrop {
    const val SHORT_SIDE_RATIO = 0.20
    const val BOX_SCALE = 3.0

    /** 결함별 정사각형 — 신뢰도 높은 순. 합친 상자와 짝지어 돌려준다. */
    fun squares(detections: List<AiBox>, frameW: Int, frameH: Int): List<Pair<AiBox, CropSquare>> =
        LiveWire.merge(detections).map { it to square(it.box, frameW, frameH) }

    fun square(box: List<Double>, frameW: Int, frameH: Int): CropSquare {
        val short = minOf(frameW, frameH)
        val longSide = maxOf(box[2] - box[0], box[3] - box[1])
        val side = maxOf(short * SHORT_SIDE_RATIO, longSide * BOX_SCALE).coerceAtMost(short.toDouble()).toInt()
        val cx = (box[0] + box[2]) / 2
        val cy = (box[1] + box[3]) / 2
        val left = (cx - side / 2.0).toInt().coerceIn(0, frameW - side)
        val top = (cy - side / 2.0).toInt().coerceIn(0, frameH - side)
        return CropSquare(left, top, side)
    }

    /** src(w×h)를 dst 안에 비율 유지로 맞출 때(ContentScale.Fit) 배율과 왼쪽·위 여백 */
    data class Fit(val scale: Float, val dx: Float, val dy: Float) {
        fun x(v: Double) = (dx + v * scale).toFloat()
        fun y(v: Double) = (dy + v * scale).toFloat()
    }

    fun fit(srcW: Int, srcH: Int, dstW: Float, dstH: Float): Fit {
        val s = minOf(dstW / srcW, dstH / srcH)
        return Fit(s, (dstW - srcW * s) / 2, (dstH - srcH * s) / 2)
    }
}
