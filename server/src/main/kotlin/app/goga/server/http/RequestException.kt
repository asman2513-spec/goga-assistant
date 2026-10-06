package app.goga.server.http

class RequestException(
    val status: Int,
    val code: String,
    message: String,
) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw RequestException(400, "bad_request", message)

fun unauthorized(code: String, message: String): Nothing = throw RequestException(401, code, message)

fun notFound(message: String): Nothing = throw RequestException(404, "not_found", message)

fun conflict(message: String): Nothing = throw RequestException(409, "conflict", message)

fun payloadTooLarge(): Nothing = throw RequestException(413, "payload_too_large", "Body exceeds 100 KiB")
