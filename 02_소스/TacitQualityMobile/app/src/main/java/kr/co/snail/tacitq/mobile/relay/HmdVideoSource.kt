package kr.co.snail.tacitq.mobile.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

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
        while (isActive) {
            var conn: HttpURLConnection? = null
            val url = url()
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000          // 3초 무프레임이면 끊고 다시 붙는다
                }
                val reader = MjpegReader(BufferedInputStream(conn.inputStream, 64 * 1024))
                Log.i(TAG, "HMD 영상 연결 $url")
                onConnected(true)
                while (true) {
                    ensureActive()
                    val part = reader.next() ?: break
                    onFrame(part.hmdSeq, part.capturedAt, part.body, part.codec, part.key)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "HMD 영상 끊김: ${e.message}")
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
