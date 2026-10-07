package app.goga.assistant.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionDelegateTest {
    @Test
    fun skipsOwnPackage() {
        val picked = pickRecognitionDelegate(
            listOf(
                RecognizerCandidate("app.goga.assistant", "app.goga.assistant.session.GogaRecognitionService"),
                RecognizerCandidate("com.other", "com.other.Recognizer"),
            ),
            ownPackage = "app.goga.assistant",
        )
        assertEquals("com.other", picked?.packageName)
    }

    @Test
    fun prefersGoogleOnDevicePackage() {
        val picked = pickRecognitionDelegate(
            listOf(
                RecognizerCandidate("com.other", "com.other.Recognizer"),
                RecognizerCandidate("com.hihonor.voice", "com.hihonor.voice.Recognizer"),
                RecognizerCandidate("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.Service"),
                RecognizerCandidate("com.google.android.tts", "com.google.android.apps.speech.tts.Service"),
            ),
            ownPackage = "app.goga.assistant",
        )
        assertEquals("com.google.android.tts", picked?.packageName)
    }

    @Test
    fun prefersOnDeviceClassInsideChosenPackage() {
        val picked = pickRecognitionDelegate(
            listOf(
                RecognizerCandidate("com.google.android.tts", "com.google.android.tts.Network"),
                RecognizerCandidate("com.google.android.tts", "com.google.android.tts.OnDeviceRecognizer"),
            ),
            ownPackage = "app.goga.assistant",
        )
        assertEquals("com.google.android.tts.OnDeviceRecognizer", picked?.className)
    }

    @Test
    fun prefersHonorWhenGoogleIsMissing() {
        val picked = pickRecognitionDelegate(
            listOf(
                RecognizerCandidate("com.other", "com.other.Recognizer"),
                RecognizerCandidate("com.huawei.vassistant", "com.huawei.vassistant.Recognizer"),
                RecognizerCandidate("com.hihonor.voice", "com.hihonor.voice.Recognizer"),
            ),
            ownPackage = "app.goga.assistant",
        )
        assertEquals("com.hihonor.voice", picked?.packageName)
    }

    @Test
    fun returnsNullWhenOnlyOurselves() {
        val picked = pickRecognitionDelegate(
            listOf(
                RecognizerCandidate("app.goga.assistant", "app.goga.assistant.session.GogaRecognitionService"),
                RecognizerCandidate("", "com.missing.Service"),
            ),
            ownPackage = "app.goga.assistant",
        )
        assertNull(picked)
    }
}
