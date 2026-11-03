package kr.co.snail.tacitq.mobile.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.co.snail.tacitq.mobile.core.InspectionRecord
import kr.co.snail.tacitq.mobile.model.CaptureMode

/**
 * C5 LocalStore 파사드 — 상태 기계(C3)의 출력(Effect.Persist·Effect.Enqueue)을
 * Room 에 즉시 영속화한다. 서비스 층은 이 클래스만 알면 되고, Room 세부는 여기서 끝난다.
 *
 * "미동기화 이력은 큐와 짝이다" — [record] 는 이력 한 줄을 쓰면서 전송 큐에도 함께 적재한다.
 * 둘 중 하나만 남으면 유실이거나 중복이 된다.
 */
class LocalStore(private val dao: QmsDao) {

    private val json = Json { encodeDefaults = true }

    /** Effect.Persist — 검사 결정 1건. 이력 저장 + 전송 큐 적재를 한 번에. */
    suspend fun record(r: InspectionRecord, now: Long = r.at) {
        dao.put(r.toEntity())
        dao.push(OutboxEntity(kind = KIND_INSPECTION, payload = encode(r), createdAt = now))
    }

    /** Effect.Enqueue — 이미 만들어진 페이로드(플러시·재전송 등)를 큐에만 적재. */
    suspend fun enqueue(payload: ByteArray, kind: String = KIND_INSPECTION, now: Long = System.currentTimeMillis()) =
        dao.push(OutboxEntity(kind = kind, payload = payload, createdAt = now))

    /** FIFO 로 다음 전송분을 꺼낸다(삭제하지 않음). 전송 성공 시에만 [ack]. */
    suspend fun nextBatch(n: Int = 32) = dao.nextBatch(n)

    /** 전송 성공(Edge ACK). 큐를 줄이는 유일한 길. */
    suspend fun ack(id: Long) = dao.ack(id)

    /** 전송 실패 — 큐에 남긴 채 시도 횟수만 올린다. */
    suspend fun fail(id: Long, error: String?) = dao.markTried(id, error)

    suspend fun queueSize(): Int = dao.queueSize()

    /** 동기화 표시 — 이후 [pruneSynced] 대상이 된다. */
    suspend fun markSynced(inspectionId: String, at: Long = System.currentTimeMillis()) =
        dao.markSynced(inspectionId, at)

    /**
     * 용량 정리 — 동기화 끝난 이력만. 큐는 절대 건드리지 않는다.
     * @param before 이 시각 이전에 촬영된 '동기화 완료' 이력을 지운다.
     */
    suspend fun pruneSynced(before: Long) = dao.pruneSynced(before)

    private fun encode(r: InspectionRecord): ByteArray = json.encodeToString(r).toByteArray()

    /**
     * core.InspectionRecord → InspectionEntity.
     * 상태 기계는 android/Room 을 모르므로 최소 필드만 만든다. 여기서 로컬 이력 행으로 완성한다.
     * inspectionId 채번은 실증 단계에서 세션·라인 규칙으로 대체한다(현재는 partId 를 키로 사용).
     */
    private fun InspectionRecord.toEntity() = InspectionEntity(
        inspectionId = partId,
        partId = partId,
        capturedAt = at,
        captureMode = CaptureMode.VIDEO_STREAM.code,
        streamSessionId = "",
        framesReceived = 0,
        framesSelected = 0,
        aiVerdict = aiVerdict,
        aiConfidence = aiScore,
        agreeRate = null,
        workerAction = workerAction,
        confirmTrigger = confirmTrigger,
        finalE2eMs = null,
        lateRecovery = lateRecovery,
        trayId = null,
        syncedAt = null,
    )

    companion object {
        const val KIND_INSPECTION = "INSPECTION"
        const val KIND_ALERT = "ALERT"
        const val KIND_ACTION = "ACTION"
    }
}
