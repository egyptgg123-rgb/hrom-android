package com.hrom.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Простой журнал, который показывается на главном экране приложения. */
object HromLog {
    private val items = ArrayList<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    @Synchronized
    fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$t] $msg"
        items.add(line)
        if (items.size > 300) items.removeAt(0)
        listener?.invoke(line)
    }

    @Synchronized
    fun all(): List<String> = ArrayList(items)
}

object Util {
    fun vibrate(ctx: Context, ms: Long) {
        try {
            @Suppress("DEPRECATION")
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(10L, 2000L), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // нет вибромотора или нет прав - не страшно
        }
    }
}

object Notif {
    const val CH_SERVICE = "hrom_service"
    const val CH_ALERT = "hrom_alert"

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Хром слушает", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Напоминания и запуск", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Уведомление «нажми, чтобы запустить Хрома» (после перезагрузки телефона). */
    fun tapToStart(ctx: Context) {
        ensure(ctx)
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("autostart", true)
        val pi = PendingIntent.getActivity(ctx, 10, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Хром выключен")
            .setContentText("Нажми, чтобы запустить ассистента")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(3001, n)
    }
}
