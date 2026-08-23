package com.example.kakaodiscordbridge

import android.content.Context

class BridgeConfig(context: Context) {

    companion object {
        private const val PREFS_NAME = "bridge_config"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var botToken: String
        get() = prefs.getString("bot_token", "") ?: ""
        set(value) = prefs.edit().putString("bot_token", value).apply()

    var serverId: String
        get() = prefs.getString("server_id", "") ?: ""
        set(value) = prefs.edit().putString("server_id", value).apply()

    var categoryId: String
        get() = prefs.getString("category_id", "") ?: ""
        set(value) = prefs.edit().putString("category_id", value).apply()

    var myUserId: String
        get() = prefs.getString("my_user_id", "") ?: ""
        set(value) = prefs.edit().putString("my_user_id", value).apply()

    var logChannelId: String
        get() = prefs.getString("log_channel_id", "") ?: ""
        set(value) = prefs.edit().putString("log_channel_id", value).apply()

    var settingsChannelId: String
        get() = prefs.getString("settings_channel_id", "") ?: ""
        set(value) = prefs.edit().putString("settings_channel_id", value).apply()

    var alertChannelId: String
        get() = prefs.getString("alert_channel_id", "") ?: ""
        set(value) = prefs.edit().putString("alert_channel_id", value).apply()

    var nightStart: Int
        get() = prefs.getInt("night_start", 23)
        set(value) = prefs.edit().putInt("night_start", value).apply()

    var nightEnd: Int
        get() = prefs.getInt("night_end", 7)
        set(value) = prefs.edit().putInt("night_end", value).apply()

    var nightModeEnabled: Boolean
        get() = prefs.getBoolean("night_mode", true)
        set(value) = prefs.edit().putBoolean("night_mode", value).apply()

    var pollingIdle: Int
        get() = prefs.getInt("polling_idle", 30)
        set(value) = prefs.edit().putInt("polling_idle", value).apply()

    var pollingActive: Int
        get() = prefs.getInt("polling_active", 5)
        set(value) = prefs.edit().putInt("polling_active", value).apply()

    var pollingSlowdown: Int
        get() = prefs.getInt("polling_slowdown", 10)
        set(value) = prefs.edit().putInt("polling_slowdown", value).apply()

    fun isConfigured(): Boolean {
        return botToken.isNotEmpty() &&
                serverId.isNotEmpty() &&
                categoryId.isNotEmpty() &&
                myUserId.isNotEmpty() &&
                logChannelId.isNotEmpty() &&
                settingsChannelId.isNotEmpty() &&
                alertChannelId.isNotEmpty()
    }
}