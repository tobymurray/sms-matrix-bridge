package com.technicallyrural.smsmatrixbridge.sms

/**
 * Result of an SMS send operation.
 */
sealed class SmsSendResult {
    data class Success(
        val messageId: Long,
        val phoneNumber: String
    ) : SmsSendResult()

    data class Failure(
        val messageId: Long,
        val phoneNumber: String,
        val reason: String,
        val errorCode: Int? = null
    ) : SmsSendResult()
}

/**
 * Result of an SMS delivery confirmation.
 */
sealed class SmsDeliveryResult {
    data class Delivered(
        val messageId: Long,
        val phoneNumber: String,
        val timestamp: Long
    ) : SmsDeliveryResult()

    data class Failed(
        val messageId: Long,
        val phoneNumber: String,
        val reason: String
    ) : SmsDeliveryResult()
}
