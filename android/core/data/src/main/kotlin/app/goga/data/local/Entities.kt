package app.goga.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notes", indices = [Index("user_id")])
data class NoteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)

@Entity(tableName = "tasks", indices = [Index("user_id")])
data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    val title: String,
    val notes: String?,
    @ColumnInfo(name = "due_at") val dueAt: String?,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(tableName = "reminders", indices = [Index("user_id"), Index("task_id")])
data class ReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "device_id") val deviceId: String?,
    @ColumnInfo(name = "task_id") val taskId: String?,
    val title: String,
    val body: String?,
    @ColumnInfo(name = "fire_at") val fireAt: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

@Entity(
    tableName = "pending_commands",
    indices = [Index(value = ["device_id", "idempotency_key"], unique = true)],
)
data class PendingCommandEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    val text: String,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)
