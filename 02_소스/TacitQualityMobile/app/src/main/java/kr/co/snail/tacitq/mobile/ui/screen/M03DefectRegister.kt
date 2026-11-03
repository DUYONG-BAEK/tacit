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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.snail.tacitq.mobile.data.SampleData
import kr.co.snail.tacitq.mobile.model.*
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel
import kr.co.snail.tacitq.mobile.vm.Screen

/** '일치 / 불일치' 2지 선택을 코드 라디오로 태우기 위한 래퍼 */
private enum class MatchChoice(override val code: String, override val label: String) : CodeItem {
    MATCH("Y", "일치"), MISMATCH("N", "불일치")
}
private enum class QualityChoice(override val code: String, override val label: String) : CodeItem {
    OK("N", "양호"), RECAPTURE("Y", "재촬영 필요")
}

/**
 * M-03 불량품 수거 · 등록 (P5) — 실물과 AI 판정의 대조.
 *
 * v0.7에서 촬영본이 사진 1장이 아니라 대표 프레임 + 앙상블 4장이다.
 * "4장 중 몇 장에서 검출됐는지"가 오검출 판단의 단서가 된다.
 *
 * v1.2에서 '불일치'를 고르면 확정 유형·심각도 입력이 열린다. v1.1까지는
 * 화면에 입력란이 없어 CONFIRMED_TYPE을 채울 방법이 없었다.
 */
@Composable
fun DefectRegisterScreen(vm: AppViewModel) {
    val items by vm.repo.defectItems.collectAsStateWithLifecycle()
    val view = SampleData.representativeView
    val defect = SampleData.representativeDefect
    val tray = vm.repo.trays.value.first { it.trayId == "T-0912-A-D" }
    val target = items.firstOrNull { it.trayId == tray.trayId } ?: return

    var match by remember { mutableStateOf(MatchChoice.MATCH) }
    var quality by remember { mutableStateOf(QualityChoice.RECAPTURE) }
    var type by remember { mutableStateOf(DefectType.entries.first { it.code == defect.defectType }) }
    var sev by remember { mutableStateOf(Severity.entries.first { it.code == defect.severity }) }
    var index by remember { mutableIntStateOf(3) }

    Field("트레이", "${tray.traySetId} · ${tray.inspectionCnt}건 (개체 ${tray.trayId})")
    Field("수거", "${tray.collectedAt?.substringBefore(' ')?.drop(5)} ${tray.collectedAt?.substringAfter(' ')?.take(5)} · 담당 ${tray.collectedBy}")

    SectionTitle("HMD 촬영본 — ${target.partId}")
    // 대표 프레임 자리. 실증에서는 frameRef를 Coil로 로드하고 bbox를 오버레이한다.
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(T.Card)
            .border(1.dp, T.Line, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("대표 프레임 · ${view.viewSeq}면(${view.viewLabel})", fontSize = 12.sp, color = T.Muted)
            Text(view.frameRef ?: "-", fontSize = 10.sp, color = T.Muted)
            Text("bbox (%.2f, %.2f)".format(defect.bboxX, defect.bboxY), fontSize = 10.sp, color = T.Red)
        }
    }

    Field(
        "AI 판정",
        "${defect.typeLabel} · ${defect.severityLabel} · ${defect.detectionText}",
        accent = T.Red
    )
    if (defect.suspectFalsePositive) {
        NoteBox("오검출 의심 표본", "앙상블 ${defect.framesEval}장 중 ${defect.seenInFrames}장에서만 검출됐다. 학습셋 재검토 대상으로 분류된다.", T.Amber, T.AmberLight)
    }

    SectionTitle("실물 재확인")
    CodeRadioGroup(MatchChoice.entries, match, { match = it })

    if (match == MatchChoice.MISMATCH) {
        SectionTitle("확정 결함 유형 — 실물 기준")
        CodeRadioGroup(DefectType.active(), type, { type = it }, columns = 3)
        SectionTitle("확정 심각도")
        CodeRadioGroup(Severity.entries, sev, { sev = it }, columns = 3)
    }

    SectionTitle("영상 품질")
    CodeRadioGroup(QualityChoice.entries, quality, { quality = it })

    NoteBox(
        "v0.7 변경",
        "촬영본이 사진 1장이 아니라 대표 프레임 + 앙상블 ${view.framesEval}장이다. " +
            "일치율 ${view.agreeRate}는 프로파일 하한 ${vm.repo.profile().agreeReview} 근처이고, " +
            "이 면의 품질 플래그는 ${QualityFlag.entries.first { it.code == view.qualityFlag }.label}이다."
    )

    SectionTitle("처리 확정")
    Row(Modifier.fillMaxWidth()) {
        Disposition.active().forEach { d ->
            PrimaryButton(
                d.label,
                color = when (d) {
                    Disposition.SCRAP -> T.Red
                    Disposition.REWORK -> T.Amber
                    else -> T.Muted
                },
                modifier = Modifier.weight(1f)
            ) {
                vm.repo.registerItem(
                    itemId = target.itemId,
                    matchesAi = match == MatchChoice.MATCH,
                    confirmedType = type, confirmedSeverity = sev,
                    recaptureNeeded = quality == QualityChoice.RECAPTURE,
                    disposition = d,
                    objectionDetail = if (match == MatchChoice.MISMATCH) "실물 재확인 불일치 — 현장 메모" else null
                )
                index = (index % tray.inspectionCnt) + 1
                vm.toast("${d.label} 등록 — ${index}/${tray.inspectionCnt}")
            }
            if (d != Disposition.active().last()) Spacer(Modifier.width(6.dp))
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "$index / ${tray.inspectionCnt} · 다음 →", fontSize = 11.5.sp, color = T.Muted,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    PrimaryButton("불량 재고 · 폐기 관리로", T.Purple) { vm.go(Screen.M04) }
}
