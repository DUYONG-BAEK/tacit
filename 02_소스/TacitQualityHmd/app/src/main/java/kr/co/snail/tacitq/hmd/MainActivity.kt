package kr.co.snail.tacitq.hmd

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kr.co.snail.tacitq.hmd.databinding.ActivityMainBinding
import kr.co.snail.tacitq.hmd.hud.HudController
import kr.co.snail.tacitq.hmd.link.BleServer
import kr.co.snail.tacitq.hmd.link.LinkService
import kr.co.snail.tacitq.hmd.video.Camera2Source
import kr.co.snail.tacitq.hmd.video.HevcEncoder
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
    /** "hevc"(기본, 설계서 §4 E2) | "mjpeg"(예비) — adb: am start ... --es video mjpeg */
    private val videoMode by lazy { intent.getStringExtra(EXTRA_VIDEO) ?: BuildConfig.VIDEO_MODE }
    /**
     * MJPEG 해상도 — 4:3(센서 비율). 2026-11-05: 16:9(1920×1080)는 4:3 센서의 위아래를 잘라 기본 카메라 앱보다
     * 약 1.33배 확대돼 보였다. 4:3 이면 센서 화각 전체를 쓴다. 실제 크기는 카메라가 고른다(상태줄·로그에 표시).
     * 2026-11-10: 1920×1440 → 1440×1080 (4:3 1080p, 서버 권장 1080p 이상 유지) — 품질 80·초당 10장과 함께 HMD 영상량을
     * 약 27Mbps → 약 8Mbps(추정)로 줄인다. 같은 Wi-Fi 에서 1920×1440 품질 90 초당 12장일 때 폰 → 서버 패킷 손실 27% 실측.
     * adb: am start ... --es res 720 → 960×720 (진단용)
     */
    private val mjpegSize by lazy { if (intent.getStringExtra(EXTRA_RES) == "720") Size(960, 720) else Size(1440, 1080) }
    /**
     * 카메라 자동 노출 장수 범위 고정값 — 2026-11-10 dumpsys: 고정 범위는 [15,15]·[24,24]·[30,30] 뿐이고, 정하지 않으면
     * 어두울 때 [10,30] 안에서 초당 10장까지 내려가 송출 12장을 못 채운다. 30 이면 노출이 33ms 이하로 짧아 흔들림도 준다.
     * 너무 어두우면 adb: am start ... --ei fps 24
     */
    private val aeFps by lazy { intent.getIntExtra(EXTRA_FPS, 30).takeIf { it in AE_FIXED_FPS } ?: 30 }
    /** 5초 요약(센서·송출 장수, NV21·JPEG 시간) — logcat TacitQ.Hmd 와 상태줄 */
    private val stats = FrameStats(System.currentTimeMillis())
    @Volatile private var statsShort = ""
    private val sensorCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ae = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
            stats.onSensor(result.get(CaptureResult.SENSOR_EXPOSURE_TIME), result.get(CaptureResult.SENSOR_SENSITIVITY), ae?.lower, ae?.upper)
        }
    }
    private lateinit var server: MjpegServer
    private var encoder: HevcEncoder? = null
    private var camera2: Camera2Source? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var hud: HudController
    private var bleState: BleServer.State? = null
    private var bleLabel = "-"

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else binding.statusText.text = "카메라 권한이 필요합니다"
        askOverlayIfMissing()
    }

    private val requestBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) LinkService.start(this) else showBleLabel("BLE 권한 없음")
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

        // RealWear 520 카메라는 Preview 표면이 있어야 시작되고(실장비), SurfaceView 표면이
        // 생성되지 않으므로 TextureView(COMPATIBLE) 로 표면을 확실히 공급한다.
        // (HAL 등급은 구형이 아니라 LEVEL_3 — 2026-11-10 dumpsys media.camera. 위 동작은 실장비에서 본 그대로)
        binding.preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        // 4:3 미리보기를 자르지 않고 16:9 화면 가운데에(좌우 검은 띠) — 기본 카메라 앱과 같은 화각.
        // FILL_CENTER(기본값)는 위아래를 잘라 확대돼 보인다. 결함 원은 MarkerView 가 같은 맞춤으로 그린다.
        binding.preview.scaleType = PreviewView.ScaleType.FIT_CENTER

        server = if (videoMode == "hevc") MjpegServer(PORT, "video/hevc") else MjpegServer(PORT)
        server.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
            askOverlayIfMissing()
        }

        // BLE 는 대기 서비스(LinkService)가 늘 켜 둔다 — 폰 쪽지가 오면 서비스가 이 화면을 앞으로 부르고 쪽지를 넘긴다
        hud = HudController(binding, sendUp = { LinkService.notifyUp(it) })
        startLink()
        LinkService.attach(linkUi)
        if (BuildConfig.DEBUG) registerDebugInjector()

        refreshStatus()
    }

    /** 대기 서비스와 잇는 쪽 — 쪽지·BLE 상태를 받고 카메라 상태(HELLO)를 알려 준다 */
    private val linkUi = object : LinkService.Ui {
        override fun onDown(bytes: ByteArray) = hud.onDown(bytes)
        override fun onLinkState(state: BleServer.State) = onBleState(state)
        override val isInFront: Boolean
            get() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        override fun cameraState(): String = this@MainActivity.cameraState()
    }

    /**
     * '다른 앱 위에 표시'가 꺼져 있으면 이 앱의 설정 화면을 연다 — 꺼져 있으면 폰이 이 화면을 띄우지 못한다.
     * 보통은 HMD_설치.bat 이 켜 준다(adb 가 없을 때의 비상용).
     */
    private fun askOverlayIfMissing() {
        if (Settings.canDrawOverlays(this)) return
        Log.w(TAG, "'다른 앱 위에 표시' 꺼짐 → 설정 화면 열기")
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { Log.w(TAG, "설정 화면을 열 수 없음: ${it.message}") }
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

    /** 뒤로 가기 = 끄지 않고 뒤로만 — 폰이 다시 부를 때 빨리 뜬다(BLE 는 대기 서비스가 따로 켜 둔다) */
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
    private var nextSendAt = 0L                    // MJPEG 다음 송출 예정 시각 (분석 스레드에서만)
    @Volatile private var analysisSize = ""
    @Volatile private var cameraBound = false
    @Volatile private var cameraOpen = ""                 // CameraX 카메라 상태(열기 대기 · 여는 중 · 열림 …)와 오류

    private fun describe(s: CameraState): String {
        val type = when (s.type) {
            CameraState.Type.PENDING_OPEN -> "열기 대기(다른 앱이 카메라를 쓰는 중일 수 있음)"
            CameraState.Type.OPENING -> "여는 중"
            CameraState.Type.OPEN -> "열림"
            CameraState.Type.CLOSING -> "닫는 중"
            CameraState.Type.CLOSED -> "닫힘"
        }
        val err = s.error?.let {
            when (it.code) {
                CameraState.ERROR_CAMERA_IN_USE -> "다른 앱이 카메라 사용 중"
                CameraState.ERROR_MAX_CAMERAS_IN_USE -> "카메라 사용 수 초과(다른 앱이 사용 중)"
                CameraState.ERROR_CAMERA_DISABLED -> "카메라 사용 금지(기기 정책)"
                CameraState.ERROR_CAMERA_FATAL_ERROR -> "카메라 치명 오류(HMD 재부팅 필요)"
                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "방해 금지 모드"
                CameraState.ERROR_STREAM_CONFIG -> "스트림 설정 실패(미리보기·분석 해상도 조합)"
                CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "일시 오류(다시 시도 중)"
                else -> "오류 코드 ${it.code}"
            }
        }
        return "카메라 $type" + (err?.let { " · $it" } ?: "")
    }

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
        if (!cameraBound) return "켜는 중 — 카메라 바인딩 전"
        if (at == 0L) return "켜는 중 — 프레임 아직 없음 · ${cameraOpen.ifEmpty { "카메라 상태 모름" }}"
        val age = System.currentTimeMillis() - at
        return if (age > 2_000) "멈춤 — 마지막 프레임 ${age / 1000}초 전" else "정상 $analysisSize"
    }

    override fun onStop() {
        super.onStop()
        // RealWear 520 은 뒤 → 앞 전환 때 CameraX 의 자동 닫기·다시 열기가 겹치면 OPENING 에서 멈춘다(실장비 2026-10-13).
        // 뒤로 갈 때 카메라를 확실히 놓고, 앞으로 나올 때 잠깐 뒤 새로 묶는다. MJPEG 만 — HEVC(Camera2)는 해당 없음.
        if (videoMode != "hevc") {
            runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
            cameraNeedsRebind = true
            cameraBound = false; cameraOpen = ""; lastFrameAt = 0L
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

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startMjpeg() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            // Preview: RealWear 520 카메라 시작에 필요(실장비). TextureView 표면을 공급한다.
            // 비율은 기본값(4:3) 그대로 — 분석 영상도 4:3 이라 둘 다 센서 화각 전체다.
            // 촬영 요청 전체에 장수 범위 고정 + 센서가 찍은 장마다 콜백(측정) — Camera2 설정은 한 곳(여기)에서만 건다
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(aeFps, aeFps))
                .setSessionCaptureCallback(sensorCallback)
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }

            // ImageAnalysis: 프레임 → JPEG → MJPEG 송출
            val analysis = ImageAnalysis.Builder()
                // 2026-10-07: 640×480 → 720p. 2026-10-10: 1080p. 2026-11-03 규격: 결함을 640px 타일로 보므로 1080p 이상 권장(API §5.5)
                // 2026-11-05: 4:3 1920×1440 — 화각을 기본 카메라 앱과 맞추고 가로 화소는 1080p 와 같게
                // 2026-11-10: 4:3 1440×1080 — 화각 그대로, 영상량을 줄인다([mjpegSize])
                .setTargetResolution(mjpegSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::onFrame) }

            val (camera, selector) = runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) to "BACK"
            }.recoverCatching { back ->
                Log.w(TAG, "후면 카메라 바인딩 실패: ${back.message}")
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis) to "FRONT"
            }.getOrElse { e ->
                // 영상이 안 나가는 원인을 HMD 화면에서 바로 보이게 — 앱을 죽이지 않는다
                Log.e(TAG, "카메라 바인딩 실패 — 영상 송출 불가", e)
                cameraError = e.message ?: e.javaClass.simpleName
                return@addListener
            }
            Log.i(TAG, "카메라 바인딩 완료 (Preview+Analysis, $selector, AE [$aeFps, $aeFps])")
            cameraError = null
            cameraBound = true
            // 확대 없이 가장 넓게 — 기본 카메라 앱과 같은 배율
            camera.cameraControl.setLinearZoom(0f)
            camera.cameraInfo.zoomState.value?.let { Log.i(TAG, "줌 ${it.zoomRatio}배 (최소 ${it.minZoomRatio} · 최대 ${it.maxZoomRatio})") }
            // 열기 상태·오류(다른 앱이 사용 중 등) — 바인딩은 됐는데 프레임이 안 올 때 원인
            camera.cameraInfo.cameraState.removeObservers(this)
            camera.cameraInfo.cameraState.observe(this) { st ->
                cameraOpen = describe(st)
                Log.i(TAG, "카메라 상태 $cameraOpen")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(image: androidx.camera.core.ImageProxy) {
        val capturedAt = System.currentTimeMillis()      // JPEG 변환 전 시각 = 촬영 시각에 가장 가깝다
        if (!analysisSizeLogged) {
            analysisSizeLogged = true; analysisSize = "${image.width}×${image.height}"; Log.i(TAG, "분석 영상 $analysisSize")
            val aspect = image.width.toFloat() / image.height
            ui.post { binding.marks.contentAspect = aspect }      // 결함 원 — 미리보기와 같은 비율로 맞춤
        }
        lastFrameAt = capturedAt
        stats.onAnalyzed()
        // 초당 MJPEG_FPS 장만 JPEG 로 만든다(HMD → 폰 영상량을 아낀다 — 폰은 받는 대로 서버로 보내므로 이 장수가 서버 도착 상한).
        // 예정 시각 방식에 반 간격 허용 — 도착 간격이 조금 흔들려도 다음 장을 버리지 않아 평균이 MJPEG_FPS 가 된다.
        if (capturedAt < nextSendAt - MJPEG_FRAME_MS / 2) { image.close(); return }
        nextSendAt = maxOf(nextSendAt + MJPEG_FRAME_MS, capturedAt - MJPEG_FRAME_MS / 2)
        try {
            val t0 = System.nanoTime()
            val nv21 = YuvToJpeg.toNv21(image)
            val t1 = System.nanoTime()
            val jpeg = YuvToJpeg.compress(nv21, image.width, image.height, MJPEG_QUALITY)
            val t2 = System.nanoTime()
            server.updateFrame(jpeg, capturedAt)
            stats.onSent(t1 - t0, t2 - t1, jpeg.size)
        } catch (e: Exception) {
            Log.w(TAG, "프레임 변환 실패: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** 대기 서비스 시작 — BLE 권한(API 31+)을 먼저 받는다. 이미 돌고 있으면 아무 일 없음. */
    private fun startLink() {
        val missing = LinkService.BLE_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) LinkService.start(this) else requestBle.launch(missing.toTypedArray())
    }

    private fun onBleState(state: BleServer.State) {
        val was = bleState
        bleState = state
        if (state == BleServer.State.CONNECTED) {
            hud.onLinkConnected()          // 앞으로 부르기는 대기 서비스가 한다
        } else if (was == BleServer.State.CONNECTED) hud.onLinkDisconnected()
        showBleLabel(state.label)
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
        binding.statusText.text = (if (Settings.canDrawOverlays(this)) "" else "⚠ '다른 앱 위에 표시' 꺼짐 — 폰이 화면을 못 띄움 · ") +
            (if (cam.startsWith("정상")) "" else "⚠ 카메라 $cam · ") +
            "영상 ${if (videoMode == "hevc") "H.265 1080p" else "MJPEG ${analysisSize.ifEmpty { "${mjpegSize.width}×${mjpegSize.height}" }}"} http://$ip:$PORT (뷰어 ${server.viewerCount}) · $bleLabel · ${hud.lastInfo}" +
            (if (statsShort.isEmpty()) "" else " · $statsShort")
        stats.take(System.currentTimeMillis())?.let { s ->
            statsShort = if (s.active) s.short() else ""
            if (s.active) Log.i(TAG, s.line())
        }
        ui.postDelayed(::refreshStatus, 1000)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (BuildConfig.DEBUG) unregisterReceiver(debugInjector)
        hud.stop()
        LinkService.detach(linkUi)
        camera2?.stop()
        encoder?.stop()
        analysisExecutor.shutdown()
        server.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TacitQ.Hmd"
        const val PORT = 8080
        private const val ACTION_INJECT = "kr.co.snail.tacitq.hmd.SHOW"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_RES = "res"
        private const val EXTRA_FPS = "fps"
        /** 카메라가 고정으로 받는 장수 범위 (2026-11-10 dumpsys aeAvailableTargetFpsRanges) — 12 이상만 */
        private val AE_FIXED_FPS = setOf(15, 24, 30)
        private const val VIDEO_W = 1920
        private const val VIDEO_H = 1080
        private const val VIDEO_FPS = 30
        private const val VIDEO_BITRATE = 8_000_000
        // 2026-11-06: JPEG 품질 80 → 95, 송출 초당 12장 (server/PLAN_product_feedback.md §7.4, 출발값)
        // 2026-11-10: 품질 95 → 90 — 실측 송출이 초당 약 7장(목표 12)이라 압축 시간·크기를 줄인다
        // 2026-11-10: 1440×1080 · 품질 80 · 초당 10장 — 영상량을 줄인다([mjpegSize]). 서버가 보는 화질은 폰 VP8 이 정하므로
        //   품질 80 은 서버 쪽 화질에 거의 영향이 없다. WebRTC 규격은 장수를 정하지 않는다(A4_WEBRTC_API.md §5)
        private const val MJPEG_QUALITY = 80
        private const val MJPEG_FPS = 10
        private const val MJPEG_FRAME_MS = 1000L / MJPEG_FPS
    }
}
