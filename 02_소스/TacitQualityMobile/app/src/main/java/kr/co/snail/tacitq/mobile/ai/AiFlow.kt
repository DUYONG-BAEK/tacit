package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.model.Verdict

/** 실시간 결함 표시 한 장(HMD LIVE · M-07). [decision] 이 null 이면 지운다. box 기준은 [frameW]×[frameH]. */
data class LiveMark(
    val decision: String?,
    val label: String? = null,
    val detections: List<AiBox> = emptyList(),
    val frameW: Int = 0,
    val frameH: Int = 0,
    val resultId: String? = null,           // 서버가 이 프레임을 저장했으면 그 ID(확대 보기용)
) {
    companion object { val CLEAR = LiveMark(null) }
}

/** 끝난 면 검사 요약 — 화면(M-01·M-07) 상태 줄용 */
data class SegmentSummary(
    val pass: Boolean,
    val aiDecision: String,                 // OK · NG · REVIEW
    val defectLabel: String?,
    val defectFrames: Int,
    val frames: Int,
    val face: String? = null,
)

/**
 * AI 서버 메시지 → 실시간 표시 · 검사 중 안내 · 면 검사 결과. 설계서 §3, `server/PLAN_product_feedback.md`.
 * 안드로이드·WebRTC 를 모른다(단위 시험 대상).
 *
 * 면 검사·판정은 **서버가 한다**(2026-11-23: 같은 면 연속 N장 = 1건, product_end). 앱은 받은 것을 그대로 보이고 넘긴다.
 *  - 실시간 표시(R8): NG·REVIEW 프레임마다 [onLive]. searching·끊김이면 표시 중일 때만(마지막 결함 뒤
 *    [markHoldMs] 안) 지우는 표시를 한 번. OK 프레임은 보내지 않는다.
 *  - 안내: inspect 마다 "<면> 검사 중"(바뀔 때만 [onHint], tone INFO = 흰 글자), searching 이면 "제품을 비춰 주세요".
 *  - 결과: product_end(완료된 검사만 온다)마다 [onProduct] 와 [Event.JudgeResult] 1건(상태 기계·알림 건수용).
 *
 * [onMessage] 는 WebRTC 콜백 스레드, 나머지는 연결 스레드에서 오므로 모두 동기화한다.
 */
class AiFlow(
    private val emit: (Event) -> Unit,
    private val markHoldMs: Long = 500,
    private val clock: () -> Long = System::currentTimeMillis,
    /** HMD 조준 화면에 띄울 진행 안내 — 바뀔 때만 부른다(BLE HINT) */
    private val onHint: (text: String, tone: String) -> Unit = { _, _ -> },
    private val onLive: (LiveMark) -> Unit = {},
    private val onProduct: (AiProduct) -> Unit = {},
) {
    private var seq = 0L
    private var lastHint: String? = null
    private var liveAt: Long? = null                      // 마지막 결함 LIVE 를 보낸 시각

    /** 화면 안내 문구 — 마지막 서버 상태 */
    @Volatile var guide: String = "연결 전"; private set
    @Volatile var lastSegment: SegmentSummary? = null; private set

    /** 연결 성공 = searching 으로 본다(API §5.4 — 첫 메시지를 기다리지 않는다). */
    @Synchronized
    fun onConnected() { guide = "제품 없음"; hint("제품을 비춰 주세요", "IDLE") }

    /** 끊김 — 진행 중인 검사는 서버가 disconnect 로 기록한다(앱에는 product_end 가 오지 않는다). */
    @Synchronized
    fun onDisconnected() {
        guide = "연결 끊김"; lastHint = null
        clearLive(clock())
    }

    @Synchronized
    fun onMessage(m: AiMsg) {
        when (m) {
            is AiMsg.Searching -> {
                guide = "제품 없음"; hint("제품을 비춰 주세요", "IDLE")
                clearLive(clock())
            }
            is AiMsg.Inspect -> {
                guide = "검사 중 · ${m.decision}" + (m.defectLabel?.let { " $it %.2f".format(m.score) } ?: "")
                hint(m.face?.let { "$it 검사 중" } ?: "검사 중", "INFO")
                if (m.decision != "OK") {                                    // 모르는 값도 안전측으로 결함 프레임
                    onLive(LiveMark(m.decision, m.defectLabel, m.detections, m.frameW, m.frameH, m.resultId))
                    liveAt = clock()
                }
            }
            is AiMsg.ProductEnd -> product(m.product)
            is AiMsg.Unknown -> Unit
        }
    }

    private fun product(p: AiProduct) {
        val decision = p.aiDecision ?: if (p.pass) "OK" else "NG"
        lastSegment = SegmentSummary(p.pass, decision, p.defectLabel, p.defectFrames, p.frames, p.face)
        val now = clock()
        emit(Event.JudgeResult(
            seq = ++seq, partId = p.productId,
            verdict = if (p.pass) Verdict.PASS else Verdict.FAIL, severity = null,
            confidence = 0.0, agreeRate = 1.0, evidence = p.defectLabel ?: decision,
            frameRef = p.bestResultId, bbox = null, capturedEndAt = now, arrivedAt = now,
            aiDecision = decision, defectLabel = p.defectLabel, resultIds = p.resultIds,
            frames = p.frames, defectFrames = p.defectFrames,
        ))
        onProduct(p)
    }

    private fun clearLive(now: Long) {
        val at = liveAt ?: return
        liveAt = null
        if (now - at < markHoldMs) onLive(LiveMark.CLEAR)    // 이미 꺼졌으면(HMD 가 스스로 지움) 보내지 않는다
    }

    private fun hint(text: String, tone: String) {
        if (text == lastHint) return
        lastHint = text
        onHint(text, tone)
    }
}
