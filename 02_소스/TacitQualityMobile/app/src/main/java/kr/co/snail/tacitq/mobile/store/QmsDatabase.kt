package kr.co.snail.tacitq.mobile.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * C5 로컬 저장고 — 검사 이력 + 전송 큐. 프로세스가 죽어도 파일로 남는다.
 * 스키마는 아직 실증 전이라 export 하지 않는다(exportSchema = false).
 *
 * 개발 중에는 스키마가 바뀌면 DB 를 지우고 다시 만든다(fallbackToDestructiveMigration) — **이력과 전송 큐(outbox)가
 * 함께 지워진다.** 실사용 전에 exportSchema = true · 명시적 Migration · 마이그레이션 시험으로 바꾼다.
 * v2(2026-11-04): 검사 이력에 원판정 · 결함 종류 · 기준값 · result_id 목록 추가.
 */
@Database(
    entities = [InspectionEntity::class, OutboxEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class QmsDatabase : RoomDatabase() {
    abstract fun dao(): QmsDao

    companion object {
        private const val DB_NAME = "tacitq.db"

        @Volatile private var instance: QmsDatabase? = null

        fun get(context: Context): QmsDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): QmsDatabase =
            Room.databaseBuilder(context, QmsDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
