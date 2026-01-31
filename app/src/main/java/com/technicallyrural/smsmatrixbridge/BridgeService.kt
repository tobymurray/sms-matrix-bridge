package com.technicallyrural.smsmatrixbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.technicallyrural.smsmatrixbridge.config.BridgeConfig
import com.technicallyrural.smsmatrixbridge.data.MessageRepository
import com.technicallyrural.smsmatrixbridge.data.SyncDirection
import com.technicallyrural.smsmatrixbridge.sms.SmsSendManager
import com.technicallyrural.smsmatrixbridge.status.BridgeStatusReporter
import com.technicallyrural.smsmatrixbridge.ui.ConversationListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that coordinates the SMS ↔ Matrix bridge.
 *
 * This is a minimal but correct SMS client with Matrix sync.
 *
 * Responsibilities:
 * 1. Run Matrix sync loop to receive messages from Matrix
 * 2. Convert Matrix messages to SMS via SmsSendManager
 * 3. Process pending outbound messages
 * 4. Update status reporter for observability
 *
 * Key architectural notes:
 * - The internal message store is the source of truth
 * - SMS works even if Matrix is down
 * - Matrix failures do not affect SMS functionality
 */
class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_matrix_bridge"

        // Sync loop timing
        private const val SYNC_TIMEOUT_MS = 30000L      // Matrix long-poll timeout
        private const val SYNC_RETRY_DELAY_MS = 5000L   // Delay before retry on error
        private const val SYNC_ERROR_MAX_DELAY_MS = 60000L  // Max backoff delay

        /** Whether the service is currently running */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Start the bridge service.
         */
        fun start(context: Context) {
            val intent = Intent(context, BridgeService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * Stop the bridge service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, BridgeService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private val matrixClient = MatrixClient()

    private lateinit var config: BridgeConfig
    private lateinit var repository: MessageRepository
    private lateinit var sendManager: SmsSendManager
    private lateinit var statusReporter: BridgeStatusReporter

    // Track consecutive errors for backoff
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BridgeService created")
        isRunning = true

        // Initialize components
        config = BridgeConfig.getInstance(this)
        repository = MessageRepository.getInstance(this)
        sendManager = SmsSendManager.getInstance(this)
        statusReporter = BridgeStatusReporter.getInstance(this)

        createNotificationChannel()
        sendManager.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BridgeService starting")

        // Start as foreground service immediately
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Start operations
        serviceScope.launch {
            // Check configuration
            if (!config.isConfigured()) {
                Log.e(TAG, "Matrix not configured, running in SMS-only mode")
                updateNotification("SMS-only mode - Matrix not configured")
            } else {
                // Start the Matrix sync loop
                startSyncLoop()
            }

            // Process any pending outbound messages
            sendManager.processPendingMessages()
        }

        // Return sticky so service restarts if killed
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "BridgeService destroyed")
        isRunning = false
        syncJob?.cancel()
        sendManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start the Matrix sync loop.
     */
    private fun startSyncLoop() {
        syncJob?.cancel()

        syncJob = serviceScope.launch {
            Log.i(TAG, "Sync loop starting")
            updateNotification("Connecting to Matrix...")

            // Update status reporter
            statusReporter.matrixConnected = false
            statusReporter.matrixSyncActive = false

            // Test connection first
            if (!matrixClient.testConnection()) {
                Log.e(TAG, "Connection test failed")
                statusReporter.lastMatrixError = "Connection failed"
                updateNotification("Matrix connection failed")
                delay(SYNC_RETRY_DELAY_MS)
            } else {
                statusReporter.matrixConnected = true
                statusReporter.lastMatrixError = null
            }

            // Get initial sync token from config (or null for initial sync)
            var since = config.syncSinceToken.first()

            while (isActive) {
                // Check if Matrix sync is enabled
                if (!config.matrixSyncEnabled.first()) {
                    Log.d(TAG, "Matrix sync disabled, pausing")
                    statusReporter.matrixSyncActive = false
                    updateNotification("SMS active, Matrix sync paused")
                    delay(5000)
                    continue
                }

                try {
                    statusReporter.matrixSyncActive = true
                    val syncResponse = matrixClient.sync(since, SYNC_TIMEOUT_MS)

                    if (syncResponse != null) {
                        // Success - reset error counter
                        consecutiveErrors = 0
                        statusReporter.matrixConnected = true
                        statusReporter.lastMatrixError = null

                        updateNotification("Running")

                        // Save sync token for next iteration and persistence
                        since = syncResponse.nextBatch
                        config.setSyncSinceToken(since)
                        config.recordMatrixSync()

                        // Process any new messages
                        processMatrixEvents(syncResponse.events)
                    } else {
                        handleSyncError("Sync returned null")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Sync loop error", e)
                    handleSyncError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Handle sync error with exponential backoff.
     */
    private suspend fun handleSyncError(error: String) {
        consecutiveErrors++
        statusReporter.matrixConnected = false
        statusReporter.lastMatrixError = error

        val delay = minOf(
            SYNC_RETRY_DELAY_MS * consecutiveErrors,
            SYNC_ERROR_MAX_DELAY_MS
        )
        Log.w(TAG, "Sync error #$consecutiveErrors: $error, retrying in ${delay}ms")
        updateNotification("Matrix connection error - retrying...")
        config.recordError("Matrix sync: $error")
        delay(delay)
    }

    /**
     * Process Matrix events from sync.
     */
    private fun processMatrixEvents(events: List<MatrixEvent>) {
        for (event in events) {
            serviceScope.launch {
                processMatrixEvent(event)
            }
        }
    }

    /**
     * Process a single Matrix event.
     *
     * Echo suppression is handled via:
     * 1. Ignore messages from our own user ID
     * 2. Check if we already have a message with this source event ID
     * 3. Content-based tracking via LoopPrevention
     */
    private suspend fun processMatrixEvent(event: MatrixEvent) {
        Log.d(TAG, "Processing event ${event.eventId} from ${event.sender} in ${event.roomId}")

        // Skip if already processed (event ID tracking)
        if (LoopPrevention.hasProcessedEvent(event.eventId)) {
            Log.d(TAG, "Event already processed: ${event.eventId}")
            return
        }

        // Mark as processed immediately to prevent double-processing
        LoopPrevention.markEventProcessed(event.eventId)

        // Skip our own messages (primary loop prevention)
        val userId = config.userId.first()
        if (event.sender == userId) {
            Log.d(TAG, "Ignoring own message from ${event.sender}")
            return
        }

        // Check if we recently sent this exact message to Matrix (secondary loop prevention)
        if (LoopPrevention.wasRecentlySentMatrix(event.roomId, event.body)) {
            Log.d(TAG, "Ignoring echoed Matrix message")
            return
        }

        // Check if we already have a message from this Matrix event (database check)
        if (repository.hasMessageFromMatrixEvent(event.eventId)) {
            Log.d(TAG, "Already sent SMS for Matrix event ${event.eventId}")
            return
        }

        // Get phone number for this room
        val conversation = repository.getConversationByMatrixRoomId(event.roomId)
        if (conversation == null) {
            Log.d(TAG, "No phone mapping for room ${event.roomId}, ignoring")
            return
        }

        // Check conversation-level sync direction
        val direction = conversation.syncDirection
        if (direction == SyncDirection.NONE || direction == SyncDirection.SMS_TO_MATRIX) {
            Log.d(TAG, "Matrix→SMS sync disabled for this conversation")
            return
        }

        // Check if SMS sending is enabled
        if (!config.smsSendingEnabled.first()) {
            Log.d(TAG, "SMS sending disabled globally")
            return
        }

        // Send SMS using the unified send pipeline
        Log.i(TAG, "Bridging Matrix message to SMS: ${event.roomId} -> ${conversation.phoneNumber}")

        val message = sendManager.sendFromMatrix(
            phoneNumber = conversation.phoneNumber,
            body = event.body,
            matrixEventId = event.eventId,
            subscriptionId = conversation.subscriptionId
        )

        if (message != null) {
            Log.i(TAG, "Queued SMS to ${conversation.phoneNumber} from Matrix event")
            config.recordMatrixEventReceived()

            // Send confirmation back to Matrix (optional)
            sendDeliveryConfirmation(event.roomId, conversation.phoneNumber)
        } else {
            Log.e(TAG, "Failed to queue SMS to ${conversation.phoneNumber}")
            sendDeliveryFailure(event.roomId, conversation.phoneNumber)
        }
    }

    /**
     * Send a delivery confirmation to the Matrix room.
     */
    private suspend fun sendDeliveryConfirmation(roomId: String, phoneNumber: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val confirmationMsg = "✓ SMS sent to $phoneNumber at $timestamp"

            // Record to prevent loop
            LoopPrevention.recordOutgoingMatrix(roomId, confirmationMsg)

            matrixClient.sendMessage(roomId, confirmationMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery confirmation", e)
        }
    }

    /**
     * Send a delivery failure notice to the Matrix room.
     */
    private suspend fun sendDeliveryFailure(roomId: String, phoneNumber: String) {
        try {
            val errorMsg = "✗ Failed to send SMS to $phoneNumber"

            // Record to prevent loop
            LoopPrevention.recordOutgoingMatrix(roomId, errorMsg)

            matrixClient.sendMessage(roomId, errorMsg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send failure notice", e)
        }
    }

    /**
     * Create the notification channel (required for Android O+).
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Matrix Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the SMS-Matrix bridge is running"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Create the foreground service notification.
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ConversationListActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS ↔ Matrix Bridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Update the notification text.
     */
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
}
