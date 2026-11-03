package kr.co.snail.tacitq.mobile.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.co.snail.tacitq.mobile.data.SampleData
import kr.co.snail.tacitq.mobile.model.*
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-06 관리자 대시보드 — 영상 방식이 잘 도는지는 프레임 지표로만 안다.
 *
 * 지표는 전부 TB_INSPECTION / TB_INSPECTION_VIEW에서 계산된다.
 * 게이팅 드롭률의 분모는 FRAMES_IN_SEGMENT(수신 전체)다 — 설계서 v0.7의
 * "60fps → 약 8fps" 전제에 따르면 90% 내외가 정상이며, 이 값이 갑자기
 * 낮아지면 게이팅이 느슨해졌다는 뜻이다.
 */
@Composable
fun AdminDashboardScreen(vm: AppViewModel) {
    val inspections by vm.repo.inspections.collectAsStateWithLifecycle()
    val profile = vm.repo.profile()
    val view = SampleData.representativeView

    val total = SampleData.SHIFT_TOTAL
    val failRate = SampleData.SHIFT_FAIL * 100.0 / total
    val objRate = SampleData.SHIFT_OBJECTION * 100.0 / total
    val reviewRate = SampleData.SHIFT_REVIEW * 100.0 / total
    val e2eP95 = inspections.map { it.finalE2eMs }.sorted().let {
        it[(it.size * 95 / 100).coerceAtMost(it.lastIndex)]
    }
    val gestureUsed = inspections.count { it.inputMethod == InputMethod.GESTURE.code }
    val gestureFail = inspections.sumOf { it.gestureRetries }
    val gestureFailRate = if (gestureUsed == 0) 0.0 else gestureFail * 100.0 / gestureUsed

    Row(Modifier.fillMaxWidth()) {
        MetricTile("%.1f%%".format(failRate), "불량률", "▼0.3", T.Ink)
        Spacer(Modifier.width(8.dp))
        MetricTile("%.1f%%".format(objRate), "이의율", "▼0.4", T.Amber)
        Spacer(Modifier.width(8.dp))
        MetricTile("%.1f%%".format(reviewRate), "REVIEW율", null, T.Ink)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        MetricTile("${e2eP95}ms", "E2E P95", null, if (e2eP95 > 1200) T.Red else T.Ink)
        Spacer(Modifier.width(8.dp))
        MetricTile("%.1f%%".format(gestureFailRate), "제스처 실패율", null, T.Ink)
        Spacer(Modifier.width(8.dp))
        MetricTile("2회 18분", "설비 정지", null, T.Ink)
    }

    SectionTitle("v0.7 신설 지표 — 프레임 계열")
    val dropRate = view.dropped * 100.0 / view.framesInSegment
    Field(
        "게이팅 드롭률",
        "%.1f%% (흔들림 %d · 반사 %d · 각도 %d · 중복 %d / 수신 %d)".format(
            dropRate, view.dropBlur, view.dropGlare, view.dropAngle, view.dropDuplicate,
            view.framesInSegment
        )
    )
    Field("면당 확보 프레임", "${view.framesEval}장 / 목표 ${profile.framesTarget}장")
    Field(
        "앙상블 일치율",
        "${view.agreeRate} / 경보 ${profile.agreeReview}",
        accent = if (view.agreeRate < profile.agreeReview) T.Red else null
    )
    Field("면 체류 시간", "${view.dwellMs}ms / 최소 ${profile.minDwellMs}ms")

    NoteBox(
        "이의율이 떨어진 것은 좋은 신호가 아닐 수 있다",
        "입력이 어려워졌다는 신호일 수도 있다. 제스처 실패율과 함께 읽어야 한다. " +
            "드롭률이 설계 전제(약 90%)보다 크게 낮아지면 게이팅 기준이 느슨해진 것이므로 " +
            "AGREE_RATE 하락과 짝지어 확인할 것.",
        T.Amber, T.AmberLight
    )

    Spacer(Modifier.height(12.dp))
    PrimaryButton("임계값 설정", T.Purple) {
        vm.toast("프로파일 ${profile.profileVersion} — 변경 시 감사 로그 기록")
    }
    Text(
        "모델 ${SampleData.MODEL_VERSION} · 프로파일 ${profile.profileVersion}",
        fontSize = 10.5.sp, color = T.Muted, modifier = Modifier.padding(top = 8.dp)
    )
}
