package kr.co.snail.tacitq.mobile.core

/**
 * 화면 쪽지(ShowCommand) 공장 — 구조설계서 §3 "화면별 fields 명세"를 코드로 옮긴 계약서.
 * 화면 번호(H-01~H-09)와 제스처 창·ttl 은 여기 한 곳에서만 정한다.
 * fields 값이 비면 HMD 팀과 통합 시점에 반드시 어긋나므로, 설계서의 필수 필드를 채워 둔다.
 */
object Screens {

    /**
     * H-01 조준 가이드 — 검사 대기 기본 화면. 2026-11-03: 처리 건수·면 수·프레임 목표는 HMD 에 보내지 않는다
     * (처리 건수는 폰 M-05). 인자는 호출처를 그대로 두려고 남겼다.
     * 2026-11-11: 품번 대신 스테이션 — HMD 는 `part_no` 글자를 왼쪽 위에 그대로 그리므로 키 이름은 HMD 앱과 맞춰 둔다.
     */
    @Suppress("UNUSED_PARAMETER")
    fun guide(station: String, doneCnt: Int, profile: Profile) = ShowCommand(
        screen = "H-01",
        ttlMs = 0,
        gestureWindow = "NONE",
        fields = mapOf("part_no" to station),
    )

    /** 대기 배너 — INIT·READY·CLOSED */
    fun idle() = ShowCommand(screen = "H-01", ttlMs = 0, fields = mapOf("state" to "IDLE"))

    /** H-02 합격 — ttl>0 이면 자동 소거(R01), ttl=0 이면 표본 확인 요구(R02) */
    fun pass(e: Event.JudgeResult, ttl: Long) = ShowCommand(
        screen = "H-02",
        ttlMs = ttl,
        gestureWindow = "OBJECT",
        fields = mapOf(
            "part_id" to e.partId,
            "confidence" to e.confidence.toString(),
            "action_text" to actionText(ttl),
        ),
    )

    /** 판정 화면 아래 안내 — 자동이면 몇 초 뒤 카메라, 수동이면 버튼 */
    private fun actionText(ttl: Long) =
        if (ttl > 0) "%.1f초 뒤 카메라 · 버튼 = 바로".format(ttl / 1000.0).replace(".0초", "초") else "버튼을 누르면 카메라로"

    /** H-03 불합격 — ttl 뒤 조준 화면으로(0 = 확인까지 유지) */
    fun fail(e: Event.JudgeResult, ttl: Long = 0) = ShowCommand(
        screen = "H-03",
        ttlMs = ttl,
        gestureWindow = "OBJECT",
        vibrate = 3,
        fields = mapOf(
            "part_id" to e.partId,
            "severity" to (e.severity ?: ""),
            "evidence" to e.evidence,
            "frame_ref" to (e.frameRef ?: ""),
            "bbox" to (e.bbox?.joinToString(",") ?: ""),
            "action_text" to actionText(ttl),
        ),
    )

    /** H-04 REVIEW 판정 — 사람에게 끄덕/젓기로 묻는다(R04) */
    fun review(e: Event.JudgeResult, windowId: String) = ShowCommand(
        screen = "H-04",
        ttlMs = 0,
        gestureWindow = "YESNO",
        windowId = windowId,
        vibrate = 2,
        fields = mapOf(
            "part_id" to e.partId,
            "confidence" to e.confidence.toString(),
            "agree_rate" to e.agreeRate.toString(),
        ),
    )

    /** H-04 카운트다운 — 제스처 확정 1초 유예(R09) */
    fun countdown(partId: String, decision: String) = ShowCommand(
        screen = "H-04",
        ttlMs = 0,
        gestureWindow = "NONE",
        fields = mapOf("part_id" to partId, "decision" to decision, "countdown" to "1"),
    )

    /** H-05 이의 사유 2택 — 헤드 커서(R10 진입·R11 선택) */
    fun objection(windowId: String) = ShowCommand(
        screen = "H-05",
        ttlMs = 0,
        gestureWindow = "CURSOR",
        windowId = windowId,
        fields = mapOf(
            "option_1" to "AI_FALSE_POSITIVE",
            "option_2" to "AI_FALSE_NEGATIVE",
        ),
    )

    /** H-06 연속 불량 경고(R13) */
    fun alert(defectType: String, hit: Int, window: Int) = ShowCommand(
        screen = "H-06",
        ttlMs = 0,
        gestureWindow = "CURSOR",
        vibrate = 3,
        fields = mapOf(
            "defect_type" to defectType,
            "window_hit" to hit.toString(),
            "window_size" to window.toString(),
        ),
    )

    /** H-07 라인 정지(R14) */
    fun stopped(reason: String, at: Long) = ShowCommand(
        screen = "H-07",
        ttlMs = 0,
        gestureWindow = "NONE",
        fields = mapOf("reason" to reason, "stopped_at" to at.toString()),
    )

    /** H-08 회수 필요 — 결과가 늦어 이미 트레이에 들어간 건(R05) */
    fun recover(partId: String, delayMs: Long) = ShowCommand(
        screen = "H-08",
        ttlMs = 0,
        gestureWindow = "YESNO",
        vibrate = 3,
        fields = mapOf("part_id" to partId, "delay_ms" to delayMs.toString()),
    )

    /** OVERLAY 켜기 — Edge 지연 등 예외 스트립(R17). SHOW 가 아닌 OVERLAY 메시지로 나간다. */
    fun overlay(text: String) = ShowCommand(
        screen = "OVERLAY",
        fields = mapOf("type" to "NET", "text" to text, "on" to "true"),
    )

    /** OVERLAY 끄기 — 복구(R18) */
    fun overlayOff() = ShowCommand(
        screen = "OVERLAY",
        fields = mapOf("type" to "NET", "on" to "false"),
    )
}
