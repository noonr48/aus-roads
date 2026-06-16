package au.com.ausroads.navigation.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class NavigationTts @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isMuted = false

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            tts?.language = Locale("en", "AU")
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) tts?.stop()
    }

    fun isMuted(): Boolean = isMuted

    suspend fun speak(text: String) {
        if (!isInitialized || isMuted) return
        suspendCancellableCoroutine { cont ->
            val utteranceId = "nav_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
            })
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            cont.invokeOnCancellation { tts?.stop() }
        }
    }

    fun speakManeuver(instruction: String, distanceMeters: Double) {
        if (!isInitialized || isMuted) return
        val text = "${formatDistance(distanceMeters)}, $instruction"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maneuver")
    }

    companion object {
        fun formatDistance(distanceMeters: Double): String =
            if (distanceMeters >= 1000) {
                "In ${(distanceMeters / 1000).toInt()} kilometers"
            } else {
                "In ${distanceMeters.toInt()} meters"
            }
    }

    fun speakArrival() {
        if (!isInitialized || isMuted) return
        tts?.speak(context.getString(R.string.nav_arrived_tts), TextToSpeech.QUEUE_FLUSH, null, "arrival")
    }

    fun speakText(text: String) {
        if (!isInitialized || isMuted) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "info")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
