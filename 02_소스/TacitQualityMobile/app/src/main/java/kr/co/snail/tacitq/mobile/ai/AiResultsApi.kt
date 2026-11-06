package kr.co.snail.tacitq.mobile.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI 서버 HTTP — `POST /offer` · `GET /results/{id}` · `GET /results/{id}/overlay` (API §4·§5.2),
 * 제품·피드백·저장소 `PUT /products/{id}/feedback` · `GET /products[/{id}[/frames[/{seq}.jpg]]]` · `GET /storage`
 * (`server/PLAN_product_feedback.md` §4).
 * 블로킹 호출이므로 IO 디스패처에서 부른다.
 *
 * 호출 전체 제한 [CALL_TIMEOUT_S]: 연결 시도 하나는 ICE 수집(최대 5초) + offer 호출을 합쳐
 * [Reconnect] 의 시도 마감(10초) 안에 끝나야 한다.
 */
class AiResultsApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build(),
) {
    data class Reply(val code: Int, val body: String)
    class Image(val code: Int, val bytes: ByteArray?)

    fun offer(sdp: String, stationId: String, feedbackMode: String = FeedbackMode.AUTO): Reply =
        post("/offer", buildJsonObject("sdp" to sdp, "type" to "offer", "station_id" to stationId, "feedback_mode" to feedbackMode))

    /** 피드백 입력·수정 — [value]: CORRECT · WRONG · UNSURE, [source]: live(M-07) · history(M-05) */
    fun putFeedback(productId: String, value: String, source: String, device: String?): Reply {
        val body = buildJsonObject(*listOfNotNull("value" to value, "source" to source, device?.let { "device" to it }).toTypedArray())
        return call(Request.Builder().url(url("/products/${seg(productId)}/feedback")).put(body.toRequestBody(JSON)).build())
    }

    /** 이력 목록 — 값이 null 인 조건은 빼고 보낸다. [feedback] 은 여러 값(쉼표로 합친다) */
    fun products(station: String?, feedback: List<String>? = null, decision: String? = null, limit: Int = 50, before: String? = null): Reply {
        val q = listOfNotNull(
            station?.let { "station" to it }, feedback?.takeIf { it.isNotEmpty() }?.let { "feedback" to it.joinToString(",") },
            decision?.let { "decision" to it }, "limit" to limit.toString(), before?.let { "before" to it },
        ).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        return get("/products?$q")
    }

    fun product(productId: String): Reply = get("/products/${seg(productId)}")

    fun frames(productId: String, offset: Int, limit: Int): Reply = get("/products/${seg(productId)}/frames?offset=$offset&limit=$limit")

    /** 프레임 사진 JPEG — [overlay] 면 결함 상자를 그려서 */
    fun frameJpeg(productId: String, seq: Long, overlay: Boolean): Image =
        image("/products/${seg(productId)}/frames/$seq.jpg" + if (overlay) "?overlay=1" else "")

    fun storage(): Reply = get("/storage")

    fun result(resultId: String): Reply = get("/results/$resultId")

    /** 오버레이 JPEG — [path] 는 서버가 준 overlay_url(예: /results/{id}/overlay) */
    fun overlay(path: String): Image = image(path)

    private fun get(path: String) = call(Request.Builder().url(url(path)).get().build())

    private fun image(path: String): Image = client.newCall(Request.Builder().url(url(path)).get().build()).execute().use {
        Image(it.code, if (it.isSuccessful) it.body?.bytes() else null)
    }

    /** 경로 한 칸 — 서버가 준 ID 를 그대로 쓰되 URL 에 안전하게 */
    private fun seg(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun post(path: String, body: String) = call(
        Request.Builder().url(url(path)).post(body.toRequestBody(JSON)).build()
    )

    private fun call(req: Request): Reply = client.newCall(req).execute().use { Reply(it.code, it.body?.string() ?: "") }

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun buildJsonObject(vararg kv: Pair<String, String>): String =
        kotlinx.serialization.json.JsonObject(kv.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) }).toString()

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val CALL_TIMEOUT_S = 4L
    }
}
