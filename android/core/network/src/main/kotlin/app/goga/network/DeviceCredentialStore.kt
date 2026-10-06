package app.goga.network

/**
 * Stage 3 stores this in Android Keystore. Stage 0 only defines the shape
 * so the gateway client never keeps the token in a random field.
 */
interface DeviceCredentialStore {
    suspend fun read(): StoredDeviceCredential?

    suspend fun save(credential: StoredDeviceCredential)

    suspend fun clear()
}

data class StoredDeviceCredential(
    val userId: String,
    val deviceId: String,
    val deviceToken: String,
)
