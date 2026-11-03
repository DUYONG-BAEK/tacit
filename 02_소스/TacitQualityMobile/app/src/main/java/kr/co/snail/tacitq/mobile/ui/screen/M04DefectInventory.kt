package kr.co.snail.tacitq.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.snail.tacitq.mobile.data.SampleData
import kr.co.snail.tacitq.mobile.model.*
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-04 불량 재고 · 폐기 관리 (P5) — 자동으로 버리지 않는다.
 *
 * 목록은 트레이 세트 단위로 집계하고, 승인·연장은 그 세트에 속한 개체 전체에
 * 적용된다. 명장 리뷰가 걸린 로트는 기한이 지나도 폐기 대상에서 빠진다.
 * 승인과 연장 모두 감사 로그에 남으며, 권한이 없으면 DENIED로 기록된다.
 */
@Composable
fun DefectInventoryScreen(vm: AppViewModel) {
    val items by vm.repo.defectItems.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var asMaster by remember { mutableStateOf(false) }

    val groups = items.groupBy { it.trayId }
        .map { (trayId, list) ->
            Triple(trayId.removeSuffix("-D"), list, list.first())
        }
        .sortedBy { it.second.first().storeUntil }

    val stored = items.count { it.status == ItemStatus.STORED }
    val ready = items.count { it.status == ItemStatus.DISPOSE_READY }
    Field("현황", "보관 중 ${stored}건 · 폐기 대상 ${ready}건 · 보류 ${items.count { it.held }}건")

    SectionTitle("트레이별 재고")
    groups.forEach { (setId, list, head) ->
        val expired = head.status == ItemStatus.DISPOSE_READY
        val held = head.held
        val (fg, bg, label) = when {
            held -> Triple(T.Amber, T.AmberLight, "보류")
            expired -> Triple(T.Red, T.RedLight, "만료")
            else -> Triple(T.Teal, T.TealLight, "보관")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected == setId) T.PurpleLight else T.Surface)
                .border(1.dp, if (selected == setId) T.Purple else T.Line, RoundedCornerShape(8.dp))
                .clickable { selected = setId }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("$setId · ${list.size}건", fontSize = 13.sp, color = T.Ink,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    when {
                        held -> "${HoldReason.entries.first { it.code == head.holdReason }.label} — 폐기 대상 제외"
                        expired -> "보관 만료 (${head.storeUntil}) — 폐기 승인 대상"
                        else -> "보관 ~${head.storeUntil}" +
                            if (head.storeExtendedCnt > 0) " · 연장 ${head.storeExtendedCnt}회" else ""
                    },
                    fontSize = 11.sp, color = T.Muted
                )
            }
            Badge(label, fg, bg)
        }
    }

    NoteBox(
        "만료돼도 자동 폐기하지 않는다.",
        "명장 리뷰가 걸린 로트는 기한이 지나도 폐기 대상에서 제외된다. " +
            "보관 기간 ${vm.repo.profile().storeDays}일은 품번 프로파일(STORE_DAYS)에서 내려온다. " +
            "승인·연장은 명장·관리자만 가능하며 감사 로그에 남는다.",
        T.Amber, T.AmberLight
    )

    // 권한 확인용 토글 — 실증에서는 로그인 세션의 역할을 그대로 쓴다
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clickable { asMaster = !asMaster },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (asMaster) "◉" else "○", color = T.Purple, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            "명장 계정으로 수행 (${if (asMaster) SampleData.master.display else vm.repo.me().display})",
            fontSize = 11.5.sp, color = T.Slate
        )
    }

    Spacer(Modifier.height(10.dp))
    val actor = if (asMaster) SampleData.master else vm.repo.me()
    Row(Modifier.fillMaxWidth()) {
        PrimaryButton("폐기 승인", T.Red, enabled = selected != null, modifier = Modifier.weight(1f)) {
            vm.repo.approveDisposal(selected!!, actor)
                .onSuccess { vm.toast(if (it == 0) "폐기 대상 없음 (보류 제외)" else "폐기 승인 ${it}건") }
                .onFailure { vm.toast(it.message ?: "실패") }
        }
        Spacer(Modifier.width(8.dp))
        PrimaryButton("연장 (+30일)", T.Teal, enabled = selected != null, modifier = Modifier.weight(1f)) {
            vm.repo.extendStorage(selected!!, vm.repo.profile().storeDays, actor)
                .onSuccess { vm.toast("보관기한 연장 ${it}건 — 감사 로그 기록") }
                .onFailure { vm.toast(it.message ?: "실패") }
        }
    }

    val logs by vm.repo.auditLogs.collectAsStateWithLifecycle()
    if (logs.isNotEmpty()) {
        SectionTitle("최근 감사 로그")
        logs.takeLast(3).reversed().forEach {
            val act = AuditAction.entries.first { a -> a.code == it.actionCd }
            val denied = it.resultCd == ResultCd.DENIED.code
            Field(
                "${act.label} · ${it.userId}",
                (it.afterVal ?: it.targetId) + if (denied) " · 권한 거부" else "",
                accent = if (denied) T.Red else null
            )
        }
    }
}
