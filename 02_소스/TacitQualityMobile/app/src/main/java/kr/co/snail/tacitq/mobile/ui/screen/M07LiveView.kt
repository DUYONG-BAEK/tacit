package kr.co.snail.tacitq.mobile.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import kr.co.snail.tacitq.mobile.ai.DefectCrop
import kr.co.snail.tacitq.mobile.ai.LiveMark
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.hmd.LiveWire
import kr.co.snail.tacitq.mobile.relay.DevSettings
import kr.co.snail.tacitq.mobile.service.InspectionService
import kr.co.snail.tacitq.mobile.ui.components.Field
import kr.co.snail.tacitq.mobile.ui.components.NoteBox
import kr.co.snail.tacitq.mobile.ui.components.SectionTitle
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-07 영상 모니터링 — 검사 중 HMD 에서 폰으로 들어오는 영상을 그대로 본다.
 *
 * HMD 에 따로 붙지 않고 서비스가 이미 받는 프레임 중 마지막 1장([InspectionService.liveFrame])만 쓴다.
 * 화면이 보일 때만, 초당 최대 10장만 그림으로 바꾼다 — 주머니 속(화면 꺼짐)에서는 비용 없음.
 */
@OptIn(FlowPreview::class)
@Composable
fun LiveViewScreen(@Suppress("UNUSED_PARAMETER") vm: AppViewModel) {
    val running by InspectionService.running.collectAsStateWithLifecycle()
    val hmdOn by InspectionService.hmdVideo.collectAsStateWithLifecycle()
    val hmdBle by InspectionService.hmdBle.collectAsStateWithLifecycle()
    val status by InspectionService.relayStatus.collectAsStateWithLifecycle()
    val liveMark by InspectionService.liveMark.collectAsStateWithLifecycle()
    val lastDefectId by InspectionService.lastDefectId.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val settings = remember { DevSettings(ctx) }
    val sampled = remember { InspectionService.liveFrame.sample(100) }
    val frame by sampled.collectAsStateWithLifecycle(initialValue = InspectionService.liveFrame.value)

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 결함 표시 — 마지막 결함 뒤 markHoldMs 유지, 지움(searching·끊김)이면 바로(R1)
    var mark by remember { mutableStateOf<LiveMark?>(null) }
    LaunchedEffect(liveMark) {
        val m = liveMark
        if (m == null || m.mark.decision == null) { mark = null; return@LaunchedEffect }
        mark = m.mark
        delay((MARK_HOLD_MS - (System.currentTimeMillis() - m.at)).coerceAtLeast(0))
        mark = null
    }
    var fps by remember { mutableStateOf(0.0) }
    val window = remember { ArrayDeque<Pair<Long, Long>>() }   // (받은 시각, HMD 순번) — 최근 1초

    LaunchedEffect(frame) {
        val f = frame ?: run { bitmap = null; window.clear(); fps = 0.0; return@LaunchedEffect }
        val now = System.currentTimeMillis()
        window.addLast(now to f.hmdSeq)
        while (window.size > 1 && now - window.first().first > 1_000) window.removeFirst()
        val (t0, s0) = window.first()
        fps = if (now > t0 && f.hmdSeq > s0) (f.hmdSeq - s0) * 1000.0 / (now - t0) else fps
        if (f.codec == "jpeg") bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(f.data, 0, f.data.size) }
    }

    val f = frame
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        when {
            !running -> Hint("검사 시작 후 HMD 영상이 표시됩니다")
            f == null -> Hint(if (hmdOn) "영상 기다리는 중…" else "HMD 영상 연결 대기 — 5초마다 다시 연결")
            f.codec != "jpeg" -> Hint("H.265 영상은 폰 미리보기 미지원\n(엣지로는 정상 전송 중)")
            b != null -> {
                Image(b.asImageBitmap(), contentDescription = "HMD 영상", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                mark?.let { LiveMarks(it, circles = settings.liveMarks) }
            }
        }
    }

    // 최근 결함 확대 — 서버가 저장한 결함 프레임(result_id)이 오면 바뀐다. 누르면 전체 화면(API §4.2)
    if (running) lastDefectId?.let { id ->
        SectionTitle("최근 결함 프레임 (누르면 크게)")
        DefectThumb(id, settings.aiUrl, Modifier.fillMaxWidth(0.5f))
    }

    if (running) {
        val s = status
        val b = bitmap
        SectionTitle("상태")
        Row {
            Field("해상도", if (b != null && f?.codec == "jpeg") "${b.width}×${b.height}" else f?.codec ?: "-", modifier = Modifier.weight(1f))
            Field("초당 장수", if (f != null) "%.0f fps".format(fps) else "-", modifier = Modifier.weight(1f))
        }
        Row {
            Field("HMD 영상", if (hmdOn) "수신" else "끊김", accent = if (hmdOn) null else T.Red, modifier = Modifier.weight(1f))
            Field("HMD 화면(BLE)", hmdBle, modifier = Modifier.weight(1f))
        }
        Row {
            Field("엣지", if (s?.edgeConnected == true) "연결" else "끊김", accent = if (s?.edgeConnected == true) null else T.Red, modifier = Modifier.weight(1f))
            Field("보냄 · 버림", s?.let { "${it.sent} · ${it.dropped}" } ?: "-", modifier = Modifier.weight(1f))
        }
        s?.guide?.let { Field("AI 서버 상태", it, accent = if (it.startsWith("설정 오류")) T.Red else T.Teal) }
        val seg = s?.lastSegment                                  // AI 서버 — 마지막 제품 구간
        val judge = s?.lastJudge                                  // 모의 엣지 — "부품ID 판정"
        val pass = seg?.pass ?: (judge?.endsWith("PASS") == true)
        Field(
            "마지막 판정",
            seg?.let { "${if (it.pass) "합격" else "불합격"} (${it.aiDecision}${it.defectLabel?.let { l -> " $l" } ?: ""}) · 결함 프레임 ${it.defectFrames}/${it.frames}장" }
                ?: judge?.let { "${it.substringBefore(' ')} · ${if (pass) "합격" else "불합격"} · ${s.lastDelayMs}ms" } ?: "아직 없음",
            accent = when { seg == null && judge == null -> null; pass -> T.Green; else -> T.Red },
        )
    } else {
        Spacer(Modifier.height(10.dp))
        NoteBox("안내", "검사 탭에서 '검사 시작'을 누르면 HMD 카메라 영상이 여기에 실시간으로 나옵니다. 엣지로 보내는 영상과 같은 영상입니다.")
    }
}

/** 영상 위 실시간 결함 — 판정 글자와(켜져 있으면) 결함 원. 좌표는 서버가 받은 프레임(frame_size) 기준 → 화면 맞춤(Fit) */
@Composable
private fun BoxScope.LiveMarks(m: LiveMark, circles: Boolean) {
    val ng = m.decision == "NG"
    val color = if (ng) Color.Red else Color(0xFFFFC107)
    if (circles && m.frameW > 0 && m.frameH > 0) {
        val minR = with(LocalDensity.current) { 14.dp.toPx() }
        val stroke = with(LocalDensity.current) { 2.5.dp.toPx() }
        val boxes = remember(m) { LiveWire.merge(m.detections) }
        Canvas(Modifier.fillMaxSize()) {
            val f = DefectCrop.fit(m.frameW, m.frameH, size.width, size.height)
            boxes.forEach { box ->
                val b = box.box
                val r = maxOf(minR, (maxOf(b[2] - b[0], b[3] - b[1]) * f.scale * 0.75).toFloat())
                drawCircle(color, r, Offset(f.x((b[0] + b[2]) / 2), f.y((b[1] + b[3]) / 2)), style = Stroke(stroke))
            }
        }
    }
    Text(
        (if (ng) "✕ 불량" else "? 애매") + (m.label?.let { " · $it" } ?: ""),
        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(color.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 결함 표시 유지 — 모바일 Profile.markHoldMs · HMD LiveHold 와 같은 값 */
private val MARK_HOLD_MS = Profile().markHoldMs

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp, modifier = Modifier.padding(16.dp))
}
