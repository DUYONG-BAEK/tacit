package kr.co.snail.tacitq.mobile.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.snail.tacitq.mobile.ai.AiMessages
import kr.co.snail.tacitq.mobile.ai.AiResultsApi
import kr.co.snail.tacitq.mobile.ai.AiStorage
import kr.co.snail.tacitq.mobile.data.SampleData
import kr.co.snail.tacitq.mobile.relay.DevSettings
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

    ServerStorage()

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

/**
 * AI 서버 디스크 사용량 — `GET /storage`. 서버는 프레임 사진을 자동으로 지우지 않으므로 여기서 남은 공간을 본다.
 * 폴더별·날짜별 용량은 서버가 5분마다 계산한 값이다(measured_at). 남은 공간이 [WARN_FREE] 아래면 빨강.
 */
@Composable
private fun ServerStorage() {
    val ctx = LocalContext.current
    val api = remember { AiResultsApi(DevSettings(ctx).aiUrl) }
    var storage by remember { mutableStateOf<AiStorage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            runCatching {
                val r = api.storage()
                if (r.code != 200) throw IllegalStateException("조회 실패 ${r.code}")
                AiMessages.parseStorage(r.body)
            }
        }.fold({ storage = it; error = null }, { error = it.message ?: "서버 연결 실패" })
    }
    Row {
        SectionTitle("AI 서버 디스크")
        Spacer(Modifier.weight(1f))
        Text("새로 고침", fontSize = 12.sp, color = T.Purple, fontWeight = FontWeight.Bold,
             modifier = Modifier.clickable { reload++ }.padding(top = 14.dp, end = 4.dp))
    }
    val s = storage
    if (s == null) { Field("상태", error ?: "불러오는 중…", accent = if (error != null) T.Red else null); return }
    val freeRate = if (s.diskTotal > 0) s.diskFree.toDouble() / s.diskTotal else 1.0
    Row {
        Field("남은 공간", "${gb(s.diskFree)} (%.0f%%)".format(freeRate * 100), accent = if (freeRate < WARN_FREE) T.Red else T.Green,
              modifier = Modifier.weight(1f))
        Field("사용 / 전체", "${gb(s.diskUsed)} / ${gb(s.diskTotal)}", modifier = Modifier.weight(1f))
    }
    if (s.data.isNotEmpty()) Field(
        "종류별", s.data.entries.joinToString(" · ") { (k, v) -> "${DATA_LABEL[k] ?: k} ${gb(v)}" },
    )
    if (s.perDay.isNotEmpty()) Field("날짜별 (최근 7일)", s.perDay.take(7).joinToString("\n") { (d, b) -> "$d  ${gb(b)}" })
    Text("계산 시각 ${s.measuredAt?.take(19)?.replace('T', ' ') ?: "아직 없음(서버 기동 직후)"}", fontSize = 10.5.sp, color = T.Muted)
}

private const val WARN_FREE = 0.10
private val DATA_LABEL = mapOf("product_frames" to "제품 프레임 사진", "results" to "결함 오버레이", "frames" to "옛 결함 원본", "logs" to "로그")

private fun gb(bytes: Long): String = if (bytes >= 1L shl 30) "%.1fGB".format(bytes / 1073741824.0) else "%.0fMB".format(bytes / 1048576.0)
