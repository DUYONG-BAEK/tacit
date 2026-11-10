package kr.co.snail.tacitq.hmd.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.TextureView

/**
 * Camera2 직접 촬영 — 출력 2개: 화면 미리보기(TextureView) + 인코더 입력 표면. 녹화용 표준 구성(TEMPLATE_RECORD).
 * 설계서 §4. CameraX 는 이 조합(미리보기 + 임의 표면)을 직접 열 수 없어 Camera2 를 쓴다.
 *
 * RealWear 520 함정(hmd-app 메모, 실장비): 미리보기 표면이 있어야 시작되고, SurfaceView 는 안 되며 TextureView 는 된다.
 * (HAL 등급은 구형이 아니라 LEVEL_3 — 2026-11-10 dumpsys media.camera)
 * 세션 구성이 거부되면 [onError] — 호출자가 MJPEG 예비 모드로 되돌린다.
 */
class Camera2Source(
    context: Context,
    private val preview: TextureView,
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onError: (String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null

    fun start() {
        if (preview.isAvailable) open(preview.surfaceTexture!!)
        else preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = open(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(st: SurfaceTexture) {
        st.setDefaultBufferSize(width, height)
        previewSurface = Surface(st)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.first()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                createSession(d, id)
            }
            override fun onDisconnected(d: CameraDevice) {
                Log.w(TAG, "카메라 연결 끊김 (다른 앱이 가져감?)")
                d.close()
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                onError("카메라 열기 실패 code=$error")
            }
        }, handler)
    }

    @Suppress("DEPRECATION")      // API 28 미만 호환 목록형 — minSdk 29 이지만 가장 무난
    private fun createSession(d: CameraDevice, id: String) {
        val targets = listOf(previewSurface!!, encoderSurface)
        d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    targets.forEach(::addTarget)
                    pickFpsRange(id)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
                s.setRepeatingRequest(req.build(), null, handler)
                Log.i(TAG, "촬영 시작 ${width}x$height 목표 ${fps}fps (미리보기 + 인코더)")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                onError("카메라 세션 구성 실패 (미리보기 + 인코더 2출력 거부)")
            }
        }, handler)
    }

    /** 목표 fps 를 고정으로 받을 수 있으면 (fps,fps), 아니면 fps 를 포함하는 가장 좁은 범위 */
    private fun pickFpsRange(id: String): Range<Int>? {
        val ranges = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { fps in it }.minByOrNull { it.upper - it.lower }
    }

    fun stop() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { previewSurface?.release() }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "TacitQ.Camera2"
    }
}
