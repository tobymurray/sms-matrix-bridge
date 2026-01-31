package com.technicallyrural.smsmatrixbridge

import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Prevents message echo loops in the SMS ↔ Matrix bridge.
 *
 * The Problem:
 * 1. SMS arrives → Bridge sends to Matrix → Matrix event appears
 * 2. Matrix message sent → Bridge sends SMS → SMS appears in inbox
 *
 * Without loop prevention, the bridge could:
 * - See its own Matrix messages and try to send them as SMS again
 * - See the SMS it just sent and forward it back to Matrix
 *
 * Solution:
 * 1. Track messages we've sent recently (by content hash + destination)
 * 2. When we see a message, check if we recently sent it
 * 3. Expire tracked messages after a timeout to prevent memory leaks
 *
 * Additionally:
 * - Ignore Matrix messages from our own user ID
 * - Track Matrix event IDs we've processed
 */
object LoopPrevention {

    private const val TAG = "LoopPrevention"

    // How long to remember messages (5 minutes)
    private const val EXPIRY_MS = 5 * 60 * 1000L

    // Maximum entries before forced cleanup
    private const val MAX_ENTRIES = 1000

    /**
     * Recently sent SMS messages: hash(phone+content) → timestamp
     * Used to ignore incoming SMS that we just sent.
     */
    private val recentSentSms = ConcurrentHashMap<String, Long>()

    /**
     * Recently sent Matrix messages: hash(roomId+content) → timestamp
     * Used to identify messages we sent to Matrix.
     */
    private val recentSentMatrix = ConcurrentHashMap<String, Long>()

    /**
     * Processed Matrix event IDs: eventId → timestamp
     * Ensures we don't process the same event twice.
     */
    private val processedEvents = ConcurrentHashMap<String, Long>()

    /**
     * Record that we're about to send an SMS.
     * Call this BEFORE sending so we can ignore it when it appears in our inbox.
     */
    fun recordOutgoingSms(phoneNumber: String, messageBody: String) {
        cleanup()
        val key = hashMessage(phoneNumber, messageBody)
        recentSentSms[key] = System.currentTimeMillis()
        Log.d(TAG, "Recorded outgoing SMS to $phoneNumber")
    }

    /**
     * Check if an incoming SMS was recently sent by us.
     * Returns true if this appears to be our own message (should be ignored).
     */
    fun wasRecentlySentSms(phoneNumber: String, messageBody: String): Boolean {
        val key = hashMessage(phoneNumber, messageBody)
        val timestamp = recentSentSms[key]
        if (timestamp != null && !isExpired(timestamp)) {
            Log.d(TAG, "Ignoring SMS from $phoneNumber - appears to be our own")
            recentSentSms.remove(key)  // One-time use
            return true
        }
        return false
    }

    /**
     * Record that we're about to send a Matrix message.
     * Call this BEFORE sending.
     */
    fun recordOutgoingMatrix(roomId: String, messageBody: String) {
        cleanup()
        val key = hashMessage(roomId, messageBody)
        recentSentMatrix[key] = System.currentTimeMillis()
        Log.d(TAG, "Recorded outgoing Matrix message to $roomId")
    }

    /**
     * Check if a Matrix message was recently sent by us.
     * Returns true if this appears to be our own message.
     */
    fun wasRecentlySentMatrix(roomId: String, messageBody: String): Boolean {
        val key = hashMessage(roomId, messageBody)
        val timestamp = recentSentMatrix[key]
        if (timestamp != null && !isExpired(timestamp)) {
            Log.d(TAG, "Ignoring Matrix message - appears to be our own")
            recentSentMatrix.remove(key)
            return true
        }
        return false
    }

    /**
     * Check if a Matrix event has already been processed.
     */
    fun hasProcessedEvent(eventId: String): Boolean {
        return processedEvents.containsKey(eventId)
    }

    /**
     * Mark a Matrix event as processed.
     */
    fun markEventProcessed(eventId: String) {
        cleanup()
        processedEvents[eventId] = System.currentTimeMillis()
    }

    /**
     * Check if a Matrix message sender is our own user.
     * This is the primary defense against processing our own messages.
     */
    fun isOwnUser(sender: String): Boolean {
        return sender == Config.userId
    }

    /**
     * Create a hash key for a message.
     */
    private fun hashMessage(destination: String, content: String): String {
        // Simple concatenation with separator
        // For real security, use a proper hash, but this is sufficient for dedup
        return "$destination|||${content.take(200)}"
    }

    /**
     * Check if a timestamp is expired.
     */
    private fun isExpired(timestamp: Long): Boolean {
        return System.currentTimeMillis() - timestamp > EXPIRY_MS
    }

    /**
     * Clean up expired entries to prevent memory leaks.
     */
    private fun cleanup() {
        val now = System.currentTimeMillis()

        // Only clean up if we have many entries
        if (recentSentSms.size + recentSentMatrix.size + processedEvents.size < MAX_ENTRIES / 2) {
            return
        }

        var cleaned = 0

        recentSentSms.entries.removeIf { (_, timestamp) ->
            (now - timestamp > EXPIRY_MS).also { if (it) cleaned++ }
        }

        recentSentMatrix.entries.removeIf { (_, timestamp) ->
            (now - timestamp > EXPIRY_MS).also { if (it) cleaned++ }
        }

        processedEvents.entries.removeIf { (_, timestamp) ->
            (now - timestamp > EXPIRY_MS).also { if (it) cleaned++ }
        }

        if (cleaned > 0) {
            Log.d(TAG, "Cleaned up $cleaned expired entries")
        }
    }

    /**
     * Clear all tracked state (for debugging/reset).
     */
    fun clear() {
        recentSentSms.clear()
        recentSentMatrix.clear()
        processedEvents.clear()
    }
}
