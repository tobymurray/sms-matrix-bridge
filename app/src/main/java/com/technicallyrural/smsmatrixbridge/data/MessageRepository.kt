package com.technicallyrural.smsmatrixbridge.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow

/**
 * Repository providing access to the message store.
 *
 * This is the primary interface for all message operations. It coordinates between:
 * - The local Room database (source of truth)
 * - Matrix synchronization
 * - SMS sending pipeline
 *
 * Key principle: Messages are stored here FIRST, then synced to external systems.
 */
class MessageRepository(context: Context) {

    companion object {
        private const val TAG = "MessageRepository"

        @Volatile
        private var instance: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository {
            return instance ?: synchronized(this) {
                instance ?: MessageRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val database = AppDatabase.getInstance(context)
    private val messageDao = database.messageDao()
    private val conversationDao = database.conversationDao()

    // ========== Conversation Operations ==========

    suspend fun getOrCreateConversation(phoneNumber: String, subscriptionId: Int = -1): ConversationEntity {
        val normalized = normalizePhoneNumber(phoneNumber)
        var conversation = conversationDao.getByPhoneNumber(normalized)

        if (conversation == null) {
            val newConversation = ConversationEntity(
                phoneNumber = normalized,
                subscriptionId = subscriptionId,
                displayName = normalized // TODO: Lookup contact name
            )
            val id = conversationDao.insert(newConversation)
            conversation = newConversation.copy(id = id)
            Log.i(TAG, "Created new conversation for $normalized with id=$id")
        }

        return conversation
    }

    suspend fun getConversationById(id: Long): ConversationEntity? {
        return conversationDao.getById(id)
    }

    suspend fun getConversationByPhoneNumber(phoneNumber: String): ConversationEntity? {
        return conversationDao.getByPhoneNumber(normalizePhoneNumber(phoneNumber))
    }

    suspend fun getConversationByMatrixRoomId(roomId: String): ConversationEntity? {
        return conversationDao.getByMatrixRoomId(roomId)
    }

    fun getActiveConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.getAllActive()
    }

    suspend fun updateConversationMatrixRoom(conversationId: Long, roomId: String) {
        conversationDao.updateMatrixRoomId(conversationId, roomId)
        Log.i(TAG, "Mapped conversation $conversationId to Matrix room $roomId")
    }

    suspend fun markConversationRead(conversationId: Long) {
        conversationDao.clearUnreadCount(conversationId)
    }

    suspend fun updateConversationSyncDirection(conversationId: Long, direction: SyncDirection) {
        conversationDao.updateSyncDirection(conversationId, direction)
    }

    // ========== Message Operations ==========

    /**
     * Insert an inbound SMS message into the store.
     * This is called when an SMS is received via SMS_DELIVER broadcast.
     *
     * @return The inserted message with its generated ID
     */
    suspend fun insertInboundSms(
        phoneNumber: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int = -1
    ): MessageEntity {
        val conversation = getOrCreateConversation(phoneNumber, subscriptionId)

        // Check for duplicates (within 5 second window)
        val duplicate = messageDao.findDuplicate(
            conversationId = conversation.id,
            body = body,
            timestampMin = timestamp - 5000,
            timestampMax = timestamp + 5000,
            direction = MessageDirection.INBOUND
        )
        if (duplicate != null) {
            Log.d(TAG, "Duplicate inbound message detected, returning existing: ${duplicate.id}")
            return duplicate
        }

        val message = MessageEntity(
            conversationId = conversation.id,
            body = body,
            timestamp = timestamp,
            direction = MessageDirection.INBOUND,
            status = MessageStatus.RECEIVED,
            subscriptionId = subscriptionId
        )

        val messageId = messageDao.insert(message)
        val inserted = message.copy(id = messageId)

        // Update conversation metadata
        conversationDao.updateLastMessage(
            id = conversation.id,
            timestamp = timestamp,
            preview = body.take(100)
        )
        conversationDao.incrementUnreadCount(conversation.id)

        Log.i(TAG, "Inserted inbound SMS from ${conversation.phoneNumber}: id=$messageId")
        return inserted
    }

    /**
     * Queue an outbound SMS message for sending.
     * This is called when:
     * - User types a message in the local UI
     * - A Matrix message needs to be sent as SMS
     *
     * @param origin Where this message originated (LOCAL or MATRIX)
     * @param matrixSourceEventId If from Matrix, the source event ID for echo prevention
     * @return The queued message with its generated ID
     */
    suspend fun queueOutboundSms(
        phoneNumber: String,
        body: String,
        subscriptionId: Int = -1,
        origin: MessageOrigin = MessageOrigin.LOCAL,
        matrixSourceEventId: String? = null
    ): MessageEntity {
        val conversation = getOrCreateConversation(phoneNumber, subscriptionId)
        val timestamp = System.currentTimeMillis()

        val message = MessageEntity(
            conversationId = conversation.id,
            body = body,
            timestamp = timestamp,
            direction = MessageDirection.OUTBOUND,
            status = MessageStatus.PENDING,
            origin = origin,
            matrixSourceEventId = matrixSourceEventId,
            subscriptionId = subscriptionId
        )

        val messageId = messageDao.insert(message)
        val inserted = message.copy(id = messageId)

        // Update conversation metadata
        conversationDao.updateLastMessage(
            id = conversation.id,
            timestamp = timestamp,
            preview = body.take(100)
        )

        Log.i(TAG, "Queued outbound SMS to ${conversation.phoneNumber}: id=$messageId, origin=$origin")
        return inserted
    }

    /**
     * Get all messages for a conversation as a Flow.
     */
    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    /**
     * Get pending messages that need to be sent.
     */
    suspend fun getPendingMessages(): List<MessageEntity> {
        return messageDao.getMessagesByStatus(MessageStatus.PENDING)
    }

    /**
     * Get messages that need retry (failed with retry count < max).
     */
    suspend fun getRetryableMessages(maxRetries: Int = 3): List<MessageEntity> {
        return messageDao.getMessagesByStatus(MessageStatus.FAILED)
            .filter { it.retryCount < maxRetries }
    }

    /**
     * Mark a message as currently being sent.
     */
    suspend fun markSending(messageId: Long) {
        messageDao.updateStatus(messageId, MessageStatus.SENDING)
    }

    /**
     * Mark a message as successfully sent.
     */
    suspend fun markSent(messageId: Long) {
        messageDao.markSent(messageId)
        val message = messageDao.getById(messageId)
        if (message != null) {
            conversationDao.updateDeliveryError(message.conversationId, false)
        }
    }

    /**
     * Mark a message as delivered (confirmed by carrier).
     */
    suspend fun markDelivered(messageId: Long) {
        messageDao.markDelivered(messageId)
    }

    /**
     * Mark a message as failed with a reason.
     */
    suspend fun markFailed(messageId: Long, reason: String?) {
        messageDao.markFailed(messageId, reason = reason)
        val message = messageDao.getById(messageId)
        if (message != null) {
            conversationDao.updateDeliveryError(message.conversationId, true)
        }
    }

    /**
     * Update the Matrix event ID for a message that was synced to Matrix.
     */
    suspend fun updateMatrixEventId(messageId: Long, eventId: String) {
        messageDao.updateMatrixEventId(messageId, eventId)
    }

    /**
     * Check if a message from a Matrix event already exists (echo prevention).
     */
    suspend fun hasMessageFromMatrixEvent(eventId: String): Boolean {
        return messageDao.hasMessageFromMatrixEvent(eventId)
    }

    /**
     * Get a message by its Matrix event ID.
     */
    suspend fun getMessageByMatrixEventId(eventId: String): MessageEntity? {
        return messageDao.getByMatrixEventId(eventId)
    }

    // ========== Statistics ==========

    suspend fun getConversationCount(): Int = conversationDao.getConversationCount()

    suspend fun getMappedConversationCount(): Int = conversationDao.getMappedConversationCount()

    suspend fun getTotalUnreadCount(): Int = conversationDao.getTotalUnreadCount() ?: 0

    // ========== Utility ==========

    /**
     * Normalize a phone number to E.164 format.
     */
    fun normalizePhoneNumber(phone: String): String {
        // Remove all non-digit characters except leading +
        val cleaned = phone.replace(Regex("[^+\\d]"), "")

        // If it already starts with +, return as-is
        if (cleaned.startsWith("+")) {
            return cleaned
        }

        // Extract just digits
        val digits = cleaned.filter { it.isDigit() }

        return when {
            // 10 digits: assume US number, add +1
            digits.length == 10 -> "+1$digits"
            // 11 digits starting with 1: assume US number with country code
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            // Otherwise, just add + prefix
            else -> "+$digits"
        }
    }
}
