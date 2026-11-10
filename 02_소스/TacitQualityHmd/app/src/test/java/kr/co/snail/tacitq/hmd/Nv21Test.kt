package kr.co.snail.tacitq.hmd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/** [Nv21] 가 옛 YuvToJpeg 의 화소 하나씩 복사와 바이트까지 같은지 — 여러 rowStride·pixelStride 배치로 */
class Nv21Test {

    /** 2026-11-10 이전 YuvToJpeg.yuv420ToNv21 그대로 (ImageProxy 대신 버퍼·stride) */
    private fun reference(
        w: Int, h: Int, y: ByteBuffer, yRow: Int, yPix: Int,
        u: ByteBuffer, v: ByteBuffer, uvRow: Int, uvPix: Int,
    ): ByteArray {
        val nv21 = ByteArray(w * h * 3 / 2)
        var pos = 0
        for (row in 0 until h) {
            val rowStart = row * yRow
            for (col in 0 until w) nv21[pos++] = y.get(rowStart + col * yPix)
        }
        for (row in 0 until h / 2) {
            val rowStart = row * uvRow
            for (col in 0 until w / 2) {
                val idx = rowStart + col * uvPix
                nv21[pos++] = v.get(idx)
                nv21[pos++] = u.get(idx)
            }
        }
        return nv21
    }

    private class Planes(val y: ByteBuffer, val yRow: Int, val yPix: Int, val u: ByteBuffer, val v: ByteBuffer, val uvRow: Int, val uvPix: Int)

    private fun random(n: Int, rnd: Random) = ByteArray(n).also { rnd.nextBytes(it) }

    /** 마지막 줄은 실제로 쓰는 바이트까지만 있는 버퍼 (카메라 버퍼 모양) */
    private fun direct(bytes: ByteArray, off: Int, len: Int): ByteBuffer =
        ByteBuffer.allocateDirect(len).put(bytes, off, len).also { it.flip() }

    /** Y 평면 — pixelStride 1 또는 2, 줄 끝 여백 pad */
    private fun yPlane(w: Int, h: Int, pix: Int, pad: Int, rnd: Random): Triple<ByteBuffer, Int, Int> {
        val row = (w - 1) * pix + 1 + pad
        val len = row * (h - 1) + (w - 1) * pix + 1
        return Triple(direct(random(len, rnd), 0, len), row, pix)
    }

    /** U·V 따로 (I420 모양, pixelStride 1) */
    private fun planar(w: Int, h: Int, yPix: Int, pad: Int, rnd: Random): Planes {
        val (y, yRow, _) = yPlane(w, h, yPix, pad, rnd)
        val row = w / 2 + pad
        val len = row * (h / 2 - 1) + w / 2
        return Planes(y, yRow, yPix, direct(random(len, rnd), 0, len), direct(random(len, rnd), 0, len), row, 1)
    }

    /** U·V 가 한 버퍼에 교대로 (pixelStride 2 — 안드로이드 카메라 대부분). vFirst 면 NV21 메모리(V 가 앞) */
    private fun semiPlanar(w: Int, h: Int, pad: Int, vFirst: Boolean, rnd: Random): Planes {
        val (y, yRow, _) = yPlane(w, h, 1, pad, rnd)
        val row = w + pad
        val span = row * (h / 2 - 1) + w - 1          // 한 평면이 쓰는 길이 (마지막 줄은 w - 1 바이트)
        val all = random(span + 1, rnd)
        val first = direct(all, 0, span)
        val second = direct(all, 1, span)
        return if (vFirst) Planes(y, yRow, 1, u = second, v = first, uvRow = row, uvPix = 2)
        else Planes(y, yRow, 1, u = first, v = second, uvRow = row, uvPix = 2)
    }

    private fun check(w: Int, h: Int, p: Planes) {
        val expected = reference(w, h, p.y, p.yRow, p.yPix, p.u, p.v, p.uvRow, p.uvPix)
        val actual = Nv21.convert(w, h, p.y, p.yRow, p.yPix, p.u, p.v, p.uvRow, p.uvPix)
        assertArrayEquals("${w}x$h yRow=${p.yRow} yPix=${p.yPix} uvRow=${p.uvRow} uvPix=${p.uvPix}", expected, actual)
    }

    @Test fun semiPlanarNoPadding() {
        val rnd = Random(1)
        check(16, 8, semiPlanar(16, 8, pad = 0, vFirst = true, rnd))
        check(16, 8, semiPlanar(16, 8, pad = 0, vFirst = false, rnd))
    }

    @Test fun semiPlanarRowPadding() {
        val rnd = Random(2)
        check(18, 10, semiPlanar(18, 10, pad = 14, vFirst = true, rnd))
        check(18, 10, semiPlanar(18, 10, pad = 14, vFirst = false, rnd))
    }

    @Test fun planarWithAndWithoutPadding() {
        val rnd = Random(3)
        check(20, 6, planar(20, 6, yPix = 1, pad = 0, rnd))
        check(20, 6, planar(20, 6, yPix = 1, pad = 5, rnd))
    }

    @Test fun yPixelStrideTwo() {
        check(12, 4, planar(12, 4, yPix = 2, pad = 3, Random(4)))
    }

    /** 실제 크기 — 1440×1080 (RealWear 520 분석 영상, 2026-11-10~) · 1920×1440 (이전) */
    @Test fun fullSize() {
        check(1440, 1080, semiPlanar(1440, 1080, pad = 64, vFirst = true, Random(5)))
        check(1920, 1440, semiPlanar(1920, 1440, pad = 64, vFirst = true, Random(8)))
    }

    /** 버퍼 위치(position)를 옮기지 않는다 — 카메라 버퍼를 다른 곳에서 다시 읽어도 처음 그대로 */
    @Test fun leavesBufferPositionsAlone() {
        val p = semiPlanar(16, 8, pad = 4, vFirst = true, Random(6))
        Nv21.convert(16, 8, p.y, p.yRow, p.yPix, p.u, p.v, p.uvRow, p.uvPix)
        assertEquals(0, p.y.position()); assertEquals(0, p.u.position()); assertEquals(0, p.v.position())
    }

    /** 넘긴 출력 배열에 그대로 쓴다 (재사용) */
    @Test fun writesIntoGivenArray() {
        val p = semiPlanar(16, 8, pad = 0, vFirst = true, Random(7))
        val out = ByteArray(16 * 8 * 3 / 2)
        val r = Nv21.convert(16, 8, p.y, p.yRow, p.yPix, p.u, p.v, p.uvRow, p.uvPix, out)
        assert(r === out)
        assertArrayEquals(reference(16, 8, p.y, p.yRow, p.yPix, p.u, p.v, p.uvRow, p.uvPix), out)
    }
}
