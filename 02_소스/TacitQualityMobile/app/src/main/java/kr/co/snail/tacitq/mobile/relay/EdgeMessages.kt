package kr.co.snail.tacitq.mobile.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 엣지 ↔ 폰 텍스트 메시지 — 설계서 부록 C ③④. 필드명은 snake_case 로 고정.
 */
sealed interface EdgeMsg {
    @Serializable
    data class Hb(
        @SerialName("recv_frames") val recvFrames: Long = 0,
        val fps: Double = 0.0,
        val queue: Int = 0,
    ) : EdgeMsg

    @Serializable
    data class Judge(
        val seq: Long,
        @SerialName("part_id") val partId: String,
        val verdict: String,
        val severity: String? = null,
        val confidence: Double = 0.0,
        @SerialName("agree_rate") val agreeRate: Double = 1.0,
        val evidence: String = "",
        @SerialName("frame_ref") val frameRef: String? = null,
        val bbox: List<Double>? = null,
        @SerialName("frame_seq_start") val frameSeqStart: Long = 0,
        @SerialName("frame_seq_end") val frameSeqEnd: Long = 0,
    ) : EdgeMsg

    @Serializable
    data class Pattern(
        val level: String,
        @SerialName("defect_type") val defectType: String,
        val hit: Int,
        val window: Int,
    ) : EdgeMsg

    /** 시험용 — 모의 엣지 대시보드 [확인] 버튼. 실제로는 현장 트레이 센서가 알린다(R08). */
    @Serializable
    data class Tray(
        @SerialName("part_id") val partId: String,
        val kind: String = "OK",
    ) : EdgeMsg

    data class Unknown(val raw: String) : EdgeMsg
}

@Serializable
data class Hello(
    val type: String = "HELLO",
    @SerialName("session_id") val sessionId: String,
    val line: String,
    @SerialName("part_no") val partNo: String,
    @SerialName("view_total") val viewTotal: Int,
    val codec: String = "jpeg",
    val app: String = "TacitQualityMobile",
)

object EdgeMessages {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(text: String): EdgeMsg = try {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj["type"]?.jsonPrimitive?.content) {
            "HB" -> json.decodeFromJsonElement(EdgeMsg.Hb.serializer(), obj)
            "JUDGE" -> json.decodeFromJsonElement(EdgeMsg.Judge.serializer(), obj)
            "PATTERN" -> json.decodeFromJsonElement(EdgeMsg.Pattern.serializer(), obj)
            "TRAY" -> json.decodeFromJsonElement(EdgeMsg.Tray.serializer(), obj)
            else -> EdgeMsg.Unknown(text)
        }
    } catch (e: Exception) {
        EdgeMsg.Unknown(text)
    }

    fun hello(h: Hello): String = json.encodeToString(Hello.serializer(), h)
}
