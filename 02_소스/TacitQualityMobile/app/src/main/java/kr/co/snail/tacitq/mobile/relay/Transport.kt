package kr.co.snail.tacitq.mobile.relay

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * 엣지로 가는 굵은 선 — 개발가이드 §5 Transport. E1 은 WebSocket, E2 에서 WebRTC 로 갈아끼운다.
 * [StreamRelay] 는 이 인터페이스만 알아서, 노트북 시험에선 가짜로 바꾼다.
 */
interface Transport {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onClosed(reason: String)
    }

    fun open(url: String, listener: Listener)
    fun sendBinary(bytes: ByteArray): Boolean
    fun sendText(text: String): Boolean
    /** 아직 망으로 못 나간 바이트 — 이게 쌓이면 보내기를 멈추고 우리 큐에 둔다 */
    val queuedBytes: Long
    fun close()
}

/** OkHttp WebSocket 구현. 연결 하나만 유지한다. */
class WebSocketTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)          // 건강검사는 엣지 HB 로 한다
        .connectTimeout(3, TimeUnit.SECONDS)
        .build(),
) : Transport {

    @Volatile private var ws: WebSocket? = null

    override fun open(url: String, listener: Transport.Listener) {
        close()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket === ws) listener.onOpen()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === ws) listener.onText(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) listener.onClosed("closed $code $reason")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS 실패: ${t.message}")
                if (webSocket === ws) listener.onClosed("failure ${t.message}")
            }
        })
    }

    override fun sendBinary(bytes: ByteArray) = ws?.send(bytes.toByteString()) ?: false
    override fun sendText(text: String) = ws?.send(text) ?: false
    override val queuedBytes: Long get() = ws?.queueSize() ?: 0

    override fun close() {
        ws?.cancel()
        ws = null
    }

    companion object {
        private const val TAG = "TacitQ.WS"
    }
}
