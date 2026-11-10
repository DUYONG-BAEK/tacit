package kr.co.snail.tacitq.hmd

import java.nio.ByteBuffer

/**
 * YUV_420_888 세 평면 → NV21(Y 전부, 그다음 V·U 교대). Android 에 기대지 않아 JVM 단위 테스트로 결과를 확인한다(Nv21Test).
 *
 * 2026-11-10: 화소마다 `ByteBuffer.get(index)` 하던 복사(1920×1440 한 장에 약 415만 번)를 줄마다 한 번에 읽도록 바꿨다.
 * 결과 바이트는 옛 방식과 같다. U·V 는 [vRowStride]·[vPixelStride] 를 함께 쓴다(옛 방식 그대로).
 */
object Nv21 {

    fun convert(
        width: Int, height: Int,
        y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        u: ByteBuffer, v: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        out: ByteArray = ByteArray(width * height * 3 / 2),
    ): ByteArray {
        val ySrc = y.duplicate()
        if (yPixelStride == 1) {
            for (row in 0 until height) {
                ySrc.position(row * yRowStride)
                ySrc.get(out, row * width, width)
            }
        } else {
            val line = ByteArray((width - 1) * yPixelStride + 1)
            var pos = 0
            for (row in 0 until height) {
                ySrc.position(row * yRowStride)
                ySrc.get(line)
                var i = 0
                while (i < line.size) { out[pos++] = line[i]; i += yPixelStride }
            }
        }

        // 한 줄에서 실제로 쓰는 바이트만 읽는다 — 마지막 줄은 버퍼가 rowStride 보다 짧게 끝날 수 있다
        val n = (width / 2 - 1) * vPixelStride + 1
        val vLine = ByteArray(n)
        val uLine = ByteArray(n)
        val vSrc = v.duplicate()
        val uSrc = u.duplicate()
        var pos = width * height
        for (row in 0 until height / 2) {
            vSrc.position(row * vRowStride); vSrc.get(vLine)
            uSrc.position(row * vRowStride); uSrc.get(uLine)
            var i = 0
            while (i < n) {
                out[pos++] = vLine[i]
                out[pos++] = uLine[i]
                i += vPixelStride
            }
        }
        return out
    }
}
