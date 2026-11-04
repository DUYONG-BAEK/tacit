package kr.co.snail.tacitq.mobile.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 저장된 결함 프레임 하나 — 판정(`GET /results/{id}`) + 오버레이 JPEG */
class DefectFrame(val view: AiResultView, val jpeg: ByteArray)

/**
 * 결함 프레임 불러오기 — API §4. 실패하면 화면에 띄울 문구를 돌려준다.
 *
 *  - 판정: 503(판정 서버 장애)이면 1초 뒤 한 번 더. 404 는 결과 없음.
 *  - 오버레이: 서버는 inspect 를 보낸 뒤에 이미지를 저장하므로 받자마자 요청하면 404 일 수 있다 — 0.5초 뒤 한 번 더.
 *    503 이면 1초 뒤 한 번 더.
 */
class DefectLoader(private val api: AiResultsApi) {

    sealed interface Result {
        class Ok(val frame: DefectFrame) : Result
        data class Fail(val message: String) : Result
    }

    suspend fun load(resultId: String): Result = withContext(Dispatchers.IO) {
        try {
            val r = retrying({ it.code }, mapOf(503 to 1_000L)) { api.result(resultId) }
            when (r.code) {
                200 -> Unit
                404 -> return@withContext Result.Fail("서버에 결과 없음")
                else -> return@withContext Result.Fail("판정 조회 실패 ${r.code}")
            }
            val v = AiMessages.parseResult(r.body)
            val img = retrying({ it.code }, mapOf(404 to 500L, 503 to 1_000L)) {
                api.overlay(v.overlayUrl ?: "/results/$resultId/overlay")
            }
            val bytes = img.bytes
            if (img.code == 200 && bytes != null) Result.Ok(DefectFrame(v, bytes))
            else Result.Fail(if (img.code == 404) "서버에 이미지 없음" else "이미지 조회 실패 ${img.code}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Fail("서버 연결 실패 ${e.message ?: ""}".trim())
        }
    }

    companion object {
        /** [call] 결과 코드가 [waitByCode] 에 있으면 그만큼 기다렸다 한 번만 더 부른다 */
        suspend fun <T> retrying(code: (T) -> Int, waitByCode: Map<Int, Long>, call: () -> T): T {
            val first = call()
            val wait = waitByCode[code(first)] ?: return first
            delay(wait)
            return call()
        }
    }
}
