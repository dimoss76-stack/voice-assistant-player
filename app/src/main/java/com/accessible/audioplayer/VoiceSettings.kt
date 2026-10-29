package com.accessible.audioplayer

import android.content.Context
import android.content.SharedPreferences

class VoiceSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)

    var activationWord: String
        get() = prefs.getString("activation_word", "слушай") ?: "слушай"
        set(value) { prefs.edit().putString("activation_word", value).apply() }

    var confirmWord: String
        get() = prefs.getString("confirm_word", "говорите") ?: "говорите"
        set(value) { prefs.edit().putString("confirm_word", value).apply() }

    var shutdownWord: String
        get() = prefs.getString("shutdown_word", "выключись") ?: "выключись"
        set(value) { prefs.edit().putString("shutdown_word", value).apply() }

    var bookCommandWord: String
        get() = prefs.getString("book_command_word", "включи книгу") ?: "включи книгу"
        set(value) { prefs.edit().putString("book_command_word", value).apply() }

    var shutdownEnabled: Boolean
        get() = prefs.getBoolean("shutdown_enabled", false)
        set(value) { prefs.edit().putBoolean("shutdown_enabled", value).apply() }

    var showCallButtons: Boolean
        get() = prefs.getBoolean("show_call_buttons", true)
        set(value) { prefs.edit().putBoolean("show_call_buttons", value).apply() }

    var callButtonsLayout: Int
        get() = prefs.getInt("call_buttons_layout", 0)
        set(value) { prefs.edit().putInt("call_buttons_layout", value).apply() }

    var modelMode: Int
        get() = prefs.getInt("model_mode", 0)
        set(value) { prefs.edit().putInt("model_mode", value).apply() }
}
