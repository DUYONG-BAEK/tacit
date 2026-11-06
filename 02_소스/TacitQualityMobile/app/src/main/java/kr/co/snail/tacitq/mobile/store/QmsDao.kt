package kr.co.snail.tacitq.mobile.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * C5 DAO — 개발가이드 §7.
 *
 * 용량 정리는 **이력만** 대상으로 한다. 큐(outbox)는 건드리지 않는다.
 * 지우는 순간 그 검사는 세상에서 사라진다.
 */
@Dao
interface QmsDao {

    // ── 이력 ─────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: InspectionEntity)

    @Query("UPDATE inspection SET syncedAt = :at WHERE inspectionId = :id")
    suspend fun markSynced(id: String, at: Long)

    @Query("SELECT * FROM inspection ORDER BY capturedAt DESC")
    suspend fun allInspections(): List<InspectionEntity>

    /** 용량 정리 — 동기화 끝난 이력만. 서버에 이미 있으므로 지워도 된다. */
    @Query("DELETE FROM inspection WHERE syncedAt IS NOT NULL AND capturedAt < :before")
    suspend fun pruneSynced(before: Long)

    // ── 전송 큐 (outbox) — 절대 임의 삭제 금지 ─────────
    @Insert
    suspend fun push(e: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id ASC LIMIT :n")   // FIFO — 순서 보장
    suspend fun nextBatch(n: Int): List<OutboxEntity>

    /** 전송 성공(ACK)한 건만 큐에서 뺀다. 이것만이 outbox 를 줄이는 유일한 길이다. */
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun ack(id: Long)

    @Query("UPDATE outbox SET tries = tries + 1, lastError = :err WHERE id = :id")
    suspend fun markTried(id: Long, err: String?)

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun queueSize(): Int
}
