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
 * 두 단계([toNv21] · [compress])로 나눠 부른다 — 단계별 시간을 [FrameStats] 로 잰다.
 * 분석 스레드 하나에서만 부른다(NV21 버퍼를 재사용).
 */
object YuvToJpeg {

    /** 장마다 약 4MB(1920×1440)를 새로 잡지 않게 다시 쓴다 — YuvImage 는 compressToJpeg 가 끝나면 놓는다 */
    private var nv21: ByteArray? = null

    /** YUV_420_888 → NV21. rowStride·pixelStride 를 지켜 복사한다([Nv21]). */
    fun toNv21(image: ImageProxy): ByteArray {
        val size = image.width * image.height * 3 / 2
        val out = nv21?.takeIf { it.size == size } ?: ByteArray(size).also { nv21 = it }
        val (y, u, v) = image.planes
        return Nv21.convert(
            image.width, image.height,
            y.buffer, y.rowStride, y.pixelStride,
            u.buffer, v.buffer, v.rowStride, v.pixelStride,
            out,
        )
    }

    fun compress(nv21: ByteArray, width: Int, height: Int, quality: Int): ByteArray {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)
        return out.toByteArray()
    }
}
