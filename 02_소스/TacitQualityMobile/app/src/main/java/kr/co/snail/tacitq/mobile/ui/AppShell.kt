package kr.co.snail.tacitq.mobile.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kr.co.snail.tacitq.mobile.service.InspectionService
import kr.co.snail.tacitq.mobile.ui.components.ScreenBar
import kr.co.snail.tacitq.mobile.ui.screen.*
import kr.co.snail.tacitq.mobile.ui.theme.T
import kr.co.snail.tacitq.mobile.vm.AppViewModel
import kr.co.snail.tacitq.mobile.vm.Screen

/**
 * 앱 뼈대 — 상단 바 · 본문 · 하단 탭 3개.
 *
 * 하단 탭은 검사(M-01) · 영상(M-07) · 이력(M-05).
 * 2026-11-11: 수거(M-03 · M-04)·관리(M-06) 탭 숨김 — 샘플 데이터 화면이라 지금 시스템(AI 서버)과 맞지 않는다.
 * 화면 코드는 남겨 두었다(다시 쓰려면 아래 목록에 되돌린다).
 */
private val TABS = listOf(
    Triple("▣", "검사", Screen.M01),
    Triple("◉", "영상", Screen.M07),
    Triple("≣", "이력", Screen.M05),
)

@Composable
fun AppShell(vm: AppViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    var showLog by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        if (toast != null) { delay(1700); vm.clearToast() }
    }

    // 검사 시작 후 HMD 영상이 처음 들어오면 영상 탭으로 한 번 넘긴다(그 뒤 다른 탭으로 가도 다시 끌지 않음)
    val hmdVideo by InspectionService.hmdVideo.collectAsStateWithLifecycle()
    LaunchedEffect(hmdVideo) {
        if (hmdVideo && vm.openLiveOnHmd) {
            vm.openLiveOnHmd = false
            vm.go(Screen.M07)
            vm.toast("HMD 연결됨 — 영상 화면")
        }
    }

    // HMD 배터리 부족 — 부족해질 때 한 번 알린다(M-01·M-07 에는 빨간 글자로 계속 보인다)
    val hmdInfo by InspectionService.hmdInfo.collectAsStateWithLifecycle()
    val hmdLow = hmdInfo?.batteryLow == true
    LaunchedEffect(hmdLow) {
        if (hmdLow) vm.toast("HMD 배터리 ${hmdInfo?.battery}% — 충전이 필요합니다")
    }

    Box(Modifier.fillMaxSize().background(T.Bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenBar(
                title = screen.title, phase = screen.phase,
                color = when (screen) {
                    Screen.M02 -> T.Red
                    Screen.M03 -> T.Amber
                    Screen.M05 -> T.Teal
                    else -> T.Purple
                },
                onLog = { showLog = true },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                when (screen) {
                    Screen.M01 -> ShiftPrepScreen(vm)
                    Screen.M02 -> EquipActionScreen(vm)
                    Screen.M03 -> DefectRegisterScreen(vm)
                    Screen.M04 -> DefectInventoryScreen(vm)
                    Screen.M05 -> InspectionHistoryScreen(vm)
                    Screen.M06 -> AdminDashboardScreen(vm)
                    Screen.M07 -> LiveViewScreen(vm)
                }
                Spacer(Modifier.height(18.dp))
            }
            BottomTabs(screen) { vm.go(it) }
        }

        if (showLog) CommLogDialog { showLog = false }

        toast?.let {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 84.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(T.Ink.copy(alpha = 0.92f))
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) { Text(it, color = Color.White, fontSize = 12.5.sp) }
        }
    }
}

@Composable
private fun BottomTabs(current: Screen, onTab: (Screen) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(T.Surface)
            .padding(top = 6.dp, bottom = 10.dp)
    ) {
        TABS.forEach { (icon, label, target) ->
            val on = current == target ||
                (current == Screen.M02 && target == Screen.M01)
            Column(
                Modifier.weight(1f).clickable { onTab(target) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(icon, fontSize = 15.sp, color = if (on) T.Purple else T.Muted)
                Text(
                    label, fontSize = 10.sp,
                    color = if (on) T.Purple else T.Muted,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Preview(widthDp = 360, heightDp = 760, showBackground = true)
@Composable
private fun PreviewShell() = AppShell(AppViewModel())
