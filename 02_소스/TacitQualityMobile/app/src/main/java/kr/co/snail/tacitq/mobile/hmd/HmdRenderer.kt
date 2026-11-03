package kr.co.snail.tacitq.mobile.hmd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.snail.tacitq.mobile.core.ShowCommand
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * C4 HmdRenderer — 상태 기계의 결정을 HMD 가 알아듣는 쪽지로 바꿔 보낸다. 개발가이드 §6.
 *
 * 두 가지가 이 부품의 존재 이유다.
 *  1. **순번(seq)** — HMD 가 오래된 지시를 걸러낼 근거. 단조 증가.
 *  2. **재전송 절제** — ACK 를 200ms 만 기다리고 딱 한 번만 재전송한다. 계속 재전송하면
 *     오래된 화면 지시가 뒤늦게 도착해 새 화면을 덮는다(HMD 순번 검사가 막아주지만 애초에 안 만드는 게 낫다).
 *
 * ACK 실패는 HMD 제어 채널의 문제이므로 [onAckFailed] 콜백으로 서비스에 알린다(예: OFFLINE 전이 판단).
 * Edge 문제와 섞지 않는다.
 */
class HmdRenderer(
    private val ble: BleLink,
    private val onAckFailed: (seq: Long) -> Unit = {},
    private val ackTimeoutMs: Long = 200,
    private val maxBytes: Int = 480,                 // BLE MTU 512 전제 — 1패킷에 담는다
) {
    private val json = Json { encodeDefaults = true }
    private val seqGen = AtomicLong(0)
    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    /** 다음 순번. 단조 증가. */
    fun nextSeq(): Long = seqGen.incrementAndGet()

    /** HMD 가 보낸 ACK(seq) 를 렌더러에 전달 — 대기 중인 show() 를 깨운다. */
    fun onAck(seq: Long) {
        waiters[seq]?.complete(Unit)
    }

    /**
     * 쪽지를 보내고 ACK 를 기다린다. 200ms 안에 없으면 한 번만 재전송하고,
     * 그래도 없으면 [onAckFailed] 를 부른다.
     * @return 부여된 seq
     */
    suspend fun show(cmd: ShowCommand): Long {
        val seq = nextSeq()
        val bytes = json.encodeToString(ShowWire.serializer(), cmd.toWire(seq)).toByteArray()
        require(bytes.size <= maxBytes) { "SHOW 가 너무 크다: ${bytes.size}B (screen=${cmd.screen})" }

        if (!writeAndAwait(seq, bytes)) {            // 1차 전송 + ACK 대기
            if (!writeAndAwait(seq, bytes)) {        // 딱 한 번 재전송 (같은 seq → HMD 멱등)
                onAckFailed(seq)
            }
        }
        return seq
    }

    private suspend fun writeAndAwait(seq: Long, bytes: ByteArray): Boolean {
        val ack = CompletableDeferred<Unit>()
        waiters[seq] = ack
        try {
            ble.write(bytes)
            return withTimeoutOrNull(ackTimeoutMs) { ack.await() } != null
        } finally {
            waiters.remove(seq)
        }
    }
}
