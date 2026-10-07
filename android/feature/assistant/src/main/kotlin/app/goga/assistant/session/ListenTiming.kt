package app.goga.assistant.session

/** When a partial transcript is good enough to treat as the heard phrase. */
object ListenTiming {
    const val PARTIAL_STABLE_MS = 1_200L
    const val END_OF_SPEECH_GRACE_MS = 400L
    const val LISTEN_CAP_MS = 10_000L

    fun shouldPromote(partial: String, stableMs: Long, endOfSpeech: Boolean, timedOut: Boolean): Boolean {
        if (partial.trim().isEmpty()) return false
        return endOfSpeech || timedOut || stableMs >= PARTIAL_STABLE_MS
    }
}

/** How long to wait for TTS before the pipeline leaves Speaking on its own. */
fun speechWatchdogMs(text: String): Long = 1_500L + text.length.coerceAtLeast(0) * 90L
