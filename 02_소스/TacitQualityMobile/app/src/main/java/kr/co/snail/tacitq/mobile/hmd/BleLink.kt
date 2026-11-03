package kr.co.snail.tacitq.mobile.hmd

/**
 * BLE GATT 쓰기 포트 — 가는 선(제어 채널).
 *
 * 인터페이스로 둬서 [HmdRenderer] 를 안드로이드/블루투스 없이 노트북에서 시험할 수 있게 한다.
 * 실증에서는 BluetoothGatt characteristic write 로 구현하고, 시험에서는 가짜(fake)로 갈아끼운다.
 */
interface BleLink {
    /** 쪽지 한 장(≤480B)을 HMD 로 쓴다. 연결이 없으면 예외를 던진다. */
    suspend fun write(bytes: ByteArray)
}
