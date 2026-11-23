package kr.co.snail.tacitq.mobile.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * AI 서버 → 폰 메시지 — 「제품 불량 판정 — 모바일 앱 연동 API」 2026-11-03 규격 §3 + 2026-11-06 제품 단위 판정
 * + 2026-11-23 면 검사 (`server/PLAN_product_feedback.md` §3·§4). `state` 필드로 구분, 모르는 필드는 무시. 설계서 §3.
 * 2026-11-23부터 "제품"(product_id · product_end · /products) 한 건은 면 검사 1건(같은 면 연속 N장)이다.
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
        // ── 2026-11-06 제품 단위 · 2026-11-23 면 검사 — 옛 서버는 없음(기본값) ──
        val productId: String? = null,              // 면 검사 ID
        val face: String? = null,                   // 검사 중인 면(PD 라벨)
        val count: Int = 0,                         // 그 검사의 몇 번째 장(1~N)
        val rxAt: Long? = null,                     // 게이트웨이가 프레임을 받은 시각(epoch ms) — 지연 측정용
    ) : AiMsg

    /** 면 검사 하나가 완료됨 — 서버가 정한 판정(§3.3) */
    data class ProductEnd(val product: AiProduct) : AiMsg

    data class Unknown(val raw: String) : AiMsg
}

/** 제품·결함 상자 — box = [x1, y1, x2, y2] 픽셀 */
data class AiBox(val label: String, val conf: Double, val box: List<Double>)

/**
 * 면 검사 1건(2026-11-23 전 기록은 제품 하나) — `product_end` 메시지 · `GET /products` 항목 · `GET /products/{id}` 상세 공통.
 * decision: PASS · FAIL (서버 판정), aiDecision: OK · NG · REVIEW. faces·facesSeen·complete 는 옛 제품 기록에만 있다.
 */
data class AiProduct(
    val productId: String,
    val stationId: String? = null,
    val face: String? = null,                  // 검사한 면 (옛 제품 기록은 null)
    val startedAt: String? = null,
    val endedAt: String? = null,
    val endReason: String? = null,             // done · incomplete · disconnect (옛 기록: gap · disconnect)
    val decision: String? = null,              // PASS · FAIL (끊긴·끝나지 않은 검사는 null)
    val aiDecision: String? = null,
    val defectLabel: String? = null,
    val frames: Int = 0,
    val defectFrames: Int = 0,
    val faces: Map<String, Int> = emptyMap(),
    val facesSeen: List<String> = emptyList(),
    val complete: Boolean = false,
    val bestSeq: Long? = null,                 // 대표 프레임 — FAIL 이면 점수가 가장 높은 결함 프레임, PASS 면 마지막
    val bestResultId: String? = null,
    val resultIds: List<String> = emptyList(),
    val feedbackMode: String? = null,          // manual · auto
    val feedback: String? = null,              // CORRECT · WRONG · UNSURE · NOT_ENTERED · NONE
    val feedbackAt: String? = null,
    val feedbackLog: List<FeedbackChange> = emptyList(),
) {
    val pass: Boolean get() = decision == "PASS"
}

/** 피드백 변경 기록 한 줄 */
data class FeedbackChange(val before: String, val after: String, val source: String, val device: String?, val changedAt: String?)

/** `GET /products` 한 페이지 — [nextBefore] 가 있으면 다음 페이지가 있다 */
data class AiProductPage(val items: List<AiProduct>, val nextBefore: String?)

/** 제품의 프레임 한 장 — `GET /products/{id}/frames` 항목 */
data class AiFrame(
    val seq: Long,
    val ts: String?,
    val decision: String,
    val score: Double,
    val defectLabel: String?,
    val detections: List<AiBox>,
    val frameW: Int,
    val frameH: Int,
    val resultId: String?,
    val hasImage: Boolean,
)

data class AiFramePage(val items: List<AiFrame>, val total: Int)

/** `GET /storage` — 서버 디스크 사용량. data·perDay 는 서버가 아직 계산하지 않았으면 비어 있다 */
data class AiStorage(
    val measuredAt: String?,
    val diskTotal: Long,
    val diskUsed: Long,
    val diskFree: Long,
    val data: Map<String, Long>,
    val perDay: List<Pair<String, Long>>,
)

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
    /** 이 앱이 따르는 서버 규격 날짜 — 통신 로그 창에 띄워 앱 빌드가 새 규격인지 확인한다 */
    const val SPEC = "2026-11-23 면 검사·피드백"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AiMsg = try {
        val o = json.parseToJsonElement(text).jsonObject
        when (o.str("state")) {
            "searching" -> AiMsg.Searching(o.str("reason"))
            "inspect" -> inspect(o)
            "product_end" -> AiMsg.ProductEnd(product(o))
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
            productId = o.str("product_id"),
            face = o.str("face"),
            count = o.int("count") ?: 0,
            rxAt = (o["rx_at"] as? JsonPrimitive)?.longOrNull,
        )
    }

    /** `GET /products/{id}` · PUT 피드백 응답 */
    fun parseProduct(text: String): AiProduct = product(json.parseToJsonElement(text).jsonObject)

    /** `GET /products` */
    fun parseProducts(text: String): AiProductPage {
        val o = json.parseToJsonElement(text).jsonObject
        return AiProductPage((o["items"] as? JsonArray)?.map { product(it.jsonObject) } ?: emptyList(), o.str("next_before"))
    }

    /** `GET /products/{id}/frames` */
    fun parseFrames(text: String): AiFramePage {
        val o = json.parseToJsonElement(text).jsonObject
        val items = (o["items"] as? JsonArray)?.map { e ->
            val f = e.jsonObject
            val (w, h) = f.frameSize()
            AiFrame(
                seq = (f["seq"] as JsonPrimitive).longOrNull!!, ts = f.str("ts"), decision = f.str("decision") ?: "OK",
                score = f.dbl("score") ?: 0.0, defectLabel = f.str("defect_label"), detections = f.boxes("detections"),
                frameW = w, frameH = h, resultId = f.str("result_id"), hasImage = f.bool("has_image") ?: false,
            )
        } ?: emptyList()
        return AiFramePage(items, o.int("total") ?: items.size)
    }

    /** `GET /storage` */
    fun parseStorage(text: String): AiStorage {
        val o = json.parseToJsonElement(text).jsonObject
        val data = (o["data_bytes"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.longOrNull ?: 0L } ?: emptyMap()
        val perDay = (o["per_day_bytes"] as? JsonArray)?.map { e ->
            val d = e.jsonObject
            (d.str("date") ?: "") to ((d["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L)
        } ?: emptyList()
        return AiStorage(
            measuredAt = o.str("measured_at"), diskTotal = o.long("disk_total_bytes") ?: 0, diskUsed = o.long("disk_used_bytes") ?: 0,
            diskFree = o.long("disk_free_bytes") ?: 0, data = data, perDay = perDay,
        )
    }

    private fun product(o: JsonObject) = AiProduct(
        productId = o.str("product_id")!!, stationId = o.str("station_id"), face = o.str("face"), startedAt = o.str("started_at"),
        endedAt = o.str("ended_at"), endReason = o.str("end_reason"), decision = o.str("decision"),
        aiDecision = o.str("ai_decision"), defectLabel = o.str("defect_label"), frames = o.int("frames") ?: 0,
        defectFrames = o.int("defect_frames") ?: 0, faces = o.counts("faces"), facesSeen = o.strings("faces_seen"),
        complete = o.bool("complete") ?: false, bestSeq = o.long("best_seq"), bestResultId = o.str("best_result_id"),
        resultIds = o.strings("result_ids"),
        feedbackMode = o.str("feedback_mode"), feedback = o.str("feedback"), feedbackAt = o.str("feedback_at"),
        feedbackLog = (o["feedback_log"] as? JsonArray)?.map { e ->
            val l = e.jsonObject
            FeedbackChange(l.str("before") ?: "", l.str("after") ?: "", l.str("source") ?: "", l.str("device"), l.str("changed_at"))
        } ?: emptyList(),
    )

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
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.strings(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
    private fun JsonObject.counts(k: String): Map<String, Int> =
        (this[k] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.intOrNull ?: 0 } ?: emptyMap()
}
