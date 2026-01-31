package com.technicallyrural.smsmatrixbridge

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal Matrix client for SMS bridge operations.
 *
 * Handles:
 * - Room creation (for new SMS contacts)
 * - Sending text messages
 * - Syncing events (long-polling)
 *
 * Uses OkHttp for HTTP operations and Gson for JSON parsing.
 * All operations are suspending functions for coroutine support.
 */
class MatrixClient {

    companion object {
        private const val TAG = "MatrixClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    // Client for normal requests (short timeout)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Client for sync requests (long timeout for long-polling)
    private val syncHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)  // Long poll can take up to 30s + buffer
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() = Config.homeserverUrl

    private val accessToken: String
        get() = Config.accessToken

    /**
     * Create a new Matrix room for an SMS contact.
     *
     * @param phoneNumber The phone number (used in room name)
     * @return The room ID if successful, null otherwise
     */
    suspend fun createRoom(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        try {
            val roomName = "SMS: $phoneNumber"
            val requestBody = JsonObject().apply {
                addProperty("name", roomName)
                addProperty("topic", "SMS conversation with $phoneNumber")
                addProperty("preset", "private_chat")
                add("creation_content", JsonObject().apply {
                    addProperty("m.federate", false)
                })
                // Set room as direct message
                addProperty("is_direct", true)
            }

            val request = Request.Builder()
                .url("$baseUrl/_matrix/client/v3/createRoom")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = JsonParser.parseString(responseBody).asJsonObject
                val roomId = json.get("room_id")?.asString
                Log.i(TAG, "Created room for $phoneNumber: $roomId")
                roomId
            } else {
                Log.e(TAG, "Failed to create room: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating room", e)
            null
        }
    }

    /**
     * Send a text message to a Matrix room.
     *
     * @param roomId The Matrix room ID
     * @param message The message text
     * @param metadata Optional metadata to include (shown as formatted body)
     * @return The event ID if successful, null otherwise
     */
    suspend fun sendMessage(
        roomId: String,
        message: String,
        metadata: MessageMetadata? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val txnId = UUID.randomUUID().toString()
            val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")

            val requestBody = JsonObject().apply {
                addProperty("msgtype", "m.text")
                addProperty("body", message)

                // If metadata provided, add formatted body with details
                if (metadata != null) {
                    val formattedBody = buildString {
                        append("<p>$message</p>")
                        append("<hr/>")
                        append("<small>")
                        append("<b>Direction:</b> ${metadata.direction}<br/>")
                        append("<b>Phone:</b> ${metadata.phoneNumber}<br/>")
                        append("<b>Time:</b> ${metadata.timestamp}")
                        append("</small>")
                    }
                    addProperty("format", "org.matrix.custom.html")
                    addProperty("formatted_body", formattedBody)
                }
            }

            val request = Request.Builder()
                .url("$baseUrl/_matrix/client/v3/rooms/$encodedRoomId/send/m.room.message/$txnId")
                .addHeader("Authorization", "Bearer $accessToken")
                .put(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = JsonParser.parseString(responseBody).asJsonObject
                val eventId = json.get("event_id")?.asString
                Log.d(TAG, "Sent message to $roomId: $eventId")
                eventId
            } else {
                Log.e(TAG, "Failed to send message: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            null
        }
    }

    /**
     * Perform a Matrix sync to get new events.
     *
     * Uses long-polling with a 30-second timeout.
     *
     * @param since The sync token from previous sync (null for initial sync)
     * @param timeout Timeout in milliseconds for long-polling
     * @return SyncResponse with new events and next_batch token
     */
    suspend fun sync(since: String?, timeout: Long = 30000): SyncResponse? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = StringBuilder("$baseUrl/_matrix/client/v3/sync?timeout=$timeout")

            // Only include room.timeline events to minimize data
            urlBuilder.append("&filter={\"room\":{\"timeline\":{\"limit\":50}}}")

            if (since != null) {
                urlBuilder.append("&since=$since")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = syncHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                parseSyncResponse(responseBody)
            } else {
                Log.e(TAG, "Sync failed: ${response.code} - $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during sync", e)
            null
        }
    }

    /**
     * Join a room by room ID (may be needed if invited).
     */
    suspend fun joinRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encodedRoomId = java.net.URLEncoder.encode(roomId, "UTF-8")
            val request = Request.Builder()
                .url("$baseUrl/_matrix/client/v3/rooms/$encodedRoomId/join")
                .addHeader("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            response.isSuccessful.also {
                if (it) Log.d(TAG, "Joined room $roomId")
                else Log.e(TAG, "Failed to join room: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
            false
        }
    }

    /**
     * Parse the sync response to extract relevant events.
     */
    private fun parseSyncResponse(responseBody: String): SyncResponse {
        val json = JsonParser.parseString(responseBody).asJsonObject
        val nextBatch = json.get("next_batch")?.asString ?: ""
        val events = mutableListOf<MatrixEvent>()

        // Parse room events from joined rooms
        val rooms = json.getAsJsonObject("rooms")
        val joinedRooms = rooms?.getAsJsonObject("join")

        joinedRooms?.entrySet()?.forEach { (roomId, roomData) ->
            val timeline = roomData.asJsonObject?.getAsJsonObject("timeline")
            val timelineEvents = timeline?.getAsJsonArray("events")

            timelineEvents?.forEach { eventElement ->
                val event = eventElement.asJsonObject
                val type = event.get("type")?.asString
                val sender = event.get("sender")?.asString
                val eventId = event.get("event_id")?.asString
                val content = event.getAsJsonObject("content")

                if (type == "m.room.message" && sender != null && eventId != null) {
                    val msgType = content?.get("msgtype")?.asString
                    val body = content?.get("body")?.asString

                    if (msgType == "m.text" && body != null) {
                        events.add(MatrixEvent(
                            eventId = eventId,
                            roomId = roomId,
                            sender = sender,
                            body = body,
                            timestamp = event.get("origin_server_ts")?.asLong ?: 0
                        ))
                    }
                }
            }
        }

        Log.d(TAG, "Sync complete: ${events.size} new message events, nextBatch=$nextBatch")
        return SyncResponse(nextBatch, events)
    }

    /**
     * Test the connection and credentials.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/_matrix/client/v3/account/whoami")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val success = response.isSuccessful
            if (success) {
                val body = response.body?.string()
                Log.i(TAG, "Connection test successful: $body")
            } else {
                Log.e(TAG, "Connection test failed: ${response.code}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test error", e)
            false
        }
    }
}

/**
 * Metadata for SMS messages sent to Matrix.
 */
data class MessageMetadata(
    val direction: String,      // "incoming" or "outgoing"
    val phoneNumber: String,
    val timestamp: String
)

/**
 * Response from Matrix sync operation.
 */
data class SyncResponse(
    val nextBatch: String,
    val events: List<MatrixEvent>
)

/**
 * A Matrix room message event.
 */
data class MatrixEvent(
    val eventId: String,
    val roomId: String,
    val sender: String,
    val body: String,
    val timestamp: Long
)
