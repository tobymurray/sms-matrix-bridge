package com.technicallyrural.smsmatrixbridge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles forwarding of SMS/MMS messages to the effective default SMS app.
 *
 * This is the core of the pass-through architecture. When this bridge app receives
 * SMS/MMS as the system default, it must forward those messages to the user's
 * actual SMS app (the "effective default") so that:
 * - Messages appear in the user's normal conversation threads
 * - Notifications are handled by their preferred app
 * - All UX remains unchanged from the user's perspective
 *
 * IMPORTANT: The effective SMS app cannot receive SMS_DELIVER broadcasts because
 * only the system default can. Therefore, we must write to the SMS content provider
 * so the effective app can display the messages.
 *
 * Forwarding Strategy:
 * 1. Write incoming SMS to the system SMS content provider (Telephony.Sms.CONTENT_URI)
 * 2. Send a notification broadcast to prompt the effective app to refresh
 * 3. The effective app reads from the content provider and displays the message
 */
object SmsForwarder {
    private const val TAG = "SmsForwarder"

    // Result codes for forwarding operations
    sealed class ForwardResult {
        data class Success(val messageUri: Uri?) : ForwardResult()
        data class Failure(val error: String, val exception: Throwable? = null) : ForwardResult()
    }

    /**
     * Forwards an incoming SMS to the effective SMS app by writing to the SMS content provider.
     *
     * When we're the default SMS app, we have write access to Telephony.Sms.CONTENT_URI.
     * Writing here makes the message visible to all SMS apps, including the effective default.
     *
     * @param context Application context
     * @param sender The sender's phone number
     * @param body The message text
     * @param timestamp When the message was received (epoch millis)
     * @param subscriptionId The SIM subscription ID (-1 for default)
     * @return ForwardResult indicating success or failure
     */
    suspend fun forwardIncomingSms(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int = -1
    ): ForwardResult = withContext(Dispatchers.IO) {
        try {
            // Validate effective app is available
            if (!EffectiveSmsAppManager.isEffectiveAppAvailable()) {
                Log.w(TAG, "Effective SMS app not available, cannot forward")
                return@withContext ForwardResult.Failure("Effective SMS app not available")
            }

            // Write to the SMS inbox
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0) // Mark as unread
                put(Telephony.Sms.SEEN, 0) // Mark as unseen (triggers notification)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)

                if (subscriptionId != -1) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                }
            }

            val uri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)

            if (uri != null) {
                Log.i(TAG, "Successfully wrote SMS to content provider: $uri (from: $sender)")

                // Notify the effective app to refresh
                notifyNewSmsReceived(context, sender, body, timestamp)

                ForwardResult.Success(uri)
            } else {
                Log.e(TAG, "Failed to insert SMS into content provider")
                ForwardResult.Failure("Insert returned null URI")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied writing to SMS content provider", e)
            ForwardResult.Failure("Permission denied: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding SMS to content provider", e)
            ForwardResult.Failure("Error: ${e.message}", e)
        }
    }

    /**
     * Sends a broadcast to notify apps that a new SMS was received.
     * This helps the effective SMS app update its UI.
     *
     * Note: This is a best-effort notification. The effective app should also
     * be monitoring the content provider for changes via ContentObserver.
     */
    private fun notifyNewSmsReceived(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long
    ) {
        try {
            // Send SMS_RECEIVED broadcast (not SMS_DELIVER which is restricted)
            // This is the broadcast that apps registered with lower priority receive
            val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                // Note: We can't fully replicate the PDU extras because we don't have
                // the original PDUs. The effective app should rely on the content provider.
                putExtra("notification_type", "sms_bridge_forward")
                putExtra("sender", sender)
                putExtra("body", body)
                putExtra("timestamp", timestamp)
            }

            // Only send to the effective app
            val effectivePackage = EffectiveSmsAppManager.getEffectivePackage()
            if (effectivePackage != null) {
                intent.setPackage(effectivePackage)
                context.sendBroadcast(intent)
                Log.d(TAG, "Sent SMS notification broadcast to $effectivePackage")
            }
        } catch (e: Exception) {
            // Non-fatal: the content provider insert is the primary mechanism
            Log.w(TAG, "Failed to send notification broadcast", e)
        }
    }

    /**
     * Forwards an outgoing SMS request to the effective SMS app for transmission.
     *
     * When the user or another app tries to send an SMS through us (because we're
     * the default), we delegate the actual sending to the effective SMS app.
     *
     * @param context Application context
     * @param recipient The destination phone number
     * @param body The message text
     * @return true if the intent was successfully launched
     */
    fun forwardOutgoingSmsIntent(
        context: Context,
        recipient: String,
        body: String
    ): Boolean {
        val effectivePackage = EffectiveSmsAppManager.getEffectivePackage()
        if (effectivePackage == null) {
            Log.e(TAG, "No effective SMS app configured for outgoing forward")
            return false
        }

        return try {
            // Create an SMS send intent targeted at the effective app
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$recipient")
                putExtra("sms_body", body)
                setPackage(effectivePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            Log.i(TAG, "Forwarded outgoing SMS intent to $effectivePackage")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward outgoing SMS intent", e)
            false
        }
    }

    /**
     * Writes a sent message to the SMS content provider's sent box.
     * Used after successfully forwarding an outgoing message.
     *
     * @param context Application context
     * @param recipient The destination phone number
     * @param body The message text
     * @param timestamp When the message was sent
     * @return The URI of the inserted message, or null on failure
     */
    suspend fun recordSentSms(
        context: Context,
        recipient: String,
        body: String,
        timestamp: Long = System.currentTimeMillis()
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, recipient)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
            }

            val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            if (uri != null) {
                Log.d(TAG, "Recorded sent SMS in content provider: $uri")
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record sent SMS", e)
            null
        }
    }

    /**
     * Extracts SMS messages from the raw PDUs in a broadcast intent.
     * Used to get the full message data for forwarding.
     *
     * @param intent The SMS_DELIVER broadcast intent
     * @return List of extracted messages with sender, body, and timestamp
     */
    fun extractMessagesFromIntent(intent: Intent): List<SmsData> {
        val messages = mutableListOf<SmsData>()

        try {
            val pdus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.extras?.get("pdus") as? Array<*>
            } else {
                @Suppress("DEPRECATION")
                intent.extras?.get("pdus") as? Array<*>
            }

            val format = intent.extras?.getString("format") ?: SmsMessage.FORMAT_3GPP
            val subscriptionId = intent.extras?.getInt("subscription", -1) ?: -1

            if (pdus == null) {
                Log.w(TAG, "No PDUs in SMS intent")
                return messages
            }

            // Group messages by sender for multipart SMS handling
            val messagesBySender = mutableMapOf<String, StringBuilder>()
            val timestampBySender = mutableMapOf<String, Long>()

            for (pdu in pdus) {
                if (pdu !is ByteArray) continue

                val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu)
                }

                if (smsMessage != null) {
                    val sender = smsMessage.displayOriginatingAddress ?: continue
                    val body = smsMessage.displayMessageBody ?: ""
                    val timestamp = smsMessage.timestampMillis

                    // Concatenate multipart messages
                    if (messagesBySender.containsKey(sender)) {
                        messagesBySender[sender]?.append(body)
                    } else {
                        messagesBySender[sender] = StringBuilder(body)
                        timestampBySender[sender] = timestamp
                    }
                }
            }

            // Create SmsData objects for each complete message
            for ((sender, bodyBuilder) in messagesBySender) {
                messages.add(
                    SmsData(
                        sender = sender,
                        body = bodyBuilder.toString(),
                        timestamp = timestampBySender[sender] ?: System.currentTimeMillis(),
                        subscriptionId = subscriptionId
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting messages from intent", e)
        }

        return messages
    }

    /**
     * Data class representing an SMS message for forwarding.
     */
    data class SmsData(
        val sender: String,
        val body: String,
        val timestamp: Long,
        val subscriptionId: Int = -1
    )

    /**
     * Checks if we can successfully forward messages.
     * Used for health checks and status display.
     */
    fun canForward(): Boolean {
        return EffectiveSmsAppManager.hasEffectiveApp() &&
                EffectiveSmsAppManager.isEffectiveAppAvailable()
    }

    /**
     * Debug information for logging.
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== SmsForwarder Debug ===")
            appendLine("Can forward: ${canForward()}")
            appendLine("Effective app available: ${EffectiveSmsAppManager.isEffectiveAppAvailable()}")
            appendLine("Effective package: ${EffectiveSmsAppManager.getEffectivePackage()}")
        }
    }
}
