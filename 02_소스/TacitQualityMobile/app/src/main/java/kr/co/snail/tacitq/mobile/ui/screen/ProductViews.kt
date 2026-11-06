package kr.co.snail.tacitq.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.snail.tacitq.mobile.ai.AiMessages
import kr.co.snail.tacitq.mobile.ai.AiProduct
import kr.co.snail.tacitq.mobile.ai.AiResultsApi
import kr.co.snail.tacitq.mobile.ai.Feedback
import kr.co.snail.tacitq.mobile.ui.components.Badge
import kr.co.snail.tacitq.mobile.ui.theme.T

/** 제품 판정 배지 — 합격 / 불합격 (NG) / 불합격 (REVIEW). 끝나지 않은 제품은 "기록 중단" */
@Composable
fun ProductBadge(p: AiProduct) = when {
    p.decision == null -> Badge("기록 중단", T.Muted, T.Card)
    p.pass -> Badge("합격", T.Green, T.GreenLight)
    p.aiDecision == "REVIEW" -> Badge("불합격 (REVIEW)", T.Amber, T.AmberLight)
    else -> Badge("불합격 (NG)", T.Red, T.RedLight)
}

/**
 * 피드백 버튼 3개 — 맞음 · 틀림 · 확인필요. 현재 값([current])은 채워서 보인다.
 * 폰은 삼각대에 거치되어 작업자 바로 옆에 있으므로 장갑 낀 손으로도 누르기 쉽게 크게 둔다.
 */
@Composable
fun FeedbackButtons(current: String?, enabled: Boolean = true, onPick: (Feedback) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Feedback.INPUTS.forEach { f ->
            val on = current == f.code
            val color = when (f) { Feedback.CORRECT -> T.Green; Feedback.WRONG -> T.Red; else -> T.Amber }
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) color else T.Surface)
                    .border(2.dp, color, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { onPick(f) },
                contentAlignment = Alignment.Center,
            ) {
                Text(f.label, color = if (on) T.Surface else color, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 피드백 값 → 화면 문구 */
fun feedbackLabel(code: String?): String = Feedback.of(code)?.label ?: (code ?: "-")

/** 면 진행 한 줄 — `■ 상면 □ 엣지 □ 하면비닐` */
fun facesLine(p: AiProduct): String? = p.faces.keys.takeIf { it.isNotEmpty() }?.joinToString("  ") {
    (if (it in p.facesSeen) "■ " else "□ ") + it
}?.let { if (p.complete) "$it  ✓ 모든 면" else it }

/**
 * 피드백 보내기 — 응답으로 온 제품(바뀐 피드백·변경 기록)을 돌려준다. 실패하면 [Result.failure] (메시지를 화면에 띄운다).
 * 같은 API 로 처음 입력(M-07, source=live)과 수정(M-05, source=history)을 모두 한다.
 */
suspend fun sendFeedback(api: AiResultsApi, productId: String, f: Feedback, source: String, device: String): Result<AiProduct> =
    withContext(Dispatchers.IO) {
        runCatching {
            val r = api.putFeedback(productId, f.code, source, device)
            if (r.code != 200) throw IllegalStateException("피드백 저장 실패 ${r.code}")
            AiMessages.parseProduct(r.body)
        }
    }
