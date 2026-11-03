package kr.co.snail.tacitq.mobile.hmd

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kr.co.snail.tacitq.mobile.core.Event
import kr.co.snail.tacitq.mobile.core.Profile
import kr.co.snail.tacitq.mobile.core.Screens
import kr.co.snail.tacitq.mobile.model.Verdict
import org.junit.Assert.*
import org.junit.Test

/**
 * C4 HmdRenderer 단위 시험 — 개발가이드 §6 "완료 확인". 장비 없이 노트북에서 돈다.
 *
 * BLE 는 [BleLink] 가짜로 갈아끼우고, ACK 지연은 coroutines-test 의 가상 시간으로 즉시 흘려보낸다.
 */
class HmdRendererTest {

    /** 쓴 바이트를 기록하고, autoAck 면 쪽지의 seq 로 즉시 ACK 를 돌려주는 가짜 BLE. */
    private class FakeBle(var autoAck: Boolean = true) : BleLink {
        val writes = mutableListOf<ByteArray>()
        var renderer: HmdRenderer? = null
        private val json = Json { ignoreUnknownKeys = true }
        override suspend fun write(bytes: ByteArray) {
            writes += bytes
            if (autoAck) {
                val seq = json.decodeFromString(ShowWire.serializer(), String(bytes)).seq
                renderer?.onAck(seq)
            }
        }
    }

    private fun sampleJudge() = Event.JudgeResult(
        seq = 1, partId = "A_0001", verdict = Verdict.FAIL, severity = "CRITICAL",
        confidence = 0.95, agreeRate = 0.88, evidence = "찍힘 · 2면 좌상단 · 4장 중 3장 검출",
        frameRef = "frm/A_0001_top.jpg", bbox = listOf(0.31, 0.22, 0.07, 0.06),
        capturedEndAt = 1_000, arrivedAt = 1_400,
    )

    /** 완료 확인 ①② — 모든 SHOW 가 480바이트 이하(1패킷)이고 seq 가 단조 증가한다. */
    @Test fun `모든 SHOW 는 480바이트 이하이고 seq 는 단조 증가한다`() = runTest {
        val ble = FakeBle(autoAck = true)
        val r = HmdRenderer(ble).also { ble.renderer = it }
        val e = sampleJudge()
        val cmds = listOf(
            Screens.guide("PRS-4410", 0, Profile()),
            Screens.idle(),
            Screens.pass(e, ttl = 1_500),
            Screens.fail(e),
            Screens.review(e, "w-1"),
            Screens.countdown("A_0001", "합격"),
            Screens.objection("obj-A_0001"),
            Screens.alert("DENT", 4, 10),
            Screens.stopped("연속 불량 ALERT", 1_000),
            Screens.recover("A_0001", 2_600),
            Screens.overlay("서버 지연 · 검사 계속"),
            Screens.overlayOff(),
        )
        val seqs = cmds.map { r.show(it) }

        assertTrue("모든 쪽지는 ≤480B — 분할 전송 없음", ble.writes.all { it.size <= 480 })
        assertEquals("seq 는 단조 증가", seqs.sorted(), seqs)
        assertEquals("seq 중복 없음", seqs.size, seqs.toSet().size)
        assertEquals("쪽지 수 = show 호출 수", cmds.size, ble.writes.size)
    }

    /** ACK 가 정상이면 딱 한 번만 보낸다(재전송 없음). */
    @Test fun `ACK 정상이면 한 번만 보낸다`() = runTest {
        val ble = FakeBle(autoAck = true)
        val r = HmdRenderer(ble).also { ble.renderer = it }
        r.show(Screens.pass(sampleJudge(), ttl = 1_500))
        assertEquals(1, ble.writes.size)
    }

    /** 완료 확인 ③ — HMD 가 응답 없으면 200ms×2 안에 실패가 올라온다(원본+재전송 딱 2회). */
    @Test fun `ACK 없으면 두 번 보내고 실패를 알린다`() = runTest {
        val fails = mutableListOf<Long>()
        val ble = FakeBle(autoAck = false)
        val r = HmdRenderer(ble, onAckFailed = { fails += it }).also { ble.renderer = it }

        val seq = r.show(Screens.fail(sampleJudge()))

        assertEquals("원본 + 재전송 = 2회", 2, ble.writes.size)
        assertEquals("실패 콜백은 그 seq 로 딱 한 번", listOf(seq), fails)
    }

    /** 와이어 계약 — HMD 팀이 읽을 필드명이 snake_case 그대로다. */
    @Test fun `와이어 JSON 은 계약 필드명을 쓴다`() = runTest {
        val ble = FakeBle(autoAck = true)
        val r = HmdRenderer(ble).also { ble.renderer = it }
        r.show(Screens.review(sampleJudge(), "w-42"))
        val sent = String(ble.writes.first())
        assertTrue(sent.contains("\"cmd\":\"SHOW\""))
        assertTrue(sent.contains("\"gesture_window\":\"YESNO\""))
        assertTrue(sent.contains("\"window_id\":\"w-42\""))
        assertTrue(sent.contains("\"screen\":\"H-04\""))
    }
}
