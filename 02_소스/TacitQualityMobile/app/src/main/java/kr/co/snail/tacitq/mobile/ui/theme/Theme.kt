package kr.co.snail.tacitq.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 프로토타입(10_모바일_프로토타입.html)의 색 토큰을 그대로 옮겼다.
 * 판정 색은 절대 단독으로 쓰지 않는다 — 색 + 아이콘/문구 + 진동 3중 부호화가 원칙이다.
 */
object T {
    val Ink = Color(0xFF1B222B)
    val Slate = Color(0xFF44515E)
    val Muted = Color(0xFF7C8896)
    val Line = Color(0xFFDFE3EA)
    val Line2 = Color(0xFFECEFF4)
    val Bg = Color(0xFFF4F5F8)
    val Surface = Color(0xFFFFFFFF)
    val Card = Color(0xFFF7F8FB)

    val Purple = Color(0xFF5B4B8A)
    val PurpleLight = Color(0xFFEFECF7)
    val Amber = Color(0xFFC4661F)
    val AmberLight = Color(0xFFFDF2E8)
    val Teal = Color(0xFF1F7A8C)
    val TealLight = Color(0xFFE9F3F5)
    val Green = Color(0xFF2E7D5B)
    val GreenLight = Color(0xFFE9F4EF)
    val Red = Color(0xFFB4453C)
    val RedLight = Color(0xFFFBECEB)
}

@Composable
fun TacitQTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = T.Purple, onPrimary = Color.White,
            secondary = T.Teal, background = T.Bg, surface = T.Surface,
            onBackground = T.Ink, onSurface = T.Ink, error = T.Red
        ),
        content = content
    )
}
