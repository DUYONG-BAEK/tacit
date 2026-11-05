package kr.co.snail.tacitq.hmd.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * BLE GATT 주소 — 설계서에 없어 HMD 쪽에서 정의했다. 모바일 BleLink(C1)도 **같은 값**을 써야 한다.
 *
 * HMD = GATT 서버(광고), 모바일 = 클라이언트.
 *  - [DOWN] 모바일 → HMD 쪽지(SHOW·PING·HINT·LIVE). write / write-no-response(LIVE).
 *  - [UP]   HMD → 모바일 쪽지(ACK, 이후 HELLO·HEARTBEAT·GESTURE). notify.
 */
object BleUuids {
    val SERVICE: UUID = UUID.fromString("9e7a1000-3c5b-4f0e-8d21-5a7c0d4e6b01")
    val DOWN: UUID = UUID.fromString("9e7a1001-3c5b-4f0e-8d21-5a7c0d4e6b01")
    val UP: UUID = UUID.fromString("9e7a1002-3c5b-4f0e-8d21-5a7c0d4e6b01")
    /** Client Characteristic Configuration — notify 켜기/끄기 (BLE 표준) */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * 모바일 → HMD 화면 쪽지. 모바일 `hmd/ShowWire.kt` 와 필드·이름이 **완전히 같아야** 한다(구조설계서 §3).
 * fields 는 모두 문자열이다(bbox 도 "x,y,w,h").
 */
@Serializable
data class ShowWire(
    val cmd: String = "SHOW",
    val screen: String,
    val seq: Long,
    @SerialName("ttl_ms") val ttlMs: Long = 0,           // 0 = 자동 소거 안 함
    val vibrate: Int = 0,                                 // 모바일이 동시에 울리는 횟수 — HMD 는 쓰지 않음
    @SerialName("gesture_window") val gestureWindow: String = "NONE",
    @SerialName("window_id") val windowId: String? = null,
    val fields: Map<String, String> = emptyMap(),
)

/** HMD → 모바일 수신 확인. 모바일 HmdRenderer 가 200ms 안에 이걸 못 받으면 1회 재전송한다. */
@Serializable
data class AckWire(
    val type: String = "ACK",
    val seq: Long,
    @SerialName("rendered_at") val renderedAt: Long,
)

/** DOWN 특성으로 들어온 쪽지 한 장의 해석 결과 */
sealed interface Downlink {
    data class Show(val wire: ShowWire) : Downlink
    data class Ping(val seq: Long) : Downlink
    /** 진행 안내 — 조준 화면(카메라 위)에만 표시. 순번·ACK 없음. tone: OK · WARN · IDLE */
    data class Hint(val text: String, val tone: String) : Downlink
    /**
     * 실시간 결함 — 조준 화면(카메라 위)에 판정 글자와 결함 원. 순번·ACK 없음(최신 것만 의미 있음).
     * d: NG · REVIEW (null = 즉시 지움) · l: 결함 종류 · b: 결함 상자 [x1,y1,x2,y2] 영상 기준 0~1000 (null = 원 끔)
     */
    data class Live(val d: String?, val l: String? = null, val b: List<List<Int>>? = null) : Downlink
    data class Unknown(val reason: String) : Downlink
}

object Wire {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 바이트 → 쪽지. 깨진 쪽지는 예외 대신 [Downlink.Unknown] 으로 돌려 수신 루프를 지킨다. */
    fun parse(bytes: ByteArray): Downlink = try {
        val obj: JsonObject = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        when (val cmd = obj["cmd"]?.jsonPrimitive?.content) {
            "SHOW" -> Downlink.Show(json.decodeFromJsonElement(ShowWire.serializer(), obj))
            "PING" -> Downlink.Ping(obj["seq"]?.jsonPrimitive?.longOrNull ?: 0)
            "HINT" -> Downlink.Hint(obj["text"]?.jsonPrimitive?.content ?: "", obj["tone"]?.jsonPrimitive?.content ?: "IDLE")
            "LIVE" -> Downlink.Live(
                d = obj.str("d"),
                l = obj.str("l"),
                b = (obj["b"] as? JsonArray)?.map { box -> box.jsonArray.map { it.jsonPrimitive.int } },
            )
            else -> Downlink.Unknown("cmd=$cmd")
        }
    } catch (e: Exception) {
        Downlink.Unknown(e.message ?: e.javaClass.simpleName)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** UP 특성 읽기 응답 — 모바일이 연결 직후 읽어 HMD 영상 주소를 자동으로 맞춘다(IP 수동 입력 제거). */
    /** [camera]: 카메라 상태 한 줄(정상 · 꺼짐 — 사유 · 시작 실패 — 사유) — 폰 통신 로그에 그대로 남는다 */
    fun hello(videoUrl: String?, camera: String? = null): ByteArray = kotlinx.serialization.json.buildJsonObject {
        put("type", JsonPrimitive("HELLO"))
        put("video_url", JsonPrimitive(videoUrl))
        if (camera != null) put("camera", JsonPrimitive(camera))
    }.toString().encodeToByteArray()

    fun ack(seq: Long, renderedAt: Long): ByteArray =
        json.encodeToString(AckWire.serializer(), AckWire(seq = seq, renderedAt = renderedAt)).encodeToByteArray()
}
