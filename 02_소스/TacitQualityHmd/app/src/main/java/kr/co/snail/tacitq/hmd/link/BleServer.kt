package kr.co.snail.tacitq.hmd.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kr.co.snail.tacitq.hmd.protocol.BleUuids
import java.io.ByteArrayOutputStream

/**
 * BLE GATT 서버 — 가는 선(제어 채널)의 HMD 쪽. 모바일 한 대만 붙는다고 가정한다.
 *
 * 광고(서비스 UUID 포함) → 모바일 연결 → DOWN 특성에 쪽지 쓰기 → [onDown]
 * HMD → 모바일은 UP 특성 notify ([notify]). 모바일이 CCCD 로 notify 를 켜야 받는다.
 *
 * 콜백은 바인더 스레드에서 오므로 [onDown]·[onState] 는 메인 스레드로 넘겨 부른다.
 * 권한(API 31+ BLUETOOTH_ADVERTISE·CONNECT)은 호출 전에 확보돼 있어야 한다.
 */
@SuppressLint("MissingPermission")
class BleServer(
    private val context: Context,
    private val onDown: (ByteArray) -> Unit,
    private val onState: (State) -> Unit,
    /** UP 특성 읽기 응답(HELLO — 지금 영상 주소). 읽을 때마다 새로 만든다 — IP 가 바뀌어도 맞다. */
    private val hello: () -> ByteArray = { ByteArray(0) },
) {
    enum class State(val label: String) {
        OFF("BLE 꺼짐"),
        UNSUPPORTED("BLE 광고 미지원"),
        ADVERTISING("BLE 광고 중"),
        CONNECTED("BLE 연결됨"),
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var up: BluetoothGattCharacteristic? = null

    @Volatile private var central: BluetoothDevice? = null
    @Volatile private var notifyOn = false
    @Volatile private var mtu = 23
    /** 긴 쓰기(prepared write) 조립 버퍼 — MTU 협상 전이면 480B 쪽지가 여러 조각으로 온다 */
    private val prepared = ByteArrayOutputStream()

    val isConnected get() = central != null

    fun start() {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) return emit(State.OFF)
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported) return emit(State.UNSUPPORTED)

        server = manager.openGattServer(context, callback)?.also { it.addService(buildService()) }
        // 광고는 서비스 등록(onServiceAdded) 뒤에 시작한다
    }

    fun stop() {
        stopAdvertising()
        server?.close()
        server = null
        central = null
        emit(State.OFF)
    }

    /** UP 특성으로 쪽지 한 장을 보낸다. 연결·notify 구독이 없으면 false. */
    fun notify(bytes: ByteArray): Boolean {
        val device = central ?: return false
        val ch = up ?: return false
        // 구독(CCCD) 기록이 없어도 보낸다 — HMD 앱만 재시작되면 폰은 기존 연결·구독을 그대로 쓰는데
        // 새 서버는 구독을 모른다. 안 보내면 ACK 가 끊겨 폰이 HMD 를 끊김으로 판단한다(실장비 2026-10-05).
        if (!notifyOn) Log.d(TAG, "notify 구독 기록 없음 — 그래도 보낸다")
        if (bytes.size > mtu - 3) Log.w(TAG, "notify ${bytes.size}B > MTU-3(${mtu - 3}) — 잘린다. 모바일이 MTU 를 올려야 함")
        val gatt = server ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            gatt.notifyCharacteristicChanged(device, ch, false, bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            gatt.notifyCharacteristicChanged(device, ch, false)
        }
    }

    private fun buildService(): BluetoothGattService {
        val down = BluetoothGattCharacteristic(
            BleUuids.DOWN,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val upCh = BluetoothGattCharacteristic(
            BleUuids.UP,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        up = upCh
        return BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(down)
            addCharacteristic(upCh)
        }
    }

    private fun startAdvertising() {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return emit(State.UNSUPPORTED)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // 128비트 UUID(18B)와 기기 이름을 함께 넣으면 31B 를 넘으므로 이름은 스캔 응답에 싣는다
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(BleUuids.SERVICE)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "광고 시작 — service=${BleUuids.SERVICE}")
            emit(State.ADVERTISING)
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "광고 실패 code=$errorCode")
            emit(State.UNSUPPORTED)
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.i(TAG, "서비스 등록 status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "연결 ${device.address}")
                central = device
                notifyOn = false
                mtu = 23
                stopAdvertising()          // 모바일 한 대 전용 — 붙으면 광고를 멈춘다
                emit(State.CONNECTED)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device == central) {
                Log.i(TAG, "끊김 ${device.address} status=$status")
                central = null
                notifyOn = false
                startAdvertising()         // 재연결을 받을 수 있게 다시 광고
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "MTU=$mtu")
            this@BleServer.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val ok = characteristic.uuid == BleUuids.DOWN
            if (ok && preparedWrite) {
                synchronized(prepared) { if (offset == 0) prepared.reset(); prepared.write(value) }
            } else if (ok) {
                deliver(value)
            }
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset, value,
                )
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val bytes = synchronized(prepared) { prepared.toByteArray().also { prepared.reset() } }
            if (execute && bytes.isNotEmpty()) deliver(bytes)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == BleUuids.CCCD) {
                notifyOn = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.i(TAG, "notify ${if (notifyOn) "켜짐" else "꺼짐"}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val v = if (notifyOn) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val body = if (characteristic.uuid == BleUuids.UP) hello() else ByteArray(0)
            val part = if (offset < body.size) body.copyOfRange(offset, body.size) else ByteArray(0)   // 긴 읽기(offset) 대응
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }
    }

    private fun deliver(bytes: ByteArray) {
        main.post { onDown(bytes) }
    }

    private fun emit(state: State) {
        main.post { onState(state) }
    }

    companion object {
        private const val TAG = "TacitQ.Ble"
    }
}
