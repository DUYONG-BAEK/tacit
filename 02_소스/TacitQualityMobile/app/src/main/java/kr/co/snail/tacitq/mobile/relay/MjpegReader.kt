package kr.co.snail.tacitq.mobile.relay

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * HMD MJPEG(`multipart/x-mixed-replace`) 본문을 파트 단위로 꺼낸다. 순수 Kotlin — 노트북에서 시험한다.
 *
 * 파트 모양 (HMD MjpegServer):
 * ```
 * --tacitqframe
 * Content-Type: image/jpeg
 * Content-Length: 41230
 * X-Seq: 1532
 * X-Captured-At: 1727851234567
 *
 * <JPEG 41230바이트>
 * ```
 * Content-Length 로 정확히 잘라 읽는다(JPEG 안의 바이트를 경계로 착각하지 않게).
 */
class MjpegReader(private val input: InputStream) {

    class Part(val headers: Map<String, String>, val body: ByteArray) {
        val hmdSeq: Long get() = headers["x-seq"]?.toLongOrNull() ?: 0
        val capturedAt: Long get() = headers["x-captured-at"]?.toLongOrNull() ?: 0
        /** "hevc" | "jpeg" — HMD 가 보낸 Content-Type 으로 구분(설계서 §4) */
        val codec: String get() = if (headers["content-type"]?.contains("hevc") == true) "hevc" else "jpeg"
        val key: Boolean get() = headers["x-key"]?.let { it == "1" } ?: true
    }

    /** 다음 파트. 스트림이 끝나면 null. */
    fun next(): Part? {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) {
                if (headers.isNotEmpty()) break      // 헤더 끝
                continue                             // 파트 사이 빈 줄
            }
            if (line.startsWith("--")) continue      // 경계선
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val n = headers["content-length"]?.toIntOrNull()
            ?: throw IOException("Content-Length 없는 파트: $headers")
        val body = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(body, off, n - off)
            if (r < 0) throw EOFException("파트 본문 중간에 끊김 ($off/$n)")
            off += r
        }
        return Part(headers, body)
    }

    /** ASCII 한 줄(\r\n 또는 \n). 스트림 끝이면 null. */
    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 1024) throw IOException("헤더 줄이 너무 길다")
        }
    }
}
