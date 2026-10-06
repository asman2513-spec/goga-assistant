package app.goga.data.local

import app.goga.model.Note

fun NoteEntity.toNote(): Note = Note(
    id = id,
    userId = userId,
    deviceId = deviceId,
    title = title,
    body = body,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Note.toEntity(deletedAt: String? = null): NoteEntity = NoteEntity(
    id = id,
    userId = userId,
    deviceId = deviceId,
    title = title,
    body = body,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)
