package kr.co.snail.tacitq.mobile.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.co.snail.tacitq.mobile.core.AppState
import kr.co.snail.tacitq.mobile.core.Effect
import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.core.StateMachine
import java.util.concurrent.ConcurrentHashMap

/**
 * 서비스의 심장 — 개발가이드 §2 "이벤트를 한 줄로 세운다".
 *
 * BLE 수신·Edge 판정·트레이 센서·타이머는 서로 다른 스레드에서 초당 수 건씩 들어온다.
 * 락으로 막으면 교착, 안 막으면 경쟁 상태다. 해법은 락이 아니라 **줄 세우기**:
 * 모든 입력을 [Channel] 하나로 모으고 **단일 스레드 스코프**에서 하나씩 꺼내 [StateMachine] 에 넣는다.
 * 상태 기계는 언제나 "한 번에 하나의 이벤트"만 보므로 락이 아예 필요 없다.
 *
 * 안드로이드를 모른다(순수 kotlinx.coroutines) — 노트북에서 순서·유실을 시험할 수 있다.
 *
 * **순서 규칙:** 느린 것(ShowScreen 의 ACK 대기, StartTimer 의 delay)만 [effectScope] 로 던지고,
 * 영속화(Persist·Enqueue·NotifyLead)는 상태 루프 안에서 순차로 await 한다.
 * 그래야 outbox 삽입 순서가 이벤트 처리 순서와 같아진다(C5 "순서가 생명이다").
 *
 * @param stateScope  단일 스레드여야 한다. 이벤트 루프가 여기서 돈다.
 * @param effectScope 느린 부작용(HMD 전송·타이머)을 던질 스코프.
 */
class InspectionEngine(
    private val sinks: EffectSinks,
    initial: AppState = AppState(),
    private val stateScope: CoroutineScope,
    private val effectScope: CoroutineScope,
) {
    private val machine = StateMachine(initial)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val timers = ConcurrentHashMap<String, Job>()

    /** 마지막 처리 상태 — 모든 이벤트를 소진한 뒤 읽어야 의미가 있다. */
    val currentState: AppState get() = machine.current

    /** 어느 스레드에서 불러도 안전하다. 채널이 줄을 세운다. */
    fun send(event: Event): Boolean = events.trySend(event).isSuccess

    /** 이벤트 루프 시작. 반환된 Job 을 취소하거나 [close] 로 채널을 닫아 멈춘다. */
    fun start(): Job = stateScope.launch {
        for (event in events) {
            val (newState, effects) = machine.reduce(event)   // 순수 함수, 동시 진입 없음
            sinks.snapshot(newState)
            for (fx in effects) dispatch(fx)
        }
    }

    /** 더 받을 이벤트가 없을 때 루프를 정상 종료시킨다. */
    fun close() {
        events.close()
        timers.values.forEach { it.cancel() }
        timers.clear()
    }

    private suspend fun dispatch(e: Effect) {
        when (e) {
            // 느리다 — ACK 를 최대 400ms 기다린다. 루프를 막지 않도록 던진다.
            is Effect.ShowScreen -> { effectScope.launch { sinks.show(e.command) } }
            is Effect.Vibrate -> sinks.vibrate(e.times)
            // 빠르고 순서가 중요하다 — 루프 안에서 순차로.
            is Effect.Persist -> sinks.persist(e.record)
            is Effect.Enqueue -> sinks.enqueue(e.payload)
            is Effect.NotifyLead -> sinks.notifyLead(e.alert)
            is Effect.StartTimer -> startTimer(e)
            is Effect.CancelTimer -> cancelTimer(e.key)
        }
    }

    private fun startTimer(e: Effect.StartTimer) {
        timers[e.key]?.cancel()
        timers[e.key] = effectScope.launch {
            delay(e.millis)
            timers.remove(e.key)
            send(Event.TimerFired(e.key, e.partId))
        }
    }

    private fun cancelTimer(key: String) {
        timers.remove(key)?.cancel()
    }
}
