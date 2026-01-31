package com.technicallyrural.smsmatrixbridge

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ContentObserver that monitors the SMS content provider for outgoing messages.
 *
 * In the pass-through architecture, the effective SMS app handles all message sending.
 * We need to detect when messages are sent so we can bridge them to Matrix.
 *
 * How it works:
 * 1. Register as an observer on Telephony.Sms.CONTENT_URI
 * 2. When changes occur, check for new sent messages
 * 3. For each new sent message, bridge it to Matrix
 *
 * This ensures that messages sent from the user's SMS app appear in their Matrix rooms,
 * maintaining the bidirectional bridge without requiring us to handle sending ourselves.
 *
 * Deduplication:
 * - We track processed message IDs to avoid bridging the same message twice
 * - We also use LoopPrevention to avoid bridging messages that came FROM Matrix
 */
class SmsContentObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "SmsContentObserver"
        private const val MAX_PROCESSED_IDS = 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track processed message IDs to avoid duplicates
    private val processedMessageIds = ConcurrentHashMap<Long, Long>() // id -> timestamp

    // Track the last processed message ID for efficient querying
    @Volatile
    private var lastProcessedId: Long = 0

    // Flag to enable/disable the observer
    @Volatile
    var isEnabled: Boolean = true

    /**
     * Called when the SMS content provider changes.
     *
     * Note: This is called for ANY change to the SMS provider, including:
     * - New incoming messages (which we handle via SmsReceiver)
     * - New outgoing messages (which we need to bridge)
     * - Message status updates
     * - Message deletions
     *
     * We focus only on new outgoing (sent) messages.
     */
    override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)

        if (!isEnabled || !Config.bridgeEnabled) {
            return
        }

        Log.d(TAG, "SMS content provider changed: selfChange=$selfChange, uri=$uri")

        // Query for new sent messages asynchronously
        scope.launch {
            checkForNewSentMessages()
        }
    }

    /**
     * Check for new messages in the sent folder and bridge them to Matrix.
     */
    private suspend fun checkForNewSentMessages() {
        try {
            val sentUri = Telephony.Sms.Sent.CONTENT_URI

            // Query for sent messages newer than our last processed ID
            val selection = "${Telephony.Sms._ID} > ?"
            val selectionArgs = arrayOf(lastProcessedId.toString())
            val sortOrder = "${Telephony.Sms._ID} ASC"

            val cursor: Cursor? = context.contentResolver.query(
                sentUri,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.DATE_SENT
                ),
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    val messageId = it.getLong(idIndex)
                    val address = it.getString(addressIndex) ?: continue
                    val body = it.getString(bodyIndex) ?: continue
                    val date = it.getLong(dateIndex)

                    // Skip if already processed
                    if (processedMessageIds.containsKey(messageId)) {
                        continue
                    }

                    // Mark as processed
                    processedMessageIds[messageId] = System.currentTimeMillis()
                    lastProcessedId = maxOf(lastProcessedId, messageId)

                    // Process this sent message
                    processSentMessage(address, body, date)
                }
            }

            // Cleanup old processed IDs
            cleanupProcessedIds()

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading SMS content provider", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for new sent messages", e)
        }
    }

    /**
     * Process a sent message by bridging it to Matrix.
     */
    private suspend fun processSentMessage(recipient: String, body: String, timestamp: Long) {
        val normalizedRecipient = RoomMapper.normalizePhoneNumber(recipient)

        Log.d(TAG, "Processing sent SMS to $normalizedRecipient: ${body.take(50)}...")

        // Check if this message originated from Matrix (loop prevention)
        // If we sent this SMS because of a Matrix message, don't bridge it back
        if (LoopPrevention.wasRecentlySentSms(normalizedRecipient, body)) {
            Log.d(TAG, "Ignoring sent SMS - originated from Matrix bridge")
            return
        }

        // Bridge to Matrix
        bridgeSentMessageToMatrix(normalizedRecipient, body, timestamp)
    }

    /**
     * Bridge a sent SMS message to Matrix.
     */
    private suspend fun bridgeSentMessageToMatrix(phoneNumber: String, body: String, timestamp: Long) {
        try {
            val matrixClient = MatrixClient()

            // Get or create room for this contact
            var roomId = RoomMapper.getRoomForPhone(phoneNumber)

            if (roomId == null) {
                Log.i(TAG, "No existing room for $phoneNumber, creating new one for sent message")
                roomId = matrixClient.createRoom(phoneNumber)

                if (roomId == null) {
                    Log.e(TAG, "Failed to create room for $phoneNumber")
                    return
                }

                RoomMapper.setMapping(phoneNumber, roomId)
                Log.i(TAG, "Created and mapped room $roomId for $phoneNumber")
            }

            // Prepare metadata for outgoing message
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val formattedTime = dateFormat.format(Date(timestamp))

            val metadata = MessageMetadata(
                direction = "outgoing",
                phoneNumber = phoneNumber,
                timestamp = formattedTime
            )

            // Record for loop prevention
            LoopPrevention.recordOutgoingMatrix(roomId, body)

            // Send to Matrix
            val eventId = matrixClient.sendMessage(roomId, body, metadata)

            if (eventId != null) {
                Log.i(TAG, "Bridged sent SMS to $phoneNumber to Matrix: $eventId")
            } else {
                Log.e(TAG, "Failed to bridge sent SMS to $phoneNumber to Matrix")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error bridging sent SMS to Matrix", e)
        }
    }

    /**
     * Cleanup old processed message IDs to prevent memory growth.
     */
    private fun cleanupProcessedIds() {
        if (processedMessageIds.size > MAX_PROCESSED_IDS) {
            // Remove oldest entries
            val cutoffTime = System.currentTimeMillis() - (5 * 60 * 1000) // 5 minutes ago
            val iterator = processedMessageIds.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value < cutoffTime) {
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Initialize the observer by finding the current highest message ID.
     * This prevents bridging old messages when the observer first starts.
     */
    fun initialize() {
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.Sent.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                null,
                null,
                "${Telephony.Sms._ID} DESC LIMIT 1"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                    lastProcessedId = it.getLong(idIndex)
                    Log.d(TAG, "Initialized with last message ID: $lastProcessedId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SMS content observer", e)
        }
    }

    /**
     * Register this observer with the content resolver.
     */
    fun register() {
        try {
            context.contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true, // notifyForDescendants
                this
            )
            Log.i(TAG, "Registered SMS content observer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SMS content observer", e)
        }
    }

    /**
     * Unregister this observer from the content resolver.
     */
    fun unregister() {
        try {
            context.contentResolver.unregisterContentObserver(this)
            Log.i(TAG, "Unregistered SMS content observer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister SMS content observer", e)
        }
    }
}
