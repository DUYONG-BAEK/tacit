package kr.co.snail.tacitq.mobile.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.snail.tacitq.mobile.ai.AiResultsApi
import kr.co.snail.tacitq.mobile.ai.FeedbackMode
import kr.co.snail.tacitq.mobile.ai.DefectCrop
import kr.co.snail.tacitq.mobile.ai.LiveMark
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.hmd.HudMirror
import kr.co.snail.tacitq.mobile.hmd.LiveWire
import kr.co.snail.tacitq.mobile.relay.DevSettings
import kr.co.snail.tacitq.mobile.service.InspectionService
import kr.co.snail.tacitq.mobile.ui.components.Field
import kr.co.snail.tacitq.mobile.ui.components.NoteBox
import kr.co.snail.tacitq.mobile.ui.components.SectionTitle
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-07 영상 모니터링 — 검사 중 HMD 에서 폰으로 들어오는 영상을 그대로 본다. 그 위에 HMD 가 그리는 글자·결함 원을
 * 똑같이 그려(HudMirror) HMD 를 쓴 작업자가 보는 화면을 폰에서 그대로 본다.
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
    val hmdInfo by InspectionService.hmdInfo.collectAsStateWithLifecycle()
    val status by InspectionService.relayStatus.collectAsStateWithLifecycle()
    val liveMark by InspectionService.liveMark.collectAsStateWithLifecycle()
    val lastDefectId by InspectionService.lastDefectId.collectAsStateWithLifecycle()
    val hudScreen by InspectionService.hudScreen.collectAsStateWithLifecycle()
    val hudHint by InspectionService.hudHint.collectAsStateWithLifecycle()
    val hudOverlay by InspectionService.hudOverlay.collectAsStateWithLifecycle()
    val hudResult by InspectionService.hudResult.collectAsStateWithLifecycle()
    val recent by InspectionService.recentProducts.collectAsStateWithLifecycle()
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
    // 직전 검사 결과 — HMD 오른쪽 위 칸, 다음 결과까지 유지
    val result = hudResult?.let { Triple(it.decision, it.label, it.face) }
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
                // HMD 와 같은 화면 — 영상(자르지 않고 가운데) + HMD 가 그리는 글자·결함 원
                Image(b.asImageBitmap(), contentDescription = "HMD 영상", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                HmdScreen(HudMirror.view(hudScreen, hudHint, mark, hudOverlay, result), mark, circles = settings.liveMarks)
            }
        }
        if (running && hmdBle != "HMD 연결됨") Text(
            "HMD 화면(BLE) 연결 안 됨 — 실제 HMD 화면과 다를 수 있음", color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }

    // 최근 면 검사 카드(최대 5건, 새 것이 위) — 대표 프레임 사진(결함이면 상자). 수동 모드면 맞음·틀림·확인필요
    // (폰이 작업자 옆 삼각대에 있다). 더 오래된 검사는 이력(M-05)
    if (running && recent.isNotEmpty()) {
        SectionTitle("최근 검사 (${recent.size}/${InspectionService.RECENT}건 · 새 것이 위)")
        recent.forEach { key(it.product.productId) { ResultCard(it, settings) } }
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
        Field("HMD 배터리", hmdBatteryText(hmdInfo), accent = if (hmdInfo?.batteryLow == true) T.Red else null)
        Row {
            Field("엣지", if (s?.edgeConnected == true) "연결" else "끊김", accent = if (s?.edgeConnected == true) null else T.Red, modifier = Modifier.weight(1f))
            Field("보냄 · 버림", s?.let { "${it.sent} · ${it.dropped}" } ?: "-", modifier = Modifier.weight(1f))
        }
        s?.guide?.let { Field("AI 서버 상태", it, accent = if (it.startsWith("설정 오류")) T.Red else T.Teal) }
        val seg = s?.lastSegment                                  // AI 서버 — 마지막 면 검사
        val judge = s?.lastJudge                                  // 모의 엣지 — "부품ID 판정"
        val pass = seg?.pass ?: (judge?.endsWith("PASS") == true)
        Field(
            "마지막 판정",
            seg?.let { "${it.face?.let { f -> "$f " } ?: ""}${if (it.pass) "합격" else "불합격"} (${it.aiDecision}${it.defectLabel?.let { l -> " $l" } ?: ""}) · 결함 프레임 ${it.defectFrames}/${it.frames}장" }
                ?: judge?.let { "${it.substringBefore(' ')} · ${if (pass) "합격" else "불합격"} · ${s.lastDelayMs}ms" } ?: "아직 없음",
            accent = when { seg == null && judge == null -> null; pass -> T.Green; else -> T.Red },
        )
    } else {
        Spacer(Modifier.height(10.dp))
        NoteBox("안내", "검사 탭에서 '검사 시작'을 누르면 HMD 카메라 영상이 여기에 실시간으로 나옵니다. 엣지로 보내는 영상과 같은 영상입니다.")
    }
}

/**
 * HMD 화면 따라 그리기 — HMD(RealWear 1280×720 = 853×480dp)가 카메라 위에 그리는 글자·결함 원을 같은 자리·크기 비율·색으로.
 * 글자는 [HudMirror](HMD Screens·LiveHold 와 같은 규칙), 배치는 HMD `activity_main.xml` 과 같다(여백 28·18dp, 위 줄 20sp,
 * 가운데 큰 글자 44sp, 본문 22sp, 예외 띠 위 56dp, 오른쪽 위 직전 결과 칸 36sp). 영상은 HMD 와 같이 자르지 않고 가운데 맞춘다.
 */
@Composable
private fun HmdScreen(v: HudMirror.View, mark: LiveMark?, circles: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val k = maxWidth / HMD_W                                    // HMD 화면 → 이 상자 배율
        val density = LocalDensity.current
        fun sp(hmdSp: Float) = with(density) { (hmdSp * k).dp.toSp() }
        val shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 4f)
        // 결함 원 — HMD MarkerView 와 같은 맞춤·크기(최소 지름 40dp, 선 4dp)
        if (v.marks && circles && mark?.decision != null && mark.frameW > 0 && mark.frameH > 0) {
            val boxes = remember(mark) { LiveWire.merge(mark.detections) }
            val minR = with(density) { (20 * k).dp.toPx() }
            val stroke = with(density) { (4 * k).dp.toPx() }
            val color = Color(if (mark.decision == "NG") HudMirror.Tone.BAD.argb else HudMirror.Tone.WARN.argb)
            Canvas(Modifier.fillMaxSize()) {
                val f = DefectCrop.fit(mark.frameW, mark.frameH, size.width, size.height)
                boxes.forEach { box ->
                    val b = box.box
                    val r = maxOf(minR, (maxOf(b[2] - b[0], b[3] - b[1]) * f.scale * 0.75).toFloat())
                    drawCircle(color, r, Offset(f.x((b[0] + b[2]) / 2), f.y((b[1] + b[3]) / 2)), style = Stroke(stroke))
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp * k, vertical = 18.dp * k)) {
            Text(v.topLeft, color = Color(v.topLeftTone.argb), fontSize = sp(20f), fontWeight = FontWeight.Bold, maxLines = 1,
                 style = TextStyle(shadow = shadow))
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                   horizontalAlignment = Alignment.CenterHorizontally) {
                if (v.big.isNotEmpty()) Text(v.big, color = Color(v.bigTone.argb), fontSize = sp(44f), fontWeight = FontWeight.Bold,
                                             textAlign = TextAlign.Center, style = TextStyle(shadow = shadow))
                if (v.lines.isNotEmpty()) Text(v.lines.joinToString("\n"), color = Color(v.linesTone.argb), fontSize = sp(22f),
                                               textAlign = TextAlign.Center, style = TextStyle(shadow = shadow),
                                               modifier = Modifier.padding(top = 10.dp * k))
            }
        }
        // 직전 검사 결과 칸 — HMD activity_main.xml 의 resultBox 와 같다(반투명 판 · 왼쪽 색 띠 8dp · "직전 검사" 16sp · 결과 36sp)
        v.result?.let { r ->
            val c = Color(v.resultTone.argb)
            Row(Modifier.align(Alignment.TopEnd).padding(top = 12.dp * k, end = 16.dp * k)
                    .background(Color(0xB3000000)).height(IntrinsicSize.Min)) {
                Box(Modifier.width(8.dp * k).fillMaxHeight().background(c))
                Column(Modifier.padding(horizontal = 14.dp * k, vertical = 6.dp * k), horizontalAlignment = Alignment.End) {
                    Text("직전 검사", color = Color(HudMirror.Tone.STOP.argb), fontSize = sp(16f))
                    Text(r, color = c, fontSize = sp(36f), fontWeight = FontWeight.Bold, maxLines = 1, style = TextStyle(shadow = shadow))
                }
            }
        }
        v.overlay?.let {
            Text(it, color = Color(0xFF101620), fontSize = sp(20f), fontWeight = FontWeight.Bold,
                 modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp * k).background(Color(0xE6F0A95C))
                     .padding(horizontal = 16.dp * k, vertical = 6.dp * k))
        }
    }
}

/**
 * 최근 검사 카드 — 면·판정·결함 종류·대표 프레임 사진(서버 `/products/{id}/frames/{best_seq}.jpg`, 결함이면 상자를 그려서).
 * 수동 모드면 버튼 3개로 피드백을 보낸다(source=live). 최근 5건에서 밀려난 검사는 M-05 이력에서 고친다.
 */
@Composable
private fun ResultCard(card: InspectionService.Companion.ProductCard, settings: DevSettings) {
    val p = card.product
    val scope = rememberCoroutineScope()
    val api = remember(settings.aiUrl) { AiResultsApi(settings.aiUrl) }
    var sending by remember(p.productId) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(10.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            p.face?.let { Text(it, fontSize = 15.sp, color = T.Ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp)) }
            ProductBadge(p)
            Text(
                listOfNotNull(p.defectLabel, "결함 ${p.defectFrames}/${p.frames}장").joinToString(" · "),
                fontSize = 12.5.sp, color = T.Slate, modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
        }
        p.bestSeq?.let { FrameThumb(p.productId, it, overlay = !p.pass, api, Modifier.padding(top = 8.dp).fillMaxWidth(0.6f)) }
        if (card.mode == FeedbackMode.MANUAL) {
            Text("AI 판정이 맞나요?", fontSize = 14.sp, color = T.Ink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            FeedbackButtons(card.sent ?: p.feedback, enabled = !sending) { f ->
                sending = true
                scope.launch {
                    val r = sendFeedback(api, p.productId, f, "live", settings.deviceLabel)
                    sending = false
                    // 그 사이 최근 5건에서 밀려났으면 건드릴 카드가 없다
                    InspectionService.recentProducts.update { list ->
                        list.map { c ->
                            if (c.product.productId != p.productId) c
                            else r.fold({ c.copy(product = it, sent = it.feedback, error = null) },
                                        { c.copy(error = it.message ?: "서버 연결 실패") })
                        }
                    }
                }
            }
            card.error?.let { Text(it, fontSize = 12.sp, color = T.Red, modifier = Modifier.padding(top = 4.dp)) }
            card.sent?.let { Text("저장됨 — ${feedbackLabel(it)} (이력에서 바꿀 수 있음)", fontSize = 12.sp, color = T.Muted, modifier = Modifier.padding(top = 4.dp)) }
        } else {
            Text("자동 모드 — 피드백 없음 (이력에서 넣을 수 있음)", fontSize = 12.sp, color = T.Muted, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** 대표 프레임 사진 — 누르면 전체 화면(핀치 확대). 결함 검사면 서버가 결함 상자를 그려 준다(overlay=1) */
@Composable
private fun FrameThumb(productId: String, seq: Long, overlay: Boolean, api: AiResultsApi, modifier: Modifier) {
    var image by remember(productId, seq) { mutableStateOf<ImageBitmap?>(null) }
    var note by remember(productId, seq) { mutableStateOf("불러오는 중…") }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(productId, seq) {
        val bmp = withContext(Dispatchers.IO) {
            runCatching { api.frameJpeg(productId, seq, overlay) }.getOrNull()
                ?.bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        image = bmp?.asImageBitmap()
        if (bmp == null) note = "사진 없음"
    }
    Box(
        modifier.aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.Black).clickable(enabled = image != null) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img == null) Text(note, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        else Image(img, contentDescription = "프레임 $seq", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
    val img = image
    if (open && img != null) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
            Text("닫기", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                 modifier = Modifier.align(Alignment.End).clickable { open = false }.padding(8.dp))
            Zoomable(Modifier.fillMaxWidth().weight(1f)) {
                Image(img, contentDescription = "프레임 $seq", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** HMD 화면 폭(RealWear 520: 1280px = 853dp) — 따라 그리기 배율 기준 */
private val HMD_W = 853.dp

/** 결함 표시 유지 — 모바일 Profile.markHoldMs · HMD LiveHold 와 같은 값 */
private val MARK_HOLD_MS = Profile().markHoldMs

@Composable
private fun Hint(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp, modifier = Modifier.padding(16.dp))
}
