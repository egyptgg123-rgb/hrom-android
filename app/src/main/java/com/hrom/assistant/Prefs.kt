package com.hrom.assistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Все настройки Хрома хранятся здесь (на устройстве, в приватном хранилище приложения). */
object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("hrom", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) {
        sp(c).edit().putString("api_key", v.trim()).apply()
    }

    /** Пусто, пока пользователь не выбрал модель (список бесплатных предлагает само приложение). */
    fun model(c: Context): String = (sp(c).getString("model", "") ?: "").trim()

    fun setModel(c: Context, v: String) {
        sp(c).edit().putString("model", v.trim()).apply()
    }

    fun wakeWord(c: Context): String =
        (sp(c).getString("wake_word", "") ?: "").trim().lowercase().ifBlank { "хром" }

    fun setWakeWord(c: Context, v: String) {
        sp(c).edit().putString("wake_word", v.trim().lowercase()).apply()
    }

    fun userName(c: Context): String = sp(c).getString("user_name", "") ?: ""
    fun setUserName(c: Context, v: String) {
        sp(c).edit().putString("user_name", v.trim()).apply()
    }

    fun wakeEnabled(c: Context): Boolean = sp(c).getBoolean("wake_enabled", true)
    fun setWakeEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("wake_enabled", v).apply()
    }

    fun ttsEnabled(c: Context): Boolean = sp(c).getBoolean("tts_enabled", true)
    fun setTtsEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("tts_enabled", v).apply()
    }

    fun bootNotify(c: Context): Boolean = sp(c).getBoolean("boot_notify", true)
    fun setBootNotify(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("boot_notify", v).apply()
    }

    private fun getJson(c: Context, key: String): JSONObject =
        try {
            JSONObject(sp(c).getString(key, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    private fun putJson(c: Context, key: String, o: JSONObject) {
        sp(c).edit().putString(key, o.toString()).apply()
    }

    /** Долгая память: факты о пользователе (ключ -> значение). */
    fun memory(c: Context): JSONObject = getJson(c, "memory")
    fun saveMemory(c: Context, o: JSONObject) = putJson(c, "memory", o)

    /** Рутины: название -> инструкция, что делать. */
    fun routines(c: Context): JSONObject = getJson(c, "routines")
    fun saveRoutines(c: Context, o: JSONObject) = putJson(c, "routines", o)
}
