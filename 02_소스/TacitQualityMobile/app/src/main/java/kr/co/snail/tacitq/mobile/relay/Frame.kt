package kr.co.snail.tacitq.mobile.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/**
 * 중계할 프레임 한 장 — HMD 가 준 원본(JPEG 또는 H.265 접근 단위) + 폰이 붙인 메타. 설계서 부록 C ② · §4.
 * 영상은 절대 손대지 않는다(재압축·리사이즈 금지 — 개발가이드 §5).
 */
class Frame(
    val seq: Long,            // 폰이 붙이는 세션 내 순번 — 엣지 판정의 frame_seq_* 가 이걸 가리킨다
    val hmdSeq: Long,         // HMD X-Seq
    val capturedAt: Long,     // HMD X-Captured-At (HMD 시계)
    val receivedAt: Long,     // 폰이 HMD 에서 받은 시각 (폰 시계)
    val jpeg: ByteArray,      // 이름은 역사적 — HEVC 면 Annex-B 접근 단위
    val codec: String = "jpeg",   // "jpeg" | "hevc"
    val key: Boolean = true,      // 키프레임(HEVC). JPEG 는 항상 true
)

@Serializable
private data class FrameHeader(
    val seq: Long,
    @SerialName("hmd_seq") val hmdSeq: Long,
    @SerialName("captured_at") val capturedAt: Long,
    @SerialName("received_at") val receivedAt: Long,
    val codec: String = "jpeg",
    val key: Boolean = true,
    val bytes: Int,
)

object FrameCodec {
    private val json = Json { encodeDefaults = true }

    /** [4바이트 빅엔디언 헤더 길이][헤더 JSON][JPEG 원본] */
    fun encode(f: Frame): ByteArray {
        val header = json.encodeToString(
            FrameHeader.serializer(),
            FrameHeader(f.seq, f.hmdSeq, f.capturedAt, f.receivedAt, f.codec, f.key, bytes = f.jpeg.size),
        ).encodeToByteArray()
        return ByteBuffer.allocate(4 + header.size + f.jpeg.size)
            .putInt(header.size).put(header).put(f.jpeg)
            .array()
    }
}
