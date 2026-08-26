package com.portal.calendar

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Speaks successful voice-command replies through the Portal's stock TTS engine. */
class SpeechOutput(context: Context) {
    private val appContext = context.applicationContext
    private var ready = false
    private var pending: String? = null
    private var tts: TextToSpeech? = null
    private var triedDefaultEngine = false

    init {
        initialize(PORTAL_TTS_ENGINE)
    }

    private fun initialize(enginePackage: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            val engine = tts ?: return@OnInitListener
            if (status != TextToSpeech.SUCCESS) {
                if (!triedDefaultEngine) {
                    triedDefaultEngine = true
                    engine.shutdown()
                    initialize(null)
                }
                return@OnInitListener
            }

            engine.language = Locale.US
            engine.setSpeechRate(0.95f)
            engine.setPitch(1.05f)
            preferredFemaleVoice(engine.voices)?.let { engine.voice = it }
            ready = true
            pending?.let {
                pending = null
                speak(it)
            }
        }
        tts = if (enginePackage == null) TextToSpeech(appContext, listener)
        else TextToSpeech(appContext, listener, enginePackage)
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!ready) {
            pending = clean
            return
        }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun shutdown() {
        pending = null
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun preferredFemaleVoice(voices: Set<Voice>?): Voice? {
        val usable = voices.orEmpty().filter {
            it.locale.language == Locale.US.language && !it.isNetworkConnectionRequired
        }
        return usable.firstOrNull { voice ->
            FEMALE_HINTS.any { hint -> voice.name.contains(hint, ignoreCase = true) }
        } ?: usable.firstOrNull()
    }

    private companion object {
        const val PORTAL_TTS_ENGINE = "com.facebook.aloha.app.ttsservice"
        const val UTTERANCE_ID = "portalhub-ai-reply"
        val FEMALE_HINTS = listOf("female", "woman", "samantha", "victoria", "karen")
    }
}
