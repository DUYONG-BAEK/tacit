package kr.co.snail.tacitq.mobile.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * AI 서버 → 폰 메시지 — 「제품 불량 판정 — 모바일 앱 연동 API」 2026-11-03 규격 §3.
 * `state` 필드로 구분, 모르는 필드는 무시. 설계서 §9.
 */
sealed interface AiMsg {
    /** 제품이 안 보임. reason: no_product · (없음 — WebSocket 연결 직후) */
    data class Searching(val reason: String?) : AiMsg

    /** 제품이 보이는 프레임 한 장의 판정(§3.3). box 좌표 기준은 [frameW]×[frameH]. */
    data class Inspect(
        val seq: Long,
        val frameW: Int,
        val frameH: Int,
        val decision: String,                 // OK · NG · REVIEW
        val defectLabel: String?,
        val score: Double,
        val threshold: Double?,
        val products: List<AiBox>,
        val detections: List<AiBox>,          // OK 면 빈 배열, 신뢰도 높은 순
        val resultId: String?,                // 서버가 저장한 결함 프레임에만
        val overlayUrl: String?,
    ) : AiMsg

    data class Unknown(val raw: String) : AiMsg
}

/** 제품·결함 상자 — box = [x1, y1, x2, y2] 픽셀 */
data class AiBox(val label: String, val conf: Double, val box: List<Double>)

/** `GET /results/{id}` 응답(§4.1) 중 앱이 쓰는 것만 */
data class AiResultView(
    val resultId: String,
    val decision: String?,
    val defectLabel: String?,
    val score: Double?,
    val threshold: Double?,
    val detections: List<AiBox>,
    val frameW: Int,
    val frameH: Int,
    val overlayUrl: String?,
)

object AiMessages {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AiMsg = try {
        val o = json.parseToJsonElement(text).jsonObject
        when (o.str("state")) {
            "searching" -> AiMsg.Searching(o.str("reason"))
            "inspect" -> inspect(o)
            else -> AiMsg.Unknown(text)
        }
    } catch (e: Exception) {
        AiMsg.Unknown(text)
    }

    fun parseResult(text: String): AiResultView {
        val o = json.parseToJsonElement(text).jsonObject
        val (w, h) = o.frameSize()
        return AiResultView(
            resultId = o.str("result_id")!!, decision = o.str("decision"), defectLabel = o.str("defect_label"),
            score = o.dbl("score"), threshold = o.dbl("threshold"), detections = o.boxes("detections"),
            frameW = w, frameH = h, overlayUrl = o.str("overlay_url"),
        )
    }

    private fun inspect(o: JsonObject): AiMsg.Inspect {
        val (w, h) = o.frameSize()
        return AiMsg.Inspect(
            seq = (o["seq"] as JsonPrimitive).longOrNull!!,
            frameW = w, frameH = h,
            decision = o.str("decision")!!,
            defectLabel = o.str("defect_label"),
            score = o.dbl("score") ?: 0.0,
            threshold = o.dbl("threshold"),
            products = o.boxes("products"),
            detections = o.boxes("detections"),
            resultId = o.str("result_id"),
            overlayUrl = o.str("overlay_url"),
        )
    }

    private fun JsonObject.frameSize(): Pair<Int, Int> {
        val a = this["frame_size"]!!.jsonArray
        return a[0].jsonPrimitive.intOrNull!! to a[1].jsonPrimitive.intOrNull!!
    }

    private fun JsonObject.boxes(k: String): List<AiBox> =
        (this[k] as? JsonArray)?.map { e ->
            val b = e.jsonObject
            AiBox(b.str("label") ?: "", b.dbl("conf") ?: 0.0, b["box"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! })
        } ?: emptyList()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
}
