package kr.co.snail.tacitq.hmd.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * 하드웨어 H.265(HEVC) 인코더 — 설계서 §4 (E2). 카메라가 [inputSurface] 에 그리면 압축된 프레임이 [onFrame] 으로 나온다.
 *
 *  - 키프레임 1초마다. 키프레임 앞에 코덱 설정(VPS/SPS/PPS)을 붙여, 아무 때나 붙은 수신자도 1초 안에 화면을 복원한다.
 *  - 출력은 Annex-B(시작 부호 00 00 00 01) 접근 단위 — 폰은 손대지 않고 엣지로 넘긴다.
 *  - 촬영 시각은 인코더 출력 시각으로 근사(인코더 지연 1~2프레임).
 */
class HevcEncoder(
    width: Int,
    height: Int,
    fps: Int = 30,
    bitrate: Int = 8_000_000,
    private val onFrame: (data: ByteArray, capturedAt: Long, key: Boolean) -> Unit,
) {
    private val thread = HandlerThread("hevc-encoder").apply { start() }
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    @Volatile private var csd: ByteArray? = null      // VPS/SPS/PPS

    val inputSurface: Surface
    val name: String get() = codec.name

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)                 // 실시간
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit   // 표면 입력이라 쓰지 않음

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buf = c.getOutputBuffer(index)
                val bytes = ByteArray(info.size)
                if (buf != null) {
                    buf.position(info.offset)
                    buf.get(bytes)
                }
                c.releaseOutputBuffer(index, false)
                if (info.size == 0) return
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    csd = bytes
                    return
                }
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val cfg = csd
                onFrame(if (key && cfg != null) cfg + bytes else bytes, System.currentTimeMillis(), key)
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                // 일부 인코더는 설정을 버퍼가 아니라 형식(csd-0)으로 준다
                format.getByteBuffer("csd-0")?.let { b ->
                    csd = ByteArray(b.remaining()).also { b.duplicate().get(it) }
                }
                Log.i(TAG, "출력 형식 $format")
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "인코더 오류: ${e.diagnosticInfo}", e)
            }
        }, Handler(thread.looper))
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        Log.i(TAG, "HEVC 인코더 시작 ${codec.name} ${width}x$height ${fps}fps ${bitrate / 1_000_000}Mbps")
    }

    /** 새 수신자가 붙었을 때 — 1초를 기다리지 않고 바로 키프레임을 만든다. */
    fun requestKeyFrame() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun stop() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "TacitQ.Hevc"
    }
}
