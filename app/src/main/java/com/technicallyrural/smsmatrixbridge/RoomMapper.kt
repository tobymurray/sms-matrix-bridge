package com.technicallyrural.smsmatrixbridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages the bidirectional mapping between phone numbers and Matrix room IDs.
 *
 * Each phone number corresponds to exactly one Matrix room.
 * The mapping is persisted using SharedPreferences.
 *
 * Phone numbers are normalized to a consistent format before storage/lookup.
 */
object RoomMapper {

    private const val TAG = "RoomMapper"
    private const val PREFS_NAME = "sms_matrix_room_mappings"

    // Prefix keys to distinguish direction in SharedPreferences
    private const val PHONE_TO_ROOM_PREFIX = "phone_"
    private const val ROOM_TO_PHONE_PREFIX = "room_"

    private lateinit var prefs: SharedPreferences

    /**
     * Initialize the mapper with application context.
     * Must be called from Application.onCreate().
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Normalize a phone number to a consistent format for mapping.
     *
     * Attempts E.164 format (+1XXXXXXXXXX) but falls back to digits-only
     * if the number doesn't fit standard patterns.
     */
    fun normalizePhoneNumber(phoneNumber: String): String {
        // Remove all non-digit characters except leading +
        val cleaned = phoneNumber.trim()
        val hasPlus = cleaned.startsWith("+")
        val digitsOnly = cleaned.filter { it.isDigit() }

        if (digitsOnly.isEmpty()) {
            return phoneNumber.trim()
        }

        // If already has + prefix, keep it (likely E.164)
        if (hasPlus) {
            return "+$digitsOnly"
        }

        // US numbers: if 10 digits, assume US and add +1
        if (digitsOnly.length == 10) {
            return "+1$digitsOnly"
        }

        // If 11 digits starting with 1, assume US
        if (digitsOnly.length == 11 && digitsOnly.startsWith("1")) {
            return "+$digitsOnly"
        }

        // Otherwise, just prefix with + and hope for the best
        return "+$digitsOnly"
    }

    /**
     * Get the Matrix room ID for a phone number.
     * Returns null if no mapping exists.
     */
    fun getRoomForPhone(phoneNumber: String): String? {
        val normalized = normalizePhoneNumber(phoneNumber)
        val roomId = prefs.getString(PHONE_TO_ROOM_PREFIX + normalized, null)
        Log.d(TAG, "getRoomForPhone($phoneNumber) -> normalized=$normalized, roomId=$roomId")
        return roomId
    }

    /**
     * Get the phone number for a Matrix room ID.
     * Returns null if no mapping exists.
     */
    fun getPhoneForRoom(roomId: String): String? {
        val phone = prefs.getString(ROOM_TO_PHONE_PREFIX + roomId, null)
        Log.d(TAG, "getPhoneForRoom($roomId) -> phone=$phone")
        return phone
    }

    /**
     * Create a bidirectional mapping between a phone number and room ID.
     */
    fun setMapping(phoneNumber: String, roomId: String) {
        val normalized = normalizePhoneNumber(phoneNumber)
        Log.d(TAG, "setMapping($phoneNumber -> $normalized, $roomId)")

        prefs.edit()
            .putString(PHONE_TO_ROOM_PREFIX + normalized, roomId)
            .putString(ROOM_TO_PHONE_PREFIX + roomId, normalized)
            .apply()
    }

    /**
     * Remove mapping for a phone number (and its reverse mapping).
     */
    fun removeMapping(phoneNumber: String) {
        val normalized = normalizePhoneNumber(phoneNumber)
        val roomId = getRoomForPhone(normalized)

        prefs.edit().apply {
            remove(PHONE_TO_ROOM_PREFIX + normalized)
            if (roomId != null) {
                remove(ROOM_TO_PHONE_PREFIX + roomId)
            }
            apply()
        }
    }

    /**
     * Get the count of phone-to-room mappings.
     */
    fun getMappingCount(): Int {
        return prefs.all.count { (key, _) -> key.startsWith(PHONE_TO_ROOM_PREFIX) }
    }

    /**
     * Get all phone number to room ID mappings.
     * Useful for debugging.
     */
    fun getAllMappings(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(PHONE_TO_ROOM_PREFIX) && value is String) {
                val phone = key.removePrefix(PHONE_TO_ROOM_PREFIX)
                result[phone] = value
            }
        }
        return result
    }

    /**
     * Clear all mappings (for debugging/reset).
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
