package app.goga.assistant.session

data class RecognizerCandidate(
    val packageName: String,
    val className: String,
)

/**
 * Picks another app's [android.speech.RecognitionService].
 * Goga's own service only satisfies the assistant metadata contract and must
 * not be chosen: binding back to this package loops.
 * Prefer the on-device Google engine, then the Google app, then Honor/Huawei.
 */
fun pickRecognitionDelegate(
    candidates: List<RecognizerCandidate>,
    ownPackage: String,
): RecognizerCandidate? {
    val others = candidates.filter { candidate ->
        candidate.packageName.isNotBlank() &&
            candidate.className.isNotBlank() &&
            candidate.packageName != ownPackage
    }
    if (others.isEmpty()) return null
    for (packageName in PREFERRED_PACKAGES) {
        val inPackage = others.filter { it.packageName == packageName }
        if (inPackage.isNotEmpty()) return preferOnDevice(inPackage)
    }
    val honor = others.filter { it.packageName.startsWith(HONOR_PREFIX) }
    if (honor.isNotEmpty()) return preferOnDevice(honor)
    val huawei = others.filter { it.packageName.startsWith(HUAWEI_PREFIX) }
    if (huawei.isNotEmpty()) return preferOnDevice(huawei)
    return preferOnDevice(others)
}

private fun preferOnDevice(candidates: List<RecognizerCandidate>): RecognizerCandidate =
    candidates.firstOrNull { simpleName(it.className).contains("ondevice", ignoreCase = true) }
        ?: candidates.firstOrNull { simpleName(it.className).contains("offline", ignoreCase = true) }
        ?: candidates.first()

private fun simpleName(className: String): String = className.substringAfterLast('.')

private val PREFERRED_PACKAGES = listOf(
    "com.google.android.tts",
    "com.google.android.as",
    "com.google.android.googlequicksearchbox",
)

private const val HONOR_PREFIX = "com.hihonor."
private const val HUAWEI_PREFIX = "com.huawei."
