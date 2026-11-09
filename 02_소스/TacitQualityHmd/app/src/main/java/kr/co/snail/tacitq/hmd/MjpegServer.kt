package kr.co.snail.tacitq.hmd

import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 프레임 송출 서버 — 외부 라이브러리 없이 ServerSocket 으로 `multipart/x-mixed-replace` 스트림을 내보낸다.
 * 이름은 1단계(MJPEG) 때 그대로 — 지금은 [contentType] 에 따라 JPEG 또는 H.265 프레임을 보낸다(설계서 §4).
 *
 * 파트마다 `X-Seq`·`X-Captured-At`·`X-Key` 헤더. 폰 중계기(C2)가 그대로 엣지로 넘긴다.
 * **수신자마다 큐**를 둬서 프레임을 빠짐없이 순서대로 보낸다 — H.265 는 한 장만 빠져도 다음 키프레임까지 화면이 깨진다.
 * 수신자가 느려 큐가 차면 비우고 다음 키프레임부터 다시 보낸다. 새 수신자도 키프레임부터 받는다([onClientJoined]).
 */
class MjpegServer(private val port: Int = 8080, val contentType: String = "image/jpeg") {

    private class Frame(val seq: Long, val capturedAt: Long, val data: ByteArray, val key: Boolean)

    private class Client(val socket: Socket) {
        val queue = ArrayBlockingQueue<Frame>(90)             // 30fps 기준 3초
        @Volatile var needKey = true
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private var seqGen = 0L

    /** 새 수신자가 붙었을 때 — HEVC 면 인코더에 키프레임을 요청한다. */
    var onClientJoined: () -> Unit = {}

    /** 지금 접속한 뷰어 수 — 화면 표시용. */
    val viewerCount: Int get() = clients.size

    @Synchronized
    fun updateFrame(data: ByteArray, capturedAt: Long = System.currentTimeMillis(), key: Boolean = true) {
        val f = Frame(++seqGen, capturedAt, data, key)
        for (c in clients) {
            if (c.needKey && !key) continue
            c.needKey = false
            if (!c.queue.offer(f)) {                          // 수신자가 너무 느림 — 비우고 다음 키프레임부터
                c.queue.clear()
                c.needKey = true
                Log.w(TAG, "수신자 지연 — 큐 비우고 다음 키프레임부터")
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "frame-accept") {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "프레임 서버 시작 :$port ($contentType)")
                while (running) {
                    val c = Client(serverSocket!!.accept())
                    clients.add(c)
                    onClientJoined()
                    thread(name = "frame-client") { serve(c) }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept 종료: ${e.message}")
            }
        }
    }

    private fun serve(c: Client) {
        try {
            c.socket.getInputStream()               // 요청 라인은 무시(루트 하나만 제공)
            val out: OutputStream = BufferedOutputStream(c.socket.getOutputStream(), 256 * 1024)
            out.write(
                ("HTTP/1.0 200 OK\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-cache, private\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n")
                    .toByteArray()
            )
            out.flush()
            while (running && !c.socket.isClosed) {
                val f = c.queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                out.write(
                    ("--$BOUNDARY\r\nContent-Type: $contentType\r\nContent-Length: ${f.data.size}\r\n" +
                        "X-Seq: ${f.seq}\r\nX-Captured-At: ${f.capturedAt}\r\nX-Key: ${if (f.key) 1 else 0}\r\n\r\n").toByteArray()
                )
                out.write(f.data)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (e: Exception) {
            // 클라이언트 연결 종료 — 정상
        } finally {
            clients.remove(c)
            runCatching { c.socket.close() }
        }
    }

    fun stop() {
        running = false
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
    }

    companion object {
        private const val TAG = "TacitQ.Hmd.Stream"
        private const val BOUNDARY = "tacitqframe"
    }
}
