package com.technicallyrural.smsmatrixbridge.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getByPhoneNumber(phoneNumber: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE matrixRoomId = :roomId LIMIT 1")
    suspend fun getByMatrixRoomId(roomId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY lastMessageTimestamp DESC")
    fun getAllActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY lastMessageTimestamp DESC")
    fun getAllActiveLiveData(): LiveData<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY lastMessageTimestamp DESC")
    fun getAllArchived(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    suspend fun getAllSync(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE hasDeliveryError = 1")
    suspend fun getConversationsWithErrors(): List<ConversationEntity>

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE id = :id")
    suspend fun incrementUnreadCount(id: Long)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnreadCount(id: Long)

    @Query("""
        UPDATE conversations
        SET lastMessageTimestamp = :timestamp,
            lastMessagePreview = :preview
        WHERE id = :id
    """)
    suspend fun updateLastMessage(id: Long, timestamp: Long, preview: String)

    @Query("UPDATE conversations SET matrixRoomId = :roomId WHERE id = :id")
    suspend fun updateMatrixRoomId(id: Long, roomId: String)

    @Query("UPDATE conversations SET hasDeliveryError = :hasError WHERE id = :id")
    suspend fun updateDeliveryError(id: Long, hasError: Boolean)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :id")
    suspend fun updateArchived(id: Long, archived: Boolean)

    @Query("UPDATE conversations SET isMuted = :muted WHERE id = :id")
    suspend fun updateMuted(id: Long, muted: Boolean)

    @Query("UPDATE conversations SET syncDirection = :direction WHERE id = :id")
    suspend fun updateSyncDirection(id: Long, direction: SyncDirection)

    @Query("UPDATE conversations SET displayName = :name WHERE id = :id")
    suspend fun updateDisplayName(id: Long, name: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    @Query("SELECT COUNT(*) FROM conversations WHERE matrixRoomId IS NOT NULL")
    suspend fun getMappedConversationCount(): Int

    @Query("SELECT SUM(unreadCount) FROM conversations")
    suspend fun getTotalUnreadCount(): Int?

    @Query("""
        SELECT * FROM conversations
        WHERE syncDirection = :direction OR syncDirection = 'BIDIRECTIONAL'
        ORDER BY lastMessageTimestamp DESC
    """)
    suspend fun getConversationsWithSyncDirection(direction: SyncDirection): List<ConversationEntity>
}
