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
 *  - 해상도: 대역폭이 모자라도 해상도를 낮추지 않게 MAINTAIN_RESOLUTION (서버는 종이 긴 변 ≥1216px 필요).
 *  - 장애: 생존 신호가 없으므로 연결 상태 disconnected·failed·closed 를 끊김으로 보고 [RECONNECT_MS] 마다 새 POST /offer.
 *    422·403 은 설정 오류 — 다시 시도하지 않고 화면에 띄운다.
 */
class AiEdgeLink(
    context: Context,
    /** 붙어 볼 서버 (이름, 주소) — 앞에서부터 응답하는 첫 서버를 쓴다. 예: 사무실 AI 서버 → 노트북 모의 AI 서버 */
    private val servers: List<Pair<String, String>>,
    private val stationId: String,
    private val emit: (Event) -> Unit,
    /** HMD 조준 화면 진행 안내(종이 없음 · 더 가까이 · 수집 n/10 · 판정 중) */
    onHint: (text: String, tone: String) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    @Volatile private var baseUrl = servers.first().second
    @Volatile private var serverName = servers.first().first
    @Volatile private var api = AiResultsApi(baseUrl)
    private val flow = AiFlow(emit, onHint = onHint)
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
    @Volatile private var connecting = false
    @Volatile private var unreachable = false
    @Volatile private var configError: String? = null
    private var lastAttempt = Long.MIN_VALUE / 2
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
        if (!connected && !connecting && configError == null && now - lastAttempt >= RECONNECT_MS) {
            lastAttempt = now
            connect()
        }
        if (connected && now - lastStats >= STATS_MS) { lastStats = now; logStats() }
        _status.value = StreamRelay.Status(
            edgeConnected = connected, received = received, sent = sent, queued = 0,
            dropped = (received - sent).coerceAtLeast(0), edgeFps = 0.0,
            lastJudge = flow.lastJudge, lastDelayMs = flow.lastDelayMs,
            guide = configError ?: ("[$serverName] " + flow.guide + (sentInfo?.let { "\n$it" } ?: if (width > 0) " · ${width}×$height" else "")),
        )
    }

    // ── 연결 ─────────────────────────────────────────────

    /**
     * 붙을 서버 고르기 — 목록 순서대로 TCP 접속을 짧게 시험해 첫 응답 서버. 매 연결 시도마다 다시 고르므로
     * 사무실 Wi-Fi 에 들어가면 실제 AI 서버로, 밖이면 모의 AI 서버로 저절로 바뀐다.
     */
    private suspend fun pickServer(): Boolean = withContext(Dispatchers.IO) {
        val hit = servers.firstOrNull { (_, url) -> url.isNotBlank() && reachable(url) } ?: return@withContext false
        if (hit.second != baseUrl) Log.i(TAG, "서버 전환 $serverName → ${hit.first} (${hit.second})")
        serverName = hit.first; baseUrl = hit.second; api = AiResultsApi(baseUrl)
        true
    }

    private fun reachable(url: String): Boolean = runCatching {
        val u = java.net.URI(url)
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else 80), PROBE_MS) }
        true
    }.getOrDefault(false)

    private suspend fun connect() {
        connecting = true
        try {
            closePc()
            if (!pickServer()) {
                serverName = "서버 없음"
                lost("AI 서버 응답 없음 — ${servers.joinToString { it.first }} 모두")
                return
            }
            val gathered = CompletableDeferred<Unit>()
            val p = factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()).apply {            // ICE 서버 없음 — 같은 망 전제
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                observer(gathered),
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
                    closePc()
                    lost("설정 오류 ${reply.code}")
                }
                else -> { closePc(); lost("offer 응답 ${reply.code} ${detail(reply.body)}") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "연결 실패: ${e.message}")
            closePc()
            lost("연결 실패 ${e.message}")
        } finally {
            connecting = false
        }
    }

    /** 대역폭이 모자라도 해상도를 낮추지 않게 — 서버는 종이 긴 변 ≥1216px 가 필요하다. */
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

    private fun observer(gathered: CompletableDeferred<Unit>) = object : PeerConnection.Observer {
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "연결 상태 $s")
            kotlinx.coroutines.CoroutineScope(linkThread).launch {
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onConnected()
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> if (connected) { closePc(); lost("연결 $s") }
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
        flow.onConnected()
        if (unreachable) { unreachable = false; emit(Event.EdgeRecovered) }
        val waiting = flow.pending
        if (waiting.isNotEmpty()) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { poll(waiting) }
    }

    /** 끊긴 사이 판정 — captured 까지 간 종이를 1초 간격으로 조회(API §7). */
    private suspend fun poll(ids: List<String>) {
        val left = ids.toMutableList()
        repeat(30) {
            left.removeAll { id ->
                runCatching { api.result(id) }.getOrNull()?.takeIf { it.code == 200 }?.let { r ->
                    val v = AiMessages.parseResult(r.body)
                    flow.onPolled(v)
                    v.status != "pending"
                } ?: false
            }
            if (left.isEmpty()) return
            delay(1_000)
        }
    }

    private fun lost(reason: String) {
        connected = false
        flow.onDisconnected()
        if (!unreachable) { unreachable = true; emit(Event.EdgeUnreachable(reason)) }
    }

    private fun closePc() {
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
        const val RECONNECT_MS = 5_000L
        const val FRAME_MS = 100L                 // 초당 최대 10장 — 서버 hold 간격 0.2초
        const val GATHER_MS = 5_000L
        const val MAX_BITRATE = 6_000_000
        const val PROBE_MS = 800
        const val MIN_BITRATE = 2_000_000          // 처음부터 낮은 비트레이트로 시작해 해상도를 깎지 않게
        const val STATS_MS = 5_000L
    }
}
