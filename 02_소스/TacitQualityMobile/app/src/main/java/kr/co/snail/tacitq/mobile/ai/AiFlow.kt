package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.core.Event

/**
 * AI 서버 메시지 → 화면 안내. 안드로이드·WebRTC 를 모른다(단위 시험 대상). 설계서 §9.
 *
 * 2026-11-03 규격(프레임 단위 판정) 1단계: 메시지 해석과 안내 문구만.
 * 제품 구간·결함 확정·이력([Event.JudgeResult])·HMD LIVE 는 2단계에서 붙인다.
 *
 * [onMessage] 는 WebRTC 콜백 스레드, [onConnected]·[onDisconnected] 는 연결 스레드에서 오므로 모두 동기화한다.
 */
class AiFlow(
    @Suppress("unused") private val emit: (Event) -> Unit,
    @Suppress("unused") private val clock: () -> Long = System::currentTimeMillis,
    /** HMD 조준 화면에 띄울 진행 안내 — 바뀔 때만 부른다(BLE HINT) */
    private val onHint: (text: String, tone: String) -> Unit = { _, _ -> },
) {
    private var lastHint: String? = null

    private fun hint(text: String, tone: String) {
        if (text == lastHint) return
        lastHint = text
        onHint(text, tone)
    }

    /** 화면 안내 문구 — 마지막 서버 상태 */
    @Volatile var guide: String = "연결 전"; private set
    @Volatile var lastJudge: String? = null; private set
    @Volatile var lastDelayMs: Long? = null; private set

    /** 연결 성공 = searching 으로 본다(API §5.4 — 첫 메시지를 기다리지 않는다). */
    @Synchronized
    fun onConnected() { guide = "제품 없음"; hint("제품을 비춰 주세요", "IDLE") }

    @Synchronized
    fun onDisconnected() { guide = "연결 끊김"; lastHint = null }

    @Synchronized
    fun onMessage(m: AiMsg) {
        when (m) {
            is AiMsg.Searching -> { guide = "제품 없음"; hint("제품을 비춰 주세요", "IDLE") }
            is AiMsg.Inspect -> {
                guide = "검사 중 · ${m.decision}" + (m.defectLabel?.let { " $it %.2f".format(m.score) } ?: "")
                hint("검사 중", "OK")
            }
            is AiMsg.Unknown -> Unit
        }
    }
}
