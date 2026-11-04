package kr.co.snail.tacitq.mobile.hmd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kr.co.snail.tacitq.mobile.ai.AiBox
import kr.co.snail.tacitq.mobile.ai.LiveMark
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

/**
 * 실시간 결함 쪽지 — `{"cmd":"LIVE","d":"NG","l":"칩찍힘","b":[[573,905,578,911]]}` · 지우기 `{"cmd":"LIVE","d":null}`.
 * 순번·ACK 없음(최신 것만 의미 있음), 응답 없는 쓰기로 보낸다. HMD 가 마지막 결함 뒤 0.5초 유지 후 스스로 지운다.
 *
 *  - d: 프레임 판정 NG · REVIEW (null = 즉시 지움) · l: defect_label
 *  - b: 결함 상자 [x1,y1,x2,y2], frame_size 기준 0~1000 정수. 겹치거나 맞닿은 상자는 합치고(타일 경계에서 겹쳐 온다),
 *    신뢰도 높은 순으로 [MAX_BYTES] 안에 들어가는 만큼만. marks=false(결함 원 끔)면 넣지 않는다.
 */
object LiveWire {
    const val MAX_BYTES = 480                    // BLE MTU 512 전제 — 1패킷

    fun encode(m: LiveMark, marks: Boolean): ByteArray {
        if (m.decision == null) return """{"cmd":"LIVE","d":null}""".encodeToByteArray()
        val boxes = if (marks && m.frameW > 0 && m.frameH > 0) merge(m.detections).map { scale(it.box, m.frameW, m.frameH) } else null
        var n = boxes?.size ?: 0
        while (true) {
            val bytes = json(m, boxes?.take(n)).encodeToByteArray()
            if (bytes.size <= MAX_BYTES || n == 0) return bytes
            n--
        }
    }

    /** 겹치거나 맞닿은 상자를 하나로(신뢰도는 큰 쪽) — 신뢰도 높은 순 */
    fun merge(boxes: List<AiBox>): List<AiBox> {
        val out = boxes.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                val a = out[i].box; val b = out[j].box
                if (a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]) {
                    val top = if (out[i].conf >= out[j].conf) out[i] else out[j]
                    out[i] = AiBox(top.label, top.conf, listOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3])))
                    out.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        return out.sortedByDescending { it.conf }
    }

    private fun scale(b: List<Double>, w: Int, h: Int) = listOf(
        k(b[0], w), k(b[1], h), k(b[2], w), k(b[3], h),
    )

    private fun k(v: Double, size: Int) = Math.round(v * 1000 / size).toInt().coerceIn(0, 1000)

    private fun json(m: LiveMark, boxes: List<List<Int>>?) = kotlinx.serialization.json.buildJsonObject {
        put("cmd", kotlinx.serialization.json.JsonPrimitive("LIVE"))
        put("d", kotlinx.serialization.json.JsonPrimitive(m.decision))
        put("l", kotlinx.serialization.json.JsonPrimitive(m.label))
        if (boxes != null) put("b", kotlinx.serialization.json.JsonArray(boxes.map { b ->
            kotlinx.serialization.json.JsonArray(b.map { kotlinx.serialization.json.JsonPrimitive(it) })
        }))
    }.toString()
}
