package ai.syne.app.library

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class, TripSampleEntity::class, NoteEntity::class,
        PlaceEntity::class, AlbumEntity::class, TagEntity::class, TrackEditEntity::class,
    ],
    // 3: 사용자 편집 테이블(track_edits) 추가 — 기존 테이블은 그대로(자동 마이그레이션, 데이터 손실 없음)
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class SyneDatabase : RoomDatabase() {
    abstract fun trips(): TripDao

    companion object {
        fun create(context: Context): SyneDatabase =
            Room.databaseBuilder(context.applicationContext, SyneDatabase::class.java, "syne.db").build()
    }
}
