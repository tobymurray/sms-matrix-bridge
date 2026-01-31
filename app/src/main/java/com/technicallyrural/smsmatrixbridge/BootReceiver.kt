package com.technicallyrural.smsmatrixbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver for boot completed and package replaced broadcasts.
 *
 * Restarts the bridge service automatically when:
 * - Device boots (BOOT_COMPLETED)
 * - App is updated (MY_PACKAGE_REPLACED)
 *
 * In the pass-through architecture, this also validates the effective SMS app
 * is still available before starting the bridge service.
 *
 * Only starts the service if the bridge was previously enabled.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Initialize all singletons (may not be initialized yet after boot)
                Config.init(context)
                RoomMapper.init(context)
                EffectiveSmsAppManager.init(context)

                // Log current state
                Log.d(TAG, "Effective SMS app: ${EffectiveSmsAppManager.getEffectivePackage()}")
                Log.d(TAG, "Effective app available: ${EffectiveSmsAppManager.isEffectiveAppAvailable()}")

                // Only start if bridge was enabled
                if (Config.bridgeEnabled && Config.isConfigured()) {
                    // Check if effective app is available (warn but don't block)
                    if (!EffectiveSmsAppManager.isEffectiveAppAvailable()) {
                        Log.w(TAG, "Effective SMS app not available, but starting bridge anyway")
                    }

                    Log.i(TAG, "Restarting bridge service after $action")
                    BridgeService.start(context)
                } else {
                    Log.d(TAG, "Bridge not enabled or not configured, skipping auto-start")
                }
            }
        }
    }
}
