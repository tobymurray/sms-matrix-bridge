package com.technicallyrural.smsmatrixbridge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Message delivery/send status.
 */
enum class MessageStatus {
    PENDING,    // Queued for sending
    SENDING,    // Currently being sent
    SENT,       // Sent but not confirmed delivered
    DELIVERED,  // Confirmed delivered to recipient
    FAILED,     // Send failed (see failureReason)
    RECEIVED    // Inbound message received
}

/**
 * Direction of the message.
 */
enum class MessageDirection {
    INBOUND,    // Received from external party
    OUTBOUND    // Sent by us
}

/**
 * Origin of an outbound message.
 */
enum class MessageOrigin {
    LOCAL,      // User typed on this device
    MATRIX      // Received from Matrix and sent as SMS
}

/**
 * Represents a single SMS message in our authoritative store.
 *
 * This is the source of truth for all messages. The system SMS content provider
 * is treated as transport only - messages are inserted here first, then synced.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversationId"),
        Index("timestamp"),
        Index("status"),
        Index("matrixEventId")
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Reference to the conversation this message belongs to */
    val conversationId: Long,

    /** Message body text */
    val body: String,

    /** Timestamp when the message was created/received (epoch millis) */
    val timestamp: Long,

    /** Direction: inbound or outbound */
    val direction: MessageDirection,

    /** Current status of the message */
    val status: MessageStatus,

    /** For outbound messages, where did it originate? */
    val origin: MessageOrigin = MessageOrigin.LOCAL,

    /** Matrix event ID if synced to Matrix (null if not yet synced or sync disabled) */
    val matrixEventId: String? = null,

    /** For Matrix-originated messages, tracks the source event to prevent reflection */
    val matrixSourceEventId: String? = null,

    /** Timestamp when message was sent (may differ from creation timestamp) */
    val sentTimestamp: Long? = null,

    /** Timestamp when delivery was confirmed */
    val deliveredTimestamp: Long? = null,

    /** Human-readable failure reason if status == FAILED */
    val failureReason: String? = null,

    /** Number of send retry attempts */
    val retryCount: Int = 0,

    /** System SMS content provider ID (for reference only, not authoritative) */
    val systemSmsId: Long? = null,

    /** Subscription ID (SIM) used to send/receive this message */
    val subscriptionId: Int = -1
)
