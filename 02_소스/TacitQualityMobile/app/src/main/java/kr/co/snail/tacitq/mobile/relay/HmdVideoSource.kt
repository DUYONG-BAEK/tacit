package kr.co.snail.tacitq.mobile.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import kr.co.snail.tacitq.mobile.log.CommLog
import kr.co.snail.tacitq.mobile.log.CommLog.Ch.HMD

/**
 * HMD 영상 수신 — HMD MjpegServer(`http://<HMD-IP>:8080`)에 붙어 프레임을 [onFrame] 으로 넘긴다.
 * 끊기면 5초 고정 주기로 다시 붙는다(개발가이드: 지수 백오프 금지).
 * 주소는 붙을 때마다 [url] 로 새로 묻는다 — HMD 가 BLE 로 알려 준 주소가 바뀌면 다음 시도부터 따른다.
 */
class HmdVideoSource(
    private val url: () -> String,
    private val onFrame: (hmdSeq: Long, capturedAt: Long, data: ByteArray, codec: String, key: Boolean) -> Unit,
    private val onConnected: (Boolean) -> Unit = {},
) {
    /** 취소될 때까지 돈다. */
    suspend fun run() = withContext(Dispatchers.IO) {
        var lastFail: String? = null                     // 통신 로그 — 같은 실패가 이어지면 한 번만
        while (isActive) {
            var conn: HttpURLConnection? = null
            val url = url()
            var frames = 0L
            var connected = false
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000          // 3초 무프레임이면 끊고 다시 붙는다
                }
                val reader = MjpegReader(BufferedInputStream(conn.inputStream, 64 * 1024))
                CommLog.i(HMD, TAG, "HMD 영상 연결됨 $url (HTTP ${conn.responseCode} ${conn.contentType ?: ""})")
                connected = true
                lastFail = null
                onConnected(true)
                while (true) {
                    ensureActive()
                    val part = reader.next() ?: break
                    if (frames++ == 0L) CommLog.i(HMD, TAG, "HMD 첫 프레임 ${part.codec} ${part.body.size / 1024}KB (HMD 순번 ${part.hmdSeq})")
                    onFrame(part.hmdSeq, part.capturedAt, part.body, part.codec, part.key)
                }
                CommLog.w(HMD, TAG, "HMD 영상 스트림 끝남 $url (받은 프레임 ${frames}장) — ${RECONNECT_MS / 1000}초 뒤 다시")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val why = "${e.javaClass.simpleName}: ${e.message}"
                if (connected) CommLog.w(HMD, TAG, "HMD 영상 끊김 $url (받은 프레임 ${frames}장) — $why")
                else if (why != lastFail) CommLog.w(HMD, TAG, "HMD 영상 연결 실패 $url — $why · ${RECONNECT_MS / 1000}초마다 다시 시도")
                if (!connected) lastFail = why
            } finally {
                conn?.disconnect()
            }
            onConnected(false)
            delay(RECONNECT_MS)
        }
    }

    companion object {
        private const val TAG = "TacitQ.HmdVideo"
        const val RECONNECT_MS = 5_000L
    }
}
