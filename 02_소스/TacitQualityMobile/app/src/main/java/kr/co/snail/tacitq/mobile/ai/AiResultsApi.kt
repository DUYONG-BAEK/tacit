package kr.co.snail.tacitq.mobile.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI 서버 HTTP — `POST /offer` · `GET /results/{id}` · `POST /results/{id}/decision`. API §4·§5.2.
 * 블로킹 호출이므로 IO 디스패처에서 부른다.
 */
class AiResultsApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
) {
    data class Reply(val code: Int, val body: String)

    fun offer(sdp: String, stationId: String): Reply =
        post("/offer", buildJsonObject("sdp" to sdp, "type" to "offer", "station_id" to stationId))

    fun result(resultId: String): Reply = call(Request.Builder().url(url("/results/$resultId")).get().build())

    fun decision(resultId: String, final: String, method: String): Reply =
        post("/results/$resultId/decision", buildJsonObject("final" to final, "method" to method))

    private fun post(path: String, body: String) = call(
        Request.Builder().url(url(path)).post(body.toRequestBody(JSON)).build()
    )

    private fun call(req: Request): Reply = client.newCall(req).execute().use { Reply(it.code, it.body?.string() ?: "") }

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun buildJsonObject(vararg kv: Pair<String, String>): String =
        kotlinx.serialization.json.JsonObject(kv.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) }).toString()

    companion object { private val JSON = "application/json".toMediaType() }
}
