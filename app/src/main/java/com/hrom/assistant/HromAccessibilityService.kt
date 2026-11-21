package com.hrom.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * «Руки» Хрома: читает экран любого приложения, нажимает кнопки, вводит текст,
 * делает свайпы и системные жесты (назад, домой, шторка, скриншот и т.д.).
 */
class HromAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: HromAccessibilityService? = null

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        HromLog.add("Служба доступности подключена")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    /** Текстовый снимок экрана: что видно, что можно нажать, где. */
    fun dumpScreen(maxNodes: Int = 150): String {
        val root = rootInActiveWindow ?: return "Экран недоступен (нет активного окна, возможно телефон заблокирован)"
        val sb = StringBuilder()
        sb.append("Приложение: ").append(root.packageName).append("\n")
        val dm = resources.displayMetrics
        sb.append("Размер экрана: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        var n = 0

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || n >= maxNodes) return
            val text: String? = if (node.isPassword) "(пароль скрыт)" else node.text?.toString()?.take(80)
            val desc: String? = node.contentDescription?.toString()?.take(80)
            val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() || node.isEditable || node.isClickable
            if (interesting && node.isVisibleToUser) {
                val r = Rect()
                node.getBoundsInScreen(r)
                sb.append("- ")
                if (!text.isNullOrBlank()) sb.append("\"").append(text).append("\" ")
                if (!desc.isNullOrBlank() && desc != text) sb.append("[описание: ").append(desc).append("] ")
                if (node.isEditable) sb.append("(поле ввода) ")
                if (node.isClickable) sb.append("(нажимается) ")
                if (node.isChecked) sb.append("(включено) ")
                sb.append("@").append(r.centerX()).append(",").append(r.centerY()).append("\n")
                n++
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }

        walk(root)
        if (n == 0) sb.append("(на экране нет читаемых элементов)\n")
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo, needle: String, out: MutableList<AccessibilityNodeInfo>) {
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (t.contains(needle) || d.contains(needle)) out.add(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, needle, out)
        }
    }

    /** Нажать на элемент по тексту или описанию (точное совпадение в приоритете). */
    fun clickText(text: String, index: Int = 0): String {
        val root = rootInActiveWindow ?: return "нет активного окна"
        val needle = text.trim().lowercase()
        if (needle.isEmpty()) return "не указан текст"
        val found = ArrayList<AccessibilityNodeInfo>()
        collect(root, needle, found)
        val exact = found.filter {
            (it.text?.toString()?.lowercase() ?: "") == needle ||
                (it.contentDescription?.toString()?.lowercase() ?: "") == needle
        }
        val list = if (exact.isNotEmpty()) exact else found
        if (list.isEmpty()) return "не найден элемент «$text» (проверь экран через read_screen)"
        val node = list.getOrNull(index) ?: return "нет элемента с номером $index (найдено ${list.size})"
        return clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo): String {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "нажато"
            }
            cur = cur.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (tap(r.centerX().toFloat(), r.centerY().toFloat())) "нажато по координатам" else "не удалось нажать"
    }

    fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** Прокрутка содержимого: down = показать то, что ниже. */
    fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "down" -> swipe(w / 2, h * 0.75f, w / 2, h * 0.25f)
            "up" -> swipe(w / 2, h * 0.25f, w / 2, h * 0.75f)
            "left" -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> false
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val r = findEditable(node.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /** Ввести текст в активное (или первое видимое) поле ввода. */
    fun typeText(text: String, submit: Boolean): String {
        val root = rootInActiveWindow ?: return "нет активного окна"
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
            ?: return "на экране нет поля ввода"
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return if (ok) "текст введён" else "не удалось ввести текст"
    }
}
