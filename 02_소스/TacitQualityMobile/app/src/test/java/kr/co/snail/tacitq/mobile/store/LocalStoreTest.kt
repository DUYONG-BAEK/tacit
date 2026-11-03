package kr.co.snail.tacitq.mobile.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kr.co.snail.tacitq.mobile.core.InspectionRecord
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V-M04 단위 시험 — C5 LocalStore. 개발가이드 §7 "완료 확인" 네 가지를 노트북에서 검증한다.
 *
 * 파일 기반 Room 을 만들고 [QmsDatabase.close] 로 **프로세스 강제 종료를 모사**한 뒤 같은 파일을
 * 다시 열어 데이터가 남았는지 본다. 핵심 불변식: **전송 큐(outbox)는 어떤 이유로도 사라지지 않는다.**
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalStoreTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** 같은 DB 파일을 여는 새 인스턴스 — 재시작(콜드 스타트)을 흉내낸다. */
    private fun open(name: String): Pair<QmsDatabase, LocalStore> {
        val db = Room.databaseBuilder(ctx, QmsDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        return db to LocalStore(db.dao())
    }

    /** 완료 확인 ①② — 미전송 큐 100건을 만든 뒤 강제 종료해도 100건이 그대로 남는다. */
    @Test fun `강제 종료 후 미전송 큐 100건이 그대로 남는다`() = runTest {
        val name = "vm04_queue.db"
        ctx.deleteDatabase(name)
        val (db, store) = open(name)
        repeat(100) { store.enqueue("p_$it".encodeToByteArray(), now = it.toLong()) }
        assertEquals(100, store.queueSize())
        db.close()                                  // ← 프로세스 강제 종료 모사

        val (db2, store2) = open(name)              // ← 재시작
        assertEquals("유실 0건이어야 한다", 100, store2.queueSize())
        db2.close()
        ctx.deleteDatabase(name)
    }

    /** 완료 확인 ③ — 복구 후 큐가 들어온 순서대로(FIFO) 빠진다. */
    @Test fun `복구 후 큐가 들어온 순서대로 빠진다`() = runTest {
        val name = "vm04_fifo.db"
        ctx.deleteDatabase(name)
        val (db, store) = open(name)
        listOf("a", "b", "c").forEachIndexed { i, p -> store.enqueue(p.encodeToByteArray(), now = i.toLong()) }
        db.close()

        val (db2, store2) = open(name)
        val batch = store2.nextBatch(10)
        assertEquals(listOf("a", "b", "c"), batch.map { String(it.payload) })
        assertEquals("id 오름차순 = FIFO", batch.map { it.id }.sorted(), batch.map { it.id })
        db2.close()
        ctx.deleteDatabase(name)
    }

    /** 완료 확인 ④ — 용량 정리는 동기화된 이력만. 미동기화 이력과 큐는 유지된다. */
    @Test fun `용량 정리는 동기화된 이력만 지우고 큐는 유지한다`() = runTest {
        val name = "vm04_prune.db"
        ctx.deleteDatabase(name)
        val (db, store) = open(name)

        val old = 1_000L
        store.record(rec("A_1", "PASS", "AUTO_PASS", old))   // 오래됐지만 미동기화
        store.record(rec("A_2", "FAIL", "CONFIRMED", old))   // 오래되고 동기화 완료
        store.markSynced("A_2", at = old)
        val queueBefore = store.queueSize()                  // record 2건 → 큐 2건

        store.pruneSynced(before = 5_000L)

        val ids = db.dao().allInspections().map { it.inspectionId }.toSet()
        assertFalse("동기화된 오래된 이력은 지워진다", "A_2" in ids)
        assertTrue("미동기화 이력은 지우면 안 된다", "A_1" in ids)
        assertEquals("큐는 정리에도 그대로 유지된다", queueBefore, store.queueSize())

        db.close()
        ctx.deleteDatabase(name)
    }

    private fun rec(id: String, verdict: String, action: String, at: Long) = InspectionRecord(
        partId = id, aiVerdict = verdict, workerAction = action,
        inputMethod = "NONE", at = at,
    )
}
