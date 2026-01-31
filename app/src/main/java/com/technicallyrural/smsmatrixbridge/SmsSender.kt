package com.technicallyrural.smsmatrixbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Handles sending SMS messages from Matrix to phone.
 *
 * Outgoing SMS Flow (Matrix → SMS):
 * 1. BridgeService detects new message in a mapped Matrix room
 * 2. LoopPrevention checks if we sent this message (ignore if so)
 * 3. SmsSender.sendSms() is called with phone number and message
 * 4. SMS is sent via Android's SmsManager
 * 5. Delivery status is optionally reported back to Matrix room
 *
 * Handles multipart messages (> 160 characters) automatically.
 */
object SmsSender {

    private const val TAG = "SmsSender"
    private const val ACTION_SMS_SENT = "com.technicallyrural.smsmatrixbridge.SMS_SENT"
    private const val ACTION_SMS_DELIVERED = "com.technicallyrural.smsmatrixbridge.SMS_DELIVERED"

    /**
     * Send an SMS message.
     *
     * @param context Application context
     * @param phoneNumber Destination phone number
     * @param message Message text to send
     * @return true if send was initiated successfully
     */
    fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            val normalizedPhone = RoomMapper.normalizePhoneNumber(phoneNumber)

            Log.i(TAG, "Sending SMS to $normalizedPhone: ${message.take(50)}...")

            // Record for loop prevention BEFORE sending
            LoopPrevention.recordOutgoingSms(normalizedPhone, message)

            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Check if message needs to be split (multipart SMS)
            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                // Single part SMS
                val sentIntent = createSentIntent(context, normalizedPhone)
                smsManager.sendTextMessage(
                    normalizedPhone,
                    null,  // Service center - use default
                    message,
                    sentIntent,
                    null   // Delivery intent - skip for simplicity
                )
            } else {
                // Multipart SMS
                Log.d(TAG, "Message split into ${parts.size} parts")
                val sentIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    sentIntents.add(createSentIntent(context, normalizedPhone, i))
                }
                smsManager.sendMultipartTextMessage(
                    normalizedPhone,
                    null,
                    parts,
                    sentIntents,
                    null
                )
            }

            Log.i(TAG, "SMS send initiated to $normalizedPhone")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
            false
        }
    }

    /**
     * Send SMS and wait for send confirmation.
     *
     * @return true if SMS was sent successfully, false otherwise
     */
    suspend fun sendSmsWithConfirmation(
        context: Context,
        phoneNumber: String,
        message: String,
        timeoutMs: Long = 30000
    ): Boolean {
        val normalizedPhone = RoomMapper.normalizePhoneNumber(phoneNumber)

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                var receiver: BroadcastReceiver? = null

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (e: Exception) {
                            // Already unregistered
                        }

                        val success = resultCode == android.app.Activity.RESULT_OK
                        Log.d(TAG, "SMS send result for $normalizedPhone: $success (code: $resultCode)")

                        if (continuation.isActive) {
                            continuation.resume(success)
                        }
                    }
                }

                val filter = IntentFilter(ACTION_SMS_SENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }

                continuation.invokeOnCancellation {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (e: Exception) {
                        // Already unregistered
                    }
                }

                // Now send the SMS
                LoopPrevention.recordOutgoingSms(normalizedPhone, message)

                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

                val sentIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_SMS_SENT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                smsManager.sendTextMessage(normalizedPhone, null, message, sentIntent, null)
            }
        } ?: false
    }

    /**
     * Create a PendingIntent for SMS sent notification.
     */
    private fun createSentIntent(
        context: Context,
        phoneNumber: String,
        partIndex: Int = 0
    ): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).apply {
            putExtra("phone", phoneNumber)
            putExtra("part", partIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            phoneNumber.hashCode() + partIndex,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
