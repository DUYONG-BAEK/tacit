package kr.co.snail.tacitq.hmd

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * CameraX 프레임(YUV_420_888) → JPEG 변환.
 *
 * 1단계(MJPEG 송출)에선 각 프레임을 JPEG 로 바꿔 네트워크로 흘린다.
 * 설계상 최종 영상 경로는 H.265(저지연)지만, 여기선 브라우저로 바로 보이는 MJPEG 로 시작한다.
 */
object YuvToJpeg {

    fun convert(image: ImageProxy, quality: Int = 80): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    /** YUV_420_888 → NV21(Y + VU 인터리브). rowStride·pixelStride 를 지켜 안전하게 복사한다. */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Y
        var pos = 0
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        for (row in 0 until h) {
            val rowStart = row * yRowStride
            for (col in 0 until w) {
                nv21[pos++] = yBuffer.get(rowStart + col * yPixStride)
            }
        }

        // VU (NV21 은 V 먼저, U 다음)
        val uvRowStride = vPlane.rowStride
        val uvPixStride = vPlane.pixelStride
        for (row in 0 until h / 2) {
            val rowStart = row * uvRowStride
            for (col in 0 until w / 2) {
                val idx = rowStart + col * uvPixStride
                nv21[pos++] = vBuffer.get(idx)
                nv21[pos++] = uBuffer.get(idx)
            }
        }
        return nv21
    }
}
