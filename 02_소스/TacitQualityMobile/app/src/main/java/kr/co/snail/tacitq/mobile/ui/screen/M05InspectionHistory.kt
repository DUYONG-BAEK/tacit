package kr.co.snail.tacitq.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.snail.tacitq.mobile.model.Verdict
import kr.co.snail.tacitq.mobile.model.WorkerAct
import kr.co.snail.tacitq.mobile.store.InspectionEntity
import kr.co.snail.tacitq.mobile.store.QmsDatabase
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M-05 검사 이력 — 폰 로컬 DB(Room, C5 LocalStore)의 실제 판정 기록을 실시간으로 보여 준다.
 * 판정이 오면 검사 서비스가 바로 DB 에 쓰고(합격 즉시 · 불합격은 "확인 대기"로 즉시), 이 화면은 DB 변경을 그대로 따라간다.
 * (2026-10-19: 프로토타입 예시 데이터 → 실제 기록으로 교체)
 */
@Composable
fun InspectionHistoryScreen(@Suppress("UNUSED_PARAMETER") vm: AppViewModel) {
    val ctx = LocalContext.current
    val dao = remember { QmsDatabase.get(ctx.applicationContext).dao() }
    val rows by remember { dao.observeInspections() }.collectAsStateWithLifecycle(initialValue = emptyList())

    val today = remember { dayKey(System.currentTimeMillis()) }
    val todays = rows.filter { dayKey(it.capturedAt) == today }
    val pass = todays.count { it.aiVerdict == Verdict.PASS.code }
    val fail = todays.size - pass

    Row {
        Field("오늘 검사", "${todays.size}건", modifier = Modifier.weight(1f))
        Field("합격", "${pass}건", accent = T.Green, modifier = Modifier.weight(1f))
        Field("불합격", "${fail}건", accent = if (fail > 0) T.Red else null, modifier = Modifier.weight(1f))
    }

    SectionTitle("판정 기록 (최근 순, 실시간)")
    if (rows.isEmpty()) {
        NoteBox("기록 없음", "검사 탭에서 검사를 시작하면 AI 판정이 올 때마다 여기에 바로 추가됩니다.")
        return
    }
    rows.forEach { HistoryRow(it) }
}

@Composable
private fun HistoryRow(r: InspectionEntity) {
    val pass = r.aiVerdict == Verdict.PASS.code
    val act = WorkerAct.entries.firstOrNull { it.code == r.workerAction }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${time(r.capturedAt)}  ${r.partId}", fontSize = 12.5.sp, color = T.Ink, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(
                    r.aiConfidence?.let { "점수 %.3f".format(it) },
                    act?.let { if (it == WorkerAct.PENDING || it == WorkerAct.TIMEOUT || it == WorkerAct.CONFIRMED) it.label else null },
                    if (r.syncedAt == null) null else "서버 전송됨",
                ).joinToString(", ").ifEmpty { "-" },
                fontSize = 11.sp, color = T.Muted,
            )
        }
        if (pass) Badge("합격", T.Green, T.GreenLight) else Badge("불합격", T.Red, T.RedLight)
    }
}

private fun time(ms: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date(ms))
private fun dayKey(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(ms))
