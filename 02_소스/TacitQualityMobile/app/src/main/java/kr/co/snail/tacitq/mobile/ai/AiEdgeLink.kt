package kr.co.snail.tacitq.mobile.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 실제 AI 엣지 연결 — HMD JPEG 를 WebRTC 영상 트랙으로 AI 서버에 보내고, 데이터 채널 메시지를 [AiFlow] 로 넘긴다.
 * 설계서 §9 · A4_WEBRTC_API.md §5. 모의 엣지용 [StreamRelay] 자리를 그대로 대신한다(같은 offer · status).
 *
 *  - 영상: 마지막 JPEG 1장만 들고 있다가 [FRAME_MS] 마다 I420 으로 바꿔 넣는다(밀린 프레임은 버림, 초당 최대 10장).
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
    /** HMD 조준 화면 진행 안내(제품을 비춰 주세요 · 검사 중) */
    onHint: (text: String, tone: String) -> Unit = { _, _ -> },
    /** 실시간 결함 표시(HMD LIVE · M-07) — NG·REVIEW 프레임과 지우는 순간 */
    onLive: (LiveMark) -> Unit = {},
    /** 제품 구간에서 결함이 처음 확정되는 순간(진동) */
    onAlarm: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val api = AiResultsApi(baseUrl)
    private val flow = AiFlow(emit, stationId, profile, onHint = onHint, onLive = onLive, onAlarm = onAlarm)
    private val policy = Reconnect()
    // WebRTC 콜백(신호 스레드)에서 pc 를 닫으면 멈추므로, 연결 다루기는 모두 이 한 줄 스레드에서 한다
    private val linkThread = Executors.newSingleThreadExecutor { Thread(it, "AiEdgeLink") }.asCoroutineDispatcher()

    private val latest = AtomicReference<ByteArray?>(null)
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
    @Volatile private var sentInfo: String? = null
    private var argb: ByteBuffer? = null

    private val _status = MutableStateFlow(StreamRelay.Status())
    val status: StateFlow<StreamRelay.Status> = _status

    /** HMD 프레임 한 장 — [StreamRelay.offer] 와 같은 모양. 어느 스레드에서 불러도 된다. */
    @Suppress("UNUSED_PARAMETER")
    fun offer(hmdSeq: Long, capturedAt: Long, data: ByteArray, codec: String = "jpeg", key: Boolean = true) {
        received++
        if (codec == "jpeg") latest.set(data)        // H.265 는 1차 범위 밖(설계서 §9) — HMD 기본값이 MJPEG
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
            Log.w(TAG, "연결 시도 ${Reconnect.ATTEMPT_TIMEOUT_MS}ms 초과 — 닫고 다시")
            closePc()
            lost("연결 시간 초과")
        }
        if (policy.due(now)) connect(now)
        flow.onTick()
        if (connected && now - lastStats >= STATS_MS) { lastStats = now; logStats() }
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
            channel = p.createDataChannel("a4", DataChannel.Init()).also { it.registerObserver(channelObserver(it)) }

            val offer = sdp { p.createOffer(it, MediaConstraints()) } ?: error("offer 생성 실패")
            sdpSet { p.setLocalDescription(it, offer) }
            withTimeoutOrNull(GATHER_MS) { gathered.await() }                // trickle 없음 — 후보 수집 끝까지 기다린다

            val reply = withContext(Dispatchers.IO) { api.offer(p.localDescription.description, stationId) }
            when (reply.code) {
                200 -> {
                    val answer = Json.parseToJsonElement(reply.body).jsonObject["sdp"]!!.jsonPrimitive.content
                    sdpSet { p.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
                    keepResolution(sender)                                    // 협상 뒤에야 encodings 가 채워진다 — 다시 건다
                    Log.i(TAG, "offer 수락 — 연결 대기 $baseUrl station=$stationId")
                }
                403, 422 -> {
                    configError = "설정 오류 ${reply.code} — 스테이션·주소 확인 (${detail(reply.body)})"
                    Log.w(TAG, configError!!)
                    policy.onConfigError()
                    closePc()
                    lost("설정 오류 ${reply.code}")
                }
                else -> { closePc(); lost("offer 응답 ${reply.code} ${detail(reply.body)}") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "연결 실패: ${e.message}")
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
        Log.i(TAG, "해상도 유지 설정 ok=$ok encodings=${p.encodings.size}")
    }

    /** 실제로 나가는 영상 — WebRTC 통계(outbound-rtp). */
    private fun logStats() {
        pc?.getStats { report ->
            report.statsMap.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }?.let { s ->
                val m = s.members
                sentInfo = "보냄 ${m["frameWidth"]}×${m["frameHeight"]} · ${m["framesPerSecond"] ?: "-"}fps · " +
                    "목표 ${(m["targetBitrate"] as? Number)?.toInt()?.div(1000) ?: "-"}kbps · 제한 ${m["qualityLimitationReason"]}"
                Log.i(TAG, sentInfo!!)
            }
        }
    }

    /** [myGen]: 이 관찰자가 붙은 PeerConnection 의 세대. 이미 닫힌 연결(dispose 때 CLOSED 를 보낸다)의 콜백은 버린다. */
    private fun observer(gathered: CompletableDeferred<Unit>, myGen: Int) = object : PeerConnection.Observer {
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "연결 상태 $s (세대 $myGen)")
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
        override fun onStateChange() { Log.i(TAG, "데이터 채널 ${ch.state()}") }
        override fun onMessage(b: DataChannel.Buffer) {
            val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
            val text = String(bytes, Charsets.UTF_8)
            Log.d(TAG, "← $text")
            flow.onMessage(AiMessages.parse(text))
        }
    }

    private fun onConnected() {
        connected = true
        policy.onConnected()
        flow.onConnected()
        if (unreachable) { unreachable = false; emit(Event.EdgeRecovered) }
    }

    private fun lost(reason: String) {
        connected = false
        policy.onFailure(System.currentTimeMillis())
        flow.onDisconnected()
        if (!unreachable) { unreachable = true; emit(Event.EdgeUnreachable(reason)) }
        if (configError == null) Log.i(TAG, "끊김($reason) — 연속 실패 ${policy.failures}회, ${Reconnect.delayMs(policy.failures)}ms 뒤 재시도")
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

    private suspend fun pumpLoop() = coroutineScope {
        while (isActive) {
            val jpeg = latest.getAndSet(null)
            if (jpeg != null && connected) runCatching { push(jpeg) }.onFailure { Log.w(TAG, "프레임 변환 실패: ${it.message}") }
            delay(FRAME_MS)
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
            override fun onCreateFailure(e: String?) { Log.w(TAG, "SDP 생성 실패 $e"); c.resume(null) }
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
        const val FRAME_MS = 100L                 // 초당 최대 10장 — 서버도 초당 10프레임까지만 쓴다(API §5.5)
        const val GATHER_MS = 5_000L
        const val MAX_BITRATE = 6_000_000
        const val MIN_BITRATE = 2_000_000          // 처음부터 낮은 비트레이트로 시작해 해상도를 깎지 않게
        const val STATS_MS = 5_000L
    }
}
