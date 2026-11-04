package kr.co.snail.tacitq.mobile.core

import kr.co.snail.tacitq.mobile.model.Verdict
import kr.co.snail.tacitq.mobile.model.WorkerAct
import org.junit.Assert.*
import org.junit.Test

/**
 * V-M05 단위 시험 — 장비 없이 노트북에서 도는 다섯 가지.
 *
 * 순서 역전·중복·지연·선투입·시간 초과는 실장비에서는 일부러 만들기 어렵고,
 * 우연히 발생하면 재현이 안 된다. 이 시험이 통과하면 현장 사고의 절반이 예방된다.
 * 핵심 불변식: **불합격이 합격으로 덮이는 사례 0건.**
 */
class StateMachineTest {

    private fun judge(
        seq: Long, id: String, v: Verdict, agree: Double = 1.0, delay: Long = 400, aiDecision: String? = null,
    ) = Event.JudgeResult(
        seq = seq, partId = id, verdict = v,
        severity = if (v == Verdict.FAIL) "CRITICAL" else null,
        confidence = 0.95, agreeRate = agree, evidence = "찍힘 · 2면", frameRef = "frm/x.jpg",
        bbox = listOf(0.31, 0.22, 0.07, 0.06),
        capturedEndAt = 1_000, arrivedAt = 1_000 + delay, aiDecision = aiDecision,
    )

    private fun machine() = StateMachine(AppState(mode = Mode.RUNNING, partNo = "PRS-4410"))

    /** R06 — 순서가 뒤바뀌어 도착해도 오래된 판정이 새 판정을 덮지 못한다. */
    @Test fun `오래된 판정은 무시된다`() {
        val m = machine()
        m.reduce(judge(4821, "A_0001", Verdict.FAIL))          // 새 판정 먼저 도착
        val (s, fx) = m.reduce(judge(4819, "A_0002", Verdict.PASS))  // 오래된 것
        assertTrue("오래된 seq 는 아무 효과도 내면 안 된다", fx.isEmpty())
        assertEquals(Verdict.FAIL, s.parts["A_0001"]!!.verdict)
    }

    /** R07 — 같은 건이 두 번 와도 한 번만 처리한다 (멱등). */
    @Test fun `중복 판정은 한 번만 처리된다`() {
        val m = machine()
        val (_, fx1) = m.reduce(judge(10, "A_0003", Verdict.FAIL))
        val (_, fx2) = m.reduce(judge(11, "A_0003", Verdict.FAIL))  // 같은 부품 재전송
        assertTrue(fx1.isNotEmpty())
        assertTrue("두 번째는 무시되어야 한다", fx2.isEmpty())
    }

    /** 판정은 AI 결과 그대로 — 늦게 와도, 일치율이 낮아도 폰이 바꾸지 않는다. */
    @Test fun `AI 결과를 그대로 기록한다`() {
        val m = machine()
        val (s1, _) = m.reduce(judge(20, "A_0004", Verdict.PASS, agree = 0.50, delay = 2_600))
        assertEquals(Verdict.PASS, s1.parts["A_0004"]!!.verdict)
        val (s2, fx2) = m.reduce(judge(21, "A_0005", Verdict.FAIL))
        assertEquals(Verdict.FAIL, s2.parts["A_0005"]!!.verdict)
        assertEquals(Verdict.FAIL.code, fx2.filterIsInstance<Effect.Persist>().single().record.aiVerdict)
    }

    /** 2026-11-03 — 제품 구간 1건은 기록만. 판정 화면(H-02·H-03)·진동·확인 타이머는 없다(진동은 AiFlow 가 구간 중에). */
    @Test fun `구간 1건은 기록만 남기고 화면 진동 타이머가 없다`() {
        val m = machine()
        val (s, fx) = m.reduce(judge(1, "ST-02-1000", Verdict.FAIL, aiDecision = "NG"))
        val r = fx.single() as Effect.Persist
        assertEquals(WorkerAct.NONE.code, r.record.workerAction)
        assertEquals("NG", r.record.aiDecision)
        assertEquals(PartPhase.CONFIRMED, s.parts["ST-02-1000"]!!.phase)
        assertEquals(1, s.doneCount)
    }

    /** 애매한 판정(REVIEW·알 수 없는 값)은 안전측 불합격 — 원판정 REVIEW 는 따로 남는다. */
    @Test fun `애매한 판정은 불합격이고 원판정 REVIEW로 기록된다`() {
        val m = machine()
        val (s, fx) = m.reduce(judge(30, "A_0006", Verdict.REVIEW, aiDecision = "REVIEW"))
        assertEquals(Verdict.FAIL, s.parts["A_0006"]!!.verdict)
        val r = fx.filterIsInstance<Effect.Persist>().single().record
        assertEquals(Verdict.FAIL.code, r.aiVerdict)
        assertEquals("REVIEW", r.aiDecision)
    }

    /** 연속 불량은 HMD 화면·진동 없이 반장 알림만. */
    @Test fun `연속 불량은 화면을 바꾸지 않는다`() {
        val (_, fx) = machine().reduce(Event.PatternAlert("WARN", "DENT", 5, 20))
        assertTrue(fx.none { it is Effect.ShowScreen || it is Effect.Vibrate })
        assertTrue(fx.any { it is Effect.NotifyLead })
    }
}
