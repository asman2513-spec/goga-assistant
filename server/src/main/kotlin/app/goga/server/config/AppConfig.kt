package app.goga.server.config

import java.util.UUID

data class AppConfig(
    val host: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String?,
    val databasePassword: String?,
    val bootstrapToken: String,
    val inboundSecret: String,
    val inboundMaxSkewSeconds: Long,
    val botBridge: String,
    val webhookUrl: String?,
    val webhookAuthHeader: String,
    val webhookAuthValue: String,
    val webhookTimeoutSeconds: Long,
    val webhookMaxRetries: Int,
    val webhookRetryBaseMillis: Long,
    val publicBaseUrl: String,
    val internalBaseUrl: String,
    val userTimezone: String,
    val userDisplayName: String,
    val singleUserId: String?,
    val pairingCodeTtlSeconds: Long,
    val mockAutoReply: Boolean,
    val mockReplyDelayMillis: Long,
) {
    val sqlite: Boolean get() = databaseUrl.startsWith("jdbc:sqlite:")

    val callbackUrl: String get() = publicBaseUrl.trimEnd('/') + "/v1/bot/results"

    companion object {
        const val VERSION = "0.1.0"

        fun fromEnv(env: (String) -> String? = { System.getenv(it) }): AppConfig {
            fun raw(name: String): String? = env(name)?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String): String =
                raw(name) ?: error("Missing required environment variable $name")

            val port = raw("PORT")?.toIntOrNull() ?: 8080
            val bridge = raw("BOT_BRIDGE") ?: "mock"
            if (bridge != "mock" && bridge != "webhook") {
                error("BOT_BRIDGE must be mock or webhook")
            }
            val bootstrap = required("GATEWAY_BOOTSTRAP_TOKEN")
            val inbound = required("BOT_INBOUND_SECRET")
            require(bootstrap.length >= 16) { "GATEWAY_BOOTSTRAP_TOKEN must be at least 16 characters" }
            require(inbound.length >= 16) { "BOT_INBOUND_SECRET must be at least 16 characters" }

            val webhookUrl = raw("BOT_WEBHOOK_URL")
            val webhookHeader = raw("BOT_WEBHOOK_AUTH_HEADER") ?: "Authorization"
            val webhookValue = raw("BOT_WEBHOOK_AUTH_VALUE")
            if (bridge == "webhook") {
                require(!webhookUrl.isNullOrBlank()) { "BOT_WEBHOOK_URL is required when BOT_BRIDGE=webhook" }
                require(!webhookValue.isNullOrBlank()) {
                    "BOT_WEBHOOK_AUTH_VALUE is required when BOT_BRIDGE=webhook"
                }
            }

            val singleUserId = raw("SINGLE_USER_ID")
            if (singleUserId != null) {
                runCatching { UUID.fromString(singleUserId) }.getOrElse {
                    error("SINGLE_USER_ID must be a UUID")
                }
            }

            val publicBase = raw("GATEWAY_PUBLIC_URL") ?: "http://127.0.0.1:$port"
            return AppConfig(
                host = raw("HOST") ?: "0.0.0.0",
                port = port,
                databaseUrl = raw("DATABASE_URL") ?: "jdbc:sqlite:./data/goga.db",
                databaseUser = raw("DATABASE_USER"),
                databasePassword = raw("DATABASE_PASSWORD"),
                bootstrapToken = bootstrap,
                inboundSecret = inbound,
                inboundMaxSkewSeconds = raw("BOT_INBOUND_MAX_SKEW_SECONDS")?.toLongOrNull() ?: 300,
                botBridge = bridge,
                webhookUrl = webhookUrl,
                webhookAuthHeader = webhookHeader,
                webhookAuthValue = webhookValue.orEmpty(),
                webhookTimeoutSeconds = raw("BOT_WEBHOOK_TIMEOUT_SECONDS")?.toLongOrNull() ?: 10,
                webhookMaxRetries = raw("BOT_WEBHOOK_MAX_RETRIES")?.toIntOrNull() ?: 3,
                webhookRetryBaseMillis = raw("BOT_WEBHOOK_RETRY_BASE_MILLIS")?.toLongOrNull() ?: 200,
                publicBaseUrl = publicBase,
                internalBaseUrl = raw("GATEWAY_INTERNAL_URL") ?: "http://127.0.0.1:$port",
                userTimezone = raw("USER_TIMEZONE") ?: "Europe/Moscow",
                userDisplayName = raw("SINGLE_USER_DISPLAY_NAME") ?: "Пользователь",
                singleUserId = singleUserId,
                pairingCodeTtlSeconds = raw("PAIRING_CODE_TTL_SECONDS")?.toLongOrNull() ?: 600,
                mockAutoReply = raw("BOT_MOCK_AUTO_REPLY")?.let { it != "false" } ?: true,
                mockReplyDelayMillis = raw("BOT_MOCK_REPLY_DELAY_MS")?.toLongOrNull() ?: 50,
            )
        }
    }
}
