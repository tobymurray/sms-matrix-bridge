package com.technicallyrural.smsmatrixbridge.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversationSync(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = :status ORDER BY timestamp ASC")
    suspend fun getMessagesByStatus(status: MessageStatus): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status IN (:statuses) ORDER BY timestamp ASC")
    suspend fun getMessagesByStatuses(statuses: List<MessageStatus>): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY timestamp ASC")
    suspend fun getPendingAndFailedMessages(): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: MessageStatus)

    @Query("UPDATE messages SET status = :status, failureReason = :reason, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markFailed(id: Long, status: MessageStatus = MessageStatus.FAILED, reason: String?)

    @Query("UPDATE messages SET status = 'SENT', sentTimestamp = :timestamp WHERE id = :id")
    suspend fun markSent(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET status = 'DELIVERED', deliveredTimestamp = :timestamp WHERE id = :id")
    suspend fun markDelivered(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET matrixEventId = :eventId WHERE id = :id")
    suspend fun updateMatrixEventId(id: Long, eventId: String)

    @Query("SELECT * FROM messages WHERE matrixEventId = :eventId LIMIT 1")
    suspend fun getByMatrixEventId(eventId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE matrixSourceEventId = :eventId LIMIT 1")
    suspend fun getByMatrixSourceEventId(eventId: String): MessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE matrixSourceEventId = :eventId)")
    suspend fun hasMessageFromMatrixEvent(eventId: String): Boolean

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllForConversation(conversationId: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int

    @Query("""
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        AND body = :body
        AND timestamp BETWEEN :timestampMin AND :timestampMax
        AND direction = :direction
        LIMIT 1
    """)
    suspend fun findDuplicate(
        conversationId: Long,
        body: String,
        timestampMin: Long,
        timestampMax: Long,
        direction: MessageDirection
    ): MessageEntity?
}
