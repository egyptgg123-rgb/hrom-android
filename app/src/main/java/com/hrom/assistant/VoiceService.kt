package com.hrom.assistant

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Фоновый «слух» Хрома. Постоянно слушает микрофон, ждёт слово «Хром» (или нажатие кнопки
 * «Слушать» в уведомлении / плитке), затем отдаёт команду мозгу и озвучивает ответ.
 */
class VoiceService : Service() {

    companion object {
        const val ACTION_START = "com.hrom.assistant.START"
        const val ACTION_STOP = "com.hrom.assistant.STOP"
        const val ACTION_LISTEN = "com.hrom.assistant.LISTEN"
        private const val NOTIF_ID = 1001

        @Volatile
        var running = false

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, VoiceService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VoiceService::class.java))
        }

        fun listenNow(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, VoiceService::class.java).setAction(ACTION_LISTEN)
            )
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null
    private var commandMode = false
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notif.ensure(this)
        Speaker.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: Exception) {
            HromLog.add("Не удалось запустить сервис: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (intent?.action == ACTION_LISTEN) {
            commandMode = true
            Util.vibrate(this, 40)
            HromLog.add("Слушаю команду...")
        } else {
            HromLog.add("Хром слушает. Скажи «${Prefs.wakeWord(this)}» и команду")
        }
        restartListening(0)
        return START_STICKY
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val listen = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_LISTEN), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (Prefs.wakeEnabled(this)) "Скажи «${Prefs.wakeWord(this)}» и команду" else "Нажми «Слушать» и говори"
        val n = NotificationCompat.Builder(this, Notif.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Хром на связи")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Слушать", listen)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private fun restartListening(delayMs: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ startRecognizer() }, delayMs)
    }

    private fun startRecognizer() {
        if (!running || busy) return
        // Без слова активации микрофон включается только по кнопке «Слушать».
        if (!Prefs.wakeEnabled(this) && !commandMode) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            HromLog.add("Нет разрешения на микрофон")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            HromLog.add("Распознавание речи недоступно: установи или включи «Голосовой ввод Google»")
            return
        }
        try {
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            r.setRecognitionListener(listener)
            recognizer = r
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            r.startListening(i)
        } catch (e: Exception) {
            HromLog.add("Ошибка микрофона: ${e.message}")
            restartListening(2000)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                HromLog.add("Нет разрешения на микрофон")
                return
            }
            val d = when {
                quiet -> 250L
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                else -> 1000L
            }
            // Если ждали команду после «Слушаю» и ничего не сказали, возвращаемся к ожиданию слова «Хром».
            if (quiet) commandMode = false
            restartListening(d)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = list?.firstOrNull()?.trim().orEmpty()
            onHeard(text)
        }
    }

    private fun onHeard(text: String) {
        if (text.isBlank()) {
            restartListening(200)
            return
        }
        val lower = text.lowercase()
        var cmd: String? = null
        if (commandMode) {
            cmd = text
            commandMode = false
        } else {
            val wake = Prefs.wakeWord(this)
            val variants = if (wake == "хром") listOf("хром", "chrome") else listOf(wake)
            var idx = -1
            var len = 0
            for (v in variants) {
                val k = lower.indexOf(v)
                if (k >= 0 && (idx < 0 || k < idx)) {
                    idx = k
                    len = v.length
                }
            }
            if (idx >= 0) {
                val rest = text.substring(idx + len).trim { it in " ,.!?:;-" }
                if (rest.isEmpty()) {
                    HromLog.add("Слово активации услышано")
                    commandMode = true
                    Speaker.speak("Слушаю")
                    waitSpeechThenListen()
                    return
                }
                cmd = rest
            }
        }
        if (cmd == null) {
            restartListening(150)
            return
        }
        process(cmd)
    }

    private fun process(cmd: String) {
        busy = true
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // не важно
        }
        HromLog.add("Команда: $cmd")
        scope.launch {
            val reply = try {
                Brain.handle(applicationContext, cmd)
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            HromLog.add("Хром: $reply")
            if (Prefs.ttsEnabled(applicationContext) && reply.isNotBlank()) Speaker.speak(reply)
            busy = false
            // Если Хром задал вопрос, следующую фразу принимаем как ответ без слова «Хром».
            if (reply.trim().endsWith("?")) commandMode = true
            waitSpeechThenListen()
        }
    }

    /** Ждём, пока Хром договорит, чтобы он не слышал сам себя, и снова включаем микрофон. */
    private fun waitSpeechThenListen() {
        scope.launch {
            delay(500)
            while (Speaker.isSpeaking()) delay(200)
            delay(350)
            if (running) startRecognizer()
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // не важно
        }
        recognizer = null
        HromLog.add("Хром остановлен")
        super.onDestroy()
    }
}
