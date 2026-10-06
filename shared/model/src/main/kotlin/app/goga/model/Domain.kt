package app.goga.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sync records. Notes stay independent of tasks, reminders, and calendar events.
 * A reminder may mention a task only by [Reminder.taskId].
 */
@Serializable
data class Note(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String? = null,
    val title: String,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class Task(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String? = null,
    val title: String,
    val notes: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    val status: String = "open",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class Reminder(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    val title: String,
    val body: String? = null,
    @SerialName("fire_at") val fireAt: String,
    val status: String = "scheduled",
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CalendarEvent(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String? = null,
    val title: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    val provider: String,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)
