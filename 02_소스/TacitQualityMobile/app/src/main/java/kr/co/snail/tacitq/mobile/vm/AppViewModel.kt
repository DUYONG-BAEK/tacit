package kr.co.snail.tacitq.mobile.vm

import androidx.lifecycle.ViewModel
import kr.co.snail.tacitq.mobile.data.InMemoryQualityRepository
import kr.co.snail.tacitq.mobile.data.QualityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 화면 식별자 — 설계서 v0.7 모바일 6종. 번호와 공정 단계를 한곳에서 관리한다. */
enum class Screen(val code: String, val title: String, val phase: String) {
    M01("M-01", "교대 시작 준비", "P0"),
    M02("M-02", "설비 이상 — 조치 등록", "P3"),
    M03("M-03", "불량품 수거 · 등록", "P5"),
    M04("M-04", "불량 재고 · 폐기", "P5"),
    M05("M-05", "검사 이력 조회", "B군"),
    M06("M-06", "관리자 대시보드", "D군"),
    M07("M-07", "영상 모니터링", "P1")
}

class AppViewModel(
    val repo: QualityRepository = InMemoryQualityRepository()
) : ViewModel() {

    private val _screen = MutableStateFlow(Screen.M01)
    val screen: StateFlow<Screen> = _screen

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    /** 검사 루프 중에는 이 값이 true이고, 화면은 꺼져 있어야 정상이다. */
    private val _relayRunning = MutableStateFlow(false)
    val relayRunning: StateFlow<Boolean> = _relayRunning

    /** 검사 시작 후 HMD 영상이 처음 들어오면 영상 탭(M-07)으로 한 번 넘긴다 */
    var openLiveOnHmd = false

    fun go(s: Screen) { _screen.value = s }
    fun toast(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }
    fun setRelay(on: Boolean) { _relayRunning.value = on }
}
