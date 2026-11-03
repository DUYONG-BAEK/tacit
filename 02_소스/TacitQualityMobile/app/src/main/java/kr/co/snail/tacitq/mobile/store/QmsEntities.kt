package kr.co.snail.tacitq.mobile.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * C5 LocalStore 영속 엔티티 — 개발가이드 §7.
 *
 * 규칙 하나가 V-M04 "유실 0건"을 만든다: **전송 큐(outbox)는 어떤 이유로도 지우지 않는다.**
 * 메모리에만 든 것은 전부 잃는다고 가정한다. 서비스는 언제든 안드로이드에 의해 종료될 수 있으므로,
 * 이벤트를 처리할 때마다 즉시 영속화해야 재시작 후 이어갈 수 있다.
 */

/** 검사 이력 1건 — TB_INSPECTION 의 로컬 투영. 동기화되면 [syncedAt] 이 채워진다. */
@Entity(tableName = "inspection")
data class InspectionEntity(
    @PrimaryKey val inspectionId: String,
    val partId: String,
    val capturedAt: Long,
    val captureMode: String,          // VIDEO_STREAM
    val streamSessionId: String,
    val framesReceived: Int,
    val framesSelected: Int,
    val aiVerdict: String?,
    val aiConfidence: Double?,
    val agreeRate: Double?,           // v0.7 — 프레임 간 일치율
    val workerAction: String?,        // AUTO_PASS | CONFIRMED | OBJECTED | TIMEOUT | REVIEW_DECIDED
    val confirmTrigger: String?,
    val finalE2eMs: Int?,
    val lateRecovery: Boolean = false,
    val trayId: String?,
    val syncedAt: Long? = null,       // null 이면 아직 서버에 못 보냈다 — 큐와 짝이다
)

/**
 * 전송 큐 — **순서가 생명이다.** 용량이 부족해도 이 표는 지우지 않는다.
 * id 오름차순이 곧 FIFO 이고, 그 순서로 Edge 에 올라가야 판정이 뒤섞이지 않는다.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,                 // INSPECTION | ALERT | ACTION
    val payload: ByteArray,
    val createdAt: Long,
    val tries: Int = 0,
    val lastError: String? = null,
) {
    // ByteArray 를 든 data class 는 equals/hashCode 를 직접 정의해야 값 비교가 맞는다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEntity) return false
        return id == other.id && kind == other.kind && payload.contentEquals(other.payload) &&
            createdAt == other.createdAt && tries == other.tries && lastError == other.lastError
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + kind.hashCode()
        r = 31 * r + payload.contentHashCode()
        r = 31 * r + createdAt.hashCode()
        r = 31 * r + tries
        r = 31 * r + (lastError?.hashCode() ?: 0)
        return r
    }
}
