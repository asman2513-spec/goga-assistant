package app.goga.assistant.session

data class RecognizerCandidate(
    val packageName: String,
    val className: String,
)

/**
 * Recognizer the overlay should bind to. The configured on-device engine wins when it
 * belongs to another package. Goga's own service is never returned: after the assistant
 * role is granted the system points the default recognizer at that service, and binding
 * to it swallows the utterance.
 *
 * Installed engines are preferred in this order: Google on-device, Android System
 * Intelligence, the Google app, then Honor, then Huawei.
 */
fun chooseRecognizer(
    configuredOnDevice: RecognizerCandidate?,
    installed: List<RecognizerCandidate>,
    ownPackage: String,
): RecognizerCandidate? {
    if (configuredOnDevice != null && configuredOnDevice.isUsable(ownPackage)) {
        return configuredOnDevice
    }
    return pickRecognitionDelegate(installed, ownPackage)
}

/** First choice, then one fallback outside that component. */
fun recognizerOrder(
    configuredOnDevice: RecognizerCandidate?,
    installed: List<RecognizerCandidate>,
    ownPackage: String,
): List<RecognizerCandidate> {
    val primary = chooseRecognizer(configuredOnDevice, installed, ownPackage) ?: return emptyList()
    val rest = installed.filterNot { it.sameComponent(primary) }
    val secondary = pickRecognitionDelegate(rest, ownPackage)
    return if (secondary == null || secondary.sameComponent(primary)) {
        listOf(primary)
    } else {
        listOf(primary, secondary)
    }
}

private fun RecognizerCandidate.isUsable(ownPackage: String): Boolean =
    packageName.isNotBlank() && className.isNotBlank() && packageName != ownPackage

private fun RecognizerCandidate.sameComponent(other: RecognizerCandidate): Boolean =
    packageName == other.packageName && className == other.className

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
