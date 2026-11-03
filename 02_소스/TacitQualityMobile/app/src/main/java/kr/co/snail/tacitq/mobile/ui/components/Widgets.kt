package kr.co.snail.tacitq.mobile.ui.components

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.snail.tacitq.mobile.model.CodeItem
import kr.co.snail.tacitq.mobile.ui.theme.T

/** 라벨 + 값 한 줄. 프로토타입의 .fld 에 대응한다. */
@Composable
fun Field(label: String, value: String, accent: Color? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(T.Surface)
            .border(1.dp, accent ?: T.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(label, fontSize = 11.sp, color = T.Muted, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 14.sp, color = accent ?: T.Ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, fontSize = 11.sp, color = T.Muted, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

/** 상태 배지. 색만으로 구분하지 않도록 항상 문구를 함께 싣는다. */
@Composable
fun Badge(text: String, fg: Color, bg: Color) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * 코드 정의서 enum으로만 만드는 단일 선택 라디오.
 * 선택지를 문자열로 직접 넘길 수 없게 막아 코드 정의서와의 이탈을 방지한다.
 */
@Composable
fun <C : CodeItem> CodeRadioGroup(
    options: List<C>, selected: C?, onSelect: (C) -> Unit, columns: Int = 2
) {
    Column(Modifier.fillMaxWidth()) {
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                row.forEach { opt ->
                    val on = opt == selected
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) T.PurpleLight else T.Surface)
                            .border(1.dp, if (on) T.Purple else T.Line, RoundedCornerShape(8.dp))
                            .clickable { onSelect(opt) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (on) "◉" else "○", fontSize = 13.sp,
                            color = if (on) T.Purple else T.Muted)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            opt.label, fontSize = 12.sp,
                            color = if (on) T.Ink else T.Muted,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    if (opt != row.last()) Spacer(Modifier.width(6.dp))
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.width(6.dp)); Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String, color: Color = T.Purple, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) color else T.Line)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 설계 의도를 화면 안에 남기는 메모 박스. 프로토타입의 .note-in */
@Composable
fun NoteBox(title: String, body: String, accent: Color = T.Purple, bg: Color = T.PurpleLight) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(12.dp)
    ) {
        Text(title, fontSize = 11.5.sp, color = accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(body, fontSize = 11.5.sp, color = T.Slate, lineHeight = 17.sp)
    }
}

/** 상단 앱바. 화면 코드(M-01 등)와 공정 단계(P0·P3·P5)를 함께 보여준다. */
@Composable
fun ScreenBar(title: String, phase: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().background(color).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        Text(phase, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MetricTile(value: String, label: String, delta: String? = null, accent: Color = T.Ink) {
    Column(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(T.Surface)
            .border(1.dp, T.Line, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = accent)
            delta?.let {
                Spacer(Modifier.width(4.dp))
                Text(it, fontSize = 10.sp, color = T.Green, fontWeight = FontWeight.Bold)
            }
        }
        Text(label, fontSize = 10.5.sp, color = T.Muted)
    }
}
