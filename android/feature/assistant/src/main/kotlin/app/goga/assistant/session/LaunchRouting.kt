package app.goga.assistant.session

enum class LaunchPath {
    /** Activity in our own package, started with startAssistantActivity. */
    ASSISTANT,

    /** Someone else's activity. A trampoline in our package does a normal startActivity. */
    TRAMPOLINE,
}

/**
 * startVoiceActivity only resolves activities that opted into voice interaction.
 * Dialer, camera, clock, and settings panels do not, so a null or foreign component
 * always goes through the trampoline.
 */
fun routeLaunch(componentPackages: List<String?>, ownPackage: String): LaunchPath {
    if (componentPackages.isNotEmpty() && componentPackages.all { it == ownPackage }) {
        return LaunchPath.ASSISTANT
    }
    return LaunchPath.TRAMPOLINE
}
