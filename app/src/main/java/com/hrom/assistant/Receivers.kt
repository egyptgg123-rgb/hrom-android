package com.hrom.assistant

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.app.PendingIntent
import androidx.core.app.NotificationCompat

/** После перезагрузки Android 14 не даёт запускать микрофонный сервис сам, поэтому просто просим нажать. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.bootNotify(ctx)) return
        Notif.tapToStart(ctx)
    }
}

/** Срабатывает, когда подошло время напоминания (set_reminder). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "Напоминание"
        Notif.ensure(ctx)
        val n = NotificationCompat.Builder(ctx, Notif.CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Хром: напоминание")
            .setContentText(text)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        val id = (System.currentTimeMillis() % 100000L).toInt() + 2000
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        Speaker.init(ctx)
        Speaker.speak("Напоминание: $text")
    }
}

/** Плитка в шторке быстрых настроек: нажал — Хром слушает команду. */
class HromTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val t = qsTile ?: return
        t.state = if (VoiceService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Хром"
        t.updateTile()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (VoiceService.running) {
            VoiceService.listenNow(this)
        } else {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("autostart", true)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 11, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                startActivityAndCollapse(i)
            }
        }
    }
}
