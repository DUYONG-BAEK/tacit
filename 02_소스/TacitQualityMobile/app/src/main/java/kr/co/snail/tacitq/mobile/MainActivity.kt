package kr.co.snail.tacitq.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import kr.co.snail.tacitq.mobile.ui.AppShell
import kr.co.snail.tacitq.mobile.ui.theme.TacitQTheme
import kr.co.snail.tacitq.mobile.vm.AppViewModel

/**
 * 품질검사 지원 APP — 모바일.
 *
 * 이 앱의 화면은 P0(교대 준비) · P3(설비 조치) · P5(수거·재고) · 조회에서만 열린다.
 * 검사 루프(P1) 동안 작업자가 보는 것은 HMD뿐이고, 이 단말은 중계기로만 동작한다.
 */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TacitQTheme { AppShell(vm) } }
    }
}
