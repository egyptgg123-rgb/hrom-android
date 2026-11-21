package com.hrom.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Читает уведомления, чтобы Хром мог ответить на «что мне пришло?». */
class HromNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: HromNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        HromLog.add("Доступ к уведомлениям подключён")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    private fun appLabel(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

    /** Сводка текущих уведомлений в шторке (новые сверху). */
    fun summary(limit: Int): String {
        val list: Array<StatusBarNotification> = try {
            activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        val sorted = list.sortedByDescending { it.postTime }
        val sb = StringBuilder()
        var n = 0
        for (sbn in sorted) {
            if (n >= limit) break
            if (sbn.packageName == packageName) continue
            val e = sbn.notification.extras
            val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) continue
            sb.append(appLabel(sbn.packageName)).append(": ").append(title)
            if (text.isNotBlank()) sb.append(" — ").append(text.take(200))
            sb.append("\n")
            n++
        }
        return if (n == 0) "Уведомлений нет" else sb.toString()
    }

    fun clearAll() {
        cancelAllNotifications()
    }
}
