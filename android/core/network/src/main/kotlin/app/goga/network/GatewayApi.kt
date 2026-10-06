package app.goga.network

import app.goga.model.CommandDto
import app.goga.model.CreateCommandRequest
import app.goga.model.EventListResponse
import app.goga.model.Note
import app.goga.model.NoteListResponse
import app.goga.model.PairDeviceRequest
import app.goga.model.PairDeviceResponse
import app.goga.model.PushRegistrationDto
import app.goga.model.RegisterPushRequest
import app.goga.model.UpsertNoteRequest

/** Phone-side client for the stage 0 gateway. The UI does not call it yet. */
interface GatewayApi {
    suspend fun pair(request: PairDeviceRequest): PairDeviceResponse

    suspend fun createCommand(deviceToken: String, request: CreateCommandRequest): CommandDto

    suspend fun getCommand(deviceToken: String, commandId: String): CommandDto

    suspend fun listNotes(deviceToken: String): NoteListResponse

    suspend fun createNote(deviceToken: String, request: UpsertNoteRequest): Note

    suspend fun listEvents(deviceToken: String, since: String? = null): EventListResponse

    suspend fun registerPush(deviceToken: String, request: RegisterPushRequest): PushRegistrationDto
}
