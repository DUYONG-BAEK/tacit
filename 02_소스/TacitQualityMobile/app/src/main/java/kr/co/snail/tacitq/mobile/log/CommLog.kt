package kr.co.snail.tacitq.mobile.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 통신 로그 — 연결 문제를 앱 안에서 바로 확인하려고 남긴다(통신 로그 창). logcat 에도 그대로 쓴다.
 * 채널마다 최근 [KEEP] 줄만 메모리에 둔다(프로세스가 죽으면 사라짐). 어느 스레드에서 불러도 된다.
 */
object CommLog {
    enum class Ch(val label: String) { AI("모바일 ↔ AI 서버"), HMD("모바일 ↔ HMD") }

    data class Line(val at: Long, val level: Char, val tag: String, val text: String)

    const val KEEP = 300

    private val flows: Map<Ch, MutableStateFlow<List<Line>>> = Ch.entries.associateWith { MutableStateFlow(emptyList()) }

    fun lines(ch: Ch): StateFlow<List<Line>> = flows.getValue(ch)

    fun i(ch: Ch, tag: String, msg: String) = add(ch, 'I', tag, msg)
    fun w(ch: Ch, tag: String, msg: String) = add(ch, 'W', tag, msg)

    fun clear(ch: Ch) { flows.getValue(ch).value = emptyList() }

    /** 복사·공유용 글 — 줄마다 "시:분:초.밀리초 수준 태그 내용" */
    fun format(lines: List<Line>): String {
        val f = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
        return lines.joinToString("\n") { "${f.format(Date(it.at))} ${it.level} ${it.tag.removePrefix("TacitQ.")} ${it.text}" }
    }

    private fun add(ch: Ch, level: Char, tag: String, msg: String) {
        runCatching { if (level == 'W') Log.w(tag, msg) else Log.i(tag, msg) }   // 단위 시험(JVM)에는 Log 가 없다
        flows.getValue(ch).update { (it + Line(System.currentTimeMillis(), level, tag, msg)).takeLast(KEEP) }
    }
}
