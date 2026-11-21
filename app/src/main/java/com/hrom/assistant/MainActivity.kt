package com.hrom.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Главный экран: настройка ключа, выдача разрешений, запуск и тест Хрома. */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var keyEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var wakeEdit: EditText
    private lateinit var nameEdit: EditText
    private lateinit var cmdEdit: EditText
    private lateinit var wakeCheck: CheckBox
    private lateinit var ttsCheck: CheckBox
    private lateinit var bootCheck: CheckBox

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Speaker.init(this)
        Notif.ensure(this)

        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(col)
        setContentView(scroll)

        val head = heading("Хром — личный голосовой ассистент")
        head.setPadding(0, 0, 0, dp(6))
        col.addView(head)
        statusView = text("")
        col.addView(statusView)

        col.addView(heading("1. Разрешения и доступы"))
        col.addView(note("Выдай всё по очереди. Без «Службы доступности» Хром не сможет нажимать кнопки в других приложениях."))
        col.addView(button("Разрешения (микрофон, контакты, звонки, SMS, уведомления)") { askPermissions() })
        col.addView(button("Служба доступности (управление экраном)") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, false) })
        col.addView(button("Доступ к уведомлениям") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false) })
        col.addView(button("Доступ к «Не беспокоить»") { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, false) })
        col.addView(button("Изменение системных настроек (яркость)") { openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) })
        col.addView(button("Поверх других окон (запуск приложений из фона)") { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) })
        col.addView(button("Не ограничивать батарею") { openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) })
        col.addView(button("Информация о приложении (если «Служба доступности» серая — см. README)") {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        })

        col.addView(heading("2. Настройки ИИ"))
        col.addView(note("Ключ берётся на openrouter.ai → Keys. Он хранится только на этом телефоне. Бесплатные модели работают без пополнения счёта, но слабее и с лимитами."))
        keyEdit = edit("Ключ OpenRouter (sk-or-...)", Prefs.apiKey(this), true)
        col.addView(keyEdit)
        modelEdit = edit("Модель (например vendor/name или vendor/name:free)", Prefs.model(this), false)
        col.addView(modelEdit)
        col.addView(button("Найти бесплатные модели (с поддержкой функций)") { pickFreeModel() })
        wakeEdit = edit("Слово активации", Prefs.wakeWord(this), false)
        col.addView(wakeEdit)
        nameEdit = edit("Как к тебе обращаться (необязательно)", Prefs.userName(this), false)
        col.addView(nameEdit)
        wakeCheck = check("Слушать слово активации всегда (выкл — только кнопка «Слушать»)", Prefs.wakeEnabled(this))
        col.addView(wakeCheck)
        ttsCheck = check("Озвучивать ответы голосом", Prefs.ttsEnabled(this))
        col.addView(ttsCheck)
        bootCheck = check("После перезагрузки напомнить запустить Хрома", Prefs.bootNotify(this))
        col.addView(bootCheck)
        col.addView(button("Сохранить настройки") {
            saveSettings()
            toast("Сохранено")
        })

        col.addView(heading("3. Запуск"))
        col.addView(button("▶ Запустить Хрома") { startAssistant() })
        col.addView(button("🎤 Слушать сейчас") {
            if (VoiceService.running) VoiceService.listenNow(this) else startAssistant()
        })
        col.addView(button("■ Остановить Хрома") {
            VoiceService.stop(this)
            statusView.postDelayed({ refresh() }, 600)
        })
        col.addView(button("Забыть текущий разговор") {
            Brain.clearHistory()
            toast("Контекст разговора очищен")
        })

        col.addView(heading("4. Проверка текстом"))
        cmdEdit = edit("Например: включи фонарик", "", false)
        col.addView(cmdEdit)
        col.addView(button("Выполнить") { runText() })

        col.addView(heading("Журнал"))
        logView = text("")
        col.addView(logView)
    }

    // --- построение интерфейса -----------------------------------------

    private fun text(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 14f
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun heading(s: String): TextView {
        val t = text(s)
        t.textSize = 18f
        t.setPadding(0, dp(20), 0, dp(6))
        return t
    }

    private fun note(s: String): TextView {
        val t = text(s)
        t.textSize = 12f
        return t
    }

    private fun button(s: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun edit(hint: String, value: String, password: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        if (password) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        return e
    }

    private fun check(s: String, value: Boolean): CheckBox {
        val c = CheckBox(this)
        c.text = s
        c.isChecked = value
        return c
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // --- действия ------------------------------------------------------

    private fun openSettings(action: String, withPackage: Boolean) {
        try {
            val i = Intent(action)
            if (withPackage) i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            toast("Не удалось открыть: ${e.message}")
        }
    }

    private fun askPermissions() {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.CALL_PHONE)
        perms.add(Manifest.permission.READ_CONTACTS)
        perms.add(Manifest.permission.SEND_SMS)
        perms.add(Manifest.permission.READ_SMS)
        perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 100)
    }

    private fun saveSettings() {
        Prefs.setApiKey(this, keyEdit.text.toString())
        Prefs.setModel(this, modelEdit.text.toString())
        Prefs.setWakeWord(this, wakeEdit.text.toString())
        Prefs.setUserName(this, nameEdit.text.toString())
        Prefs.setWakeEnabled(this, wakeCheck.isChecked)
        Prefs.setTtsEnabled(this, ttsCheck.isChecked)
        Prefs.setBootNotify(this, bootCheck.isChecked)
    }

    private fun startAssistant() {
        saveSettings()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Сначала выдай разрешение на микрофон")
            askPermissions()
            return
        }
        if (Prefs.apiKey(this).isBlank()) {
            toast("Введи ключ OpenRouter и нажми «Сохранить»")
        } else if (Prefs.model(this).isBlank()) {
            toast("Выбери модель: «Найти бесплатные модели»")
        }
        VoiceService.start(this)
        statusView.postDelayed({ refresh() }, 800)
    }

    private fun pickFreeModel() {
        toast("Запрашиваю список у OpenRouter...")
        scope.launch {
            val list = try {
                Brain.freeModels()
            } catch (e: Exception) {
                toast("Не получилось: ${e.message}")
                return@launch
            }
            if (list.isEmpty()) {
                toast("Бесплатных моделей с функциями сейчас нет")
                return@launch
            }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Выбери модель")
                .setItems(list.toTypedArray()) { _, which ->
                    modelEdit.setText(list[which])
                    Prefs.setModel(this@MainActivity, list[which])
                    toast("Выбрано: ${list[which]}")
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun runText() {
        val t = cmdEdit.text.toString().trim()
        if (t.isEmpty()) return
        saveSettings()
        HromLog.add("Команда: $t")
        cmdEdit.setText("")
        scope.launch {
            val r = Brain.handle(applicationContext, t)
            HromLog.add("Хром: $r")
            if (Prefs.ttsEnabled(applicationContext)) Speaker.speak(r)
        }
    }

    private fun mark(ok: Boolean): String = if (ok) "✔" else "✖"

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        val sb = StringBuilder()
        sb.append(mark(HromAccessibilityService.isRunning())).append(" Служба доступности (руки)\n")
        sb.append(mark(HromNotificationListener.instance != null)).append(" Доступ к уведомлениям\n")
        sb.append(mark(nm.isNotificationPolicyAccessGranted)).append(" «Не беспокоить»\n")
        sb.append(mark(Settings.System.canWrite(this))).append(" Системные настройки (яркость)\n")
        sb.append(mark(Settings.canDrawOverlays(this))).append(" Поверх других окон\n")
        sb.append(mark(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)).append(" Микрофон\n")
        sb.append(mark(checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)).append(" Контакты\n")
        sb.append(mark(Prefs.apiKey(this).isNotBlank())).append(" Ключ OpenRouter введён\n")
        sb.append(mark(Prefs.model(this).isNotBlank())).append(" Модель выбрана\n")
        sb.append(mark(VoiceService.running)).append(" Хром слушает")
        statusView.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        logView.text = HromLog.all().joinToString("\n")
        HromLog.listener = { line ->
            runOnUiThread { logView.append(line + "\n") }
        }
        if (intent?.getBooleanExtra("autostart", false) == true) {
            intent.removeExtra("autostart")
            startAssistant()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onPause() {
        HromLog.listener = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
