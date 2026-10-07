package app.goga.assistant.session

/**
 * Honor often prints the phrase as a partial and then delivers an empty final,
 * a punctuation-only final, or ERROR_NO_MATCH. Words already heard stay.
 */
fun resolveTranscript(finalText: String?, partialText: String?): String? {
    val finalClean = finalText?.trim().orEmpty()
    val partialClean = partialText?.trim().orEmpty()
    if (hasWords(finalClean)) return finalClean
    if (hasWords(partialClean)) return partialClean
    if (finalClean.isNotEmpty()) return finalClean
    return partialClean.ifEmpty { null }
}

private fun hasWords(text: String): Boolean = text.any { it.isLetterOrDigit() }
