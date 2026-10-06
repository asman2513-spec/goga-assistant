package app.goga.server.db

import app.goga.model.BotEntity
import app.goga.model.BotResultKinds
import app.goga.model.BotResultRequest
import app.goga.model.BotResultResponse
import app.goga.model.CommandDto
import app.goga.model.CommandResultDto
import app.goga.model.CommandSources
import app.goga.model.CommandStatuses
import app.goga.model.CreateCommandRequest
import app.goga.model.CreatePairingCodeResponse
import app.goga.model.EntityTypes
import app.goga.model.InboundEventDto
import app.goga.model.Note
import app.goga.model.PairDeviceResponse
import app.goga.model.PatchNoteRequest
import app.goga.model.PushChannels
import app.goga.model.PushRegistrationDto
import app.goga.model.Timestamps
import app.goga.model.UpsertNoteRequest
import app.goga.server.auth.Tokens
import app.goga.server.config.AppConfig
import app.goga.server.http.badRequest
import app.goga.server.http.conflict
import app.goga.server.http.notFound
import app.goga.server.http.unauthorized
import app.goga.server.json.GatewayJson
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

data class DevicePrincipal(
    val id: String,
    val userId: String,
    val name: String,
    val createdAt: String,
)

data class QueuedCommand(
    val id: String,
    val userId: String,
    val deviceId: String,
    val text: String,
    val source: String,
    val createdAt: String,
)

class GatewayStore(
    private val database: GatewayDatabase,
    private val config: AppConfig,
    private val clock: () -> Instant = { Instant.now() },
) {
    fun ensureUser() {
        tx { sql ->
            val existing = sql.one("SELECT id FROM users LIMIT 1") { it.getString(1) }
            if (existing != null) return@tx
            val now = now()
            sql.update(
                """
                INSERT INTO users (id, display_name, timezone, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                config.singleUserId ?: UUID.randomUUID().toString(),
                config.userDisplayName,
                config.userTimezone,
                now,
                now,
            )
        }
    }

    fun issuePairingCode(): CreatePairingCodeResponse {
        val userId = singleUserId()
        val code = Tokens.pairingCode()
        val nowInstant = clock()
        val now = Timestamps.formatUtc(nowInstant)
        val expires = Timestamps.formatUtc(nowInstant.plusSeconds(config.pairingCodeTtlSeconds))
        tx { sql ->
            sql.update(
                """
                INSERT INTO pairing_codes (id, user_id, code_hash, expires_at, consumed_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                userId,
                Tokens.sha256Hex(code),
                expires,
                now,
            )
        }
        return CreatePairingCodeResponse(code = code, expiresAt = expires, userId = userId)
    }

    fun pairDevice(rawCode: String, rawName: String): PairDeviceResponse {
        val code = rawCode.trim().uppercase()
        if (code.length != 8) unauthorized("invalid_code", "Invalid pairing code")
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 80) badRequest("device_name must be 1..80 characters")
        val token = Tokens.deviceToken()
        val now = now()
        val deviceId = UUID.randomUUID().toString()
        val userId = tx { sql ->
            val row = sql.one(
                "SELECT user_id, expires_at, consumed_at FROM pairing_codes WHERE code_hash = ?",
                Tokens.sha256Hex(code),
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
                ?: unauthorized("invalid_code", "Invalid pairing code")
            if (row.third != null || row.second < now) {
                unauthorized("invalid_code", "Invalid pairing code")
            }
            val updated = sql.update(
                "UPDATE pairing_codes SET consumed_at = ? WHERE code_hash = ? AND consumed_at IS NULL",
                now,
                Tokens.sha256Hex(code),
            )
            if (updated == 0) unauthorized("invalid_code", "Invalid pairing code")
            sql.update(
                """
                INSERT INTO devices (id, user_id, name, token_hash, created_at, updated_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """.trimIndent(),
                deviceId,
                row.first,
                name,
                Tokens.sha256Hex(token),
                now,
                now,
            )
            row.first
        }
        return PairDeviceResponse(deviceId = deviceId, userId = userId, deviceToken = token)
    }

    fun authenticate(token: String): DevicePrincipal? = read { sql ->
        sql.one(
            """
            SELECT id, user_id, name, created_at FROM devices
            WHERE token_hash = ? AND revoked_at IS NULL
            """.trimIndent(),
            Tokens.sha256Hex(token),
        ) {
            DevicePrincipal(
                id = it.getString(1),
                userId = it.getString(2),
                name = it.getString(3),
                createdAt = it.getString(4),
            )
        }
    }

    /** Marks the device revoked. A second call for the same id is a no-op success. */
    fun revokeDevice(deviceId: String) {
        val now = now()
        tx { sql ->
            val exists = sql.one("SELECT 1 FROM devices WHERE id = ?", deviceId) { 1 } != null
            if (!exists) notFound("Device not found")
            sql.update(
                """
                UPDATE devices
                SET revoked_at = ?, updated_at = ?
                WHERE id = ? AND revoked_at IS NULL
                """.trimIndent(),
                now,
                now,
                deviceId,
            )
        }
    }

    fun createCommand(device: DevicePrincipal, request: CreateCommandRequest): Pair<CommandDto, Boolean> {
        val key = request.idempotencyKey.trim()
        requireUuid(key, "idempotency_key")
        val text = request.text.trim()
        if (text.isEmpty() || text.length > 4_000) badRequest("text must be 1..4000 characters")
        if (request.source !in CommandSources.all) badRequest("Unknown source")
        request.clientTimestamp?.let { parseInstant(it, "client_timestamp") }
        val existing = findCommand(device.id, key, byIdempotency = true)
        if (existing != null) {
            if (existing.text != text) conflict("idempotency_key was already used with different text")
            return existing to false
        }
        val id = UUID.randomUUID().toString()
        val now = now()
        try {
            tx { sql ->
                sql.update(
                    """
                    INSERT INTO commands (
                        id, user_id, device_id, idempotency_key, text, source, client_timestamp,
                        status, attempt_count, last_error, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?)
                    """.trimIndent(),
                    id,
                    device.userId,
                    device.id,
                    key,
                    text,
                    request.source,
                    request.clientTimestamp,
                    CommandStatuses.QUEUED,
                    now,
                    now,
                )
            }
        } catch (error: SQLException) {
            if (!isUniqueViolation(error)) throw error
            val raced = findCommand(device.id, key, byIdempotency = true)
                ?: throw error
            if (raced.text != text) conflict("idempotency_key was already used with different text")
            return raced to false
        }
        return (findCommand(device.id, id, byIdempotency = false) ?: error("command insert vanished")) to true
    }

    fun commandForDevice(device: DevicePrincipal, commandId: String): CommandDto =
        findCommand(device.id, commandId, byIdempotency = false) ?: notFound("Command not found")

    fun claimNext(now: String): QueuedCommand? = tx { sql ->
        val id = sql.one(
            "SELECT id FROM commands WHERE status = ? ORDER BY created_at ASC LIMIT 1",
            CommandStatuses.QUEUED,
        ) { it.getString(1) } ?: return@tx null
        val updated = sql.update(
            """
            UPDATE commands
            SET status = ?, updated_at = ?, attempt_count = attempt_count + 1
            WHERE id = ? AND status = ?
            """.trimIndent(),
            CommandStatuses.DISPATCHED,
            now,
            id,
            CommandStatuses.QUEUED,
        )
        if (updated == 0) return@tx null
        sql.one(
            "SELECT id, user_id, device_id, text, source, created_at FROM commands WHERE id = ?",
            id,
        ) {
            QueuedCommand(
                id = it.getString(1),
                userId = it.getString(2),
                deviceId = it.getString(3),
                text = it.getString(4),
                source = it.getString(5),
                createdAt = it.getString(6),
            )
        }
    }

    fun markFailed(commandId: String, error: String) {
        tx { sql ->
            sql.update(
                """
                UPDATE commands SET status = ?, last_error = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """.trimIndent(),
                CommandStatuses.FAILED,
                error.take(300),
                now(),
                commandId,
                CommandStatuses.DISPATCHED,
            )
        }
    }

    fun findResultBySignature(signature: String): BotResultResponse? = read { sql ->
        val resultId = sql.one(
            "SELECT result_id FROM result_signatures WHERE signature = ?",
            signature,
        ) { it.getString(1) } ?: return@read null
        resultResponse(sql, resultId, idempotent = true)
    }

    fun acceptResult(raw: ByteArray, signature: String, request: BotResultRequest): BotResultResponse {
        validateResult(request)
        val bodyHash = Tokens.sha256Hex(raw)
        return try {
            tx { sql -> acceptInTransaction(sql, request, signature, bodyHash) }
        } catch (error: SQLException) {
            if (!isUniqueViolation(error)) throw error
            findResultBySignature(signature)
                ?: request.commandId?.let { findResultByCommand(it, bodyHash) }
                ?: request.eventId?.let { findResultByEvent(it, bodyHash) }
                ?: throw error
        }
    }

    fun listNotes(userId: String, limit: Int): List<Note> = read { sql ->
        sql.list(
            """
            SELECT id, user_id, device_id, title, body, created_at, updated_at
            FROM notes
            WHERE user_id = ? AND deleted_at IS NULL
            ORDER BY updated_at DESC
            LIMIT ?
            """.trimIndent(),
            userId,
            limit,
        ) { it.toNote() }
    }

    fun note(userId: String, noteId: String): Note = read { sql ->
        sql.one(
            """
            SELECT id, user_id, device_id, title, body, created_at, updated_at
            FROM notes
            WHERE id = ? AND user_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            noteId,
            userId,
        ) { it.toNote() }
    } ?: notFound("Note not found")

    fun createNote(device: DevicePrincipal, request: UpsertNoteRequest): Note {
        val title = cleanTitle(request.title)
        val body = cleanBody(request.body)
        val now = now()
        val id = UUID.randomUUID().toString()
        tx { sql ->
            sql.update(
                """
                INSERT INTO notes (id, user_id, device_id, title, body, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                """.trimIndent(),
                id,
                device.userId,
                device.id,
                title,
                body,
                now,
                now,
            )
        }
        return note(device.userId, id)
    }

    fun patchNote(userId: String, noteId: String, request: PatchNoteRequest): Note {
        if (request.title == null && request.body == null) badRequest("title or body is required")
        val current = note(userId, noteId)
        val title = request.title?.let { cleanTitle(it) } ?: current.title
        val body = request.body?.let { cleanBody(it) } ?: current.body
        val now = now()
        tx { sql ->
            sql.update(
                "UPDATE notes SET title = ?, body = ?, updated_at = ? WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
                title,
                body,
                now,
                noteId,
                userId,
            )
        }
        return note(userId, noteId)
    }

    fun deleteNote(userId: String, noteId: String) {
        val now = now()
        val updated = tx { sql ->
            sql.update(
                """
                UPDATE notes SET deleted_at = ?, updated_at = ?
                WHERE id = ? AND user_id = ? AND deleted_at IS NULL
                """.trimIndent(),
                now,
                now,
                noteId,
                userId,
            )
        }
        if (updated == 0) notFound("Note not found")
    }

    fun registerPush(device: DevicePrincipal, channel: String, token: String): PushRegistrationDto {
        if (channel !in PushChannels.all) badRequest("Unknown push channel")
        val clean = token.trim()
        if (clean.isEmpty() || clean.length > 4_096) badRequest("token must be 1..4096 characters")
        val now = now()
        tx { sql ->
            sql.update(
                """
                INSERT INTO push_registrations (id, user_id, device_id, channel, token, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (device_id, channel) DO UPDATE SET token = excluded.token, updated_at = excluded.updated_at
                """.trimIndent(),
                UUID.randomUUID().toString(),
                device.userId,
                device.id,
                channel,
                clean,
                now,
                now,
            )
        }
        return read { sql ->
            sql.one(
                "SELECT id, device_id, channel, updated_at FROM push_registrations WHERE device_id = ? AND channel = ?",
                device.id,
                channel,
            ) {
                PushRegistrationDto(
                    id = it.getString(1),
                    deviceId = it.getString(2),
                    channel = it.getString(3),
                    updatedAt = it.getString(4),
                )
            }
        } ?: error("push registration missing")
    }

    fun listEvents(device: DevicePrincipal, since: String?, limit: Int): List<InboundEventDto> = read { sql ->
        sql.list(
            """
            SELECT id, event_id, command_id, kind, reply_text, question, entities_json, created_at
            FROM command_results
            WHERE user_id = ?
              AND command_id IS NULL
              AND (device_id IS NULL OR device_id = ?)
              AND (? IS NULL OR created_at > ?)
            ORDER BY created_at ASC
            LIMIT ?
            """.trimIndent(),
            device.userId,
            device.id,
            since,
            since,
            limit,
        ) {
            InboundEventDto(
                id = it.getString(1),
                eventId = it.getString(2),
                commandId = it.getString(3),
                kind = it.getString(4),
                replyText = it.getString(5),
                question = it.getString(6),
                entities = decodeEntities(it.getString(7)),
                createdAt = it.getString(8),
            )
        }
    }

    private fun acceptInTransaction(
        sql: Sql,
        request: BotResultRequest,
        signature: String,
        bodyHash: String,
    ): BotResultResponse {
        val now = now()
        val existingSignature = sql.one(
            "SELECT result_id FROM result_signatures WHERE signature = ?",
            signature,
        ) { it.getString(1) }
        if (existingSignature != null) return resultResponse(sql, existingSignature, idempotent = true)

        val commandId = request.commandId
        if (commandId != null) {
            requireUuid(commandId, "command_id")
            val command = sql.one(
                "SELECT user_id, device_id FROM commands WHERE id = ?",
                commandId,
            ) { it.getString(1) to it.getString(2) } ?: notFound("Unknown command_id")
            val existing = sql.one(
                "SELECT id, body_hash FROM command_results WHERE command_id = ?",
                commandId,
            ) { it.getString(1) to it.getString(2) }
            if (existing != null) {
                if (existing.second != bodyHash) conflict("command_id already has a different result")
                rememberSignature(sql, signature, existing.first, now)
                return resultResponse(sql, existing.first, idempotent = true)
            }
            return insertResult(
                sql = sql,
                request = request,
                signature = signature,
                bodyHash = bodyHash,
                userId = command.first,
                deviceId = command.second,
                now = now,
                completeCommand = true,
            )
        }

        val eventId = request.eventId ?: badRequest("event_id is required when command_id is absent")
        requireUuid(eventId, "event_id")
        val existingEvent = sql.one(
            "SELECT id, body_hash FROM command_results WHERE event_id = ?",
            eventId,
        ) { it.getString(1) to it.getString(2) }
        if (existingEvent != null) {
            if (existingEvent.second != bodyHash) conflict("event_id already has a different result")
            rememberSignature(sql, signature, existingEvent.first, now)
            return resultResponse(sql, existingEvent.first, idempotent = true)
        }
        val userId = singleUserId(sql)
        val deviceId = request.deviceId?.let { raw ->
            requireUuid(raw, "device_id")
            val owner = sql.one(
                "SELECT user_id FROM devices WHERE id = ? AND revoked_at IS NULL",
                raw,
            ) { it.getString(1) } ?: badRequest("Unknown device_id")
            if (owner != userId) badRequest("device_id does not belong to the user")
            raw
        }
        return insertResult(
            sql = sql,
            request = request,
            signature = signature,
            bodyHash = bodyHash,
            userId = userId,
            deviceId = deviceId,
            now = now,
            completeCommand = false,
        )
    }

    private fun insertResult(
        sql: Sql,
        request: BotResultRequest,
        signature: String,
        bodyHash: String,
        userId: String,
        deviceId: String?,
        now: String,
        completeCommand: Boolean,
    ): BotResultResponse {
        val createdNotes = mutableListOf<String>()
        val entities = request.entities.map { entity ->
            if (entity.type != EntityTypes.NOTE) entity
            else materializeNote(sql, entity, userId, deviceId, now, createdNotes)
        }
        val resultId = UUID.randomUUID().toString()
        sql.update(
            """
            INSERT INTO command_results (
                id, command_id, event_id, user_id, device_id, kind, reply_text, question,
                entities_json, body_hash, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            resultId,
            request.commandId,
            request.eventId,
            userId,
            deviceId,
            request.kind,
            request.replyText,
            request.question,
            encodeEntities(entities),
            bodyHash,
            now,
        )
        rememberSignature(sql, signature, resultId, now)
        if (completeCommand) {
            sql.update(
                "UPDATE commands SET status = ?, last_error = NULL, updated_at = ? WHERE id = ?",
                CommandStatuses.COMPLETED,
                now,
                request.commandId,
            )
        }
        return BotResultResponse(
            resultId = resultId,
            commandId = request.commandId,
            eventId = request.eventId,
            status = if (completeCommand) CommandStatuses.COMPLETED else "accepted",
            createdNoteIds = createdNotes,
            idempotent = false,
        )
    }

    private fun materializeNote(
        sql: Sql,
        entity: BotEntity,
        userId: String,
        deviceId: String?,
        now: String,
        created: MutableList<String>,
    ): BotEntity {
        val noteId = entity.id ?: UUID.randomUUID().toString()
        if (entity.id != null) requireUuid(noteId, "entity.id")
        val title = entity.title?.trim().orEmpty().ifBlank { "Заметка" }.take(200)
        val body = entity.body?.trim().orEmpty().ifBlank { title }.take(20_000)
        val exists = sql.one("SELECT id FROM notes WHERE id = ?", noteId) { it.getString(1) }
        if (exists == null) {
            sql.update(
                """
                INSERT INTO notes (id, user_id, device_id, title, body, created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                """.trimIndent(),
                noteId,
                userId,
                deviceId,
                title,
                body,
                now,
                now,
            )
            created += noteId
        }
        return entity.copy(id = noteId, title = title, body = body)
    }

    private fun rememberSignature(sql: Sql, signature: String, resultId: String, now: String) {
        val known = sql.one(
            "SELECT result_id FROM result_signatures WHERE signature = ?",
            signature,
        ) { it.getString(1) }
        if (known == null) {
            sql.update(
                "INSERT INTO result_signatures (signature, result_id, seen_at) VALUES (?, ?, ?)",
                signature,
                resultId,
                now,
            )
        }
    }

    private fun findResultByCommand(commandId: String, bodyHash: String): BotResultResponse? = read { sql ->
        val row = sql.one(
            "SELECT id, body_hash FROM command_results WHERE command_id = ?",
            commandId,
        ) { it.getString(1) to it.getString(2) } ?: return@read null
        if (row.second != bodyHash) conflict("command_id already has a different result")
        resultResponse(sql, row.first, idempotent = true)
    }

    private fun findResultByEvent(eventId: String, bodyHash: String): BotResultResponse? = read { sql ->
        val row = sql.one(
            "SELECT id, body_hash FROM command_results WHERE event_id = ?",
            eventId,
        ) { it.getString(1) to it.getString(2) } ?: return@read null
        if (row.second != bodyHash) conflict("event_id already has a different result")
        resultResponse(sql, row.first, idempotent = true)
    }

    private fun resultResponse(sql: Sql, resultId: String, idempotent: Boolean): BotResultResponse {
        val loaded = sql.one(
            """
            SELECT command_id, event_id, entities_json FROM command_results WHERE id = ?
            """.trimIndent(),
            resultId,
        ) { Triple(it.getString(1), it.getString(2), it.getString(3)) } ?: notFound("Result not found")
        val noteIds = decodeEntities(loaded.third).mapNotNull { entity ->
            if (entity.type == EntityTypes.NOTE) entity.id else null
        }
        val status = if (loaded.first != null) CommandStatuses.COMPLETED else "accepted"
        return BotResultResponse(
            resultId = resultId,
            commandId = loaded.first,
            eventId = loaded.second,
            status = status,
            createdNoteIds = noteIds,
            idempotent = idempotent,
        )
    }

    private fun findCommand(deviceId: String, key: String, byIdempotency: Boolean): CommandDto? = read { sql ->
        val column = if (byIdempotency) "c.idempotency_key" else "c.id"
        sql.one(
            """
            SELECT c.id, c.user_id, c.device_id, c.idempotency_key, c.text, c.source, c.status,
                   c.client_timestamp, c.created_at, c.updated_at, c.last_error,
                   r.id, r.kind, r.reply_text, r.question, r.entities_json, r.created_at
            FROM commands c
            LEFT JOIN command_results r ON r.command_id = c.id
            WHERE c.device_id = ? AND $column = ?
            """.trimIndent(),
            deviceId,
            key,
        ) { rs -> rs.toCommand() }
    }

    private fun singleUserId(): String = read { sql -> singleUserId(sql) }

    private fun singleUserId(sql: Sql): String =
        sql.one("SELECT id FROM users LIMIT 1") { it.getString(1) } ?: error("User row is missing")

    private fun now(): String = Timestamps.formatUtc(clock())

    private fun <T> tx(block: (Sql) -> T): T = database.connect { connection ->
        connection.autoCommit = false
        try {
            val result = block(Sql(connection))
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun <T> read(block: (Sql) -> T): T = database.connect { connection -> block(Sql(connection)) }
}

private fun validateResult(request: BotResultRequest) {
    if (request.commandId == null && request.eventId == null) {
        badRequest("command_id or event_id is required")
    }
    request.commandId?.let { requireUuid(it, "command_id") }
    request.eventId?.let { requireUuid(it, "event_id") }
    when (request.kind) {
        BotResultKinds.REPLY ->
            if (request.replyText.isNullOrBlank()) badRequest("reply requires reply_text")
        BotResultKinds.CLARIFICATION ->
            if (request.question.isNullOrBlank()) badRequest("clarification requires question")
        BotResultKinds.EVENT ->
            if (request.replyText.isNullOrBlank() && request.entities.isEmpty()) {
                badRequest("event requires reply_text or entities")
            }
        else -> badRequest("Unknown kind")
    }
    if ((request.replyText?.length ?: 0) > 8_000) badRequest("reply_text is too long")
    if ((request.question?.length ?: 0) > 2_000) badRequest("question is too long")
    if (request.entities.size > 20) badRequest("too many entities")
    request.entities.forEach { entity ->
        if (entity.type !in EntityTypes.all) badRequest("Unknown entity type ${entity.type}")
        if (entity.type == EntityTypes.NOTE && entity.title.isNullOrBlank() && entity.body.isNullOrBlank()) {
            badRequest("note entity needs a title or a body")
        }
        entity.id?.let { requireUuid(it, "entity.id") }
    }
}

internal fun requireUuid(value: String, field: String) {
    val ok = value.matches(
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"),
    )
    if (!ok) badRequest("$field must be a UUID")
}

internal fun parseInstant(value: String, field: String): Instant = try {
    Instant.parse(value)
} catch (error: Exception) {
    badRequest("$field must be ISO-8601")
}

private fun cleanTitle(value: String): String {
    val title = value.trim()
    if (title.isEmpty() || title.length > 200) badRequest("title must be 1..200 characters")
    return title
}

private fun cleanBody(value: String): String {
    if (value.length > 20_000) badRequest("body must be at most 20000 characters")
    return value
}

private fun encodeEntities(entities: List<BotEntity>): String =
    GatewayJson.encodeToString(ListSerializer(BotEntity.serializer()), entities)

private fun decodeEntities(json: String): List<BotEntity> =
    GatewayJson.decodeFromString(ListSerializer(BotEntity.serializer()), json)

private fun ResultSet.toNote(): Note = Note(
    id = getString(1),
    userId = getString(2),
    deviceId = getString(3),
    title = getString(4),
    body = getString(5),
    createdAt = getString(6),
    updatedAt = getString(7),
)

private fun ResultSet.toCommand(): CommandDto {
    val resultId = getString(12)
    val result = if (resultId == null) {
        null
    } else {
        CommandResultDto(
            id = resultId,
            kind = getString(13),
            replyText = getString(14),
            question = getString(15),
            entities = decodeEntities(getString(16)),
            createdAt = getString(17),
        )
    }
    return CommandDto(
        id = getString(1),
        userId = getString(2),
        deviceId = getString(3),
        idempotencyKey = getString(4),
        text = getString(5),
        source = getString(6),
        status = getString(7),
        clientTimestamp = getString(8),
        createdAt = getString(9),
        updatedAt = getString(10),
        error = getString(11),
        result = result,
    )
}
