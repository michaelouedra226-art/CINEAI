package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CreationEntity
import com.example.data.model.FilmEntity
import com.example.data.model.QueueItemEntity
import com.example.data.model.SettingsEntity
import com.example.data.model.UsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CreationDao {
    @Query("SELECT * FROM creations ORDER BY createdAt DESC")
    fun getAllCreations(): Flow<List<CreationEntity>>

    @Query("SELECT * FROM creations WHERE type = :type ORDER BY createdAt DESC")
    fun getCreationsByType(type: String): Flow<List<CreationEntity>>

    @Query("SELECT * FROM creations ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentCreations(limit: Int = 10): Flow<List<CreationEntity>>

    @Query("SELECT * FROM creations WHERE id = :id")
    suspend fun getCreationById(id: String): CreationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(creation: CreationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(creations: List<CreationEntity>)

    @Update
    suspend fun update(creation: CreationEntity)

    @Query("DELETE FROM creations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE creations SET favorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: String, favorite: Boolean)

    @Query("UPDATE creations SET status = :status, progress = :progress, resultUrl = :resultUrl, thumbnail = :thumbnail, error = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
        progress: Int,
        resultUrl: String?,
        thumbnail: String?,
        error: String?,
        updatedAt: Long = System.currentTimeMillis()
    )
}

@Dao
interface FilmDao {
    @Query("SELECT * FROM films ORDER BY createdAt DESC")
    fun getAllFilms(): Flow<List<FilmEntity>>

    @Query("SELECT * FROM films WHERE id = :id")
    fun getFilmById(id: String): Flow<FilmEntity?>

    @Query("SELECT * FROM films WHERE id = :id")
    suspend fun getFilmByIdDirect(id: String): FilmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(film: FilmEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(films: List<FilmEntity>)

    @Query("SELECT COUNT(*) FROM films")
    suspend fun getFilmCount(): Int

    @Update
    suspend fun update(film: FilmEntity)

    @Query("DELETE FROM films WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE films SET favorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: String, favorite: Boolean)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage WHERE date = :date")
    fun getUsageForDate(date: String): Flow<UsageEntity?>

    @Query("SELECT * FROM usage WHERE date = :date")
    suspend fun getUsageForDateDirect(date: String): UsageEntity?

    @Query("SELECT * FROM usage ORDER BY date DESC")
    fun getAllUsage(): Flow<List<UsageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(usage: UsageEntity)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 'main'")
    fun getSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 'main'")
    suspend fun getSettingsDirect(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settings: SettingsEntity)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY addedAt ASC")
    fun getAllQueueItems(): Flow<List<QueueItemEntity>>

    @Query("SELECT * FROM queue WHERE status IN ('queued', 'processing', 'stalled') ORDER BY addedAt ASC")
    suspend fun getActiveQueueItems(): List<QueueItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueItemEntity)

    @Update
    suspend fun update(item: QueueItemEntity)

    @Query("DELETE FROM queue WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM queue WHERE status IN ('done', 'failed')")
    suspend fun clearCompleted()
}
