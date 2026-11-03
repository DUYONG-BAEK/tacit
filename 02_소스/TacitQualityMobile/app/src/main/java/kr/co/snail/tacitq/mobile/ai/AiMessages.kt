package kr.co.snail.tacitq.mobile.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 서버 → 폰 메시지 — A4_WEBRTC_API.md §3. `state` 필드로 구분, 모르는 필드는 무시.
 * 설계서 §9.
 */
sealed interface AiMsg {
    /** 조건에 맞는 종이가 없음. reason: no_paper · too_small · (없음) */
    data class Searching(val reason: String?) : AiMsg
    data class Hold(val collected: Int, val max: Int) : AiMsg
    data class Captured(val resultId: String) : AiMsg
    data class Result(val resultId: String, val decision: String, val defectLabel: String?, val score: Double?) : AiMsg
    data class Alert(val type: String, val count: Int, val resultIds: List<String>) : AiMsg
    data class Unknown(val raw: String) : AiMsg
}

/** `GET /results/{id}` 응답 중 앱이 쓰는 것만. status: pending · done · failed */
data class AiResultView(val resultId: String, val status: String, val decision: String?, val defectLabel: String?,
                        val score: Double?, val alert: AiMsg.Alert?)

object AiMessages {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AiMsg = try {
        val o = json.parseToJsonElement(text).jsonObject
        when (o.str("state")) {
            "searching" -> AiMsg.Searching(o.str("reason"))
            "hold" -> AiMsg.Hold(o.int("collected") ?: 0, o.int("max") ?: 10)
            "captured" -> AiMsg.Captured(o.str("result_id")!!)
            "result" -> AiMsg.Result(o.str("result_id")!!, o.str("decision")!!, o.str("defect_label"), o.dbl("score"))
            "alert" -> alert(o)
            else -> AiMsg.Unknown(text)
        }
    } catch (e: Exception) {
        AiMsg.Unknown(text)
    }

    fun parseResult(text: String): AiResultView {
        val o = json.parseToJsonElement(text).jsonObject
        return AiResultView(
            resultId = o.str("result_id")!!, status = o.str("status")!!, decision = o.str("decision"),
            defectLabel = o.str("defect_label"), score = o.dbl("score"),
            alert = (o["alert"] as? JsonObject)?.let(::alert),
        )
    }

    private fun alert(o: JsonObject) = AiMsg.Alert(
        type = o.str("type") ?: "",
        count = o.int("count") ?: 0,
        resultIds = o["result_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
    )

    private fun JsonObject.str(k: String): String? =
        (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(k: String): Int? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
    private fun JsonObject.dbl(k: String): Double? = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
}
