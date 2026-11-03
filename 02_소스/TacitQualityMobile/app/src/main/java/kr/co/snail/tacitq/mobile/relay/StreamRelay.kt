package kr.co.snail.tacitq.mobile.relay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.model.Verdict

/**
 * C2 StreamRelay — HMD 영상을 손대지 않고 엣지로 넘기고, 엣지 판정을 상태기계로 되돌린다.
 * 개발가이드 §5 · 설계서(doc/design) §3·§4.
 *
 *  - **큐**: 최대 [maxFrames]장 · [maxBytes]. 엣지가 느리면 여기 쌓는다. 90% 넘으면 [Event.RelayOverflow].
 *    가득 차면 새 프레임을 버리되 순번(seq)은 소비해 엣지가 누락을 셀 수 있게 한다. **몰래 화질을 낮추지 않는다.**
 *  - **건강검사**: 엣지 HB 가 [silenceMs] 없으면 [Event.EdgeUnreachable](R17). 붙으면 [Event.EdgeRecovered](R18).
 *  - **재연결**: [reconnectMs] 고정 주기, 무한 재시도(지수 백오프 금지).
 *  - **지연(R05)**: 시계 맞추기 없이 폰 시계로만 — 판정이 가리키는 마지막 프레임을 폰이 받은 시각 → 판정 도착 시각.
 *
 * 안드로이드를 모른다 — [Transport] 를 가짜로 바꿔 노트북에서 시험한다.
 */
class StreamRelay(
    private val transport: Transport,
    private val emit: (Event) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFrames: Int = 900,                    // 60fps 기준 약 15초
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val silenceMs: Long = 3_000,
    private val reconnectMs: Long = 5_000,
    private val wireHighWater: Long = 2L * 1024 * 1024,  // 소켓 송신 버퍼가 이만큼 차면 우리 큐에 둔다
) {
    data class Status(
        val edgeConnected: Boolean = false,
        val received: Long = 0,
        val sent: Long = 0,
        val queued: Int = 0,
        val dropped: Long = 0,
        val edgeFps: Double = 0.0,
        val lastJudge: String? = null,
        val lastDelayMs: Long? = null,
        val guide: String? = null,                       // AI 서버 진행 상태(종이 없음 · 수집 n/10 …) — 모의 엣지는 없음
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val lock = Any()
    private val queue = ArrayDeque<Frame>()
    private var queueBytes = 0L
    private var seqGen = 0L
    private var received = 0L
    private var sent = 0L
    private var dropped = 0L
    private var overflowRaised = false
    private val receivedAt = LinkedHashMap<Long, Long>()  // seq → 폰 수신 시각 (최근 RECEIVED_KEEP 장)

    @Volatile private var connected = false
    @Volatile private var unreachable = false              // EdgeUnreachable 을 내보낸 뒤 복구 대기
    @Volatile private var lastHeard = 0L
    @Volatile private var edgeFps = 0.0
    @Volatile private var lastJudge: String? = null
    @Volatile private var lastDelayMs: Long? = null
    private var lastAttempt = Long.MIN_VALUE / 2
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** HMD 프레임 한 장 — 어느 스레드에서 불러도 된다. */
    fun offer(hmdSeq: Long, capturedAt: Long, jpeg: ByteArray, codec: String = "jpeg", key: Boolean = true) {
        val now = clock()
        var overflow = false
        synchronized(lock) {
            received++
            val seq = ++seqGen
            if (queue.size >= maxFrames || queueBytes + jpeg.size > maxBytes) {
                dropped++                                  // 순번은 소비 → 엣지가 누락으로 센다
                return
            }
            queue.addLast(Frame(seq, hmdSeq, capturedAt, now, jpeg, codec, key))
            queueBytes += jpeg.size
            receivedAt[seq] = now
            if (receivedAt.size > RECEIVED_KEEP) receivedAt.remove(receivedAt.keys.first())
            if (!overflowRaised && queue.size >= maxFrames * 9 / 10) {
                overflowRaised = true
                overflow = true
            } else if (overflowRaised && queue.size < maxFrames / 2) {
                overflowRaised = false
            }
        }
        if (overflow) emit(Event.RelayOverflow)
        wake.trySend(Unit)
    }

    /** 취소될 때까지 송신·건강검사를 돌린다. */
    suspend fun run(url: String, hello: Hello) {
        val helloText = EdgeMessages.hello(hello)
        val listener = object : Transport.Listener {
            override fun onOpen() {
                connected = true
                lastHeard = clock()
                transport.sendText(helloText)
                if (unreachable) {
                    unreachable = false
                    emit(Event.EdgeRecovered)
                }
            }
            override fun onText(text: String) {
                lastHeard = clock()
                onEdgeMessage(EdgeMessages.parse(text))
            }
            override fun onClosed(reason: String) = lost(reason)
        }
        try {
            coroutineScope {
                launch { pumpLoop() }
                while (isActive) {
                    healthTick(url, listener)
                    delay(TICK_MS)
                }
            }
        } finally {
            transport.close()
            connected = false
        }
    }

    private suspend fun pumpLoop() = coroutineScope {
        while (isActive) {
            val f = if (connected && transport.queuedBytes < wireHighWater) {
                synchronized(lock) { queue.removeFirstOrNull()?.also { queueBytes -= it.jpeg.size } }
            } else null
            if (f == null) {
                withTimeoutOrNull(20) { wake.receive() }
                continue
            }
            if (transport.sendBinary(FrameCodec.encode(f))) {
                synchronized(lock) { sent++ }
            } else {
                synchronized(lock) { queue.addFirst(f); queueBytes += f.jpeg.size }   // 순서 유지
                delay(20)
            }
        }
    }

    private fun healthTick(url: String, listener: Transport.Listener) {
        val now = clock()
        if (connected) {
            if (now - lastHeard >= silenceMs) {
                transport.close()
                lost("엣지 ${silenceMs}ms 무응답")
            }
        } else if (now - lastAttempt >= reconnectMs) {
            lastAttempt = now
            transport.open(url, listener)
        }
        _status.value = synchronized(lock) {
            Status(connected, received, sent, queue.size, dropped, edgeFps, lastJudge, lastDelayMs)
        }
    }

    private fun lost(reason: String) {
        connected = false
        if (!unreachable) {
            unreachable = true
            emit(Event.EdgeUnreachable(reason))
        }
    }

    private fun onEdgeMessage(m: EdgeMsg) {
        when (m) {
            is EdgeMsg.Hb -> edgeFps = m.fps
            is EdgeMsg.Judge -> {
                val now = clock()
                val end = synchronized(lock) { receivedAt[m.frameSeqEnd] } ?: now
                lastJudge = "${m.partId} ${m.verdict}"
                lastDelayMs = now - end
                emit(m.toEvent(capturedEndAt = end, arrivedAt = now))
            }
            is EdgeMsg.Pattern -> emit(Event.PatternAlert(m.level, m.defectType, m.hit, m.window))
            is EdgeMsg.Tray -> emit(Event.TrayDetected(m.partId, m.kind, at = clock()))
            is EdgeMsg.Unknown -> Unit
        }
    }

    companion object {
        const val TICK_MS = 500L
        private const val RECEIVED_KEEP = 2_000            // 14fps 기준 2분 남짓
    }
}

/** 엣지 판정 → 상태기계 입력. 모르는 verdict 는 REVIEW 로 넘기고, 상태기계가 안전측(불합격)으로 표시한다. */
internal fun EdgeMsg.Judge.toEvent(capturedEndAt: Long, arrivedAt: Long) = Event.JudgeResult(
    seq = seq,
    partId = partId,
    verdict = Verdict.entries.firstOrNull { it.code == verdict } ?: Verdict.REVIEW,
    severity = severity,
    confidence = confidence,
    agreeRate = agreeRate,
    evidence = evidence,
    frameRef = frameRef,
    bbox = bbox,
    capturedEndAt = capturedEndAt,
    arrivedAt = arrivedAt,
)
