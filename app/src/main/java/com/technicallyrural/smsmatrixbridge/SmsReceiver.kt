package com.technicallyrural.smsmatrixbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.technicallyrural.smsmatrixbridge.config.BridgeConfig
import com.technicallyrural.smsmatrixbridge.data.MessageRepository
import com.technicallyrural.smsmatrixbridge.data.SyncDirection
import com.technicallyrural.smsmatrixbridge.ui.ConversationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BroadcastReceiver for incoming SMS messages.
 *
 * As the system default SMS app, this receiver intercepts SMS_DELIVER broadcasts.
 *
 * Processing flow:
 * 1. Extract message data (sender, body, timestamp, subscription ID)
 * 2. Insert into internal message store (source of truth)
 * 3. Show notification to user
 * 4. Trigger Matrix sync (if enabled)
 *
 * The internal message store is authoritative. The system SMS content provider
 * is treated as transport only.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val NOTIFICATION_CHANNEL_ID = "sms_notifications"
    }

    // Coroutine scope for async operations (survives receiver lifecycle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // Handle both SMS_DELIVER (as default app) and SMS_RECEIVED (fallback)
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
            action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val isDeliverIntent = action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
        Log.d(TAG, "Received ${if (isDeliverIntent) "SMS_DELIVER" else "SMS_RECEIVED"} broadcast")

        // Extract messages from the intent
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "No messages in intent")
            return
        }

        // Get subscription ID for dual SIM support
        val subscriptionId = intent.getIntExtra("subscription", -1)

        // Group message parts by sender (multipart SMS)
        val messagesBySender = mutableMapOf<String, StringBuilder>()
        val timestampBySender = mutableMapOf<String, Long>()

        for (sms in messages) {
            val sender = sms.originatingAddress ?: continue
            val body = sms.messageBody ?: continue
            messagesBySender.getOrPut(sender) { StringBuilder() }.append(body)
            if (!timestampBySender.containsKey(sender)) {
                timestampBySender[sender] = sms.timestampMillis
            }
        }

        // Process each complete message
        for ((sender, bodyBuilder) in messagesBySender) {
            val body = bodyBuilder.toString()
            val timestamp = timestampBySender[sender] ?: System.currentTimeMillis()

            processIncomingSms(
                context = context,
                sender = sender,
                body = body,
                timestamp = timestamp,
                subscriptionId = subscriptionId
            )
        }
    }

    /**
     * Process a complete incoming SMS message.
     *
     * Processing order:
     * 1. Check loop prevention (is this our own sent message echoing?)
     * 2. Insert into internal message store
     * 3. Show notification
     * 4. Trigger Matrix sync (if enabled for this conversation)
     */
    private fun processIncomingSms(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int
    ) {
        val repository = MessageRepository.getInstance(context)
        val normalizedSender = repository.normalizePhoneNumber(sender)

        Log.i(TAG, "Processing SMS from $normalizedSender: ${body.take(50)}...")

        // Loop prevention check (for SMS we sent that are echoing back)
        if (LoopPrevention.wasRecentlySentSms(normalizedSender, body)) {
            Log.d(TAG, "Ignoring SMS - appears to be echo of our sent message")
            return
        }

        // Process asynchronously
        scope.launch {
            try {
                // 1. Insert into internal message store (source of truth)
                val message = repository.insertInboundSms(
                    phoneNumber = normalizedSender,
                    body = body,
                    timestamp = timestamp,
                    subscriptionId = subscriptionId
                )

                Log.i(TAG, "Inserted inbound SMS: id=${message.id}")

                // 2. Record timestamp in config
                val config = BridgeConfig.getInstance(context)
                config.recordSmsReceived()

                // 3. Show notification
                showNotification(context, normalizedSender, body, message.conversationId)

                // 4. Bridge to Matrix (if enabled for this conversation)
                bridgeToMatrixIfEnabled(context, repository, message.conversationId, normalizedSender, body)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming SMS", e)
            }
        }
    }

    /**
     * Show a notification for the incoming message.
     */
    private fun showNotification(
        context: Context,
        sender: String,
        body: String,
        conversationId: Long
    ) {
        try {
            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // Create notification channel if needed
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "SMS Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming SMS message notifications"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)

            // Create intent to open conversation
            val intent = Intent(context, ConversationActivity::class.java).apply {
                putExtra(ConversationActivity.EXTRA_CONVERSATION_ID, conversationId)
                putExtra(ConversationActivity.EXTRA_PHONE_NUMBER, sender)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                conversationId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(sender)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(conversationId.toInt(), notification)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }

    /**
     * Bridge message to Matrix if sync is enabled for this conversation.
     */
    private suspend fun bridgeToMatrixIfEnabled(
        context: Context,
        repository: MessageRepository,
        conversationId: Long,
        phoneNumber: String,
        body: String
    ) {
        try {
            val config = BridgeConfig.getInstance(context)

            // Check global bridge enabled
            if (!config.bridgeEnabled.first()) {
                Log.d(TAG, "Bridge disabled globally, skipping Matrix sync")
                return
            }

            // Check if Matrix sync is enabled
            if (!config.matrixSyncEnabled.first()) {
                Log.d(TAG, "Matrix sync disabled, skipping")
                return
            }

            // Check conversation-level sync direction
            val conversation = repository.getConversationById(conversationId)
            if (conversation != null) {
                val direction = conversation.syncDirection
                if (direction == SyncDirection.NONE || direction == SyncDirection.MATRIX_TO_SMS) {
                    Log.d(TAG, "SMS→Matrix sync disabled for this conversation")
                    return
                }
            }

            // Bridge to Matrix
            bridgeToMatrix(context, repository, phoneNumber, body, conversationId)

        } catch (e: Exception) {
            Log.e(TAG, "Error checking Matrix sync settings", e)
        }
    }

    /**
     * Bridge an incoming SMS to Matrix.
     */
    private suspend fun bridgeToMatrix(
        context: Context,
        repository: MessageRepository,
        phoneNumber: String,
        messageBody: String,
        conversationId: Long
    ) {
        try {
            val matrixClient = MatrixClient()
            val config = BridgeConfig.getInstance(context)

            // Get or create room for this contact
            var conversation = repository.getConversationById(conversationId)
            var roomId = conversation?.matrixRoomId

            if (roomId == null) {
                Log.i(TAG, "No existing room for $phoneNumber, creating new one")
                roomId = matrixClient.createRoom(phoneNumber)

                if (roomId == null) {
                    Log.e(TAG, "Failed to create room for $phoneNumber")
                    return
                }

                // Update conversation with room mapping
                repository.updateConversationMatrixRoom(conversationId, roomId)
                Log.i(TAG, "Created and mapped room $roomId for $phoneNumber")
            }

            // Prepare metadata
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val metadata = MessageMetadata(
                direction = "incoming",
                phoneNumber = phoneNumber,
                timestamp = timestamp
            )

            // Record outgoing Matrix message for loop prevention
            LoopPrevention.recordOutgoingMatrix(roomId, messageBody)

            // Send to Matrix
            val eventId = matrixClient.sendMessage(roomId, messageBody, metadata)

            if (eventId != null) {
                Log.i(TAG, "Bridged SMS from $phoneNumber to Matrix: $eventId")
                config.recordMatrixEventSent()
            } else {
                Log.e(TAG, "Failed to bridge SMS from $phoneNumber to Matrix")
            }

        } catch (e: Exception) {
            // Matrix errors are logged but do not affect SMS functionality
            Log.e(TAG, "Error bridging SMS to Matrix (non-fatal)", e)
        }
    }
}
