package kr.co.snail.tacitq.mobile.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI 서버 HTTP — `POST /offer` · `GET /results/{id}` · `GET /results/{id}/overlay`. API §4·§5.2.
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

    fun offer(sdp: String, stationId: String): Reply =
        post("/offer", buildJsonObject("sdp" to sdp, "type" to "offer", "station_id" to stationId))

    fun result(resultId: String): Reply = call(Request.Builder().url(url("/results/$resultId")).get().build())

    /** 오버레이 JPEG — [path] 는 서버가 준 overlay_url(예: /results/{id}/overlay) */
    fun overlay(path: String): Image = client.newCall(Request.Builder().url(url(path)).get().build()).execute().use {
        Image(it.code, if (it.isSuccessful) it.body?.bytes() else null)
    }

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
