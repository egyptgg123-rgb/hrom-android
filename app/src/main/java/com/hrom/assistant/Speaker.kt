package com.hrom.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Голос Хрома (встроенный синтезатор речи Android). */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()

    @Synchronized
    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext, this)
    }

    @Synchronized
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            HromLog.add("Синтезатор речи не запустился")
            return
        }
        tts?.setLanguage(Locale.forLanguageTag("ru-RU"))
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
        })
        ready = true
        for (p in pending) say(p)
        pending.clear()
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "hrom-" + System.currentTimeMillis())
    }

    @Synchronized
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pending.add(text)
            return
        }
        say(text)
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true
}
