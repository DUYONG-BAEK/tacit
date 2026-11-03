package kr.co.snail.tacitq.mobile.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * C5 로컬 저장고 — 검사 이력 + 전송 큐. 프로세스가 죽어도 파일로 남는다.
 * 스키마는 아직 실증 전이라 export 하지 않는다(exportSchema = false).
 */
@Database(
    entities = [InspectionEntity::class, OutboxEntity::class],
    version = 1,
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
            Room.databaseBuilder(context, QmsDatabase::class.java, DB_NAME).build()
    }
}
