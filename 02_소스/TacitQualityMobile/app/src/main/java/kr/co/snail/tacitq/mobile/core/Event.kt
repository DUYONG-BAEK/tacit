package kr.co.snail.tacitq.mobile.core

import kr.co.snail.tacitq.mobile.model.Verdict

/**
 * 상태 기계의 입력 — 구조설계서 §2 전이 규칙표의 "입력 이벤트" 열.
 * 어디서 왔는지(세션·Edge·HMD·현장·설비)로 묶어 두면 규칙표와 대조하기 쉽다.
 */
sealed interface Event {

    // ── 세션 ─────────────────────────────────────────
    data class ShiftStarted(val line: String, val partNo: String, val profile: Profile) : Event
    data object ShiftClosed : Event                                                  // R20

    // ── Edge ─────────────────────────────────────────
    /** Edge 판정 수신 — R01~R07 이 갈리는 가장 복잡한 입력 */
    data class JudgeResult(
        val seq: Long,
        val partId: String,
        val verdict: Verdict,
        val severity: String?,
        val confidence: Double,
        val agreeRate: Double,
        val evidence: String,
        val frameRef: String?,
        val bbox: List<Double>?,
        val capturedEndAt: Long,
        val arrivedAt: Long,
    ) : Event

    data class PatternAlert(val level: String, val defectType: String,              // R13
                            val hit: Int, val window: Int) : Event
    data class EdgeUnreachable(val reason: String) : Event                          // R17
    data object EdgeRecovered : Event                                               // R18
    data object RelayOverflow : Event

    // ── HMD ──────────────────────────────────────────
    data class HmdConnected(val info: DeviceInfo) : Event
    data class HmdHeartbeat(val battery: Int, val temp: Int) : Event                // R19
    data class HmdLost(val gapMs: Long) : Event                                     // R16
    data class Gesture(val kind: String, val repeats: Int, val windowId: String?) : Event // R09·R10
    data class CursorPick(val optionId: String) : Event                            // R11
    data class ShowAck(val seq: Long) : Event

    // ── 현장 ─────────────────────────────────────────
    data class TrayDetected(val partId: String, val kind: String, val at: Long) : Event // R08
    data class TimerFired(val key: String, val partId: String?) : Event            // R12·확정

    // ── 설비 ─────────────────────────────────────────
    data class StopApproved(val reason: String, val at: Long) : Event              // R14
    data class ResumeApproved(val at: Long) : Event                                // R15
}
