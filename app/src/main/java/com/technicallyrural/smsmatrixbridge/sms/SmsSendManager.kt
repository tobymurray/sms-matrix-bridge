package com.technicallyrural.smsmatrixbridge.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.technicallyrural.smsmatrixbridge.BridgeApplication
import com.technicallyrural.smsmatrixbridge.LoopPrevention
import com.technicallyrural.smsmatrixbridge.data.MessageEntity
import com.technicallyrural.smsmatrixbridge.data.MessageOrigin
import com.technicallyrural.smsmatrixbridge.data.MessageRepository
import com.technicallyrural.smsmatrixbridge.data.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified SMS send manager that handles all outbound SMS.
 *
 * This is the single send path for:
 * - Local UI (user typing on phone)
 * - Matrix → SMS delivery
 *
 * Features:
 * - PendingIntent callbacks for SENT and DELIVERED status
 * - Status updates to the message store
 * - Retry support
 * - Dual SIM awareness
 * - Multipart message handling
 */
class SmsSendManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SmsSendManager"

        const val ACTION_SMS_SENT = "com.technicallyrural.smsmatrixbridge.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.technicallyrural.smsmatrixbridge.SMS_DELIVERED"

        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_TOTAL_PARTS = "total_parts"

        @Volatile
        private var instance: SmsSendManager? = null

        fun getInstance(context: Context): SmsSendManager {
            return instance ?: synchronized(this) {
                instance ?: SmsSendManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Translate SmsManager result code to human-readable reason.
         */
        fun getErrorReason(resultCode: Int): String {
            return when (resultCode) {
                Activity.RESULT_OK -> "Success"
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
                SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
                SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS limit exceeded"
                SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "Short code not allowed"
                SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Short code never allowed"
                else -> "Unknown error ($resultCode)"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository: MessageRepository by lazy { BridgeApplication.instance.messageRepository }

    // Track pending multipart messages: messageId -> (partsReceived, totalParts, allSucceeded)
    private val pendingMultipart = ConcurrentHashMap<Long, MultipartState>()

    // Events for observers
    private val _sendResults = MutableSharedFlow<SmsSendResult>(extraBufferCapacity = 64)
    val sendResults: SharedFlow<SmsSendResult> = _sendResults.asSharedFlow()

    private val _deliveryResults = MutableSharedFlow<SmsDeliveryResult>(extraBufferCapacity = 64)
    val deliveryResults: SharedFlow<SmsDeliveryResult> = _deliveryResults.asSharedFlow()

    // Broadcast receivers
    private var sentReceiver: BroadcastReceiver? = null
    private var deliveredReceiver: BroadcastReceiver? = null

    private data class MultipartState(
        val totalParts: Int,
        var partsReceived: Int = 0,
        var allSucceeded: Boolean = true,
        var firstFailureReason: String? = null
    )

    /**
     * Start listening for SMS send/delivery callbacks.
     * Call this when the service starts.
     */
    fun start() {
        registerReceivers()
        Log.i(TAG, "SmsSendManager started")
    }

    /**
     * Stop listening for callbacks.
     * Call this when the service stops.
     */
    fun stop() {
        unregisterReceivers()
        Log.i(TAG, "SmsSendManager stopped")
    }

    /**
     * Send an SMS message. The message must already be in the store.
     *
     * @param message The message entity to send (must have status PENDING)
     * @return true if send was initiated successfully
     */
    suspend fun sendMessage(message: MessageEntity): Boolean {
        if (message.status != MessageStatus.PENDING && message.status != MessageStatus.FAILED) {
            Log.w(TAG, "Message ${message.id} is not in sendable state: ${message.status}")
            return false
        }

        val conversation = repository.getConversationById(message.conversationId)
        if (conversation == null) {
            Log.e(TAG, "Conversation not found for message ${message.id}")
            repository.markFailed(message.id, "Conversation not found")
            return false
        }

        val phoneNumber = conversation.phoneNumber
        val body = message.body

        Log.i(TAG, "Sending SMS to $phoneNumber: ${body.take(50)}... (id=${message.id})")

        // Mark as sending
        repository.markSending(message.id)

        // Record for loop prevention BEFORE sending
        // This prevents the ContentObserver from seeing this as a user-sent message
        LoopPrevention.recordOutgoingSms(phoneNumber, body)

        return try {
            val smsManager = getSmsManager(message.subscriptionId)
            val parts = smsManager.divideMessage(body)

            if (parts.size == 1) {
                sendSinglePart(smsManager, phoneNumber, body, message.id)
            } else {
                sendMultipart(smsManager, phoneNumber, parts, message.id)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
            repository.markFailed(message.id, e.message ?: "Send failed")
            _sendResults.emit(SmsSendResult.Failure(message.id, phoneNumber, e.message ?: "Send failed"))
            false
        }
    }

    /**
     * Queue and send an SMS from local UI.
     *
     * @param phoneNumber Destination phone number
     * @param body Message text
     * @param subscriptionId SIM to use (-1 for default)
     * @return The queued message, or null if queueing failed
     */
    suspend fun sendFromLocalUi(
        phoneNumber: String,
        body: String,
        subscriptionId: Int = -1
    ): MessageEntity? {
        val message = repository.queueOutboundSms(
            phoneNumber = phoneNumber,
            body = body,
            subscriptionId = subscriptionId,
            origin = MessageOrigin.LOCAL
        )

        val success = sendMessage(message)
        return if (success) message else null
    }

    /**
     * Queue and send an SMS from a Matrix message.
     *
     * @param phoneNumber Destination phone number
     * @param body Message text
     * @param matrixEventId The source Matrix event ID (for echo prevention)
     * @param subscriptionId SIM to use (-1 for default)
     * @return The queued message, or null if queueing failed
     */
    suspend fun sendFromMatrix(
        phoneNumber: String,
        body: String,
        matrixEventId: String,
        subscriptionId: Int = -1
    ): MessageEntity? {
        // Check if we already processed this Matrix event
        if (repository.hasMessageFromMatrixEvent(matrixEventId)) {
            Log.d(TAG, "Already sent SMS for Matrix event $matrixEventId, skipping")
            return null
        }

        val message = repository.queueOutboundSms(
            phoneNumber = phoneNumber,
            body = body,
            subscriptionId = subscriptionId,
            origin = MessageOrigin.MATRIX,
            matrixSourceEventId = matrixEventId
        )

        val success = sendMessage(message)
        return if (success) message else null
    }

    /**
     * Retry sending a failed message.
     */
    suspend fun retryMessage(messageId: Long): Boolean {
        val message = repository.getConversationById(messageId)?.let {
            // Actually get the message, not conversation
            null
        }

        // Get the actual message
        val actualMessage = repository.getPendingMessages().find { it.id == messageId }
            ?: repository.getRetryableMessages().find { it.id == messageId }

        if (actualMessage == null) {
            Log.w(TAG, "Message $messageId not found for retry")
            return false
        }

        return sendMessage(actualMessage)
    }

    /**
     * Process all pending messages in the queue.
     */
    suspend fun processPendingMessages() {
        val pending = repository.getPendingMessages()
        Log.i(TAG, "Processing ${pending.size} pending messages")

        for (message in pending) {
            sendMessage(message)
        }
    }

    private fun getSmsManager(subscriptionId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (subscriptionId > 0) {
                context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subscriptionId)
            } else {
                context.getSystemService(SmsManager::class.java)
            }
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId > 0) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
    }

    private fun sendSinglePart(
        smsManager: SmsManager,
        phoneNumber: String,
        message: String,
        messageId: Long
    ) {
        val sentIntent = createSentPendingIntent(messageId, phoneNumber, 0, 1)
        val deliveredIntent = createDeliveredPendingIntent(messageId, phoneNumber, 0, 1)

        smsManager.sendTextMessage(
            phoneNumber,
            null,  // Service center
            message,
            sentIntent,
            deliveredIntent
        )

        Log.d(TAG, "Single-part SMS send initiated: messageId=$messageId")
    }

    private fun sendMultipart(
        smsManager: SmsManager,
        phoneNumber: String,
        parts: ArrayList<String>,
        messageId: Long
    ) {
        val totalParts = parts.size
        Log.d(TAG, "Multipart SMS: $totalParts parts for messageId=$messageId")

        // Initialize multipart tracking
        pendingMultipart[messageId] = MultipartState(totalParts)

        val sentIntents = ArrayList<PendingIntent>()
        val deliveredIntents = ArrayList<PendingIntent>()

        for (i in parts.indices) {
            sentIntents.add(createSentPendingIntent(messageId, phoneNumber, i, totalParts))
            deliveredIntents.add(createDeliveredPendingIntent(messageId, phoneNumber, i, totalParts))
        }

        smsManager.sendMultipartTextMessage(
            phoneNumber,
            null,
            parts,
            sentIntents,
            deliveredIntents
        )
    }

    private fun createSentPendingIntent(
        messageId: Long,
        phoneNumber: String,
        partIndex: Int,
        totalParts: Int
    ): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
        }

        val requestCode = (messageId.toInt() * 100) + partIndex

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun createDeliveredPendingIntent(
        messageId: Long,
        phoneNumber: String,
        partIndex: Int,
        totalParts: Int
    ): PendingIntent {
        val intent = Intent(ACTION_SMS_DELIVERED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
        }

        val requestCode = (messageId.toInt() * 100) + partIndex + 50

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun registerReceivers() {
        // Sent receiver
        sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                handleSentCallback(intent, resultCode)
            }
        }

        val sentFilter = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(sentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(sentReceiver, sentFilter)
        }

        // Delivered receiver
        deliveredReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                handleDeliveredCallback(intent, resultCode)
            }
        }

        val deliveredFilter = IntentFilter(ACTION_SMS_DELIVERED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(deliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(deliveredReceiver, deliveredFilter)
        }

        Log.d(TAG, "Broadcast receivers registered")
    }

    private fun unregisterReceivers() {
        sentReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister sent receiver", e)
            }
        }
        sentReceiver = null

        deliveredReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister delivered receiver", e)
            }
        }
        deliveredReceiver = null

        Log.d(TAG, "Broadcast receivers unregistered")
    }

    private fun handleSentCallback(intent: Intent, resultCode: Int) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val totalParts = intent.getIntExtra(EXTRA_TOTAL_PARTS, 1)

        if (messageId < 0) {
            Log.w(TAG, "Received sent callback without message ID")
            return
        }

        val success = resultCode == Activity.RESULT_OK
        val reason = if (!success) getErrorReason(resultCode) else null

        Log.d(TAG, "SMS sent callback: messageId=$messageId, part=${partIndex + 1}/$totalParts, success=$success")

        if (totalParts == 1) {
            // Single part message
            scope.launch {
                if (success) {
                    repository.markSent(messageId)
                    _sendResults.emit(SmsSendResult.Success(messageId, phoneNumber))
                } else {
                    repository.markFailed(messageId, reason)
                    _sendResults.emit(SmsSendResult.Failure(messageId, phoneNumber, reason ?: "Unknown error", resultCode))
                }
            }
        } else {
            // Multipart message - wait for all parts
            handleMultipartSent(messageId, phoneNumber, success, reason, resultCode)
        }
    }

    private fun handleMultipartSent(
        messageId: Long,
        phoneNumber: String,
        partSuccess: Boolean,
        reason: String?,
        resultCode: Int
    ) {
        val state = pendingMultipart[messageId] ?: return

        synchronized(state) {
            state.partsReceived++
            if (!partSuccess) {
                state.allSucceeded = false
                if (state.firstFailureReason == null) {
                    state.firstFailureReason = reason
                }
            }

            if (state.partsReceived >= state.totalParts) {
                // All parts received
                pendingMultipart.remove(messageId)

                scope.launch {
                    if (state.allSucceeded) {
                        repository.markSent(messageId)
                        _sendResults.emit(SmsSendResult.Success(messageId, phoneNumber))
                    } else {
                        repository.markFailed(messageId, state.firstFailureReason)
                        _sendResults.emit(
                            SmsSendResult.Failure(
                                messageId,
                                phoneNumber,
                                state.firstFailureReason ?: "Multipart send failed",
                                resultCode
                            )
                        )
                    }
                }
            }
        }
    }

    private fun handleDeliveredCallback(intent: Intent, resultCode: Int) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: return
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val totalParts = intent.getIntExtra(EXTRA_TOTAL_PARTS, 1)

        if (messageId < 0) {
            Log.w(TAG, "Received delivery callback without message ID")
            return
        }

        val delivered = resultCode == Activity.RESULT_OK

        Log.d(TAG, "SMS delivery callback: messageId=$messageId, part=${partIndex + 1}/$totalParts, delivered=$delivered")

        // For simplicity, mark as delivered on first delivery confirmation
        // (multipart delivery tracking would require separate state)
        if (delivered && partIndex == 0) {
            scope.launch {
                repository.markDelivered(messageId)
                _deliveryResults.emit(
                    SmsDeliveryResult.Delivered(messageId, phoneNumber, System.currentTimeMillis())
                )
            }
        }
    }
}
