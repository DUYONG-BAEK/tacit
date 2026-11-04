package kr.co.snail.tacitq.mobile.ai

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 결함 프레임 조회 재시도 — API §4.2·§4.3 (404 는 0.5초 뒤, 503 은 1초 뒤 한 번 더) */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DefectLoaderTest {
    private val overlayRule = mapOf(404 to 500L, 503 to 1_000L)

    private fun calls(vararg codes: Int): Pair<MutableList<Int>, () -> Int> {
        val seen = mutableListOf<Int>()
        val queue = ArrayDeque(codes.toList())
        return seen to { queue.removeFirst().also { seen += it } }
    }

    @Test fun `저장 중이라 404 면 0점5초 뒤 한 번 더`() = runTest {
        val (seen, call) = calls(404, 200)
        assertEquals(200, DefectLoader.retrying({ it }, overlayRule, call))
        assertEquals(listOf(404, 200), seen)
        assertEquals(500L, currentTime)
    }

    @Test fun `503 이면 1초 뒤 한 번 더 그래도 실패면 그대로`() = runTest {
        val (seen, call) = calls(503, 503)
        assertEquals(503, DefectLoader.retrying({ it }, overlayRule, call))
        assertEquals(2, seen.size)
        assertEquals(1_000L, currentTime)
    }

    @Test fun `성공이나 규칙에 없는 코드는 다시 부르지 않는다`() = runTest {
        val (seen, call) = calls(200)
        assertEquals(200, DefectLoader.retrying({ it }, overlayRule, call))
        val (seen2, call2) = calls(404)
        assertEquals(404, DefectLoader.retrying({ it }, mapOf(503 to 1_000L), call2))   // 판정 조회의 404 = 결과 없음
        assertEquals(listOf(200), seen); assertEquals(listOf(404), seen2)
        assertEquals(0L, currentTime)
    }
}
