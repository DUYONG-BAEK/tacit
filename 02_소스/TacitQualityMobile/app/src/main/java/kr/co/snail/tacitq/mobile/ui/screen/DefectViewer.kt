package kr.co.snail.tacitq.mobile.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.snail.tacitq.mobile.ai.AiBox
import kr.co.snail.tacitq.mobile.ai.AiResultsApi
import kr.co.snail.tacitq.mobile.ai.CropSquare
import kr.co.snail.tacitq.mobile.ai.DefectCrop
import kr.co.snail.tacitq.mobile.ai.DefectFrame
import kr.co.snail.tacitq.mobile.ai.DefectLoader
import kr.co.snail.tacitq.mobile.ui.theme.T

/**
 * 결함 확대 보기 — API §4.2. 서버가 저장한 결함 프레임(result_id)의 오버레이를 불러와
 * 전체 이미지(결함마다 원) + 결함별 정사각 확대(넘겨 보기)를 보여 주고, 둘 다 손가락 확대·이동을 지원한다.
 * 이미지는 AI 서버([baseUrl] = 설정의 AI 서버 주소)에서 가져온다.
 */

private sealed interface Load {
    data object Loading : Load
    data class Fail(val message: String) : Load
    class Ok(val frame: DefectFrame, val bitmap: ImageBitmap, val defects: List<Pair<AiBox, CropSquare>>) : Load
}

@Composable
private fun rememberDefect(resultId: String, baseUrl: String): Load {
    val loader = remember(baseUrl) { DefectLoader(AiResultsApi(baseUrl)) }
    var state by remember(resultId, baseUrl) { mutableStateOf<Load>(Load.Loading) }
    LaunchedEffect(resultId, baseUrl) {
        state = when (val r = loader.load(resultId)) {
            is DefectLoader.Result.Fail -> Load.Fail(r.message)
            is DefectLoader.Result.Ok -> {
                val v = r.frame.view
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(r.frame.jpeg, 0, r.frame.jpeg.size) }
                if (bmp == null || v.frameW <= 0 || v.frameH <= 0) Load.Fail("이미지를 열 수 없음")
                else Load.Ok(r.frame, bmp.asImageBitmap(), DefectCrop.squares(v.detections, v.frameW, v.frameH))
            }
        }
    }
    return state
}

/** M-07 작은 창 — 최근 결함 프레임에서 가장 확실한 결함의 확대. 누르면 전체 화면으로 연다. */
@Composable
fun DefectThumb(resultId: String, baseUrl: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val s = rememberDefect(resultId, baseUrl)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        when (s) {
            Load.Loading -> Note("불러오는 중…")
            is Load.Fail -> Note(s.message)
            is Load.Ok -> FrameCanvas(s, s.defects.firstOrNull()?.second, Modifier.fillMaxSize())
        }
    }
    if (open) DefectDialog(listOf(resultId), baseUrl) { open = false }
}

/** 전체 화면 — 결함 프레임 여러 장([resultIds], 대표가 첫 번째)을 넘겨 본다 */
@Composable
fun DefectDialog(resultIds: List<String>, baseUrl: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var frameIdx by remember { mutableIntStateOf(0) }
        val id = resultIds[frameIdx]
        var defectIdx by remember(id) { mutableIntStateOf(0) }
        val s = rememberDefect(id, baseUrl)
        Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("결함 프레임  $id", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("닫기", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                     modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
            }
            if (resultIds.size > 1) Stepper("프레임", frameIdx, resultIds.size) { frameIdx = it }
            when (s) {
                Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Note("불러오는 중…") }
                is Load.Fail -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Note(s.message) }
                is Load.Ok -> {
                    val v = s.frame.view
                    Text(listOfNotNull(v.decision, v.defectLabel, v.score?.let { "점수 %.2f".format(it) }).joinToString(" · "),
                         color = if (v.decision == "NG") T.Red else T.Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                         modifier = Modifier.padding(vertical = 6.dp))
                    key(id) { Zoomable(Modifier.fillMaxWidth().weight(1f)) { FrameCanvas(s, null, Modifier.fillMaxSize(), circles = true) } }
                    if (s.defects.isNotEmpty()) {
                        val (box, sq) = s.defects[defectIdx.coerceAtMost(s.defects.size - 1)]
                        Stepper("결함 ${box.label} %.2f ·".format(box.conf), defectIdx, s.defects.size) { defectIdx = it }
                        key(id, defectIdx) { Zoomable(Modifier.fillMaxWidth().weight(1f)) { FrameCanvas(s, sq, Modifier.fillMaxSize()) } }
                    }
                }
            }
        }
    }
}

/** 오버레이의 [crop] 영역(null = 전체)을 화면에 맞춰 그린다. [circles] 면 결함마다 원 — 결함이 수 px 라 위치 표시용. */
@Composable
private fun FrameCanvas(s: Load.Ok, crop: CropSquare?, modifier: Modifier, circles: Boolean = false) {
    val minR = with(LocalDensity.current) { 20.dp.toPx() }
    val stroke = with(LocalDensity.current) { 3.dp.toPx() }
    val v = s.frame.view
    val color = if (v.decision == "NG") Color.Red else Color(0xFFFFC107)
    Canvas(modifier) {
        val l = crop?.left ?: 0; val t = crop?.top ?: 0
        val w = crop?.side ?: v.frameW; val h = crop?.side ?: v.frameH
        // 오버레이 크기는 frame_size 와 같다(API §4.2) — 혹시 다르면 비율로 맞춘다
        val sx = s.bitmap.width.toDouble() / v.frameW; val sy = s.bitmap.height.toDouble() / v.frameH
        val f = DefectCrop.fit(w, h, size.width, size.height)
        drawImage(
            s.bitmap,
            srcOffset = IntOffset((l * sx).toInt(), (t * sy).toInt()),
            srcSize = IntSize((w * sx).toInt(), (h * sy).toInt()),
            dstOffset = IntOffset(f.dx.toInt(), f.dy.toInt()),
            dstSize = IntSize((w * f.scale).toInt(), (h * f.scale).toInt()),
        )
        if (circles) s.defects.forEach { (box, _) ->
            val b = box.box
            val r = maxOf(minR, (maxOf(b[2] - b[0], b[3] - b[1]) * f.scale * 0.75).toFloat())
            drawCircle(color, r, Offset(f.x((b[0] + b[2]) / 2 - l), f.y((b[1] + b[3]) / 2 - t)), style = Stroke(stroke))
        }
    }
}

/** 핀치 확대(1~8배)·끌어서 이동 — M-05 프레임 보기도 쓴다 */
@Composable
internal fun Zoomable(modifier: Modifier, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
            content()
        }
    }
}

@Composable
internal fun Stepper(label: String, index: Int, count: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Text("◀", color = if (index > 0) Color.White else Color.DarkGray, fontSize = 18.sp,
             modifier = Modifier.clickable(enabled = index > 0) { onChange(index - 1) }.padding(horizontal = 18.dp, vertical = 6.dp))
        Text("$label ${index + 1}/$count", color = Color.White, fontSize = 13.sp)
        Text("▶", color = if (index < count - 1) Color.White else Color.DarkGray, fontSize = 18.sp,
             modifier = Modifier.clickable(enabled = index < count - 1) { onChange(index + 1) }.padding(horizontal = 18.dp, vertical = 6.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
}
