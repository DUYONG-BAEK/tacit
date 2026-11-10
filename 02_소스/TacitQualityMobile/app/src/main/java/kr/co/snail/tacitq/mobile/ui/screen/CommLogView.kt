package kr.co.snail.tacitq.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.snail.tacitq.mobile.BuildConfig
import kr.co.snail.tacitq.mobile.ai.AiMessages
import kr.co.snail.tacitq.mobile.log.CommLog
import kr.co.snail.tacitq.mobile.service.InspectionService
import kr.co.snail.tacitq.mobile.ui.theme.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 통신 로그 창 — 연결이 안 될 때 원인을 앱 안에서 바로 본다. 탭: 모바일 ↔ AI 서버 / 모바일 ↔ HMD(BLE·영상).
 * 맨 위에 지금 상태, 아래에 최근 로그(새 줄이 오면 맨 아래로). 복사해서 메신저 등으로 보낼 수 있다.
 */
@Composable
fun CommLogDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var tab by remember { mutableStateOf(CommLog.Ch.AI) }
        val lines by CommLog.lines(tab).collectAsStateWithLifecycle()
        val relay by InspectionService.relayStatus.collectAsStateWithLifecycle()
        val hmdVideo by InspectionService.hmdVideo.collectAsStateWithLifecycle()
        val hmdBle by InspectionService.hmdBle.collectAsStateWithLifecycle()
        val running by InspectionService.running.collectAsStateWithLifecycle()
        val clipboard = LocalClipboardManager.current
        val list = rememberLazyListState()
        LaunchedEffect(tab, lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }

        Column(Modifier.fillMaxSize().background(T.Ink).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("통신 로그", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("닫기", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
            }
            Text(APP_INFO, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Row(Modifier.padding(vertical = 8.dp)) {
                CommLog.Ch.entries.forEach { ch ->
                    val on = ch == tab
                    Text(
                        ch.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (on) T.Ink else Color.White,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
                            .clickable { tab = ch }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            // 지금 상태 — 로그를 읽기 전에 어디가 끊겼는지 먼저 본다
            val now = when (tab) {
                CommLog.Ch.AI -> if (!running) "검사 시작 전" else
                    "엣지 ${if (relay?.edgeConnected == true) "연결" else "끊김"} · ${relay?.guide?.replace("\n", " · ") ?: "-"}"
                CommLog.Ch.HMD -> if (!running) "검사 시작 전" else
                    "HMD 영상 ${if (hmdVideo) "수신 중" else "끊김"} · 화면(BLE) $hmdBle · 받은 프레임 ${relay?.received ?: 0}장"
            }
            Text("지금: $now", color = Color(0xFFFFE08A), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(8.dp),
                state = list,
            ) {
                if (lines.isEmpty()) item { Text("로그 없음 — 검사를 시작하면 연결 과정이 여기에 남습니다", color = Color.Gray, fontSize = 12.sp) }
                items(lines) { l ->
                    Text(
                        "${TIME.get()!!.format(Date(l.at))} ${l.text}",
                        color = if (l.level == 'W') Color(0xFFFF8A80) else Color(0xFFD0D8E0),
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogButton("복사 (${lines.size}줄)") { clipboard.setText(AnnotatedString("$APP_INFO\n[${tab.label}] 지금: $now\n" + CommLog.format(lines))) }
                LogButton("지우기") { CommLog.clear(tab) }
            }
        }
    }
}

private val TIME = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }

/** 화면 위·복사 맨 위 — 어떤 빌드의 로그인지 (빌드 시각은 app/build.gradle.kts BUILD_TIME) */
private val APP_INFO = "앱 ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_TIME} · AI 서버 규격 ${AiMessages.SPEC}"

@Composable
private fun LogButton(text: String, onClick: () -> Unit) {
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
         modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(T.Purple).clickable(onClick = onClick)
             .padding(horizontal = 14.dp, vertical = 9.dp))
}
