package com.technicallyrural.smsmatrixbridge

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Service for handling "respond via message" functionality using pass-through architecture.
 *
 * This service handles the RESPOND_VIA_MESSAGE intent, which is triggered when the user
 * wants to respond to an incoming call with an SMS message (e.g., declining a call and
 * sending a quick "I'll call you back" message).
 *
 * Pass-through behavior:
 * 1. Receive the respond-via-message intent (as the default SMS app)
 * 2. Forward the intent to the effective default SMS app
 * 3. The effective app handles the actual message sending and any UI
 *
 * We do NOT handle the message sending ourselves - the effective app maintains full
 * control over the user's messaging experience.
 *
 * Note: This is a critical user-facing feature. When a user declines a call and taps
 * "Send message", they expect immediate feedback. Forwarding must be fast and reliable.
 */
class HeadlessSmsSendService : Service() {

    companion object {
        private const val TAG = "HeadlessSmsSendService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Received RESPOND_VIA_MESSAGE intent")

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Extract the phone number from the intent data
            val phoneUri = intent.data
            val phoneNumber = phoneUri?.schemeSpecificPart

            // Extract the message text
            val messageText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d(TAG, "Respond via message: phone=$phoneNumber, text=${messageText?.take(50)}")

            // Forward to the effective SMS app
            val forwarded = forwardToEffectiveApp(phoneUri, messageText)

            if (!forwarded) {
                Log.e(TAG, "Failed to forward respond-via-message intent")
                // The call was already declined by this point - user will need to
                // manually send the message. Not ideal but better than failing silently.
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing respond-via-message intent", e)
        }

        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Forward the respond-via-message request to the effective SMS app.
     *
     * We recreate the RESPOND_VIA_MESSAGE intent targeted at the effective app.
     * Most SMS apps support this intent when they're the default SMS app, and
     * many can handle it even when not the default.
     *
     * @param phoneUri The SMS/SMSTO URI with the recipient phone number
     * @param messageText The pre-filled message text (e.g., "I'll call you back")
     * @return true if forwarding succeeded
     */
    private fun forwardToEffectiveApp(phoneUri: Uri?, messageText: String?): Boolean {
        val effectivePackage = EffectiveSmsAppManager.getEffectivePackage()

        if (effectivePackage == null) {
            Log.e(TAG, "No effective SMS app configured")
            return false
        }

        if (!EffectiveSmsAppManager.isEffectiveAppAvailable()) {
            Log.e(TAG, "Effective SMS app not available: $effectivePackage")
            return false
        }

        return try {
            // Try RESPOND_VIA_MESSAGE first (if the effective app supports it)
            val respondIntent = Intent(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE).apply {
                data = phoneUri ?: Uri.parse("smsto:")
                if (messageText != null) {
                    putExtra(Intent.EXTRA_TEXT, messageText)
                }
                setPackage(effectivePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if the effective app can handle this intent
            val canHandle = packageManager.queryIntentServices(respondIntent, 0).isNotEmpty() ||
                    packageManager.queryIntentActivities(respondIntent, 0).isNotEmpty()

            if (canHandle) {
                startService(respondIntent)
                Log.i(TAG, "Forwarded respond-via-message to $effectivePackage via RESPOND_VIA_MESSAGE")
                return true
            }

            // Fallback: Open the effective app's compose screen with pre-filled data
            val phoneNumber = phoneUri?.schemeSpecificPart
            val composeIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = if (phoneNumber != null) {
                    Uri.parse("smsto:$phoneNumber")
                } else {
                    Uri.parse("smsto:")
                }
                if (messageText != null) {
                    putExtra("sms_body", messageText)
                }
                setPackage(effectivePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(composeIntent)
            Log.i(TAG, "Forwarded respond-via-message to $effectivePackage via compose intent")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward respond-via-message", e)
            false
        }
    }
}
