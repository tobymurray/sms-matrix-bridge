package com.technicallyrural.smsmatrixbridge

import android.app.Application
import android.util.Log
import com.technicallyrural.smsmatrixbridge.data.MessageRepository

/**
 * Application class for SMS Matrix Bridge.
 *
 * Initializes core components:
 * - Config (SharedPreferences for Matrix settings)
 * - RoomMapper (SharedPreferences for phone↔room mappings - legacy, migrating to DB)
 * - MessageRepository (Room database for authoritative message store)
 *
 * Architecture: This app is a minimal but correct SMS client that bridges to Matrix.
 * The internal message store is the source of truth; the system SMS provider is transport only.
 */
class BridgeApplication : Application() {

    companion object {
        private const val TAG = "BridgeApplication"

        lateinit var instance: BridgeApplication
            private set
    }

    lateinit var messageRepository: MessageRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Application starting")

        // Initialize singletons in dependency order
        Config.init(this)
        RoomMapper.init(this)

        // Initialize the message repository (Room database)
        messageRepository = MessageRepository.getInstance(this)

        // Log initialization state
        Log.i(TAG, "Configuration initialized:")
        Log.i(TAG, "  - Matrix configured: ${Config.isConfigured()}")
        Log.i(TAG, "  - Bridge enabled: ${Config.bridgeEnabled}")
        Log.i(TAG, "  - Mapped rooms: ${RoomMapper.getMappingCount()}")
    }
}
