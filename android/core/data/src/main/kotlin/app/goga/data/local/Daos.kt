package app.goga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE user_id = :userId AND deleted_at IS NULL ORDER BY updated_at DESC")
    suspend fun list(userId: String): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NoteEntity)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE user_id = :userId ORDER BY updated_at DESC")
    suspend fun list(userId: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskEntity)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE user_id = :userId AND status = 'scheduled' ORDER BY fire_at")
    suspend fun scheduled(userId: String): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderEntity)
}

@Dao
interface PendingCommandDao {
    @Query("SELECT * FROM pending_commands WHERE device_id = :deviceId ORDER BY created_at")
    suspend fun pending(deviceId: String): List<PendingCommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingCommandEntity)

    @Query("DELETE FROM pending_commands WHERE idempotency_key = :idempotencyKey")
    suspend fun deleteByKey(idempotencyKey: String)
}
