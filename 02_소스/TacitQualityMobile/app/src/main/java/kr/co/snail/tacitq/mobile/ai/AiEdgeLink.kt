package kr.co.snail.tacitq.mobile.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.log.CommLog
import kr.co.snail.tacitq.mobile.log.CommLog.Ch.AI
import kr.co.snail.tacitq.mobile.relay.StreamRelay
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.YuvHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 실제 AI 엣지 연결 — HMD JPEG 를 WebRTC 영상 트랙으로 AI 서버에 보내고, 데이터 채널 메시지를 [AiFlow] 로 넘긴다.
 * 설계서 §3 · 「제품 불량 판정 — 모바일 앱 연동 API」 2026-11-03 규격 §5 + 2026-11-06 제품 단위(`server/PLAN_product_feedback.md`).
 * 모의 엣지용 [StreamRelay] 자리를 그대로 대신한다(같은 offer · status).
 *
 *  - 영상: HMD JPEG 가 오는 대로 I420 으로 바꿔 넣는다(쉬는 간격 없음 — HMD 송출 초당 12장이 상한). 변환이 밀리면 최신 1장만 남긴다.
 *  - 해상도: 대역폭이 모자라도 해상도를 낮추지 않게 MAINTAIN_RESOLUTION (서버는 결함을 640px 타일로 보므로 해상도가 곧 검출력, API §5.5).
 *  - 장애: 생존 신호가 없으므로 연결 상태 disconnected·failed·closed 를 끊김으로 보고 새 POST /offer.
 *    재시도 간격·시도 마감은 [Reconnect](1→2→4→5초, 10초). 422·403 은 설정 오류 — 다시 시도하지 않고 화면에 띄운다.
 */
class AiEdgeLink(
    context: Context,
    /** AI 서버 주소 — 예: http://<서버>:8080 */
    private val baseUrl: String,
    private val stationId: String,
    private val emit: (Event) -> Unit,
    profile: Profile = Profile(),
    /** 피드백 모드 — `/offer` 의 feedback_mode (manual · auto). 바꾸려면 새로 연결한다 */
    private val feedbackMode: String = FeedbackMode.AUTO,
    /** HMD 조준 화면 진행 안내(제품을 비춰 주세요 · 검사 중) */
    onHint: (text: String, tone: String) -> Unit = { _, _ -> },
    /** 실시간 결함 표시(HMD LIVE · M-07) — NG·REVIEW 프레임과 지우는 순간 */
    onLive: (LiveMark) -> Unit = {},
    /** 면 진행 표시(HMD FACES · M-07) — 바뀔 때만 */
    onFaces: (FacesMark) -> Unit = {},
    /** 제품이 끝남(서버 product_end) — 결과 카드 · HMD 결과 · 진동 */
    onProduct: (AiProduct) -> Unit = {},
    /** 연결될 때마다(첫 연결 포함) — 끊긴 사이 끝난 제품을 서버에서 다시 받을 때 쓴다 */
    private val onLinkUp: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val api = AiResultsApi(baseUrl)
    private val flow = AiFlow(emit, profile.markHoldMs, onHint = onHint, onLive = onLive, onFaces = onFaces, onProduct = onProduct)
    private val policy = Reconnect()
    // WebRTC 콜백(신호 스레드)에서 pc 를 닫으면 멈추므로, 연결 다루기는 모두 이 한 줄 스레드에서 한다
    private val linkThread = Executors.newSingleThreadExecutor { Thread(it, "AiEdgeLink") }.asCoroutineDispatcher()

    /** HMD 최신 JPEG 1장 — 변환이 끝나기 전에 새 장이 오면 덮어쓴다(밀린 프레임은 버림) */
    private val latest = Channel<ByteArray>(Channel.CONFLATED)
    @Volatile private var received = 0L
    @Volatile private var sent = 0L
    @Volatile private var width = 0
    @Volatile private var height = 0

    private lateinit var factory: PeerConnectionFactory
    private lateinit var source: VideoSource
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    @Volatile private var connected = false
    @Volatile private var unreachable = false
    @Volatile private var configError: String? = null
    /** 지금 PeerConnection 의 세대 — [closePc] 마다 올린다. 닫힌 연결이 뒤늦게 보내는 상태 콜백을 거른다. */
    private var gen = 0
    private var lastStats = 0L
    // 통신 로그 요약(5초마다 한 줄) — 받은 HMD 영상 · 보낸 프레임 · 서버 메시지 수
    private val nInspect = AtomicInteger()
    private val nSearching = AtomicInteger()
    private val nProduct = AtomicInteger()
    private val nUnknown = AtomicInteger()
    @Volatile private var lastUnknown: String? = null
    @Volatile private var firstMessage = true
    private var statsReceived = 0L
    private var statsSent = 0L
    private var idleSince = 0L                        // HMD 영상이 끊긴 것을 처음 본 시각 (0 = 들어오는 중) — 연결 스레드에서만
    private var idleLogged = 0L                       // 마지막으로 "HMD 영상 없음"을 남긴 시각
    @Volatile private var sentInfo: String? = null
    private var argb: ByteBuffer? = null

    private val _status = MutableStateFlow(StreamRelay.Status())
    val status: StateFlow<StreamRelay.Status> = _status

    /** HMD 프레임 한 장 — [StreamRelay.offer] 와 같은 모양. 어느 스레드에서 불러도 된다. */
    @Suppress("UNUSED_PARAMETER")
    fun offer(hmdSeq: Long, capturedAt: Long, data: ByteArray, codec: String = "jpeg", key: Boolean = true) {
        received++
        if (codec == "jpeg") latest.trySend(data)    // H.265 는 1차 범위 밖(설계서 §3) — HMD 기본값이 MJPEG
    }

    /** 취소될 때까지 연결·재연결·영상 넣기를 돌린다. */
    suspend fun run() {
        withContext(linkThread) { init() }
        try {
            coroutineScope {
                launch(Dispatchers.Default) { pumpLoop() }
                while (isActive) {
                    withContext(linkThread) { tick() }
                    delay(TICK_MS)
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + linkThread) { closePc() }
            linkThread.close()
        }
    }

    private fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(SoftwareVideoEncoderFactory())       // VP8 — EGL 없이 동작
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
        source = factory.createVideoSource(false)
        source.capturerObserver.onCapturerStarted(true)
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        if (policy.expired(now)) {
            CommLog.w(AI, TAG, "연결 시도 ${Reconnect.ATTEMPT_TIMEOUT_MS}ms 초과 — 닫고 다시")
            closePc()
            lost("연결 시간 초과")
        }
        if (policy.due(now)) connect(now)
        if (connected && now - lastStats >= STATS_MS) { lastStats = now; summary(); logStats() }
        _status.value = StreamRelay.Status(
            edgeConnected = connected, received = received, sent = sent, queued = 0,
            dropped = (received - sent).coerceAtLeast(0), edgeFps = 0.0,
            lastSegment = flow.lastSegment,
            guide = configError ?: (flow.guide + (sentInfo?.let { "\n$it" } ?: if (width > 0) " · ${width}×$height" else "")),
        )
    }

    // ── 연결 ─────────────────────────────────────────────

    private suspend fun connect(now: Long) {
        policy.onAttempt(now)
        if (policy.failures == 0) CommLog.i(AI, TAG, "연결 시도 $baseUrl (스테이션 $stationId, 모드 $feedbackMode)")
        try {
            closePc()
            val myGen = gen
            val gathered = CompletableDeferred<Unit>()
            val p = factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()).apply {            // ICE 서버 없음 — 같은 망 전제
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                observer(gathered, myGen),
            ) ?: error("PeerConnection 생성 실패")
            pc = p
            val sender = p.addTrack(factory.createVideoTrack("hmd", source), listOf("hmd"))
            keepResolution(sender)
            // 데이터 채널은 offer 전에 — 그래야 SDP 에 들어간다(API §5.1)
            channel = p.createDataChannel("tacitq", DataChannel.Init()).also { it.registerObserver(channelObserver(it)) }

            val offer = sdp { p.createOffer(it, MediaConstraints()) } ?: error("offer 생성 실패")
            sdpSet { p.setLocalDescription(it, offer) }
            withTimeoutOrNull(GATHER_MS) { gathered.await() }                // trickle 없음 — 후보 수집 끝까지 기다린다

            val reply = withContext(Dispatchers.IO) { api.offer(p.localDescription.description, stationId, feedbackMode) }
            when (reply.code) {
                200 -> {
                    val answer = Json.parseToJsonElement(reply.body).jsonObject["sdp"]!!.jsonPrimitive.content
                    sdpSet { p.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
                    keepResolution(sender)                                    // 협상 뒤에야 encodings 가 채워진다 — 다시 건다
                    CommLog.i(AI, TAG, "offer 수락 — 연결 대기 $baseUrl station=$stationId")
                }
                403, 422 -> {
                    configError = "설정 오류 ${reply.code} — 스테이션·주소 확인 (${detail(reply.body)})"
                    CommLog.w(AI, TAG, configError!!)
                    policy.onConfigError()
                    closePc()
                    lost("설정 오류 ${reply.code}")
                }
                else -> { closePc(); lost("offer 응답 ${reply.code} ${detail(reply.body)}") }
            }
        } catch (e: Exception) {
            CommLog.w(AI, TAG, "연결 실패: ${e.message}")
            closePc()
            lost("연결 실패 ${e.message}")
        }
    }

    /** 대역폭이 모자라도 해상도를 낮추지 않게 — 해상도가 낮을수록 작은 결함을 놓친다(API §5.5). */
    private fun keepResolution(sender: RtpSender) {
        val p = sender.parameters
        p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        p.encodings.forEach { it.maxBitrateBps = MAX_BITRATE; it.minBitrateBps = MIN_BITRATE; it.scaleResolutionDownBy = 1.0 }
        val ok = sender.setParameters(p)
        CommLog.i(AI, TAG, "해상도 유지 설정 ok=$ok encodings=${p.encodings.size}")
    }

    /** 통신 로그 5초 요약 — HMD 영상이 안 들어오면 서버로 보낼 것도 없다는 것을 바로 보이게 */
    private fun summary() {
        val now = System.currentTimeMillis()
        val r = received - statsReceived; val s = sent - statsSent
        statsReceived = received; statsSent = sent
        val u = nUnknown.getAndSet(0)
        val line = "최근 ${STATS_MS / 1000}초: HMD 영상 ${r}장 받음 · 서버로 ${s}장 보냄 · 서버 메시지 inspect ${nInspect.getAndSet(0)} · " +
            "searching ${nSearching.getAndSet(0)} · product_end ${nProduct.getAndSet(0)}" + (if (u > 0) " · 모르는 메시지 $u (예: $lastUnknown)" else "") +
            (sentInfo?.let { " · $it" } ?: "")
        if (r > 0L) {
            if (idleSince > 0L) CommLog.i(AI, TAG, "HMD 영상 다시 들어옴 (${(now - idleSince) / 1000}초 만)")
            idleSince = 0L
            CommLog.i(AI, TAG, line)
            return
        }
        // HMD 영상 없음 — 끊긴 순간 한 번, 그 뒤로는 1분에 한 번만 남긴다. 5초마다 남기면 KEEP(300줄)이 25분 만에 차서
        // 시험하던 때의 줄이 밀려난다 (2026-11-10: HMD 를 끄고 앱만 켜 둔 동안 같은 경고만 남았다).
        if (idleSince == 0L) {
            idleSince = now; idleLogged = now
            CommLog.w(AI, TAG, "$line — HMD 영상이 들어오지 않아 서버로 보낼 프레임이 없음(HMD 로그 확인)")
        } else if (now - idleLogged >= IDLE_LOG_MS) {
            idleLogged = now
            CommLog.w(AI, TAG, "HMD 영상 없음 ${(now - idleSince) / 60_000}분째 — 서버로 보낸 프레임 없음 (1분마다 한 줄)")
        }
    }

    /** 실제로 나가는 영상 — WebRTC 통계(outbound-rtp). */
    private fun logStats() {
        pc?.getStats { report ->
            report.statsMap.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }?.let { s ->
                val m = s.members
                sentInfo = "보냄 ${m["frameWidth"]}×${m["frameHeight"]} · ${m["framesPerSecond"] ?: "-"}fps · " +
                    "목표 ${(m["targetBitrate"] as? Number)?.toInt()?.div(1000) ?: "-"}kbps · 제한 ${m["qualityLimitationReason"]}"
            }
        }
    }

    /** [myGen]: 이 관찰자가 붙은 PeerConnection 의 세대. 이미 닫힌 연결(dispose 때 CLOSED 를 보낸다)의 콜백은 버린다. */
    private fun observer(gathered: CompletableDeferred<Unit>, myGen: Int) = object : PeerConnection.Observer {
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            CommLog.i(AI, TAG, "연결 상태 $s (세대 $myGen)")
            kotlinx.coroutines.CoroutineScope(linkThread).launch {
                if (myGen != gen) return@launch
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onConnected()
                    // 연결 전(시도 중)에 온 것도 실패로 센다 — Reconnect 가 다음 간격을 정한다
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> { closePc(); lost("연결 $s") }
                    else -> Unit
                }
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(b: Boolean) = Unit
        override fun onIceCandidate(c: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
        override fun onAddStream(s: MediaStream) = Unit
        override fun onRemoveStream(s: MediaStream) = Unit
        override fun onDataChannel(d: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun channelObserver(ch: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(l: Long) = Unit
        override fun onStateChange() { CommLog.i(AI, TAG, "데이터 채널 ${ch.state()}") }
        override fun onMessage(b: DataChannel.Buffer) {
            val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
            val text = String(bytes, Charsets.UTF_8)
            Log.d(TAG, "← $text")
            val msg = AiMessages.parse(text)
            if (firstMessage) { firstMessage = false; CommLog.i(AI, TAG, "첫 서버 메시지 ← ${text.take(300)}") }
            when (msg) {
                is AiMsg.Inspect -> nInspect.incrementAndGet()
                is AiMsg.Searching -> nSearching.incrementAndGet()
                is AiMsg.ProductEnd -> {
                    nProduct.incrementAndGet()
                    val p = msg.product
                    CommLog.i(AI, TAG, "제품 끝 ${p.productId} ${p.decision}(${p.aiDecision}${p.defectLabel?.let { " $it" } ?: ""}) · " +
                        "프레임 ${p.frames}장(결함 ${p.defectFrames}) · 면 ${p.facesSeen.joinToString("·").ifEmpty { "-" }}${if (p.complete) " 완료" else ""} · ${p.endReason}")
                }
                is AiMsg.Unknown -> { nUnknown.incrementAndGet(); lastUnknown = text.take(200) }
            }
            flow.onMessage(msg)
        }
    }

    private fun onConnected() {
        connected = true
        CommLog.i(AI, TAG, "연결됨 — 영상 전송 시작, 서버 메시지 대기 ($baseUrl)")
        firstMessage = true
        nInspect.set(0); nSearching.set(0); nProduct.set(0); nUnknown.set(0)
        statsReceived = received; statsSent = sent; lastStats = System.currentTimeMillis(); idleSince = 0L
        policy.onConnected()
        flow.onConnected()
        onLinkUp()
        if (unreachable) { unreachable = false; emit(Event.EdgeRecovered) }
    }

    private fun lost(reason: String) {
        connected = false
        policy.onFailure(System.currentTimeMillis())
        flow.onDisconnected()
        if (!unreachable) { unreachable = true; emit(Event.EdgeUnreachable(reason)) }
        if (configError == null) CommLog.w(AI, TAG, "끊김($reason) — 연속 실패 ${policy.failures}회, ${Reconnect.delayMs(policy.failures)}ms 뒤 재시도")
    }

    private fun closePc() {
        gen++
        channel?.let { runCatching { it.unregisterObserver(); it.close(); it.dispose() } }
        channel = null
        pc?.let { runCatching { it.dispose() } }
        pc = null
        connected = false
    }

    // ── 영상 ─────────────────────────────────────────────

    /**
     * 새 HMD 프레임이 오면 깨어나 바로 넣는다 — 2026-11-09: "넣은 뒤 100ms 쉼"이라 주기가 100ms + 변환 시간이 돼
     * 서버 도착이 초당 6.9장이었다. 프레임이 없으면 잠들어 기다린다(바쁜 대기 없음).
     */
    private suspend fun pumpLoop() {
        for (jpeg in latest) {
            if (connected) runCatching { push(jpeg) }.onFailure { CommLog.w(AI, TAG, "프레임 변환 실패: ${it.message}") }
        }
    }

    /** JPEG → I420 → 영상 트랙. 안드로이드 ARGB_8888 은 메모리상 R,G,B,A 순서 = libyuv "ABGR". */
    private fun push(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return
        try {
            val w = bmp.width and 1.inv()
            val h = bmp.height and 1.inv()
            val need = bmp.rowBytes * bmp.height
            val buf = argb?.takeIf { it.capacity() >= need }
                ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { argb = it }
            buf.clear()
            bmp.copyPixelsToBuffer(buf)
            buf.rewind()
            val i420 = JavaI420Buffer.allocate(w, h)
            YuvHelper.ABGRToI420(buf, bmp.rowBytes, i420.dataY, i420.strideY, i420.dataU, i420.strideU,
                                 i420.dataV, i420.strideV, w, h)
            val frame = VideoFrame(i420, 0, System.nanoTime())
            source.capturerObserver.onFrameCaptured(frame)
            frame.release()
            width = w; height = h
            sent++
        } finally {
            bmp.recycle()
        }
    }

    // ── SDP 콜백 → 코루틴 ─────────────────────────────────

    private suspend fun sdp(call: (SdpObserver) -> Unit): SessionDescription? = suspendCoroutine { c ->
        call(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = c.resume(d)
            override fun onCreateFailure(e: String?) { CommLog.w(AI, TAG, "SDP 생성 실패 $e"); c.resume(null) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String?) = Unit
        })
    }

    private suspend fun sdpSet(call: (SdpObserver) -> Unit) = suspendCoroutine { c ->
        call(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) = Unit
            override fun onCreateFailure(e: String?) = Unit
            override fun onSetSuccess() = c.resume(Unit)
            override fun onSetFailure(e: String?) { c.resumeWith(Result.failure(IllegalStateException("SDP 설정 실패 $e"))) }
        })
    }

    private fun detail(body: String) = runCatching {
        Json.parseToJsonElement(body).jsonObject["detail"]?.toString()?.take(80)
    }.getOrNull() ?: body.take(80)

    companion object {
        private const val TAG = "TacitQ.AiEdge"
        const val TICK_MS = 500L
        const val GATHER_MS = 5_000L
        // 2026-11-06: 6→12Mbps(출발값) — 압축을 덜 해 작은 결함을 지키려고(PLAN_product_feedback.md §7.3).
        // 폰 소프트웨어 VP8 이 1920×1440 초당 12장을 이 비트레이트로 내는지 통신 로그의 "보냄 … fps" 로 확인한다.
        const val MAX_BITRATE = 12_000_000
        const val MIN_BITRATE = 4_000_000          // 처음부터 낮은 비트레이트로 시작해 해상도를 깎지 않게
        const val STATS_MS = 5_000L
        const val IDLE_LOG_MS = 60_000L            // HMD 영상이 없는 동안 통신 로그에 남기는 간격
    }
}
