package kr.co.snail.tacitq.mobile.core

import kr.co.snail.tacitq.mobile.model.Verdict

/**
 * C3 상태 기계 — 앱의 심장. 구조설계서 §2 전이 규칙표 R01~R20 이 그대로 코드가 된다.
 *
 * 규칙 번호를 주석으로 남겨 두면 설계서와 코드가 어긋났을 때 바로 찾을 수 있다.
 * 규칙을 바꿀 때는 **설계서 → 코드 → 시험** 순으로 같이 고친다.
 *
 * 규칙은 위에서부터 평가하고, 먼저 맞는 규칙 하나만 실행한다.
 */
class StateMachine(private var state: AppState = AppState()) {

    /** 현재 상태 스냅샷(읽기 전용). */
    val current: AppState get() = state

    /** 순수 함수. 부작용 없음. 같은 입력이면 언제나 같은 출력. */
    fun reduce(e: Event): Pair<AppState, List<Effect>> {
        val (s, fx) = handle(state, e)
        state = s
        return s to fx
    }

    private fun handle(s: AppState, e: Event): Pair<AppState, List<Effect>> = when (e) {

        // ── 세션 ─────────────────────────────────────────
        is Event.ShiftStarted -> s.copy(
            mode = Mode.RUNNING, line = e.line, partNo = e.partNo, profile = e.profile,
        ) to listOf(Effect.ShowScreen(Screens.guide(e.partNo, 0, e.profile)))

        // R20 — 교대 마감. 세션 종료, 큐 강제 플러시.
        is Event.ShiftClosed -> s.copy(mode = Mode.CLOSED) to
            listOf(Effect.ShowScreen(Screens.idle()), Effect.Enqueue(Payloads.flush()))

        // ── 판정 수신 — 가장 복잡한 분기 (R01~R07) ────────
        is Event.JudgeResult -> onJudge(s, e)

        // R08 — 트레이 투입 = 확인
        is Event.TrayDetected -> {
            val p = s.parts[e.partId]
            when {
                // 판정보다 먼저 도착: 자리만 만들어 두고 판정을 기다린다(R05 대비).
                p == null ->
                    s.withPart(Part(e.partId, PartPhase.CAPTURING, trayDetectedAt = e.at)) to emptyList()
                p.phase == PartPhase.JUDGED ->
                    s.withPart(p.copy(phase = PartPhase.CONFIRMED)).bump() to listOf(
                        Effect.CancelTimer("confirm:${e.partId}"),
                        Effect.Persist(Records.confirmed(p, trigger = "TRAY_SENSOR")),
                        Effect.ShowScreen(Screens.guide(s.partNo, s.doneCount + 1, s.profile)),
                    )
                else -> s to emptyList()
            }
        }

        // R09 · R10 — 제스처
        is Event.Gesture -> onGesture(s, e)

        // R11 — 헤드 커서 (이의 사유 2택 선택)
        is Event.CursorPick -> s.copy(openWindowId = null) to listOf(
            Effect.Persist(Records.objection(s.lastObjectionPartId(), e.optionId)),
            Effect.ShowScreen(Screens.guide(s.partNo, s.doneCount, s.profile)),
        )

        // R12 — 확인 대기 만료 / 제스처 확정
        is Event.TimerFired -> onTimer(s, e)

        // R13 — 연속 불량: HMD 화면은 합격·불합격·통신불량 3가지만 쓴다 → 반장 알림 기록만 남긴다.
        is Event.PatternAlert -> s to listOf(
            Effect.NotifyLead("연속 불량 ${e.level} · ${e.defectType}"),
        )

        // R14 · R15 — 라인 정지 / 재개
        is Event.StopApproved -> s.copy(mode = Mode.LINE_STOPPED) to
            listOf(Effect.ShowScreen(Screens.stopped(e.reason, e.at)))
        is Event.ResumeApproved -> s.copy(mode = Mode.RUNNING) to
            listOf(Effect.ShowScreen(Screens.guide(s.partNo, s.doneCount, s.profile)))

        // R16 — HMD 끊김. 화면 지시를 보낼 수 없다. HMD 가 스스로 H-09 를 띄운다.
        is Event.HmdLost -> s.copy(mode = Mode.OFFLINE) to
            listOf(Effect.NotifyLead("HMD 연결 끊김 ${e.gapMs}ms"))

        // R17 — Edge 무응답
        is Event.EdgeUnreachable -> s.copy(mode = Mode.DEGRADED) to
            listOf(Effect.ShowScreen(Screens.overlay("서버 지연 · 검사 계속")))

        // R18 — 복구
        is Event.EdgeRecovered -> s.copy(mode = Mode.RUNNING) to
            listOf(Effect.ShowScreen(Screens.overlayOff()), Effect.Enqueue(Payloads.resend()))

        // R19 — 배터리
        is Event.HmdHeartbeat ->
            if (e.battery < 15) s to listOf(Effect.NotifyLead("HMD 배터리 ${e.battery}%"))
            else s to emptyList()

        // 그 외(HELLO·ACK·RelayOverflow 등)는 상태 기계가 관여하지 않는다.
        else -> s to emptyList()
    }

    /**
     * 판정 수신 분기 — R01~R07. 여기에 이 앱의 안전이 걸려 있다.
     * R06(순서 역전 무시)·R07(중복 무시)을 빠뜨리면 오래된 합격 판정이 새 불합격 판정을
     * 덮어써서 불량품이 양품 트레이로 간다. 무선은 순서를 보장하지 않고, 재전송 때문에
     * 같은 결과가 두 번 오기도 한다.
     */
    private fun onJudge(s: AppState, e: Event.JudgeResult): Pair<AppState, List<Effect>> {
        // R06 — 순서 역전. 오래된 판정은 버린다.
        if (e.seq <= s.lastSeq) return s to emptyList()
        // R07 — 중복. 이미 처리한 건은 다시 처리하지 않는다 (멱등).
        if (e.partId in s.handled) return s.copy(lastSeq = e.seq) to emptyList()

        // 판정은 AI(엣지)가 한다 — 폰은 결과를 그대로 보여 준다(2026-10-07 결정).
        // 합격이 아닌 값(REVIEW·알 수 없는 값)은 안전측으로 불합격. 일치율·지연·표본으로 판정을 바꾸지 않는다.
        val verdict = if (e.verdict == Verdict.PASS) Verdict.PASS else Verdict.FAIL
        val prev = s.parts[e.partId]
        val base = (prev ?: Part(e.partId, PartPhase.CAPTURING)).copy(
            phase = PartPhase.JUDGED, verdict = verdict, severity = e.severity,
            confidence = e.confidence, agreeRate = e.agreeRate, judgedAt = e.arrivedAt,
        )
        val next = s.copy(lastSeq = e.seq, handled = s.handled + e.partId)

        // R03 — 불합격. 화면은 FAIL_TTL_MS 보여 준 뒤 조준 화면(카메라)으로 돌아간다 — AI 서버에는 트레이 확인 신호가
        // 없어 화면을 계속 덮으면 다음 종이를 비출 수 없다(2026-10-18 결정). 진동 3회 · 확인 대기 기록은 그대로.
        if (verdict == Verdict.FAIL) {
            return next.withPart(base) to listOf(
                Effect.ShowScreen(Screens.fail(e, ttl = if (s.profile.autoDismiss) FAIL_TTL_MS else 0)),
                Effect.Vibrate(3),
                Effect.Persist(Records.failPending(base)),     // 이력에 바로 — 확인·시간 초과 시 같은 건을 덮어쓴다
                Effect.StartTimer("confirm:${e.partId}", s.profile.confirmWaitMs, e.partId),
            )
        }

        // R01 — 합격. 1.5초 후 자동 소거. 작업자 조작 0회.
        return next.withPart(base.copy(phase = PartPhase.CONFIRMED)).bump() to listOf(
            Effect.ShowScreen(Screens.pass(e, ttl = if (s.profile.autoDismiss) PASS_TTL_MS else 0)),
            Effect.Persist(Records.autoPass(base)),
        )
    }

    /** 제스처 — R09(끄덕/확정)·R10(젓기/이의). 문맥 게이팅으로 오인식을 막는다. */
    private fun onGesture(s: AppState, e: Event.Gesture): Pair<AppState, List<Effect>> {
        // 내가 연 창이 아니면 버린다. 오인식 방어의 핵심.
        if (e.windowId == null || e.windowId != s.openWindowId) return s to emptyList()
        if (e.repeats < 2) return s to emptyList()   // 2회 반복 요구
        val p = s.parts.values.lastOrNull { it.phase == PartPhase.JUDGED } ?: return s to emptyList()

        return when (e.kind) {
            // R09 — 끄덕임. REVIEW 판정을 합격으로. 1초 취소 유예 후 확정.
            "NOD" -> s.copy(openWindowId = null) to listOf(
                Effect.StartTimer("commit:${p.id}", s.profile.commitGraceMs, p.id),
                Effect.ShowScreen(Screens.countdown(p.id, "합격")),
            )
            // R10 — 가로젓기.
            "SHAKE" -> if (p.verdict == Verdict.REVIEW)
                // REVIEW 를 불합격으로 확정.
                s.copy(openWindowId = null) to listOf(
                    Effect.StartTimer("commit:${p.id}", s.profile.commitGraceMs, p.id),
                    Effect.ShowScreen(Screens.countdown(p.id, "불합격")),
                )
            else {
                // PASS/FAIL 판정에 대한 젓기 = 이의 제기 진입(H-05).
                val win = "obj-${p.id}"
                s.copy(openWindowId = win) to listOf(Effect.ShowScreen(Screens.objection(win)))
            }
            else -> s to emptyList()
        }
    }

    /** 타이머 — R12(확인 초과)·제스처 확정 유예 만료. */
    private fun onTimer(s: AppState, e: Event.TimerFired): Pair<AppState, List<Effect>> {
        val id = e.partId ?: return s to emptyList()
        val p = s.parts[id] ?: return s to emptyList()
        return when {
            // R12 — 확인 시간 초과. 안전측(불합격)으로 남긴다. 합격으로 바뀌지 않는다.
            e.key.startsWith("confirm:") && p.phase == PartPhase.JUDGED ->
                s.withPart(p.copy(phase = PartPhase.TIMEOUT)) to
                    listOf(Effect.Persist(Records.timeout(p)))
            // 제스처 확정 유예 만료 → 확정(R09).
            e.key.startsWith("commit:") ->
                s.withPart(p.copy(phase = PartPhase.CONFIRMED)).bump() to listOf(
                    Effect.Persist(Records.reviewDecided(p)),
                    Effect.ShowScreen(Screens.guide(s.partNo, s.doneCount + 1, s.profile)),
                )
            else -> s to emptyList()
        }
    }

    // ── 작은 도우미 — 상태를 불변으로 바꾼다 ──────────────
    private fun AppState.withPart(p: Part) = copy(parts = parts + (p.id to p))
    private fun AppState.bump() = copy(doneCount = doneCount + 1)

    companion object {
        /** 불합격 화면 표시 시간 — 그 뒤 조준 화면(카메라) */
        const val FAIL_TTL_MS = 2_000L
        /** 합격 화면 표시 시간(자동 모드) */
        const val PASS_TTL_MS = 1_500L
    }

    /** R11 — 이의 사유 선택 직전에 이의로 들어간 건을 찾는다(제스처 창 obj-<id>). */
    private fun AppState.lastObjectionPartId(): String =
        parts.values.lastOrNull { it.phase == PartPhase.JUDGED }?.id ?: ""
}
