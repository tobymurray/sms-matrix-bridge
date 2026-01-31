package com.technicallyrural.smsmatrixbridge.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Sync direction configuration for a conversation.
 */
enum class SyncDirection {
    NONE,           // No sync
    SMS_TO_MATRIX,  // Only SMS → Matrix
    MATRIX_TO_SMS,  // Only Matrix → SMS
    BIDIRECTIONAL   // Both directions
}

/**
 * Represents a conversation thread with a single contact.
 *
 * Each conversation maps to exactly one phone number and optionally one Matrix room.
 * The phone number is normalized to E.164 format for consistent matching.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["phoneNumber"], unique = true),
        Index("matrixRoomId"),
        Index("lastMessageTimestamp")
    ]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Normalized phone number in E.164 format (e.g., +14165551234) */
    val phoneNumber: String,

    /** Subscription ID (SIM) primarily used for this conversation (-1 = default) */
    val subscriptionId: Int = -1,

    /** Matrix room ID if mapped (null if no Matrix room yet) */
    val matrixRoomId: String? = null,

    /** Contact display name (from contacts or phone number if not found) */
    val displayName: String? = null,

    /** Timestamp of last message in this conversation */
    val lastMessageTimestamp: Long = 0,

    /** Preview of the last message body */
    val lastMessagePreview: String? = null,

    /** Number of unread messages */
    val unreadCount: Int = 0,

    /** Whether the conversation is archived (hidden from main list) */
    val isArchived: Boolean = false,

    /** Whether the conversation is muted (no notifications) */
    val isMuted: Boolean = false,

    /** Whether to mute Matrix echo for this conversation */
    val muteMatrixEcho: Boolean = false,

    /** Sync direction for this specific conversation */
    val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,

    /** Whether there's a pending delivery error to surface */
    val hasDeliveryError: Boolean = false,

    /** Timestamp when conversation was created */
    val createdAt: Long = System.currentTimeMillis()
)
