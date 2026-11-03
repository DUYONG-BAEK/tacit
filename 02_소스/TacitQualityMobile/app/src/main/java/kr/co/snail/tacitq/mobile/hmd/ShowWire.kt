package kr.co.snail.tacitq.mobile.hmd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kr.co.snail.tacitq.mobile.core.ShowCommand

/**
 * HMD ↔ 모바일 BLE "쪽지"(SHOW)의 와이어 규격 — 구조설계서 §3 메시지 목록.
 *
 * 상태 기계의 내부 결정([ShowCommand])과 전선 위 계약을 분리한다. 필드명은 HMD 팀과 합의한
 * snake_case 그대로여야 하므로 @SerialName 으로 고정한다. 여기가 어긋나면 통합 시점에 반드시 깨진다.
 *
 * 그림(프레임)은 이 쪽지에 담지 않는다 — frame_ref(경로)만 싣고 실제 영상은 굵은 선(Wi-Fi)으로 간다.
 */
@Serializable
data class ShowWire(
    val cmd: String = "SHOW",
    val screen: String,
    val seq: Long,                                       // 순번 — HMD 가 역전을 걸러내는 근거
    @SerialName("ttl_ms") val ttlMs: Long = 0,           // 0 = 자동 소거 안 함
    val vibrate: Int = 0,
    @SerialName("gesture_window") val gestureWindow: String = "NONE",
    @SerialName("window_id") val windowId: String? = null,
    val fields: Map<String, String> = emptyMap(),
)

/**
 * 진행 안내 쪽지 — `{"cmd":"HINT","text":"수집 4/10 — 그대로","tone":"OK"}`. HMD 조준 화면(카메라 위)에만 띄운다.
 * SHOW 와 달리 순번·ACK 없음(최신 것만 의미 있음). tone: OK · WARN · IDLE.
 */
object HintWire {
    fun encode(text: String, tone: String): ByteArray =
        kotlinx.serialization.json.buildJsonObject {
            put("cmd", kotlinx.serialization.json.JsonPrimitive("HINT"))
            put("text", kotlinx.serialization.json.JsonPrimitive(text))
            put("tone", kotlinx.serialization.json.JsonPrimitive(tone))
        }.toString().encodeToByteArray()
}

/** 내부 결정 → 전선 쪽지. seq 는 렌더러가 부여한다. */
fun ShowCommand.toWire(seq: Long) = ShowWire(
    screen = screen,
    seq = seq,
    ttlMs = ttlMs,
    vibrate = vibrate,
    gestureWindow = gestureWindow,
    windowId = windowId,
    fields = fields,
)
