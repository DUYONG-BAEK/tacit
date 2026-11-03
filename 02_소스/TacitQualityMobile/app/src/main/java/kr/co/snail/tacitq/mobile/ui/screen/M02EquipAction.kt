package kr.co.snail.tacitq.mobile.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.snail.tacitq.mobile.model.*
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-02 설비 조치 등록 (P3) — 조치 지식이 만들어지는 곳.
 *
 * 이 화면의 핵심 산출물은 파라미터 전/후 값이다. 이 숫자가 쌓여야 어떤 조치가
 * 실제로 들었는지 되짚을 수 있다 — 5번째 암묵지 자산.
 *
 * v1.2에서 원인 목록에 '금형 면 손상'(MOLD_DAMAGE)이, 조치 목록에 '기타'(OTHER)가
 * 추가됐다. OTHER는 메모 없이 등록되지 않는다.
 */
@Composable
fun EquipActionScreen(vm: AppViewModel) {
    val actions by vm.repo.equipActions.collectAsStateWithLifecycle()
    val action = actions.firstOrNull() ?: return

    var cause by remember { mutableStateOf(Cause.entries.first { it.code == action.estimatedCause }) }
    var taken by remember { mutableStateOf(ActionTaken.entries.first { it.code == action.actionTaken }) }
    var before by remember { mutableStateOf(action.paramBefore ?: "") }
    var after by remember { mutableStateOf(action.paramAfter ?: "") }
    val note = action.actionNote

    Field("감지", "${action.detectedAt.substringAfter(' ').take(5)} · ${action.detectionText}" +
        if (action.locationClustered) " · 위치 군집" else "", accent = T.Red)
    Field("경보 등급", PatternLevel.entries.first { it.code == action.severityLevel }.label, accent = T.Red)

    SectionTitle("추정 원인")
    CodeRadioGroup(Cause.entries, cause, { cause = it })

    SectionTitle("조치 내용")
    CodeRadioGroup(ActionTaken.active(), taken, {
        taken = it
        if (it.paramName == null) { before = ""; after = "" }
    })

    if (taken.paramName != null) {
        SectionTitle("파라미터 변경 — 전 / 후")
        Row(Modifier.fillMaxWidth()) {
            Field(taken.paramName!! + " (전)", before.ifBlank { "미입력" },
                accent = T.Amber, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Field(taken.paramName!! + " (후)", after.ifBlank { "미입력" },
                accent = T.Amber, modifier = Modifier.weight(1f))
        }
        // 실제 앱에서는 숫자 키패드 입력. 프로토타입은 대표값으로 채운다.
        PrimaryButton("대표값 입력 (420ms → 480ms)", T.Amber) { before = "420ms"; after = "480ms" }
    }

    note?.let { Field("메모", it) }

    NoteBox(
        "파라미터 변경 전/후 값을 반드시 기록한다.",
        "이 숫자가 쌓여야 어떤 조치가 실제로 효과가 있었는지 되짚을 수 있다. " +
            "변경 사실은 감사 로그(PLC_PARAM_CHANGE)에도 함께 남는다.",
        T.Amber, T.AmberLight
    )

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth()) {
        PrimaryButton("조치 등록", T.Red, modifier = Modifier.weight(1f)) {
            vm.repo.registerAction(
                action.actionId, cause, taken,
                before.ifBlank { null }, after.ifBlank { null }, note
            ).onSuccess { vm.toast("조치 등록 — 감사 로그에 기록됨") }
                .onFailure { vm.toast(it.message ?: "등록 실패") }
        }
        Spacer(Modifier.width(8.dp))
        PrimaryButton("검증 시작", T.Green, modifier = Modifier.weight(1f)) {
            vm.repo.startVerification(action.actionId)
            vm.toast("검증 시작 — 다음 20건 모니터링")
        }
    }
    Text(
        "정지 ${action.downtimeSec / 60}분 · 검증 ${VerifyResult.entries.first { it.code == action.verifyResult }.label}",
        fontSize = 11.sp, color = T.Muted, modifier = Modifier.padding(top = 8.dp)
    )
}
