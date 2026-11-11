package kr.co.snail.tacitq.hmd.link

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kr.co.snail.tacitq.hmd.MainActivity
import kr.co.snail.tacitq.hmd.R
import kr.co.snail.tacitq.hmd.deviceIp
import kr.co.snail.tacitq.hmd.protocol.Wire

/**
 * HMD 상시 대기 서비스 — BLE GATT 서버(광고·연결)를 화면과 따로 늘 켜 둔다(2026-11-11).
 *
 * 전에는 BLE 를 화면(MainActivity)이 들고 있어서, 화면이 정리되면(메모리 정리 · 최근 앱에서 지움 · 앱 업데이트)
 * 폰이 HMD 를 부를 수 없었고 HMD 에서 앱을 손으로 실행해야 했다.
 *
 *  - 시작: HMD 부팅 · 앱 설치/업데이트([kr.co.snail.tacitq.hmd.BootReceiver]) · 앱 실행. 정리돼도 다시 뜬다(START_STICKY).
 *  - 폰이 연결하거나 쪽지를 보내면 화면을 앞으로 띄운다(3초에 한 번, 꺼진 화면은 깨움). 카메라는 화면이 떠 있어야 켜진다.
 *  - 화면이 뜨기 전 SHOW 는 서비스가 대신 ACK 하고 들고 있다가 화면이 붙으면 넘긴다([HeldShow]).
 *  - HELLO(영상 주소 · 카메라 상태 · 배터리)도 서비스가 답한다 — 카메라 상태만 화면에서 받는다.
 *
 * 안드로이드 10+ 는 뒤에서 화면 띄우기를 막으므로 "다른 앱 위에 표시"(SYSTEM_ALERT_WINDOW) 허용이 필요하다(HMD_설치.bat).
 * 설정에서 "강제 종료"하면 서비스도 멈춘다(안드로이드 규칙) — 그때는 재부팅하거나 앱을 한 번 실행한다.
 */
class LinkService : Service() {

    /** 화면 쪽 — 붙어 있을 때만 쪽지·상태를 받는다. 모두 메인 스레드에서 부른다([cameraState] 만 BLE 스레드). */
    interface Ui {
        fun onDown(bytes: ByteArray)
        fun onLinkState(state: BleServer.State)
        val isInFront: Boolean
        fun cameraState(): String
    }

    private var ble: BleServer? = null
    private var state: BleServer.State? = null
    private val held = HeldShow()
    private var lastBringAt = 0L
    private val battery by lazy { getSystemService(BatteryManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_ID, "폰 연결 대기", NotificationManager.IMPORTANCE_LOW),
        )
        startForegroundCompat("폰 연결 대기 중")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ble == null) startBle()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ble?.stop()
        ble = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startBle() {
        val missing = BLE_PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "BLE 권한 없음 $missing — 앱을 한 번 실행해 허용하세요")
            notice("BLE 권한 없음 — HMD 앱을 한 번 실행해 허용하세요")
            return
        }
        ble = BleServer(this, onDown = ::onDown, onState = ::onState, hello = ::hello).also { it.start() }
        Log.i(TAG, "BLE 대기 시작")
    }

    /** 폰 → HMD 쪽지(메인 스레드) — 화면을 앞으로 부르고, 화면이 붙어 있으면 넘기고 없으면 SHOW 만 대신 ACK·보관 */
    private fun onDown(bytes: ByteArray) {
        val u = ui
        if (u?.isInFront != true) bringToFront()
        if (u != null) { u.onDown(bytes); return }
        held.offer(bytes)?.let { seq -> ble?.notify(Wire.ack(seq, System.currentTimeMillis())) }
    }

    private fun onState(s: BleServer.State) {
        state = s
        if (s == BleServer.State.CONNECTED) bringToFront()     // 폰에서 검사 시작 → BLE 연결 → 화면이 앞으로 나와 카메라를 켠다
        else held.clear()
        notice(if (s == BleServer.State.CONNECTED) "폰 연결됨" else "폰 연결 대기 중 (${s.label})")
        ui?.onLinkState(s)
    }

    /** UP 특성 읽기 응답(BLE 스레드) */
    private fun hello(): ByteArray = Wire.hello(
        deviceIp()?.let { "http://$it:${MainActivity.PORT}" },
        ui?.cameraState() ?: ("꺼짐 — HMD 앱 화면이 아직 안 뜸(폰 연결 시 자동으로 뜸)" +
            if (!Settings.canDrawOverlays(this)) " · '다른 앱 위에 표시' 권한 없음(화면을 띄우지 못함)" else ""),
        battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 },
        battery.isCharging,
    )

    /** 화면을 맨 앞으로(없으면 새로 띄움). 쪽지마다 부르므로 3초에 한 번만. */
    private fun bringToFront() {
        val now = System.currentTimeMillis()
        if (now - lastBringAt < 3_000) return
        lastBringAt = now
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "화면 띄우기 불가 — '다른 앱 위에 표시' 허용 필요(HMD_설치.bat)")
            return
        }
        Log.i(TAG, "폰 연결 → 화면을 앞으로")
        // 화면이 꺼져 있으면 먼저 깨운다(3초 뒤 놓음 — 그 뒤는 화면의 켜짐 유지가 맡는다). 꺼진 화면에선 카메라가 안 열림
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) {
            Log.i(TAG, "화면 꺼짐 → 켜기")
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "TacitQ:wake").acquire(3_000)
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    /** 화면이 붙었다 — 지금 BLE 상태와 들고 있던 SHOW 를 넘긴다 */
    private fun onAttach(u: Ui) {
        ui = u
        state?.let(u::onLinkState)
        held.take().forEach(u::onDown)
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTI_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTI_ID, notification(text))
    }

    private fun notice(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(text))

    private fun notification(text: String): Notification =
        Notification.Builder(this, CH_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "TacitQ.Link"
        private const val NOTI_ID = 2001
        private const val CH_ID = "link"

        /** API 31+ 런타임 권한(Navigator 520 = Android 11 은 설치만 하면 허용) */
        val BLE_PERMISSIONS: List<String> =
            if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            else emptyList()

        @Volatile private var instance: LinkService? = null
        @Volatile private var ui: Ui? = null

        /** 서비스 시작(이미 돌면 아무 일 없음). 부팅·업데이트·화면에서 부른다. */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java))
        }

        /** 화면 붙이기·떼기 — MainActivity onCreate·onDestroy. 서비스가 아직 안 떴으면 뜰 때까지 들고 있다. */
        fun attach(u: Ui) {
            instance?.onAttach(u) ?: run { ui = u }
        }

        fun detach(u: Ui) {
            if (ui === u) ui = null
        }

        /** HMD → 폰 쪽지(ACK) — HudController 가 쓴다. 서비스·연결이 없으면 false. */
        fun notifyUp(bytes: ByteArray): Boolean = instance?.ble?.notify(bytes) ?: false
    }
}
