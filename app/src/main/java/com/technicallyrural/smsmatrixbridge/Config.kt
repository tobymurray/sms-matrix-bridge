package com.technicallyrural.smsmatrixbridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration manager for the SMS Matrix Bridge.
 *
 * Stores Matrix homeserver URL, user ID, and access token.
 * Uses SharedPreferences for persistence.
 *
 * IMPORTANT: For v1, you can hardcode values in the companion object defaults,
 * or use the MainActivity to configure them.
 */
object Config {

    private const val PREFS_NAME = "sms_matrix_bridge_config"
    private const val KEY_HOMESERVER_URL = "homeserver_url"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_BRIDGE_ENABLED = "bridge_enabled"
    private const val KEY_SYNC_SINCE_TOKEN = "sync_since_token"

    // ============================================================
    // DEFAULT VALUES - Edit these for quick setup without UI
    // ============================================================
    private const val DEFAULT_HOMESERVER_URL = "https://matrix.example.com"
    private const val DEFAULT_USER_ID = "@smsbridge:example.com"
    private const val DEFAULT_ACCESS_TOKEN = ""  // Must be set via UI or edited here

    private lateinit var prefs: SharedPreferences

    /**
     * Initialize config with application context.
     * Must be called from Application.onCreate() before any other access.
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var homeserverUrl: String
        get() = prefs.getString(KEY_HOMESERVER_URL, DEFAULT_HOMESERVER_URL) ?: DEFAULT_HOMESERVER_URL
        set(value) = prefs.edit().putString(KEY_HOMESERVER_URL, value.trimEnd('/')).apply()

    var userId: String
        get() = prefs.getString(KEY_USER_ID, DEFAULT_USER_ID) ?: DEFAULT_USER_ID
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var accessToken: String
        get() = prefs.getString(KEY_ACCESS_TOKEN, DEFAULT_ACCESS_TOKEN) ?: DEFAULT_ACCESS_TOKEN
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var bridgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BRIDGE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BRIDGE_ENABLED, value).apply()

    var syncSinceToken: String?
        get() = prefs.getString(KEY_SYNC_SINCE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SYNC_SINCE_TOKEN, value).apply()

    /**
     * Check if the configuration is valid (all required fields set).
     */
    fun isConfigured(): Boolean {
        return homeserverUrl.isNotBlank() &&
                homeserverUrl != DEFAULT_HOMESERVER_URL &&
                userId.isNotBlank() &&
                accessToken.isNotBlank()
    }

    /**
     * Clear all configuration (for debugging/reset).
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
