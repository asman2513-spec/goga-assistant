package app.goga.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object Tokens {
    private val random = SecureRandom()
    private const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun deviceToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun pairingCode(): String = buildString(8) {
        repeat(8) { append(PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)]) }
    }

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}

object Hmac {
    fun sign(secret: String, timestamp: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(timestamp.toByteArray(Charsets.UTF_8))
        mac.update(byteArrayOf('.'.code.toByte()))
        mac.update(body)
        return mac.doFinal().toHex()
    }

    fun verify(secret: String, timestamp: String, signatureHex: String, body: ByteArray): Boolean {
        val expected = sign(secret, timestamp, body).hexToBytes() ?: return false
        val provided = signatureHex.trim().lowercase().hexToBytes() ?: return false
        return MessageDigest.isEqual(expected, provided)
    }

    /** Ten-digit Unix seconds, within [skewSeconds] of [nowEpochSeconds]. */
    fun timestampFresh(timestamp: String, nowEpochSeconds: Long, skewSeconds: Long): Boolean {
        if (!timestamp.matches(Regex("^[0-9]{10}$"))) return false
        val stamped = timestamp.toLongOrNull() ?: return false
        return abs(nowEpochSeconds - stamped) <= skewSeconds
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0 || isEmpty()) return null
    return runCatching {
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()
}
