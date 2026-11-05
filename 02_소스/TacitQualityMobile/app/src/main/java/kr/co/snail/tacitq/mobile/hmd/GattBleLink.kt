package kr.co.snail.tacitq.mobile.hmd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kr.co.snail.tacitq.mobile.log.CommLog
import kr.co.snail.tacitq.mobile.log.CommLog.Ch.HMD
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * C1 BLE 가는 선 — 폰(GATT 클라이언트) → HMD(GATT 서버) 화면 지시. [BleLink] 실물.
 *
 * HMD 앱 `protocol/Wire.kt` 의 UUID 와 **같아야** 한다:
 *  - SERVICE 로 스캔 → 처음 보이는 HMD 에 연결 → MTU 512 → UP notify 켜기 → 준비 완료
 *  - [write] = DOWN 특성 write(응답 있음). GATT 는 동시 쓰기가 안 되므로 한 번에 하나씩.
 *  - UP notify 의 ACK → [onAck] (HmdRenderer 가 200ms 기다리는 그것)
 *  - 1초마다 PING — HMD 가 폰 끊김 3초를 감지해 H-09 를 스스로 띄운다(R16)
 *  - 끊기면 5초 고정 주기 재스캔(지수 백오프 금지)
 *
 * 권한(Android 12+ BLUETOOTH_SCAN·CONNECT)은 화면에서 미리 받아 둔다.
 */
@SuppressLint("MissingPermission")
class GattBleLink(
    private val context: Context,
    private val onAck: (seq: Long) -> Unit,
    private val onReady: () -> Unit = {},
    /** HMD 가 알려 준 영상 주소(HELLO) — IP 수동 입력 없이 자동으로 맞춘다 */
    private val onVideoUrl: (String) -> Unit = {},
) : BleLink {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var down: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingRead: CompletableDeferred<ByteArray?>? = null
    @Volatile private var up: BluetoothGattCharacteristic? = null
    @Volatile private var needHello = true
    private var pingSeq = 0L
    @Volatile private var connectStartedAt = 0L
    private var pingFailing = false                    // 통신 로그 — PING 실패는 바뀔 때만 남긴다

    private val _state = MutableStateFlow("BLE 준비")
    val state: StateFlow<String> = _state

    /** 취소될 때까지: 찾기 → 연결 → PING. 끊기면 5초 뒤 다시. */
    suspend fun run() {
        try {
            while (coroutineContext.isActive) {
                if (gatt == null) {
                    if (adapter == null || !adapter.isEnabled) {
                        state("폰 블루투스 꺼짐", warn = true)
                    } else {
                        _state.value = "HMD 찾는 중"
                        val device = scanOnce(SCAN_MS)
                        if (device != null) connect(device)
                        else state("HMD 못 찾음(${SCAN_MS / 1000}초 스캔) — HMD 앱·블루투스 확인, 5초 뒤 재시도", warn = true)
                    }
                    delay(RECONNECT_MS)
                    continue
                }
                if (ready) {
                    if (needHello) readHello()
                    val ok = runCatching { write("""{"cmd":"PING","seq":${++pingSeq}}""".toByteArray()) }
                        .onFailure { if (!pingFailing) CommLog.w(HMD, TAG, "PING 쓰기 실패: ${it.message}") }.isSuccess
                    if (ok && pingFailing) CommLog.i(HMD, TAG, "PING 쓰기 다시 성공")
                    pingFailing = !ok
                } else if (System.currentTimeMillis() - connectStartedAt > CONNECT_TIMEOUT_MS) {
                    CommLog.w(HMD, TAG, "연결 준비가 ${CONNECT_TIMEOUT_MS}ms 안에 끝나지 않음 — 다시 시도")
                    close()
                    continue
                }
                delay(PING_MS)
            }
        } finally {
            close()
        }
    }

    override suspend fun write(bytes: ByteArray) = write(bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    /**
     * 응답 없는 쓰기 — 실시간 결함 쪽지(LIVE)처럼 최신 것만 의미 있는 쪽지. HMD 의 쓰기 응답을 기다리지 않는다.
     * 안드로이드는 응답 없는 쓰기도 [BluetoothGattCallback.onCharacteristicWrite] 가 와야 다음 쓰기를 받으므로 같은 잠금을 쓴다.
     */
    suspend fun writeNoResponse(bytes: ByteArray) = write(bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    private suspend fun write(bytes: ByteArray, writeType: Int) {
        val g = gatt
        val ch = down
        if (!ready || g == null || ch == null) throw IOException("BLE 미연결")
        writeLock.withLock {
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, bytes, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (!started) throw IOException("BLE 쓰기 시작 실패")
            if (withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() } != true) throw IOException("BLE 쓰기 응답 없음")
        }
    }

    /** 상태 표시 + 통신 로그(같은 문구가 이어지면 한 번만) */
    private fun state(text: String, warn: Boolean = false) {
        if (_state.value != text) { if (warn) CommLog.w(HMD, TAG, text) else CommLog.i(HMD, TAG, text) }
        _state.value = text
    }

    /** 영상이 끊겼을 때 — HMD IP 가 바뀌었을 수 있으니 다음 주기에 HELLO 를 다시 읽는다. */
    fun refreshVideoUrl() { needHello = true }

    /** UP 특성 읽기 = HMD HELLO {"type":"HELLO","video_url":"http://IP:8080"}. 옛 HMD 앱은 빈 값 → 수동 주소 유지. */
    private suspend fun readHello() {
        val g = gatt ?: return
        val ch = up ?: return
        val bytes = writeLock.withLock {
            val done = CompletableDeferred<ByteArray?>()
            pendingRead = done
            if (!g.readCharacteristic(ch)) return
            withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
        } ?: return
        needHello = false
        runCatching {
            val o = json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val url = o["video_url"]?.jsonPrimitive?.takeIf { it.isString }?.content
            if (o["type"]?.jsonPrimitive?.content == "HELLO" && !url.isNullOrBlank()) {
                CommLog.i(HMD, TAG, "HMD 영상 주소 $url")
                onVideoUrl(url)
            }
        }.onFailure { CommLog.i(HMD, TAG, "HELLO 없음 — 수동 HMD 주소 사용") }
    }

    private suspend fun scanOnce(timeoutMs: Long): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        runCatching { scanner.stopScan(this) }
                        if (cont.isActive) cont.resume(result.device)
                    }
                    override fun onScanFailed(errorCode: Int) {
                        CommLog.w(HMD, TAG, "스캔 실패 code=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner.startScan(listOf(filter), settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    private fun connect(device: BluetoothDevice) {
        CommLog.i(HMD, TAG, "HMD 발견 ${device.address} — 연결")
        _state.value = "HMD 연결 중"
        connectStartedAt = System.currentTimeMillis()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    /** ACK 가 안 오는 등 연결이 이상할 때 — 끊고 처음(스캔)부터 다시 붙는다. */
    fun reset() {
        CommLog.w(HMD, TAG, "BLE 재설정 — 다시 연결")
        _state.value = "HMD 재연결 중"
        close()
    }

    private fun close() {
        ready = false
        down = null
        up = null
        needHello = true
        pendingRead?.complete(null)
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                CommLog.i(HMD, TAG, "GATT 연결 — MTU 요청")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)   // 15~30ms 간격(설계 B3)
                g.requestMtu(MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                CommLog.w(HMD, TAG, "GATT 끊김 status=$status")
                pendingWrite?.complete(false)
                close()
                _state.value = "HMD 끊김 — 재연결 대기"
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            CommLog.i(HMD, TAG, "MTU=$mtu status=$status")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            val dn = svc?.getCharacteristic(DOWN)
            val up = svc?.getCharacteristic(UP)
            if (dn == null || up == null) {
                CommLog.w(HMD, TAG, "TacitQ 서비스/특성 없음 — HMD 앱 버전 확인")
                g.disconnect()
                return
            }
            down = dn
            this@GattBleLink.up = up
            g.setCharacteristicNotification(up, true)
            val cccd = up.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                ready = true
                CommLog.i(HMD, TAG, "HMD 준비 완료 (notify 켜짐)")
                _state.value = "HMD 연결됨"
                onReady()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        // API 32 이하
        @Deprecated("API 33 미만 호환")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            pendingRead?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ch.value else null)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            onUp(value)

        // API 32 이하
        @Deprecated("API 33 미만 호환")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) =
            onUp(ch.value ?: ByteArray(0))
    }

    /** HMD → 폰 쪽지. 지금은 ACK 만 쓴다(HELLO·HEARTBEAT·GESTURE 는 3단계). */
    private fun onUp(bytes: ByteArray) {
        runCatching {
            val o = json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (o["type"]?.jsonPrimitive?.content == "ACK") {
                o["seq"]?.jsonPrimitive?.longOrNull?.let(onAck)
            }
        }.onFailure { CommLog.w(HMD, TAG, "UP 쪽지 해석 실패: ${bytes.decodeToString()}") }
    }

    companion object {
        private const val TAG = "TacitQ.BLE"
        // HMD 앱 protocol/Wire.kt BleUuids 와 동일
        val SERVICE: UUID = UUID.fromString("9e7a1000-3c5b-4f0e-8d21-5a7c0d4e6b01")
        val DOWN: UUID = UUID.fromString("9e7a1001-3c5b-4f0e-8d21-5a7c0d4e6b01")
        val UP: UUID = UUID.fromString("9e7a1002-3c5b-4f0e-8d21-5a7c0d4e6b01")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MTU = 512
        private const val SCAN_MS = 10_000L
        private const val RECONNECT_MS = 5_000L
        private const val PING_MS = 1_000L
        private const val WRITE_TIMEOUT_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
