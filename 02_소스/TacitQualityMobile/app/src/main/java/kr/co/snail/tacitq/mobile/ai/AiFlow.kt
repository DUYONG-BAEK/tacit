package kr.co.snail.tacitq.mobile.ai

import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.model.Verdict

/** 실시간 결함 표시 한 장(HMD LIVE · M-07). [decision] 이 null 이면 지운다. box 기준은 [frameW]×[frameH]. */
data class LiveMark(
    val decision: String?,
    val label: String? = null,
    val detections: List<AiBox> = emptyList(),
    val frameW: Int = 0,
    val frameH: Int = 0,
) {
    companion object { val CLEAR = LiveMark(null) }
}

/** 끝난 제품 구간 요약 — 화면(M-01·M-07) 상태 줄용 */
data class SegmentSummary(
    val pass: Boolean,
    val aiDecision: String,                 // OK · NG · REVIEW
    val defectLabel: String?,
    val defectFrames: Int,
    val frames: Int,
)

/**
 * AI 서버 메시지(2026-11-03 규격, 프레임 단위 판정) → 실시간 표시 · 진동 · 제품 구간 판정. 설계서 §9.
 * 안드로이드·WebRTC 를 모른다(단위 시험 대상).
 *
 *  - 실시간 표시(R8): NG·REVIEW 프레임마다 [onLive]. searching·끊김이면 표시 중일 때만(마지막 결함 뒤
 *    [Profile.markHoldMs] 안) 지우는 표시를 한 번. OK 프레임은 보내지 않는다.
 *  - 제품 구간(R2): 첫 inspect 에서 시작, 마지막 inspect 뒤 [Profile.segmentEndMs] 동안 inspect 가 없거나([onTick])
 *    연결이 끊기면 끝. searching 한 장은 구간을 끝내지 않는다(서버는 제품을 놓친 한 장에도 searching 을 보낸다).
 *  - 결함 확정(R3): 구간 안 최근 [Profile.confirmWindow] 장 중 [Profile.confirmHits] 장 이상이 NG·REVIEW 면 확정 —
 *    그 안에 NG 가 있으면 NG, 없으면 REVIEW. 나중에 NG 로 다시 확정되면 올린다. 처음 확정될 때 [onAlarm](R5, 구간당 1회).
 *  - 구간 판정(R4·R6): 확정이 없으면 OK(합격), 있으면 확정 판정(불합격). 구간 끝에 [Event.JudgeResult] 1건.
 *
 * [onMessage] 는 WebRTC 콜백 스레드, 나머지는 연결 스레드에서 오므로 모두 동기화한다.
 */
class AiFlow(
    private val emit: (Event) -> Unit,
    private val stationId: String = "",
    private val profile: Profile = Profile(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** HMD 조준 화면에 띄울 진행 안내 — 바뀔 때만 부른다(BLE HINT) */
    private val onHint: (text: String, tone: String) -> Unit = { _, _ -> },
    private val onLive: (LiveMark) -> Unit = {},
    private val onAlarm: () -> Unit = {},
) {
    private class Segment(val startAt: Long) {
        var lastAt = startAt
        var frames = 0
        var defectFrames = 0
        var maxScore = 0.0
        var threshold: Double? = null
        val recent = ArrayDeque<String>()                 // 최근 판정(confirmWindow 장)
        var confirmed: String? = null                     // NG · REVIEW
        var label: String? = null
        val resultIds = mutableListOf<Pair<String, Double>>()
    }

    private var seg: Segment? = null
    private var seq = 0L
    private var lastHint: String? = null
    private var liveAt: Long? = null                      // 마지막 결함 LIVE 를 보낸 시각

    /** 화면 안내 문구 — 마지막 서버 상태 */
    @Volatile var guide: String = "연결 전"; private set
    @Volatile var lastSegment: SegmentSummary? = null; private set

    /** 연결 성공 = searching 으로 본다(API §5.4 — 첫 메시지를 기다리지 않는다). */
    @Synchronized
    fun onConnected() { guide = "제품 없음"; hint("제품을 비춰 주세요", "IDLE") }

    @Synchronized
    fun onDisconnected() {
        guide = "연결 끊김"; lastHint = null
        val now = clock()
        clearLive(now)
        endSegment(now)
    }

    /** 주기 호출(연결 스레드 0.5초) — 구간 끝 판단 */
    @Synchronized
    fun onTick() {
        val now = clock()
        val s = seg ?: return
        if (now - s.lastAt >= profile.segmentEndMs) endSegment(now)
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
                hint("검사 중", "OK")
                inspect(m, clock())
            }
            is AiMsg.Unknown -> Unit
        }
    }

    private fun inspect(m: AiMsg.Inspect, now: Long) {
        val s = seg ?: Segment(now).also { seg = it }
        s.lastAt = now
        s.frames++
        s.maxScore = maxOf(s.maxScore, m.score)
        m.threshold?.let { s.threshold = it }
        if (m.decision != "OK") {                                    // 모르는 값도 안전측으로 결함 프레임
            s.defectFrames++
            m.resultId?.let { s.resultIds += it to m.score }
            onLive(LiveMark(m.decision, m.defectLabel, m.detections, m.frameW, m.frameH))
            liveAt = now
        }
        s.recent.addLast(m.decision)
        while (s.recent.size > profile.confirmWindow) s.recent.removeFirst()
        val c = confirm(s.recent, profile.confirmHits) ?: return
        if (rank(c) <= rank(s.confirmed)) return
        val first = s.confirmed == null
        s.confirmed = c
        s.label = m.defectLabel                                      // 확정을 만든 프레임은 늘 결함 프레임이다
        if (first) onAlarm()
    }

    private fun clearLive(now: Long) {
        val at = liveAt ?: return
        liveAt = null
        if (now - at < profile.markHoldMs) onLive(LiveMark.CLEAR)    // 이미 꺼졌으면(HMD 가 스스로 지움) 보내지 않는다
    }

    private fun endSegment(now: Long) {
        val s = seg ?: return
        seg = null
        val decision = s.confirmed ?: "OK"
        val pass = s.confirmed == null
        val ids = s.resultIds.sortedByDescending { it.second }.map { it.first }
        lastSegment = SegmentSummary(pass, decision, s.label, s.defectFrames, s.frames)
        emit(Event.JudgeResult(
            seq = ++seq, partId = "$stationId-${s.startAt}",
            verdict = if (pass) Verdict.PASS else Verdict.FAIL, severity = null,
            confidence = s.maxScore, agreeRate = 1.0, evidence = s.label ?: decision,
            frameRef = ids.firstOrNull(), bbox = null, capturedEndAt = s.lastAt, arrivedAt = now,
            aiDecision = decision, defectLabel = s.label, threshold = s.threshold, resultIds = ids,
            frames = s.frames, defectFrames = s.defectFrames,
        ))
    }

    private fun hint(text: String, tone: String) {
        if (text == lastHint) return
        lastHint = text
        onHint(text, tone)
    }

    companion object {
        /** 결함 확정(R3) — 최근 판정 중 NG·REVIEW 가 [hits] 장 이상이면 NG(NG 가 있으면) 또는 REVIEW, 아니면 null */
        fun confirm(recent: Collection<String>, hits: Int): String? {
            val defects = recent.filter { it != "OK" }
            if (defects.size < hits) return null
            return if ("NG" in defects) "NG" else "REVIEW"
        }

        private fun rank(d: String?) = when (d) { null -> 0; "NG" -> 2; else -> 1 }
    }
}
