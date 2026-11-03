package kr.co.snail.tacitq.mobile.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kr.co.snail.tacitq.mobile.core.AppState
import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.core.InspectionRecord
import kr.co.snail.tacitq.mobile.core.Mode
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.core.ShowCommand
import kr.co.snail.tacitq.mobile.model.Verdict
import kr.co.snail.tacitq.mobile.model.WorkerAct
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 서비스 엔진 단위 시험 — 개발가이드 §2 "완료 확인". 실제 멀티스레드로 노트북에서 검증한다.
 *
 * 핵심: 서로 다른 스레드에서 밀어넣어도 (1) 유실이 없고 (2) 처리 순서가 섞이지 않는다.
 * 덤으로 상태 기계(C3) → 화면(C4)·저장(C5) 배선과 타이머 왕복이 실제로 도는지 확인한다.
 */
class InspectionEngineTest {

    /** 스레드 안전하게 이펙트를 기록하는 가짜 싱크. */
    private class RecordingSinks : EffectSinks {
        val shows = Collections.synchronizedList(mutableListOf<ShowCommand>())
        val persists = Collections.synchronizedList(mutableListOf<InspectionRecord>())
        val enqueues = Collections.synchronizedList(mutableListOf<ByteArray>())
        val vibrates = Collections.synchronizedList(mutableListOf<Int>())
        val notifies = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun show(cmd: ShowCommand) { shows += cmd }
        override suspend fun persist(record: InspectionRecord) { persists += record }
        override suspend fun enqueue(payload: ByteArray) { enqueues += payload }
        override fun vibrate(times: Int) { vibrates += times }
        override suspend fun notifyLead(alert: String) { notifies += alert }
    }

    private class Harness(initial: AppState) {
        private val stateExec = Executors.newSingleThreadExecutor()
        private val effectExec = Executors.newFixedThreadPool(4)
        private val stateScope = CoroutineScope(stateExec.asCoroutineDispatcher())
        private val effectScope = CoroutineScope(effectExec.asCoroutineDispatcher())
        val sinks = RecordingSinks()
        val engine = InspectionEngine(sinks, initial, stateScope, effectScope).also { it.start() }
        fun close() {
            engine.close(); stateScope.cancel(); effectScope.cancel()
            stateExec.shutdownNow(); effectExec.shutdownNow()
        }
    }

    private fun running() = Harness(AppState(mode = Mode.RUNNING, partNo = "PRS-4410"))

    private fun judge(seq: Long, id: String, v: Verdict, agree: Double = 1.0) = Event.JudgeResult(
        seq = seq, partId = id, verdict = v, severity = if (v == Verdict.FAIL) "CRITICAL" else null,
        confidence = 0.95, agreeRate = agree, evidence = "찍힘", frameRef = null, bbox = null,
        capturedEndAt = 1_000, arrivedAt = 1_400,
    )

    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("조건 미충족(타임아웃 ${timeoutMs}ms)")
            Thread.sleep(2)
        }
    }

    /** 완료 확인 ② — 서로 다른 스레드 8개에서 1000건을 밀어넣어도 하나도 유실되지 않는다. */
    @Test fun `여러 스레드에서 밀어넣어도 유실 없이 모두 처리된다`() {
        val h = running()
        try {
            val threads = 8; val per = 125
            (0 until threads).map { t ->
                Thread { repeat(per) { h.engine.send(Event.PatternAlert("WARN", "t$t", 1, 10)) } }
            }.onEach { it.start() }.forEach { it.join() }

            waitUntil { h.sinks.notifies.size == threads * per }
            assertEquals("유실 0건", threads * per, h.sinks.notifies.size)
        } finally { h.close() }
    }

    /** 완료 확인 ① — 한 스레드가 보낸 순서 그대로 처리된다(채널 FIFO + 단일 소비자). */
    @Test fun `이벤트는 보낸 순서대로 처리된다`() {
        val h = running()
        try {
            repeat(100) { h.engine.send(Event.PatternAlert("WARN", "idx-$it", it, 100)) }
            waitUntil { h.sinks.notifies.size == 100 }
            val order = h.sinks.notifies.map { it.substringAfter("· ").trim() }
            assertEquals((0..99).map { "idx-$it" }, order)
        } finally { h.close() }
    }

    /** 배선 — PASS 는 자동 통과(AUTO_PASS)로 저장되고 H-02 를 띄운다(C3→C5·C4). */
    @Test fun `PASS 는 자동 통과로 저장되고 화면을 띄운다`() {
        val h = running()
        try {
            h.engine.send(Event.ShiftStarted("A", "PRS-4410", Profile(sampleRatePct = 0.0)))
            h.engine.send(judge(1, "A_0001", Verdict.PASS))
            waitUntil { h.sinks.persists.isNotEmpty() && h.sinks.shows.any { it.screen == "H-02" } }
            assertEquals(WorkerAct.AUTO_PASS.code, h.sinks.persists.last().workerAction)
        } finally { h.close() }
    }

    /** 타이머 왕복 — 확인 없이 시간이 지나면 엔진이 TimerFired 를 되먹여 TIMEOUT 을 남긴다(R12). */
    @Test fun `확인 시간 초과가 엔진을 거쳐 TIMEOUT 으로 남는다`() {
        val h = running()
        try {
            // 확인 대기를 50ms 로 줄여 타이머가 시험 중에 만료되게 한다.
            h.engine.send(Event.ShiftStarted("A", "PRS-4410", Profile(confirmWaitMs = 50)))
            h.engine.send(judge(1, "A_0002", Verdict.FAIL))
            waitUntil { h.sinks.persists.any { it.workerAction == WorkerAct.TIMEOUT.code } }
            assertTrue("FAIL 은 진동 3회", h.sinks.vibrates.contains(3))
            assertTrue("불합격 화면", h.sinks.shows.any { it.screen == "H-03" })
        } finally { h.close() }
    }

    /** 타이머 취소 — 트레이 투입이 먼저 오면 확인으로 확정되고 TIMEOUT 은 남지 않는다(R08 + CancelTimer). */
    @Test fun `트레이 투입이 먼저 오면 TIMEOUT 없이 확인된다`() {
        val h = running()
        try {
            h.engine.send(Event.ShiftStarted("A", "PRS-4410", Profile(confirmWaitMs = 5_000)))
            h.engine.send(judge(1, "A_0003", Verdict.FAIL))
            waitUntil { h.sinks.shows.any { it.screen == "H-03" } }
            h.engine.send(Event.TrayDetected("A_0003", "DEFECT", at = 2_000))
            waitUntil { h.sinks.persists.any { it.workerAction == WorkerAct.CONFIRMED.code } }
            Thread.sleep(150)   // 타이머(5초)가 살아있다면 그 사이 TIMEOUT 이 끼어들 기회를 준다
            assertFalse("취소된 타이머는 TIMEOUT 을 남기지 않는다",
                h.sinks.persists.any { it.workerAction == WorkerAct.TIMEOUT.code })
        } finally { h.close() }
    }
}
