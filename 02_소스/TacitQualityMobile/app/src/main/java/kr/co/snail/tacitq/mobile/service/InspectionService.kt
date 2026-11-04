package kr.co.snail.tacitq.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kr.co.snail.tacitq.mobile.R
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.core.Screens
import kotlinx.coroutines.flow.StateFlow
import kr.co.snail.tacitq.mobile.ai.AiEdgeLink
import kr.co.snail.tacitq.mobile.relay.DevSettings
import kr.co.snail.tacitq.mobile.relay.Hello
import kr.co.snail.tacitq.mobile.relay.HmdVideoSource
import kr.co.snail.tacitq.mobile.relay.StreamRelay
import kr.co.snail.tacitq.mobile.relay.WebSocketTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kr.co.snail.tacitq.mobile.core.AppState
import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.core.InspectionRecord
import kr.co.snail.tacitq.mobile.core.Mode
import kr.co.snail.tacitq.mobile.core.ShowCommand
import kr.co.snail.tacitq.mobile.hmd.GattBleLink
import kr.co.snail.tacitq.mobile.hmd.HintWire
import kr.co.snail.tacitq.mobile.hmd.HmdRenderer
import kr.co.snail.tacitq.mobile.store.LocalStore
import kr.co.snail.tacitq.mobile.store.QmsDatabase
import java.util.concurrent.Executors

/**
 * 검사 서비스 — C3 상태 기계 · C4 HmdRenderer · C5 LocalStore 를 한 줄로 잇는 오케스트레이터.
 * 개발가이드 §2. 화면은 꺼져 있어도 이 서비스는 교대 내내 살아 있어야 한다(주머니 속 8시간).
 *
 * 실제 로직(줄 세우기·분배)은 안드로이드를 모르는 [InspectionEngine] 에 있고, 여기서는
 * 포그라운드·잠금·알림과 **실물 부품 주입**만 한다.
 *
 * C2 [StreamRelay]: [start] 로 교대를 시작하면 HMD 영상([HmdVideoSource]) → 엣지(WebSocket) 중계를 돌리고,
 *   엣지 판정은 [InspectionEngine] 으로 되먹인다. [stop] 은 교대 마감.
 * C1 [GattBleLink]: 상태기계의 화면 지시(SHOW)를 BLE 로 HMD 에 보내고 ACK 를 받는다.
 */
class InspectionService : LifecycleService() {

    private val job = SupervisorJob()
    private val ioScope = CoroutineScope(job + Dispatchers.IO)
    private val effectScope = CoroutineScope(job + Dispatchers.Default)
    // 상태 기계 전용 단일 스레드 — 동시 진입이 없다
    private val stateDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val stateScope = CoroutineScope(job + stateDispatcher)

    private lateinit var store: LocalStore
    private lateinit var hmd: HmdRenderer
    private lateinit var ble: GattBleLink
    private lateinit var engine: InspectionEngine
    private var relayJob: Job? = null
    @Volatile private var hmdUrl = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat("검사 대기 중")
        acquireLocks()

        store = LocalStore(QmsDatabase.get(applicationContext).dao())
        // C1 — 실물 BLE. HMD ACK 는 렌더러로 되먹이고, 연결되면 지금 화면을 다시 보낸다.
        ble = GattBleLink(
            this, onAck = { seq -> hmd.onAck(seq) }, onReady = ::resendCurrentScreen,
            // HMD 가 BLE 로 알려 준 영상 주소 — 다음 연결부터 쓰고, 화면 설정에도 남긴다(IP 수동 입력 제거)
            onVideoUrl = { url ->
                if (url != hmdUrl) { Log.i(TAG, "HMD 영상 주소 자동 변경 $hmdUrl → $url"); hmdUrl = url; DevSettings(this).hmdUrl = url }
            },
        )
        hmd = HmdRenderer(
            ble = ble,
            onAckFailed = { seq ->
                engine.send(Event.HmdLost(gapMs = 0))
                Log.w(TAG, "HMD ACK 실패 seq=$seq → OFFLINE 전이 요청 · BLE 재연결")
                ble.reset()   // 붙으면 onReady 가 현재 화면을 다시 보낸다
            },
        )
        engine = InspectionEngine(
            sinks = sinks,
            stateScope = stateScope,
            effectScope = effectScope,
        )
        engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startShift(
                line = intent.getStringExtra(EXTRA_LINE) ?: "A",
                partNo = intent.getStringExtra(EXTRA_PART_NO) ?: "",
                hmdUrl = intent.getStringExtra(EXTRA_HMD_URL) ?: "",
                edgeUrl = intent.getStringExtra(EXTRA_EDGE_URL) ?: "",
            )
            ACTION_STOP -> closeShift()
        }
        return START_STICKY   // 죽어도 안드로이드가 다시 띄운다
    }

    /** 교대 시작 — 상태기계를 RUNNING 으로, HMD 영상 → 엣지 중계를 시작한다. */
    private fun startShift(line: String, partNo: String, hmdUrl: String, edgeUrl: String) {
        if (relayJob != null) return
        // 저장된 값 우선 — 지난 교대에 BLE HELLO 로 받은 주소가 들어 있다(화면 입력칸은 옛 값일 수 있음).
        // 그래도 틀리면 BLE 가 붙는 즉시 HELLO 로 다시 맞춘다.
        this.hmdUrl = DevSettings(this).hmdUrl.ifBlank { hmdUrl }
        engine.send(Event.ShiftStarted(line, partNo, Profile(autoDismiss = DevSettings(this).autoDismiss)))
        val sessionId = "S-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-$line"
        val toEngine: (Event) -> Unit = { e -> Log.i(TAG, "엣지 → 엔진: $e"); engine.send(e) }
        val settings = DevSettings(this)
        // 엣지 종류에 따라 모의 엣지(WebSocket 중계) 또는 실제 AI 서버(WebRTC, 설계서 §9)
        val offer: (Long, Long, ByteArray, String, Boolean) -> Unit
        val edgeRun: suspend () -> Unit
        val edgeStatus: StateFlow<StreamRelay.Status>
        if (settings.edgeKind == DevSettings.EDGE_AI) {
            val ai = AiEdgeLink(this, settings.aiUrl, settings.stationId, toEngine, onHint = { text, tone ->
                // HMD 조준 화면 진행 안내 — 화면 지시(SHOW)와 달리 순번·ACK 없이 최신 것만 보낸다
                effectScope.launch { runCatching { ble.write(HintWire.encode(text, tone)) } }
            })
            offer = ai::offer; edgeRun = ai::run; edgeStatus = ai.status
            Log.i(TAG, "교대 시작 $sessionId · HMD ${this.hmdUrl} → AI 서버 ${settings.aiUrl} (${settings.stationId})")
        } else {
            val relay = StreamRelay(transport = WebSocketTransport(), emit = toEngine)
            offer = relay::offer
            edgeRun = { relay.run(edgeUrl, Hello(sessionId = sessionId, line = line, partNo = partNo, viewTotal = Profile().viewCount)) }
            edgeStatus = relay.status
            Log.i(TAG, "교대 시작 $sessionId · HMD ${this.hmdUrl} → 모의 엣지 $edgeUrl")
        }
        relayJob = ioScope.launch {
            launch { edgeRun() }
            launch {
                HmdVideoSource({ this@InspectionService.hmdUrl }, { seq, at, data, codec, key ->
                    offer(seq, at, data, codec, key)
                    liveFrame.value = LiveFrame(seq, data, codec)     // M-07 영상 화면용 — 마지막 1장만
                }, onConnected = { on ->
                    hmdVideo.value = on
                    if (!on) ble.refreshVideoUrl()                    // 끊기면 HMD IP 가 바뀌었는지 BLE 로 다시 묻는다
                }).run()
            }
            launch { ble.run() }
            launch { ble.state.collect { hmdBle.value = it } }
            edgeStatus.collect { relayStatus.value = it }
        }
        running.value = true
    }

    /**
     * BLE 가 (다시) 붙었을 때 — 그 사이 보낸 지시는 못 갔으므로 지금 화면을 다시 보낸다.
     * 판정 화면 중에 끊겼다면 조준 화면으로 돌아가지만, 판정은 상태기계·폰 진동에 이미 남아 있다.
     */
    private fun resendCurrentScreen() {
        val s = engine.currentState
        val cmd = if (s.mode == Mode.RUNNING || s.mode == Mode.DEGRADED || s.mode == Mode.OFFLINE)
            Screens.guide(s.partNo, s.doneCount, s.profile) else Screens.idle()
        effectScope.launch { sinks.show(cmd) }
    }

    /** 교대 마감(R20) — 중계를 멈추고, 상태기계가 마감 효과를 처리할 틈을 준 뒤 서비스 종료. */
    private fun closeShift() {
        engine.send(Event.ShiftClosed)
        running.value = false
        relayStatus.value = null
        hmdVideo.value = false
        hmdBle.value = "-"
        liveFrame.value = null
        val job = relayJob
        relayJob = null
        effectScope.launch {
            delay(500)          // 마감 화면(대기)이 BLE 로 HMD 에 닿은 뒤 끊는다 — 먼저 끊으면 HMD 가 장애(H-09)로 본다
            job?.cancel()
            stopSelf()
        }
    }

    /** 상태 기계 출력을 실물 부품에 연결. 순서가 중요한 것(persist·enqueue)은 엔진이 순차로 부른다. */
    private val sinks = object : EffectSinks {
        override suspend fun show(cmd: ShowCommand) {
            // BLE 가 아직 안 붙었으면 쓰기가 예외를 던진다 — 앱을 죽이지 않고 남긴다(붙으면 resendCurrentScreen)
            try { hmd.show(cmd) } catch (e: Exception) { Log.w(TAG, "SHOW ${cmd.screen} 못 보냄: ${e.message}") }
        }
        override suspend fun persist(record: InspectionRecord) {
            store.record(record)
        }
        override suspend fun enqueue(payload: ByteArray) {
            store.enqueue(payload)
        }
        override fun vibrate(times: Int) {
            this@InspectionService.vibrate(times)
        }
        override suspend fun notifyLead(alert: String) {
            store.enqueue(alert.toByteArray(), kind = LocalStore.KIND_ALERT)
        }
        override suspend fun snapshot(state: AppState) {
            updateNotification(state)
        }
    }

    /** 판정 촉각 피드백. 색·아이콘과 함께 3중 부호화를 이룬다(O-40: 520 에 햅틱 없음). */
    private fun vibrate(times: Int) {
        if (times <= 0) return
        val pattern = LongArray(times * 2) { if (it % 2 == 0) 80L else 120L }
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        val effect = VibrationEffect.createWaveform(pattern, -1)
        // 미디어 진동 세기에 묶이지 않게 경보 용도로 — 판정 알림은 작업자 안전 신호다
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TacitQ:inspection").apply {
            setReferenceCounted(false); acquire()
        }
        // 화면이 꺼져도 Wi-Fi 성능을 낮추지 않는다 — 스트림 끊김의 흔한 원인
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TacitQ:wifi").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_ID, "검사 진행", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTI_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else
            @Suppress("DEPRECATION") startForeground(NOTI_ID, notification(text))
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)                    // "A라인 · 247건 · 정상"
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

    /** 상태가 바뀔 때마다 알림 문구 갱신 — 작업자가 주머니에서 꺼내지 않고 확인. */
    private fun updateNotification(s: AppState) {
        val text = when (s.mode) {
            Mode.RUNNING -> "${s.line}라인 · ${s.doneCount}건 · 정상"
            Mode.DEGRADED -> "${s.line}라인 · 서버 지연 — 검사 계속"
            Mode.OFFLINE -> "연결 끊김 — 육안 검사 모드"
            Mode.LINE_STOPPED -> "라인 정지 중"
            else -> "검사 대기 중"
        }
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(text))
    }

    override fun onDestroy() {
        relayJob?.cancel()
        running.value = false
        relayStatus.value = null
        liveFrame.value = null
        engine.close()
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        job.cancel()
        stateDispatcher.close()
        super.onDestroy()
    }


    companion object {
        private const val TAG = "TacitQ.Service"
        private const val NOTI_ID = 1001
        private const val CH_ID = "inspection"
        private const val ACTION_START = "kr.co.snail.tacitq.mobile.START_SHIFT"
        private const val ACTION_STOP = "kr.co.snail.tacitq.mobile.CLOSE_SHIFT"
        private const val EXTRA_LINE = "line"
        private const val EXTRA_PART_NO = "part_no"
        private const val EXTRA_HMD_URL = "hmd_url"
        private const val EXTRA_EDGE_URL = "edge_url"

        /** 화면(M-01) 표시용 — 프로세스 안에서 공유하는 가장 단순한 방법 */
        val running = MutableStateFlow(false)
        val relayStatus = MutableStateFlow<StreamRelay.Status?>(null)
        val hmdVideo = MutableStateFlow(false)
        val hmdBle = MutableStateFlow("-")
        /** HMD 에서 받은 마지막 프레임 — 영상 화면(M-07)이 켜져 있을 때만 그림으로 바꾼다 */
        val liveFrame = MutableStateFlow<LiveFrame?>(null)

        class LiveFrame(val hmdSeq: Long, val data: ByteArray, val codec: String)

        fun start(ctx: Context, line: String, partNo: String, hmdUrl: String, edgeUrl: String) {
            ctx.startForegroundService(
                Intent(ctx, InspectionService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_LINE, line).putExtra(EXTRA_PART_NO, partNo)
                    .putExtra(EXTRA_HMD_URL, hmdUrl).putExtra(EXTRA_EDGE_URL, edgeUrl),
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, InspectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
