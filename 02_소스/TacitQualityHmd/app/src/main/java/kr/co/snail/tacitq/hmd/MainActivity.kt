package kr.co.snail.tacitq.hmd

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kr.co.snail.tacitq.hmd.databinding.ActivityMainBinding
import kr.co.snail.tacitq.hmd.hud.HudController
import kr.co.snail.tacitq.hmd.link.BleServer
import kr.co.snail.tacitq.hmd.protocol.Wire
import kr.co.snail.tacitq.hmd.video.Camera2Source
import kr.co.snail.tacitq.hmd.video.HevcEncoder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Base64
import java.util.concurrent.Executors

/**
 * HMD 앱 — 촬영·송출(1단계) + BLE 쪽지 수신·HUD 표시(2단계).
 *
 * 영상: CameraX ImageAnalysis(프레임) → JPEG 변환 → [MjpegServer] → 폰 브라우저 (`http://<IP>:8080`).
 * 제어: [BleServer] DOWN 쓰기 → [HudController] 순번 검사·화면·ACK → BleServer UP notify.
 * 디버그 빌드는 BLE 없이 adb 브로드캐스트로 쪽지를 넣을 수 있다(README 참고).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    /** "hevc"(기본, 설계서 §8 E2) | "mjpeg"(예비) — adb: am start ... --es video mjpeg */
    private val videoMode by lazy { intent.getStringExtra(EXTRA_VIDEO) ?: BuildConfig.VIDEO_MODE }
    /** MJPEG 해상도 — 기본 1080p(실제 AI 서버용). adb: am start ... --es res 720 */
    private val mjpegSize by lazy { if (intent.getStringExtra(EXTRA_RES) == "720") Size(1280, 720) else Size(1920, 1080) }
    private lateinit var server: MjpegServer
    private var encoder: HevcEncoder? = null
    private var camera2: Camera2Source? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var hud: HudController
    private lateinit var ble: BleServer
    private var bleState: BleServer.State? = null
    private var bleLabel = "-"

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else binding.statusText.text = "카메라 권한이 필요합니다"
    }

    private val requestBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) ble.start() else showBleLabel("BLE 권한 없음")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 화면이 꺼지면 RealWear 는 카메라도 멈춘다(영상 끊김) — 앱이 떠 있는 동안 화면을 켜 둔다.
        // (매니페스트 activity 의 keepScreenOn 은 효과가 없다 — View/Window 속성이다)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 대기 중 화면이 꺼져 있어도(10분 자동 꺼짐) 폰 검사 시작으로 앞으로 나올 때 화면을 켠다 — 꺼진 화면에선 카메라가 안 열림
        setTurnScreenOn(true)
        setShowWhenLocked(true)

        // 구형(legacy) 카메라 HAL 은 Preview 표면이 있어야 시작된다. RealWear 에서 SurfaceView 표면이
        // 생성되지 않으므로 TextureView(COMPATIBLE) 로 표면을 확실히 공급한다.
        binding.preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        server = if (videoMode == "hevc") MjpegServer(PORT, "video/hevc") else MjpegServer(PORT)
        server.start()
        val background = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA)
        } else if (background && videoMode != "hevc") {
            cameraNeedsRebind = true        // 대기 실행 — 카메라는 앞으로 나올 때(onResume) 연다
        } else {
            startCamera()
        }

        // 폰 쪽지(1초 PING·SHOW)가 오는 동안 = 검사 중 → 뒤에 있으면 앞으로(연결 순서와 상관없이 1초 안에)
        ble = BleServer(this, onDown = { bringToFront(); hud.onDown(it) }, onState = ::onBleState,
                        hello = { Wire.hello(deviceIp()?.let { "http://$it:$PORT" }, cameraState()) })
        hud = HudController(binding, sendUp = ble::notify)
        startBle()
        if (BuildConfig.DEBUG) registerDebugInjector()

        refreshStatus()
        // 부팅 자동 실행이면 화면은 비켜 두고 BLE 만 켠 채 기다린다 — 폰이 검사 시작하면 앞으로 나온다
        if (background) moveTaskToBack(true)
    }

    private fun startCamera() {
        if (videoMode == "hevc") startHevc() else startMjpeg()
    }

    /**
     * HMD 버튼 2개(볼륨 업·다운 신호로 들어옴, 실장비 2026-10-18 확인) — 판정 화면에서 누르면 바로 카메라로.
     * 이 앱이 앞에 있을 때는 볼륨을 바꾸지 않는다(다른 앱에선 원래대로).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i(TAG, "버튼 ${android.view.KeyEvent.keyCodeToString(code)} → 카메라로")
                hud.toCamera()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 이미 떠 있는데 다시 실행됨(홈에서 "품질검사" · 부팅 대기 실행 · 폰 신호) — singleTask 라 새 화면을 만들지 않고 여기로 온다.
     * 화면이 두 개 뜨면 영상 포트(8080)·BLE 서버가 겹쳐 폰 연동이 깨졌다(실장비 2026-10-18).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "다시 실행됨 — 기존 화면 사용")
    }

    /** 뒤로 가기 = 끄지 않고 뒤로만 — 꺼지면 BLE 도 꺼져 폰이 다시 붙지 못한다 */
    @Deprecated("Android 13 미만(RealWear 11) 뒤로 가기")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    /** 뒤에 갔다 온 적이 있으면 앞으로 나올 때 카메라를 새로 묶는다 */
    private var cameraNeedsRebind = false
    @Volatile private var analysisSizeLogged = false
    /** 카메라 바인딩 실패 사유 — 상태줄 맨 앞에 띄운다(영상이 안 나가는 원인) */
    @Volatile private var cameraError: String? = null
    @Volatile private var foreground = false             // 카메라는 앱이 화면 맨 앞에 있을 때만 돈다
    @Volatile private var lastFrameAt = 0L
    @Volatile private var analysisSize = ""

    /**
     * 카메라 상태 한 줄 — HMD 상태줄과 BLE HELLO(폰 통신 로그)에 띄운다. 폰이 영상 서버에 붙어도 프레임이 안 오면
     * 원인이 여기 나온다. BLE 스레드에서도 부른다.
     */
    private fun cameraState(): String {
        cameraError?.let { return "시작 실패 — $it" }
        if (videoMode == "hevc") return "H.265 모드"
        if (!foreground) return "꺼짐 — HMD 앱이 화면 뒤에 있음(앱이 앞에 있어야 카메라가 켜짐)" +
            if (!android.provider.Settings.canDrawOverlays(this)) " · '다른 앱 위에 표시' 권한 없음(폰이 앞으로 불러오지 못함)" else ""
        val at = lastFrameAt
        if (at == 0L) return "켜는 중 — 프레임 아직 없음"
        val age = System.currentTimeMillis() - at
        return if (age > 2_000) "멈춤 — 마지막 프레임 ${age / 1000}초 전" else "정상 $analysisSize"
    }
    private var lastBringAt = 0L

    override fun onStop() {
        super.onStop()
        // 구형 카메라 HAL 은 뒤 → 앞 전환 때 CameraX 의 자동 닫기·다시 열기가 겹치면 OPENING 에서 멈춘다(실장비 2026-10-13).
        // 뒤로 갈 때 카메라를 확실히 놓고, 앞으로 나올 때 잠깐 뒤 새로 묶는다. MJPEG 만 — HEVC(Camera2)는 해당 없음.
        if (videoMode != "hevc") {
            runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
            cameraNeedsRebind = true
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (cameraNeedsRebind &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            cameraNeedsRebind = false
            Log.i(TAG, "앞으로 복귀 — 카메라 다시 묶기")
            ui.postDelayed({ if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) startMjpeg() }, 400)
        }
    }

    /** E2 — Camera2 1080p → 하드웨어 H.265 → 프레임 서버. 미리보기는 TextureView. */
    private fun startHevc() {
        binding.preview.visibility = View.GONE
        binding.cameraTexture.visibility = View.VISIBLE
        val enc = try {
            HevcEncoder(VIDEO_W, VIDEO_H, VIDEO_FPS, VIDEO_BITRATE) { data, at, key -> server.updateFrame(data, at, key) }
        } catch (e: Exception) {
            return fallbackToMjpeg("HEVC 인코더 생성 실패: ${e.message}")
        }
        encoder = enc
        server.onClientJoined = { enc.requestKeyFrame() }
        camera2 = Camera2Source(this, binding.cameraTexture, enc.inputSurface, VIDEO_W, VIDEO_H, VIDEO_FPS) { msg ->
            runOnUiThread { fallbackToMjpeg(msg) }
        }.also { it.start() }
    }

    /** HEVC 경로가 안 되면 예비(MJPEG)로 다시 띄운다 — 영상이 끊기는 것보다 낫다. */
    private fun fallbackToMjpeg(reason: String) {
        Log.e(TAG, "$reason → MJPEG 예비 모드로 전환")
        if (videoMode == "mjpeg") return
        startActivity(Intent(intent).putExtra(EXTRA_VIDEO, "mjpeg"))
        finish()
    }

    private fun startMjpeg() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            // Preview: 구형 카메라 HAL 시작에 필요. TextureView 표면을 공급한다.
            // 비율은 기본값 그대로 — 4:3 미리보기가 16:9 화면을 채우면(FILL_CENTER) 가운데 16:9 띠만 보여
            // 16:9 분석 영상(1080p)과 화각이 같다. 결함 원(0~1000 좌표)은 그대로 맞는다.
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }

            // ImageAnalysis: 프레임 → JPEG → MJPEG 송출
            val analysis = ImageAnalysis.Builder()
                // 2026-10-07: 640×480 → 720p. 2026-10-10: 1080p. 2026-11-03 규격: 결함을 640px 타일로 보므로 1080p 이상 권장(API §5.5)
                .setTargetResolution(mjpegSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

            val selector = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                "BACK"
            }.recoverCatching { back ->
                Log.w(TAG, "후면 카메라 바인딩 실패: ${back.message}")
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                "FRONT"
            }.getOrElse { e ->
                // 영상이 안 나가는 원인을 HMD 화면에서 바로 보이게 — 앱을 죽이지 않는다
                Log.e(TAG, "카메라 바인딩 실패 — 영상 송출 불가", e)
                cameraError = e.message ?: e.javaClass.simpleName
                return@addListener
            }
            Log.i(TAG, "카메라 바인딩 완료 (Preview+Analysis, $selector)")
            cameraError = null
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: androidx.camera.core.ImageProxy) {
        val capturedAt = System.currentTimeMillis()      // JPEG 변환 전 시각 = 촬영 시각에 가장 가깝다
        if (!analysisSizeLogged) { analysisSizeLogged = true; analysisSize = "${image.width}×${image.height}"; Log.i(TAG, "분석 영상 $analysisSize") }
        lastFrameAt = capturedAt
        try {
            server.updateFrame(YuvToJpeg.convert(image), capturedAt)
        } catch (e: Exception) {
            Log.w(TAG, "프레임 변환 실패: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun startBle() {
        val needed = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else emptyList()
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ble.start() else requestBle.launch(missing.toTypedArray())
    }

    private fun onBleState(state: BleServer.State) {
        val was = bleState
        bleState = state
        if (state == BleServer.State.CONNECTED) {
            hud.onLinkConnected()
            bringToFront()                 // 폰에서 검사 시작 → BLE 연결 → 앱이 앞으로 나와 카메라를 켠다
        } else if (was == BleServer.State.CONNECTED) hud.onLinkDisconnected()
        showBleLabel(state.label)
    }

    /**
     * 뒤에 있으면 화면 맨 앞으로. 카메라는 화면에 보일 때만 돈다(생명주기 연결) — 뒤에 있으면 영상이 끊긴다.
     * Android 10+ 는 뒤에서 앞으로 나오기를 막으므로 "다른 앱 위에 표시" 허용이 있어야 동작한다.
     */
    private fun bringToFront() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val now = System.currentTimeMillis()
        if (now - lastBringAt < 3_000) return            // PING 마다 부르므로 3초에 한 번만
        lastBringAt = now
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "앞으로 나오기 불가 — '다른 앱 위에 표시' 허용 필요")
            return
        }
        Log.i(TAG, "폰 연결 → 앱을 앞으로")
        // 화면이 꺼져 있으면 먼저 깨운다(3초 뒤 놓음 — 그 뒤는 앱의 화면 켜짐 유지가 맡는다)
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isInteractive) {
            Log.i(TAG, "화면 꺼짐 → 켜기")
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TacitQ:wake",
            ).acquire(3_000)
        }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun showBleLabel(label: String) {
        bleLabel = label
        hud.onLinkState(label)
    }

    /**
     * 디버그 전용 — BLE 없이 쪽지를 넣는다. JSON 을 base64 로 감싸 셸 따옴표 문제를 피한다.
     * `adb shell am broadcast -a kr.co.snail.tacitq.hmd.SHOW --es b64 <base64>`
     */
    private val debugInjector = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bytes = intent.getStringExtra("b64")?.let { Base64.getDecoder().decode(it) }
                ?: intent.getStringExtra("json")?.encodeToByteArray()
                ?: return
            hud.onDown(bytes)
        }
    }

    private fun registerDebugInjector() {
        ContextCompat.registerReceiver(
            this, debugInjector, IntentFilter(ACTION_INJECT), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** 하단 상태줄: 영상 주소 · 뷰어 수 · BLE 상태 · 마지막 쪽지. 1초마다 갱신. */
    private fun refreshStatus() {
        val ip = deviceIp() ?: "IP 없음(Wi-Fi 확인)"
        val cam = cameraState()
        binding.statusText.text = (if (cam.startsWith("정상")) "" else "⚠ 카메라 $cam · ") +
            "영상 ${if (videoMode == "hevc") "H.265 1080p" else "MJPEG ${mjpegSize.height}p"} http://$ip:$PORT (뷰어 ${server.viewerCount}) · $bleLabel · ${hud.lastInfo}"
        ui.postDelayed(::refreshStatus, 1000)
    }

    /** wlan0 등 사설 IPv4 주소를 찾는다. */
    private fun deviceIp(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (BuildConfig.DEBUG) unregisterReceiver(debugInjector)
        hud.stop()
        ble.stop()
        camera2?.stop()
        encoder?.stop()
        analysisExecutor.shutdown()
        server.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TacitQ.Hmd"
        private const val PORT = 8080
        private const val ACTION_INJECT = "kr.co.snail.tacitq.hmd.SHOW"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_RES = "res"
        const val EXTRA_BACKGROUND = "background"
        private const val VIDEO_W = 1920
        private const val VIDEO_H = 1080
        private const val VIDEO_FPS = 30
        private const val VIDEO_BITRATE = 8_000_000
    }
}
