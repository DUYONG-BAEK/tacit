package kr.co.snail.tacitq.mobile.hmd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HMD HELLO 해석 — HMD 앱 Wire.hello() 가 내보내는 모양 그대로 */
class HmdHelloTest {

    @Test fun `영상 주소 카메라 배터리 충전 여부를 읽는다`() {
        val h = HmdHello.parse(
            """{"type":"HELLO","video_url":"http://192.168.1.2:8080","camera":"정상","battery":87,"charging":false}""".encodeToByteArray(),
        )
        assertEquals(HmdHello("http://192.168.1.2:8080", "정상", 87, false), h)
        assertFalse(h!!.batteryLow)
    }

    @Test fun `옛 HMD 앱 HELLO 는 배터리가 없다`() {
        val h = HmdHello.parse("""{"type":"HELLO","video_url":null}""".encodeToByteArray())!!
        assertNull(h.videoUrl)
        assertNull(h.battery)
        assertFalse(h.batteryLow)
    }

    @Test fun `빈 값 깨진 쪽지 HELLO 아닌 쪽지는 null`() {
        assertNull(HmdHello.parse(ByteArray(0)))
        assertNull(HmdHello.parse("{not json".encodeToByteArray()))
        assertNull(HmdHello.parse("""{"type":"ACK","seq":1}""".encodeToByteArray()))
    }

    @Test fun `배터리 부족은 15퍼센트 미만이고 충전 중이 아닐 때`() {
        assertTrue(HmdHello(null, battery = 14, charging = false).batteryLow)
        assertTrue(HmdHello(null, battery = 14).batteryLow)
        assertFalse(HmdHello(null, battery = 14, charging = true).batteryLow)
        assertFalse(HmdHello(null, battery = 15).batteryLow)
    }
}
