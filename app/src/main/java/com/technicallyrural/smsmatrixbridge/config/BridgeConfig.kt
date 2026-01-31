package com.technicallyrural.smsmatrixbridge.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

// Extension property for DataStore
private val Context.bridgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "bridge_config"
)

/**
 * Bridge configuration using DataStore for persistence.
 *
 * Configuration categories:
 * - Matrix connection settings
 * - Global bridge controls
 * - Sync settings
 * - Status tracking
 */
class BridgeConfig private constructor(private val context: Context) {

    companion object {
        // Matrix connection
        private val KEY_HOMESERVER_URL = stringPreferencesKey("homeserver_url")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_SYNC_SINCE_TOKEN = stringPreferencesKey("sync_since_token")

        // Global controls
        private val KEY_BRIDGE_ENABLED = booleanPreferencesKey("bridge_enabled")
        private val KEY_MATRIX_SYNC_ENABLED = booleanPreferencesKey("matrix_sync_enabled")
        private val KEY_SMS_SENDING_ENABLED = booleanPreferencesKey("sms_sending_enabled")
        private val KEY_SMS_RECEIVING_ENABLED = booleanPreferencesKey("sms_receiving_enabled")

        // Background behavior
        private val KEY_BACKGROUND_SYNC_MODE = stringPreferencesKey("background_sync_mode")

        // Status tracking
        private val KEY_LAST_SMS_SENT_TIME = longPreferencesKey("last_sms_sent_time")
        private val KEY_LAST_SMS_RECEIVED_TIME = longPreferencesKey("last_sms_received_time")
        private val KEY_LAST_MATRIX_SYNC_TIME = longPreferencesKey("last_matrix_sync_time")
        private val KEY_LAST_MATRIX_EVENT_SENT_TIME = longPreferencesKey("last_matrix_event_sent_time")
        private val KEY_LAST_MATRIX_EVENT_RECEIVED_TIME = longPreferencesKey("last_matrix_event_received_time")

        // Error tracking
        private val KEY_LAST_ERROR = stringPreferencesKey("last_error")
        private val KEY_LAST_ERROR_TIME = longPreferencesKey("last_error_time")

        @Volatile
        private var instance: BridgeConfig? = null

        fun getInstance(context: Context): BridgeConfig {
            return instance ?: synchronized(this) {
                instance ?: BridgeConfig(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dataStore = context.bridgeDataStore

    // ========== Matrix Connection Settings ==========

    val homeserverUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_HOMESERVER_URL] ?: ""
    }

    val userId: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_USER_ID] ?: ""
    }

    val accessToken: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_ACCESS_TOKEN] ?: ""
    }

    val syncSinceToken: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_SINCE_TOKEN]
    }

    suspend fun setMatrixCredentials(homeserver: String, userId: String, accessToken: String) {
        dataStore.edit { prefs ->
            prefs[KEY_HOMESERVER_URL] = homeserver.trimEnd('/')
            prefs[KEY_USER_ID] = userId
            prefs[KEY_ACCESS_TOKEN] = accessToken
        }
    }

    suspend fun setSyncSinceToken(token: String?) {
        dataStore.edit { prefs ->
            if (token != null) {
                prefs[KEY_SYNC_SINCE_TOKEN] = token
            } else {
                prefs.remove(KEY_SYNC_SINCE_TOKEN)
            }
        }
    }

    // ========== Global Controls ==========

    val bridgeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BRIDGE_ENABLED] ?: false
    }

    val matrixSyncEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_MATRIX_SYNC_ENABLED] ?: true
    }

    val smsSendingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SMS_SENDING_ENABLED] ?: true
    }

    val smsReceivingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SMS_RECEIVING_ENABLED] ?: true
    }

    suspend fun setBridgeEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_BRIDGE_ENABLED] = enabled
        }
    }

    suspend fun setMatrixSyncEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_MATRIX_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setSmsSendingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SMS_SENDING_ENABLED] = enabled
        }
    }

    suspend fun setSmsReceivingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SMS_RECEIVING_ENABLED] = enabled
        }
    }

    // ========== Background Sync Mode ==========

    enum class BackgroundSyncMode {
        ALWAYS,         // Always sync in background
        WIFI_ONLY,      // Only sync on WiFi
        MANUAL          // Only sync when app is open
    }

    val backgroundSyncMode: Flow<BackgroundSyncMode> = dataStore.data.map { prefs ->
        val modeStr = prefs[KEY_BACKGROUND_SYNC_MODE] ?: BackgroundSyncMode.ALWAYS.name
        try {
            BackgroundSyncMode.valueOf(modeStr)
        } catch (e: IllegalArgumentException) {
            BackgroundSyncMode.ALWAYS
        }
    }

    suspend fun setBackgroundSyncMode(mode: BackgroundSyncMode) {
        dataStore.edit { prefs ->
            prefs[KEY_BACKGROUND_SYNC_MODE] = mode.name
        }
    }

    // ========== Status Tracking ==========

    val lastSmsSentTime: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SMS_SENT_TIME]
    }

    val lastSmsReceivedTime: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SMS_RECEIVED_TIME]
    }

    val lastMatrixSyncTime: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_MATRIX_SYNC_TIME]
    }

    val lastMatrixEventSentTime: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_MATRIX_EVENT_SENT_TIME]
    }

    val lastMatrixEventReceivedTime: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_MATRIX_EVENT_RECEIVED_TIME]
    }

    suspend fun recordSmsSent() {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_SMS_SENT_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun recordSmsReceived() {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_SMS_RECEIVED_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun recordMatrixSync() {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_MATRIX_SYNC_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun recordMatrixEventSent() {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_MATRIX_EVENT_SENT_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun recordMatrixEventReceived() {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_MATRIX_EVENT_RECEIVED_TIME] = System.currentTimeMillis()
        }
    }

    // ========== Error Tracking ==========

    data class LastError(val message: String, val timestamp: Long)

    val lastError: Flow<LastError?> = dataStore.data.map { prefs ->
        val msg = prefs[KEY_LAST_ERROR]
        val time = prefs[KEY_LAST_ERROR_TIME]
        if (msg != null && time != null) {
            LastError(msg, time)
        } else {
            null
        }
    }

    suspend fun recordError(message: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_ERROR] = message
            prefs[KEY_LAST_ERROR_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun clearError() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_ERROR)
            prefs.remove(KEY_LAST_ERROR_TIME)
        }
    }

    // ========== Validation ==========

    suspend fun isConfigured(): Boolean {
        val homeserver = homeserverUrl.first()
        val user = userId.first()
        val token = accessToken.first()
        return homeserver.isNotBlank() && user.isNotBlank() && token.isNotBlank()
    }

    /**
     * Blocking version of isConfigured for use in BroadcastReceivers.
     * Use sparingly - prefer the suspend version.
     */
    fun isConfiguredBlocking(): Boolean = runBlocking {
        isConfigured()
    }

    /**
     * Get current bridge enabled state synchronously.
     * Use sparingly - prefer the Flow version.
     */
    fun isBridgeEnabledBlocking(): Boolean = runBlocking {
        bridgeEnabled.first()
    }

    // ========== Snapshot for Status UI ==========

    data class ConfigSnapshot(
        val homeserverUrl: String,
        val userId: String,
        val hasAccessToken: Boolean,
        val bridgeEnabled: Boolean,
        val matrixSyncEnabled: Boolean,
        val smsSendingEnabled: Boolean,
        val smsReceivingEnabled: Boolean,
        val backgroundSyncMode: BackgroundSyncMode,
        val lastSmsSentTime: Long?,
        val lastSmsReceivedTime: Long?,
        val lastMatrixSyncTime: Long?,
        val lastMatrixEventSentTime: Long?,
        val lastMatrixEventReceivedTime: Long?,
        val lastError: LastError?
    )

    suspend fun getSnapshot(): ConfigSnapshot {
        val prefs = dataStore.data.first()
        return ConfigSnapshot(
            homeserverUrl = prefs[KEY_HOMESERVER_URL] ?: "",
            userId = prefs[KEY_USER_ID] ?: "",
            hasAccessToken = !prefs[KEY_ACCESS_TOKEN].isNullOrBlank(),
            bridgeEnabled = prefs[KEY_BRIDGE_ENABLED] ?: false,
            matrixSyncEnabled = prefs[KEY_MATRIX_SYNC_ENABLED] ?: true,
            smsSendingEnabled = prefs[KEY_SMS_SENDING_ENABLED] ?: true,
            smsReceivingEnabled = prefs[KEY_SMS_RECEIVING_ENABLED] ?: true,
            backgroundSyncMode = try {
                BackgroundSyncMode.valueOf(prefs[KEY_BACKGROUND_SYNC_MODE] ?: BackgroundSyncMode.ALWAYS.name)
            } catch (e: IllegalArgumentException) {
                BackgroundSyncMode.ALWAYS
            },
            lastSmsSentTime = prefs[KEY_LAST_SMS_SENT_TIME],
            lastSmsReceivedTime = prefs[KEY_LAST_SMS_RECEIVED_TIME],
            lastMatrixSyncTime = prefs[KEY_LAST_MATRIX_SYNC_TIME],
            lastMatrixEventSentTime = prefs[KEY_LAST_MATRIX_EVENT_SENT_TIME],
            lastMatrixEventReceivedTime = prefs[KEY_LAST_MATRIX_EVENT_RECEIVED_TIME],
            lastError = prefs[KEY_LAST_ERROR]?.let { msg ->
                prefs[KEY_LAST_ERROR_TIME]?.let { time ->
                    LastError(msg, time)
                }
            }
        )
    }
}
