package kr.co.snail.tacitq.mobile.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.snail.tacitq.mobile.ai.AiFrame
import kr.co.snail.tacitq.mobile.ai.AiMessages
import kr.co.snail.tacitq.mobile.ai.AiProduct
import kr.co.snail.tacitq.mobile.ai.AiResultsApi
import kr.co.snail.tacitq.mobile.relay.DevSettings
import kr.co.snail.tacitq.mobile.service.InspectionService
import kr.co.snail.tacitq.mobile.ui.components.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * M-05 검사 이력 — AI 서버의 제품 기록(`GET /products`)을 최신 순으로 본다. 2026-11-06: 이력의 원본은 서버다
 * (폰 DB 가 아님 — 폰을 바꿔도 남는다). 제품을 누르면 상세: 프레임 사진 전부 넘겨 보기, 결함 확대, 피드백
 * 맞음·틀림·확인필요 바꾸기(누구나, 서버가 변경 기록을 남긴다), 변경 기록.
 * 서버 주소·스테이션은 M-01 설정값을 쓴다. 새 제품이 끝나면(검사 중) 목록을 다시 받는다.
 */
@Composable
fun InspectionHistoryScreen(@Suppress("UNUSED_PARAMETER") vm: AppViewModel) {
    val ctx = LocalContext.current
    val settings = remember { DevSettings(ctx) }
    val api = remember { AiResultsApi(settings.aiUrl) }
    val card by InspectionService.lastProduct.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(Filter.ALL) }
    var items by remember { mutableStateOf<List<AiProduct>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun page(before: String?) {
        loading = true
        val r = withContext(Dispatchers.IO) {
            runCatching { api.products(settings.stationId, filter.feedback, filter.decision, PAGE, before) }
        }
        loading = false
        val reply = r.getOrNull()
        when {
            reply == null -> error = "서버 연결 실패 ${r.exceptionOrNull()?.message ?: ""}".trim()
            reply.code != 200 -> error = "이력 조회 실패 ${reply.code}"
            else -> {
                val pg = AiMessages.parseProducts(reply.body)
                items = if (before == null) pg.items else items + pg.items
                next = pg.nextBefore
                error = null
            }
        }
    }
    LaunchedEffect(filter, reload, card?.product?.productId) { page(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Filter.entries.forEach { f ->
            val on = filter == f
            Text(
                (if (on) "● " else "○ ") + f.label, fontSize = 13.sp, color = if (on) T.Purple else T.Muted,
                modifier = Modifier.padding(end = 14.dp).clickable { filter = f },
            )
        }
        Spacer(Modifier.weight(1f))
        Text("새로 고침", fontSize = 13.sp, color = T.Purple, fontWeight = FontWeight.Bold,
             modifier = Modifier.clickable { reload++ }.padding(4.dp))
    }

    SectionTitle("제품 기록 (최신 순 · 스테이션 ${settings.stationId})")
    error?.let { NoteBox("조회 실패", "$it — AI 서버 주소(${settings.aiUrl})와 연결을 확인하세요.", T.Red, T.RedLight) }
    if (items.isEmpty() && error == null) {
        NoteBox(if (loading) "불러오는 중" else "기록 없음", "검사 탭에서 검사를 시작하면 제품이 끝날 때마다 여기에 추가됩니다.")
        return
    }
    items.forEach { p -> HistoryRow(p) { open = p.productId } }
    if (next != null) Text(
        if (loading) "불러오는 중…" else "더 보기 ›", fontSize = 13.sp, color = T.Purple, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading) { scope.launch { page(next) } }.padding(12.dp),
    )
    open?.let { id -> ProductDialog(id, api, settings) { open = null; reload++ } }
}

/** 목록 필터 — 미입력·확인필요를 모아 몰아서 정리할 수 있게 */
private enum class Filter(val label: String, val feedback: List<String>?, val decision: String?) {
    ALL("전체", null, null),
    NOT_ENTERED("미입력", listOf("NOT_ENTERED"), null),
    UNSURE("확인필요", listOf("UNSURE"), null),
    FAIL("불합격", null, "FAIL"),
}

private const val PAGE = 50

@Composable
private fun HistoryRow(p: AiProduct, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${time(p.startedAt)}  ${p.productId}", fontSize = 12.5.sp, color = T.Ink, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(p.defectLabel, "프레임 ${p.frames}장 · 결함 ${p.defectFrames}장", if (p.complete) "모든 면" else null)
                    .joinToString(", "),
                fontSize = 11.sp, color = T.Muted,
            )
            Text("피드백 ${feedbackLabel(p.feedback)}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                 color = when (p.feedback) { "NOT_ENTERED" -> T.Amber; "UNSURE" -> T.Amber; "WRONG" -> T.Red; "CORRECT" -> T.Green; else -> T.Muted })
        }
        ProductBadge(p)
    }
}

/** 제품 상세 — 정보 · 피드백 바꾸기 · 변경 기록 · 프레임 사진 넘겨 보기 · 결함 확대 */
@Composable
private fun ProductDialog(productId: String, api: AiResultsApi, settings: DevSettings, onDismiss: () -> Unit) {
    var product by remember { mutableStateOf<AiProduct?>(null) }
    var frames by remember { mutableStateOf<List<AiFrame>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var defects by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(productId) {
        withContext(Dispatchers.IO) {
            runCatching {
                val d = api.product(productId)
                if (d.code != 200) throw IllegalStateException("제품 조회 실패 ${d.code}")
                val f = api.frames(productId, 0, MAX_FRAMES)
                AiMessages.parseProduct(d.body) to (if (f.code == 200) AiMessages.parseFrames(f.body).items else emptyList())
            }
        }.fold({ (p, fs) -> product = p; frames = fs }, { error = it.message ?: "서버 연결 실패" })
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(T.Bg).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(productId, fontSize = 13.sp, color = T.Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("닫기", fontSize = 15.sp, color = T.Purple, fontWeight = FontWeight.Bold,
                     modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
            }
            val p = product
            when {
                p == null && error == null -> Text("불러오는 중…", color = T.Muted)
                p == null -> NoteBox("조회 실패", error ?: "", T.Red, T.RedLight)
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProductBadge(p)
                        Text("${time(p.startedAt)} ~ ${time(p.endedAt)}", fontSize = 12.sp, color = T.Slate, modifier = Modifier.padding(start = 10.dp))
                    }
                    Text(listOfNotNull(p.defectLabel, "프레임 ${p.frames}장 · 결함 ${p.defectFrames}장",
                                       "모드 ${if (p.feedbackMode == "manual") "수동" else "자동"}").joinToString(" · "),
                         fontSize = 12.sp, color = T.Slate, modifier = Modifier.padding(top = 4.dp))
                    facesLine(p)?.let { Text(it, fontSize = 12.sp, color = if (p.complete) T.Green else T.Muted, modifier = Modifier.padding(top = 2.dp)) }

                    SectionTitle("피드백 — 지금 ${feedbackLabel(p.feedback)}")
                    FeedbackButtons(p.feedback, enabled = !sending) { f ->
                        sending = true
                        scope.launch {
                            sendFeedback(api, p.productId, f, "history", settings.deviceLabel)
                                .fold({ product = it; error = null }, { error = it.message ?: "서버 연결 실패" })
                            sending = false
                        }
                    }
                    error?.let { Text(it, fontSize = 12.sp, color = T.Red, modifier = Modifier.padding(top = 4.dp)) }
                    if (p.feedbackLog.isNotEmpty()) {
                        SectionTitle("변경 기록")
                        p.feedbackLog.forEach { c ->
                            Text("${time(c.changedAt)}  ${feedbackLabel(c.before)} → ${feedbackLabel(c.after)}  " +
                                 "(${if (c.source == "live") "현장" else "이력"}${c.device?.let { " · $it" } ?: ""})",
                                 fontSize = 11.5.sp, color = T.Slate)
                        }
                    }
                    if (p.resultIds.isNotEmpty()) Text(
                        "결함 확대 보기 (${p.resultIds.size}장) ›", fontSize = 13.sp, color = T.Purple, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { defects = p.resultIds }.padding(vertical = 10.dp),
                    )
                    SectionTitle("프레임 사진 (${frames.size}장${if (p.frames > frames.size) " / 전체 ${p.frames}장" else ""})")
                    if (frames.isNotEmpty()) FrameBrowser(p.productId, frames, api)
                }
            }
        }
    }
    defects?.let { DefectDialog(it, settings.aiUrl) { defects = null } }
}

/** 프레임 사진 넘겨 보기 — 결함 프레임은 결함 상자를 그린 사진(overlay=1). 핀치 확대·끌어서 이동 */
@Composable
private fun FrameBrowser(productId: String, frames: List<AiFrame>, api: AiResultsApi) {
    var index by remember(productId) { mutableIntStateOf(0) }
    val f = frames[index.coerceAtMost(frames.lastIndex)]
    var image by remember(productId, f.seq) { mutableStateOf<ImageBitmap?>(null) }
    var note by remember(productId, f.seq) { mutableStateOf("불러오는 중…") }
    LaunchedEffect(productId, f.seq) {
        val bmp = withContext(Dispatchers.IO) {
            runCatching { api.frameJpeg(productId, f.seq, overlay = f.decision != "OK") }.getOrNull()
                ?.bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        image = bmp?.asImageBitmap()
        if (bmp == null) note = "사진 없음"
    }
    Stepper("프레임 ${f.seq} · ${f.decision}${f.defectLabel?.let { " $it" } ?: ""} ·", index, frames.size) { index = it }
    Box(
        Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp)).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img == null) Text(note, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        else key(productId, f.seq) {
            Zoomable(Modifier.fillMaxSize()) {
                Image(img, contentDescription = "프레임 ${f.seq}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** 상세에서 한 번에 받는 프레임 수 — 서버 frames 목록 한 번 최대(500) */
private const val MAX_FRAMES = 500

/** 서버 ISO 시각(2026-11-06T10:12:03.120+09:00) → "11-06 10:12:03" */
private fun time(iso: String?): String = iso?.takeIf { it.length >= 19 }?.let { "${it.substring(5, 10)} ${it.substring(11, 19)}" } ?: "-"
