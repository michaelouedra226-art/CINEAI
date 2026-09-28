package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.CreationDao
import com.example.data.dao.FilmDao
import com.example.data.dao.QueueDao
import com.example.data.dao.SettingsDao
import com.example.data.dao.UsageDao
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.data.model.QueueItemEntity
import com.example.data.model.SettingsEntity
import com.example.data.model.UsageEntity

@Database(
    entities = [
        CreationEntity::class,
        FilmEntity::class,
        UsageEntity::class,
        SettingsEntity::class,
        QueueItemEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AgnesDatabase : RoomDatabase() {
    abstract fun creationDao(): CreationDao
    abstract fun filmDao(): FilmDao
    abstract fun usageDao(): UsageDao
    abstract fun settingsDao(): SettingsDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var INSTANCE: AgnesDatabase? = null

        fun getInstance(context: Context): AgnesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AgnesDatabase::class.java,
                    "agnes_studio_v2.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
