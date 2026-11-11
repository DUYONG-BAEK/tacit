package kr.co.snail.tacitq.mobile.hmd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HMD HELLO — BLE UP 특성 읽기 응답. HMD 앱 `protocol/Wire.kt` hello() 와 필드가 같아야 한다.
 * {"type":"HELLO","video_url":"http://IP:8080","camera":"정상 …","battery":87,"charging":false}
 * 옛 HMD 앱은 camera·battery·charging 이 없다(null).
 */
data class HmdHello(
    val videoUrl: String?,
    val camera: String? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
) {
    /** 배터리 부족 — 충전 중이면 아니다 */
    val batteryLow: Boolean get() = battery != null && battery < BATTERY_LOW && charging != true

    companion object {
        /** 이 % 미만이면 부족(R19 · M-01 · M-07 빨강 · 토스트) */
        const val BATTERY_LOW = 15

        private val json = Json { ignoreUnknownKeys = true }

        /** 깨졌거나 HELLO 가 아니면 null */
        fun parse(bytes: ByteArray): HmdHello? = try {
            val o = json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (o["type"]?.jsonPrimitive?.content != "HELLO") null
            else HmdHello(
                videoUrl = o.str("video_url")?.takeIf { it.isNotBlank() },
                camera = o.str("camera"),
                battery = (o["battery"] as? JsonPrimitive)?.intOrNull,
                charging = (o["charging"] as? JsonPrimitive)?.booleanOrNull,
            )
        } catch (e: Exception) {
            null
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
