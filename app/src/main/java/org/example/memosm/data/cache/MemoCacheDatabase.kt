package org.example.memosm.data.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for caching memos locally.
 * Uses destructive migration since cache can always be rebuilt from server.
 */
@Database(
    entities = [CachedMemo::class, CachedAttachment::class],
    version = 4,
    exportSchema = false
)
abstract class MemoCacheDatabase : RoomDatabase() {

    abstract fun memoDao(): MemoDao

    companion object {
        @Volatile
        private var INSTANCE: MemoCacheDatabase? = null

        fun getInstance(context: Context): MemoCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MemoCacheDatabase::class.java,
                    "memo_cache_database"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
