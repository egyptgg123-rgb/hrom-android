package com.hrom.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Все умения Хрома. definitions - описание для ИИ (что он может вызвать),
 * run - выполнение вызова на телефоне.
 */
object Tools {

    private const val NEED_ACC =
        "Служба доступности Хрома выключена. Включи её: Настройки → Специальные возможности → Хром."

    private var torchOn = false

    private val ALIASES = mapOf(
        "тг" to "telegram", "телега" to "telegram", "телеграм" to "telegram", "телеграмм" to "telegram",
        "ютуб" to "youtube", "ватсап" to "whatsapp", "вотсап" to "whatsapp", "вацап" to "whatsapp",
        "вк" to "vk", "вконтакте" to "vk", "браузер" to "chrome", "гугл" to "google",
        "карты" to "maps", "почта" to "gmail", "календарь" to "calendar", "часы" to "clock",
        "галерея" to "photos", "фото" to "photos", "камера" to "camera", "настройки" to "settings",
        "калькулятор" to "calculator", "музыка" to "music", "контакты" to "contacts",
        "телефон" to "phone", "сообщения" to "messages"
    )

    // ------------------------------------------------------------------
    // Описание инструментов для ИИ
    // ------------------------------------------------------------------

    private fun p(n: String, t: String, d: String) = Triple(n, t, d)

    private fun tool(
        name: String,
        desc: String,
        vararg props: Triple<String, String, String>,
        required: List<String>? = null
    ): JSONObject {
        val properties = JSONObject()
        for ((n, t, d) in props) {
            properties.put(n, JSONObject().put("type", t).put("description", d))
        }
        val req = JSONArray()
        for (r in (required ?: props.map { it.first })) req.put(r)
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", req)
        return JSONObject().put("name", name).put("description", desc).put("input_schema", schema)
    }

    val definitions: JSONArray by lazy { buildDefs() }

    private fun buildDefs(): JSONArray {
        val a = JSONArray()

        // --- приложения и интернет
        a.put(tool("open_app", "Открыть приложение по названию (Telegram, YouTube, Настройки, Камера) или по имени пакета.", p("name", "string", "Название приложения")))
        a.put(tool("list_apps", "Найти установленные приложения по части названия. Без запроса вернёт первые 40.", p("query", "string", "Часть названия"), required = emptyList()))
        a.put(tool("open_url", "Открыть ссылку в браузере.", p("url", "string", "Адрес сайта")))
        a.put(tool("web_search", "Поискать в Google в браузере (результат видно на экране).", p("query", "string", "Поисковый запрос")))
        a.put(tool("fetch_url", "Скачать страницу или API и вернуть текст (для погоды, курсов, справок). Пример погоды: https://wttr.in/Красноярск?format=3", p("url", "string", "https-адрес")))
        a.put(tool("open_settings", "Открыть раздел настроек: wifi, bluetooth, display, sound, battery, location, apps, accessibility, nfc, airplane, hotspot, date, language, storage, security, privacy, dnd, notifications, vpn.", p("page", "string", "Раздел")))
        a.put(tool("app_info", "Открыть страницу приложения в настройках (там можно остановить, очистить кэш, удалить).", p("name", "string", "Название приложения")))

        // --- звук, свет, режимы
        a.put(tool("dnd", "Режим «Не беспокоить». mode: on (только важные), alarms (только будильники), none (полная тишина), off (выключить).", p("mode", "string", "on | alarms | none | off")))
        a.put(tool("set_volume", "Громкость в процентах. stream: media, ring, alarm, notification, call.", p("stream", "string", "Тип звука"), p("percent", "integer", "0-100")))
        a.put(tool("ringer_mode", "Режим звонка: normal, vibrate или silent.", p("mode", "string", "normal | vibrate | silent")))
        a.put(tool("set_brightness", "Яркость экрана в процентах.", p("percent", "integer", "1-100")))
        a.put(tool("flashlight", "Включить или выключить фонарик.", p("on", "boolean", "true - включить, false - выключить")))
        a.put(tool("quick_toggle", "Нажать плитку в шторке быстрых настроек (Wi-Fi, Bluetooth, Мобильные данные/Интернет, Авиарежим, Геолокация, Автоповорот, Точка доступа, Экономия заряда). Название как на экране телефона.", p("name", "string", "Название плитки")))
        a.put(tool("media", "Управление музыкой и видео: play_pause, play, pause, next, previous, stop, volume_up, volume_down.", p("action", "string", "Действие")))
        a.put(tool("vibrate", "Короткая вибрация.", p("ms", "integer", "Длительность в мс"), required = emptyList()))

        // --- информация
        a.put(tool("battery_status", "Уровень заряда и идёт ли зарядка."))
        a.put(tool("get_time", "Текущие дата и время."))
        a.put(tool("get_current_app", "Какое приложение сейчас открыто на экране."))

        // --- время и планирование
        a.put(tool("set_alarm", "Поставить будильник на время суток (повторяется не каждый день).", p("hour", "integer", "Час 0-23"), p("minute", "integer", "Минуты 0-59"), p("label", "string", "Название"), required = listOf("hour", "minute")))
        a.put(tool("set_timer", "Запустить таймер.", p("seconds", "integer", "Секунды"), p("label", "string", "Название"), required = listOf("seconds")))
        a.put(tool("set_reminder", "Напомнить через N минут (уведомление и голос).", p("minutes", "integer", "Через сколько минут"), p("text", "string", "О чём напомнить")))
        a.put(tool("create_calendar_event", "Создать событие в календаре. Откроется форма: потом нажми «Сохранить».", p("title", "string", "Название"), p("start", "string", "Начало, формат yyyy-MM-dd HH:mm"), p("duration_minutes", "integer", "Длительность"), required = listOf("title", "start")))
        a.put(tool("navigate", "Проложить маршрут до места в Google Картах.", p("destination", "string", "Адрес или название места")))

        // --- связь
        a.put(tool("call", "Позвонить контакту (по имени из записной книжки) или на номер.", p("who", "string", "Имя контакта в именительном падеже или номер")))
        a.put(tool("end_call", "Завершить текущий звонок."))
        a.put(tool("answer_call", "Ответить на входящий звонок."))
        a.put(tool("send_sms", "Отправить SMS контакту или на номер.", p("to", "string", "Имя контакта или номер"), p("text", "string", "Текст")))
        a.put(tool("read_sms", "Прочитать последние входящие SMS (коды и пароли не зачитывать).", p("count", "integer", "Сколько"), required = emptyList()))
        a.put(tool("find_contact", "Найти контакт по имени и вернуть номера.", p("name", "string", "Имя")))
        a.put(tool("whatsapp_message", "Открыть чат WhatsApp с готовым текстом. Потом нужно нажать кнопку отправки (click_text «Отправить» или «Send»).", p("to", "string", "Имя контакта или номер"), p("text", "string", "Текст")))
        a.put(tool("telegram_open_chat", "Открыть чат в Telegram: @username или имя контакта. Потом type_text с текстом и click_text «Отправить» или «Send».", p("target", "string", "@username или имя контакта")))
        a.put(tool("share_text", "Поделиться текстом через меню «Отправить» (в конкретное приложение, если указано app). Дальше выбери получателя на экране.", p("text", "string", "Текст"), p("app", "string", "Приложение, например Telegram"), required = listOf("text")))

        // --- прочее на устройстве
        a.put(tool("open_camera", "Открыть камеру. mode: photo или video. Кнопку затвора нажми через click_text по описанию или tap.", p("mode", "string", "photo | video"), required = emptyList()))
        a.put(tool("copy_to_clipboard", "Скопировать текст в буфер обмена.", p("text", "string", "Текст")))
        a.put(tool("read_notifications", "Прочитать текущие уведомления.", p("count", "integer", "Сколько"), required = emptyList()))
        a.put(tool("clear_notifications", "Смахнуть все уведомления."))
        a.put(tool("wake_screen", "Включить экран (разблокировать PIN-кодом Хром не умеет)."))
        a.put(tool("speak", "Сказать пользователю фразу вслух прямо сейчас (для промежуточного прогресса).", p("text", "string", "Фраза")))
        a.put(tool("wait", "Подождать секунды (пока приложение загрузится).", p("seconds", "integer", "1-10")))

        // --- управление экраном (руки)
        a.put(tool("read_screen", "Прочитать, что сейчас на экране: тексты, кнопки, поля ввода и их координаты."))
        a.put(tool("click_text", "Нажать на элемент экрана по тексту или описанию. index - номер, если совпадений несколько.", p("text", "string", "Текст или описание кнопки"), p("index", "integer", "Номер совпадения, с 0"), required = listOf("text")))
        a.put(tool("tap", "Нажать в точку экрана (координаты из read_screen).", p("x", "integer", "X"), p("y", "integer", "Y")))
        a.put(tool("long_press", "Долгое нажатие в точку экрана.", p("x", "integer", "X"), p("y", "integer", "Y")))
        a.put(tool("swipe", "Свайп из точки в точку.", p("x1", "integer", "Откуда X"), p("y1", "integer", "Откуда Y"), p("x2", "integer", "Куда X"), p("y2", "integer", "Куда Y")))
        a.put(tool("scroll", "Прокрутить экран. direction: down (показать ниже), up, left, right.", p("direction", "string", "down | up | left | right")))
        a.put(tool("type_text", "Ввести текст в поле ввода на экране. submit=true нажмёт Enter/Поиск после ввода.", p("text", "string", "Текст"), p("submit", "boolean", "Нажать Enter"), required = listOf("text")))
        a.put(tool("press_key", "Системная кнопка: back, home, recents, notifications, quick_settings, power_menu, lock, screenshot.", p("key", "string", "Кнопка")))

        // --- память и рутины
        a.put(tool("remember", "Запомнить факт о пользователе навсегда (например: «мама» = контакт «Мама»).", p("key", "string", "Что"), p("value", "string", "Значение")))
        a.put(tool("recall", "Показать всё, что запомнено."))
        a.put(tool("forget", "Забыть один факт.", p("key", "string", "Что забыть")))
        a.put(tool("save_routine", "Сохранить рутину (сценарий): название и инструкция, что делать по шагам.", p("name", "string", "Название, например «спокойной ночи»"), p("instructions", "string", "Что именно сделать")))
        a.put(tool("list_routines", "Показать сохранённые рутины."))
        a.put(tool("run_routine", "Получить инструкцию рутины, чтобы тут же выполнить её шаги.", p("name", "string", "Название")))
        a.put(tool("delete_routine", "Удалить рутину.", p("name", "string", "Название")))

        return a
    }

    // ------------------------------------------------------------------
    // Выполнение
    // ------------------------------------------------------------------

    suspend fun run(ctx: Context, name: String, a: JSONObject): String = withContext(Dispatchers.Default) {
        when (name) {
            "open_app" -> openApp(ctx, a.optString("name"))
            "list_apps" -> listApps(ctx, a.optString("query"))
            "open_url" -> openUrl(ctx, a.optString("url"))
            "web_search" -> openUrl(ctx, "https://www.google.com/search?q=" + Uri.encode(a.optString("query")))
            "fetch_url" -> fetchUrl(a.optString("url"))
            "open_settings" -> startOk(ctx, Intent(settingsAction(a.optString("page"))), "настройки открыты")
            "app_info" -> appInfo(ctx, a.optString("name"))

            "dnd" -> dnd(ctx, a.optString("mode"))
            "set_volume" -> setVolume(ctx, a.optString("stream"), a.optInt("percent", 50))
            "ringer_mode" -> ringerMode(ctx, a.optString("mode"))
            "set_brightness" -> setBrightness(ctx, a.optInt("percent", 50))
            "flashlight" -> flashlight(ctx, a.optBoolean("on", true))
            "quick_toggle" -> quickToggle(a.optString("name"))
            "media" -> media(ctx, a.optString("action"))
            "vibrate" -> { Util.vibrate(ctx, a.optLong("ms", 300L)); "ok" }

            "battery_status" -> batteryStatus(ctx)
            "get_time" -> SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(Date())
            "get_current_app" -> acc()?.currentPackage()?.ifBlank { "неизвестно (экран заблокирован?)" } ?: NEED_ACC

            "set_alarm" -> setAlarm(ctx, a.optInt("hour", 7), a.optInt("minute", 0), a.optString("label"))
            "set_timer" -> setTimer(ctx, a.optInt("seconds", 60), a.optString("label"))
            "set_reminder" -> setReminder(ctx, a.optInt("minutes", 5), a.optString("text"))
            "create_calendar_event" -> createEvent(ctx, a.optString("title"), a.optString("start"), a.optInt("duration_minutes", 60))
            "navigate" -> navigate(ctx, a.optString("destination"))

            "call" -> call(ctx, a.optString("who"))
            "end_call" -> endCall(ctx)
            "answer_call" -> answerCall(ctx)
            "send_sms" -> sendSms(ctx, a.optString("to"), a.optString("text"))
            "read_sms" -> readSms(ctx, a.optInt("count", 3))
            "find_contact" -> findContact(ctx, a.optString("name"))
            "whatsapp_message" -> whatsapp(ctx, a.optString("to"), a.optString("text"))
            "telegram_open_chat" -> telegramChat(ctx, a.optString("target"))
            "share_text" -> shareText(ctx, a.optString("text"), a.optString("app"))

            "open_camera" -> openCamera(ctx, a.optString("mode"))
            "copy_to_clipboard" -> copyClip(ctx, a.optString("text"))
            "read_notifications" -> HromNotificationListener.instance?.summary(a.optInt("count", 5))
                ?: "Нет доступа к уведомлениям. Включи его в приложении Хром."
            "clear_notifications" -> {
                val l = HromNotificationListener.instance
                if (l == null) "Нет доступа к уведомлениям. Включи его в приложении Хром." else { l.clearAll(); "уведомления очищены" }
            }
            "wake_screen" -> wakeScreen(ctx)
            "speak" -> { Speaker.speak(a.optString("text")); "сказано" }
            "wait" -> { delay(a.optInt("seconds", 2).coerceIn(1, 10) * 1000L); "ok" }

            "read_screen" -> acc()?.dumpScreen() ?: NEED_ACC
            "click_text" -> {
                val s = acc()
                if (s == null) NEED_ACC else {
                    val r = s.clickText(a.optString("text"), a.optInt("index", 0))
                    delay(600)
                    r
                }
            }
            "tap" -> {
                val s = acc()
                if (s == null) NEED_ACC else {
                    val ok = s.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat())
                    delay(500)
                    if (ok) "нажато" else "не получилось"
                }
            }
            "long_press" -> {
                val s = acc()
                if (s == null) NEED_ACC else {
                    val ok = s.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat(), 900)
                    delay(700)
                    if (ok) "долгое нажатие выполнено" else "не получилось"
                }
            }
            "swipe" -> {
                val s = acc()
                if (s == null) NEED_ACC else {
                    val ok = s.swipe(
                        a.optDouble("x1").toFloat(), a.optDouble("y1").toFloat(),
                        a.optDouble("x2").toFloat(), a.optDouble("y2").toFloat()
                    )
                    delay(600)
                    if (ok) "свайп выполнен" else "не получилось"
                }
            }
            "scroll" -> {
                val s = acc()
                if (s == null) NEED_ACC else {
                    val ok = s.scroll(a.optString("direction"))
                    delay(600)
                    if (ok) "прокручено" else "не получилось (direction: down, up, left, right)"
                }
            }
            "type_text" -> {
                val s = acc()
                if (s == null) NEED_ACC else {
                    val r = s.typeText(a.optString("text"), a.optBoolean("submit", false))
                    delay(500)
                    r
                }
            }
            "press_key" -> {
                val r = pressKey(a.optString("key"))
                delay(500)
                r
            }

            "remember" -> remember(ctx, a.optString("key"), a.optString("value"))
            "recall" -> recall(ctx)
            "forget" -> forget(ctx, a.optString("key"))
            "save_routine" -> saveRoutine(ctx, a.optString("name"), a.optString("instructions"))
            "list_routines" -> listRoutines(ctx)
            "run_routine" -> runRoutine(ctx, a.optString("name"))
            "delete_routine" -> deleteRoutine(ctx, a.optString("name"))

            else -> "неизвестный инструмент: $name"
        }
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private fun acc(): HromAccessibilityService? = HromAccessibilityService.instance

    /** Запуск экрана. Возвращает null при успехе или текст ошибки. */
    private fun start(ctx: Context, i: Intent): String? {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = HromAccessibilityService.instance ?: ctx
        return try {
            c.startActivity(i)
            null
        } catch (e: Exception) {
            "не удалось открыть: ${e.message}"
        }
    }

    private fun startOk(ctx: Context, i: Intent, okMsg: String): String = start(ctx, i) ?: okMsg

    private fun granted(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Приложения
    // ------------------------------------------------------------------

    private fun findApp(ctx: Context, query: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(i, 0)
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = 0
        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            val l = label.lowercase()
            val pkg = ri.activityInfo.packageName
            val score = when {
                l == q -> 100
                l.startsWith(q) -> 80
                l.contains(q) -> 60
                l.length >= 3 && q.contains(l) -> 50
                pkg.lowercase().contains(q.replace(" ", "")) -> 40
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = Pair(label, pkg)
            }
        }
        return best
    }

    private fun openApp(ctx: Context, nameIn: String): String {
        val name = nameIn.trim()
        if (name.isEmpty()) return "не указано приложение"
        val pm = ctx.packageManager
        var pkg: String? = null
        var label = name
        if (name.contains('.') && !name.contains(' ')) {
            if (pm.getLaunchIntentForPackage(name) != null) pkg = name
        }
        if (pkg == null) {
            val q = ALIASES[name.lowercase()] ?: name
            val found = findApp(ctx, q) ?: (if (q != name) findApp(ctx, name) else null)
            if (found != null) {
                label = found.first
                pkg = found.second
            }
        }
        if (pkg == null) return "не нашёл приложение «$name». Попробуй list_apps."
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "у приложения нет запускаемого экрана"
        return startOk(ctx, i, "открыто: $label")
    }

    private fun listApps(ctx: Context, query: String): String {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = query.lowercase().trim()
        val out = pm.queryIntentActivities(i, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            .distinct()
            .sortedBy { it.first.lowercase() }
            .take(40)
        if (out.isEmpty()) return "ничего не найдено"
        return out.joinToString("\n") { "${it.first} (${it.second})" }
    }

    private fun openUrl(ctx: Context, urlIn: String): String {
        var url = urlIn.trim()
        if (url.isEmpty()) return "пустая ссылка"
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return startOk(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "открыто в браузере")
    }

    private fun fetchUrl(url: String): String {
        if (!url.startsWith("https://")) return "разрешены только https-адреса"
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Hrom").build()
            Http.client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val text = body
                    .replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                "HTTP ${r.code}: ${text.take(3500)}"
            }
        } catch (e: Exception) {
            "ошибка сети: ${e.message}"
        }
    }

    private fun settingsAction(page: String): String = when (page.lowercase().trim()) {
        "wifi", "wi-fi", "вайфай" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth", "блютуз" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display", "экран" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound", "звук" -> Settings.ACTION_SOUND_SETTINGS
        "battery", "батарея" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        "location", "геолокация" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "apps", "приложения" -> Settings.ACTION_APPLICATION_SETTINGS
        "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        "nfc" -> Settings.ACTION_NFC_SETTINGS
        "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        "hotspot", "mobile", "network" -> Settings.ACTION_WIRELESS_SETTINGS
        "date" -> Settings.ACTION_DATE_SETTINGS
        "language" -> Settings.ACTION_LOCALE_SETTINGS
        "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
        "security" -> Settings.ACTION_SECURITY_SETTINGS
        "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
        "dnd" -> "android.settings.ZEN_MODE_SETTINGS"
        "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        "vpn" -> Settings.ACTION_VPN_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }

    private fun appInfo(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val pkg = if (name.contains('.') && pm.getLaunchIntentForPackage(name) != null) name
        else findApp(ctx, ALIASES[name.lowercase()] ?: name)?.second ?: findApp(ctx, name)?.second
        if (pkg == null) return "не нашёл приложение «$name»"
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        return startOk(ctx, i, "открыта страница приложения (кнопки: Остановить / Force stop, Хранилище, Удалить)")
    }

    // ------------------------------------------------------------------
    // Звук, свет, режимы
    // ------------------------------------------------------------------

    private fun dnd(ctx: Context, mode: String): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            start(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "Нет доступа к режиму «Не беспокоить». Открыл настройки: один раз включи доступ для Хрома."
        }
        val f = when (mode.lowercase().trim()) {
            "off", "выкл", "выключить" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "on", "priority", "вкл", "включить" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "none", "total" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return "неизвестный режим: $mode (on, alarms, none, off)"
        }
        nm.setInterruptionFilter(f)
        return "режим «Не беспокоить» переключён: $mode"
    }

    private fun streamOf(s: String): Int = when (s.lowercase().trim()) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "call" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private fun setVolume(ctx: Context, stream: String, percent: Int): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val st = streamOf(stream)
            val max = am.getStreamMaxVolume(st)
            val v = percent.coerceIn(0, 100) * max / 100
            am.setStreamVolume(st, v, AudioManager.FLAG_SHOW_UI)
            "громкость ($stream) = ${percent.coerceIn(0, 100)}%"
        } catch (e: Exception) {
            "не удалось изменить громкость: ${e.message}"
        }
    }

    private fun ringerMode(ctx: Context, mode: String): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = when (mode.lowercase().trim()) {
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            "режим звонка: $mode"
        } catch (e: Exception) {
            "не удалось (для беззвучного нужен доступ к «Не беспокоить»): ${e.message}"
        }
    }

    private fun setBrightness(ctx: Context, percent: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            start(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return "Нет права менять системные настройки. Открыл экран разрешения: включи для Хрома."
        }
        return try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val v = (percent.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            "яркость ${percent.coerceIn(1, 100)}%"
        } catch (e: Exception) {
            "не удалось: ${e.message}"
        }
    }

    private fun flashlight(ctx: Context, on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "на телефоне нет вспышки"
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "фонарик включён" else "фонарик выключен"
        } catch (e: Exception) {
            "не удалось: ${e.message}"
        }
    }

    private suspend fun quickToggle(name: String): String {
        val s = acc() ?: return NEED_ACC
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        delay(1300)
        val r = s.clickText(name)
        delay(700)
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return "плитка «$name»: $r. Если не нашлась, открой read_screen после quick_settings и посмотри точное название."
    }

    private fun media(ctx: Context, action: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key: Int = when (action.lowercase().trim()) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "volume_up" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return "громче"
            }
            "volume_down" -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return "тише"
            }
            else -> return "неизвестное действие: $action"
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "медиа: $action"
    }

    private fun batteryStatus(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "заряд $level%, " + if (bm.isCharging) "заряжается" else "не заряжается"
    }

    // ------------------------------------------------------------------
    // Время, планирование
    // ------------------------------------------------------------------

    private fun setAlarm(ctx: Context, h: Int, m: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, m.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return startOk(ctx, i, "будильник на %02d:%02d".format(h, m))
    }

    private fun setTimer(ctx: Context, seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return startOk(ctx, i, "таймер на $seconds сек")
    }

    private fun setReminder(ctx: Context, minutes: Int, text: String): String {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("text", text)
        val code = (System.currentTimeMillis() % 100000L).toInt()
        val pi = PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = System.currentTimeMillis() + minutes.coerceIn(1, 60 * 24 * 30) * 60_000L
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return "напомню через $minutes мин: $text"
    }

    private fun createEvent(ctx: Context, title: String, startStr: String, durationMin: Int): String {
        val start = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(startStr.trim())?.time
        } catch (e: Exception) {
            null
        } ?: return "не понял дату «$startStr», нужен формат yyyy-MM-dd HH:mm"
        val i = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + durationMin.coerceIn(5, 24 * 60) * 60_000L)
        return startOk(ctx, i, "форма события открыта. Теперь нажми «Сохранить» (click_text «Сохранить» или «Save»).")
    }

    private fun navigate(ctx: Context, dest: String): String {
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
            .setPackage("com.google.android.apps.maps")
        if (start(ctx, maps) == null) return "маршрут до «$dest» открыт в Google Картах. Нажми «Начать», если нужно."
        return startOk(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))), "открыл карту с «$dest»")
    }

    // ------------------------------------------------------------------
    // Связь
    // ------------------------------------------------------------------

    /** Все контакты, подходящие под запрос (регистр не важен, падежи: «маме» найдёт «Мама»). */
    private fun contactsMatching(ctx: Context, q: String, limit: Int = 5): List<Pair<String, String>> {
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val all = ArrayList<Pair<String, String>>()
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        cur?.use {
            while (it.moveToNext()) all.add(Pair(it.getString(0) ?: "", it.getString(1) ?: ""))
        }
        val base = q.lowercase().trim()
        if (base.isEmpty()) return emptyList()
        for (cut in 0..2) {
            if (base.length - cut < 2) break
            val needle = base.dropLast(cut)
            val res = all.filter { it.first.lowercase().contains(needle) }.distinct().take(limit)
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun resolveNumber(ctx: Context, who: String): String? {
        val w = who.trim()
        if (Regex("^[+0-9 ()\\-]{5,}\$").matches(w)) return w.filter { it.isDigit() || it == '+' }
        return contactsMatching(ctx, w, 1).firstOrNull()?.second
    }

    private fun findContact(ctx: Context, name: String): String {
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return "Нет разрешения на контакты. Выдай его в приложении Хром."
        val r = contactsMatching(ctx, name, 5)
        if (r.isEmpty()) return "контакт «$name» не найден"
        return r.joinToString("\n") { "${it.first}: ${it.second}" }
    }

    private fun call(ctx: Context, who: String): String {
        if (!granted(ctx, Manifest.permission.CALL_PHONE)) return "Нет разрешения на звонки. Выдай его в приложении Хром."
        val number = resolveNumber(ctx, who) ?: return "контакт «$who» не найден"
        return startOk(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))), "звоню: $who")
    }

    private fun endCall(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 28) return "На этой версии Android завершить звонок голосом нельзя."
        if (!granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "Нет разрешения управлять звонками."
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("DEPRECATION")
            val ok = tm.endCall()
            if (ok) "звонок завершён" else "активного звонка нет"
        } catch (e: Exception) {
            "не удалось: ${e.message}"
        }
    }

    private fun answerCall(ctx: Context): String {
        if (!granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "Нет разрешения управлять звонками."
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            "ответил на звонок"
        } catch (e: Exception) {
            "не удалось: ${e.message}"
        }
    }

    private fun sendSms(ctx: Context, to: String, text: String): String {
        if (!granted(ctx, Manifest.permission.SEND_SMS)) return "Нет разрешения на SMS. Выдай его в приложении Хром."
        val num = resolveNumber(ctx, to) ?: return "контакт «$to» не найден"
        return try {
            @Suppress("DEPRECATION")
            val sm = SmsManager.getDefault()
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null)
            "SMS отправлено: $to"
        } catch (e: Exception) {
            "не удалось отправить SMS: ${e.message}"
        }
    }

    private fun readSms(ctx: Context, count: Int): String {
        if (!granted(ctx, Manifest.permission.READ_SMS)) return "Нет разрешения на чтение SMS."
        val sb = StringBuilder()
        try {
            val c = ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )
            c?.use {
                var n = 0
                while (it.moveToNext() && n < count.coerceIn(1, 10)) {
                    sb.append("От ").append(it.getString(0)).append(": ").append((it.getString(1) ?: "").take(200)).append("\n")
                    n++
                }
            }
        } catch (e: Exception) {
            return "не удалось прочитать SMS: ${e.message}"
        }
        return if (sb.isEmpty()) "входящих SMS нет" else sb.toString()
    }

    private fun normalizePhone(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("8")) d = "7" + d.drop(1)
        return d
    }

    private fun whatsapp(ctx: Context, to: String, text: String): String {
        val num = resolveNumber(ctx, to) ?: return "контакт «$to» не найден"
        val uri = Uri.parse("https://wa.me/${normalizePhone(num)}?text=" + Uri.encode(text))
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")
        return startOk(ctx, i, "чат WhatsApp открыт, текст подставлен. Теперь нажми кнопку отправки: click_text «Отправить» или «Send».")
    }

    private fun telegramChat(ctx: Context, target: String): String {
        val t = target.trim()
        val uri: Uri = if (t.startsWith("@")) {
            Uri.parse("tg://resolve?domain=" + Uri.encode(t.drop(1)))
        } else {
            val num = resolveNumber(ctx, t)
                ?: return "контакт «$t» не найден в телефоне. Попробуй @username или share_text."
            Uri.parse("tg://resolve?phone=" + normalizePhone(num))
        }
        return startOk(ctx, Intent(Intent.ACTION_VIEW, uri), "чат Telegram открыт. Дальше: type_text с текстом, затем click_text «Отправить» или «Send». Проверь экран через read_screen.")
    }

    private fun shareText(ctx: Context, text: String, app: String): String {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (app.isNotBlank()) {
            val pkg = findApp(ctx, ALIASES[app.lowercase()] ?: app)?.second ?: findApp(ctx, app)?.second
                ?: return "не нашёл приложение «$app»"
            i.setPackage(pkg)
            return startOk(ctx, i, "открыл «Отправить» в «$app». Выбери получателя через click_text и подтверди отправку.")
        }
        return startOk(ctx, Intent.createChooser(i, "Отправить"), "открыл меню «Отправить». Выбери приложение через click_text.")
    }

    // ------------------------------------------------------------------
    // Прочее
    // ------------------------------------------------------------------

    private fun openCamera(ctx: Context, mode: String): String {
        val action = if (mode.lowercase().trim() == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA
        else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        return startOk(ctx, Intent(action), "камера открыта")
    }

    private fun copyClip(ctx: Context, text: String): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hrom", text))
        return "скопировано"
    }

    private fun wakeScreen(ctx: Context): String {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "hrom:wake"
            )
            wl.acquire(5000L)
            "экран включён (если стоит блокировка, разблокируй сам)"
        } catch (e: Exception) {
            "не удалось: ${e.message}"
        }
    }

    private fun pressKey(key: String): String {
        val s = acc() ?: return NEED_ACC
        val ok = when (key.lowercase().trim()) {
            "back" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "power_menu" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) else false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) else false
            else -> return "неизвестная кнопка: $key"
        }
        return if (ok) "выполнено: $key" else "не получилось: $key"
    }

    // ------------------------------------------------------------------
    // Память и рутины
    // ------------------------------------------------------------------

    private fun remember(ctx: Context, key: String, value: String): String {
        if (key.isBlank()) return "пустой ключ"
        val m = Prefs.memory(ctx)
        m.put(key.trim(), value.trim())
        Prefs.saveMemory(ctx, m)
        return "запомнил: $key = $value"
    }

    private fun recall(ctx: Context): String {
        val m = Prefs.memory(ctx)
        if (m.length() == 0) return "пока ничего не запомнено"
        val sb = StringBuilder()
        val it = m.keys()
        while (it.hasNext()) {
            val k = it.next()
            sb.append(k).append(": ").append(m.optString(k)).append("\n")
        }
        return sb.toString()
    }

    private fun forget(ctx: Context, key: String): String {
        val m = Prefs.memory(ctx)
        if (!m.has(key)) return "такого факта нет"
        m.remove(key)
        Prefs.saveMemory(ctx, m)
        return "забыл: $key"
    }

    private fun saveRoutine(ctx: Context, name: String, instr: String): String {
        if (name.isBlank() || instr.isBlank()) return "нужны название и инструкция"
        val r = Prefs.routines(ctx)
        r.put(name.trim().lowercase(), instr.trim())
        Prefs.saveRoutines(ctx, r)
        return "рутина «$name» сохранена"
    }

    private fun listRoutines(ctx: Context): String {
        val r = Prefs.routines(ctx)
        if (r.length() == 0) return "рутин пока нет"
        val sb = StringBuilder()
        val it = r.keys()
        while (it.hasNext()) {
            val k = it.next()
            sb.append(k).append(": ").append(r.optString(k)).append("\n")
        }
        return sb.toString()
    }

    private fun runRoutine(ctx: Context, name: String): String {
        val r = Prefs.routines(ctx)
        val key = name.trim().lowercase()
        if (!r.has(key)) return "рутины «$name» нет. Есть: " + listRoutines(ctx)
        return "Выполни по шагам: " + r.optString(key)
    }

    private fun deleteRoutine(ctx: Context, name: String): String {
        val r = Prefs.routines(ctx)
        val key = name.trim().lowercase()
        if (!r.has(key)) return "такой рутины нет"
        r.remove(key)
        Prefs.saveRoutines(ctx, r)
        return "рутина «$name» удалена"
    }
}
