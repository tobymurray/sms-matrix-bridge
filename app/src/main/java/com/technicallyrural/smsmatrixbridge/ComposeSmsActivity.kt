package com.technicallyrural.smsmatrixbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.technicallyrural.smsmatrixbridge.ui.ConversationActivity

/**
 * Activity to handle SMS/MMS compose intents.
 *
 * As the system default SMS app, we receive all intents from other apps that want to
 * send SMS messages (e.g., sharing a link, contact app's "Send SMS" button, etc.).
 *
 * This activity extracts the recipient and message body from the intent and opens
 * our ConversationActivity to handle it.
 */
class ComposeSmsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ComposeSmsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "ComposeSmsActivity launched with action: ${intent?.action}")

        // Handle the incoming intent
        handleIntent(intent)

        // Close immediately - ConversationActivity handles the rest
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    /**
     * Handle compose intent by opening ConversationActivity.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        try {
            var phoneNumber: String? = null
            var messageBody: String? = null

            // Extract phone number from URI (sms:+1234567890, smsto:+1234567890)
            val data: Uri? = intent.data
            if (data != null) {
                val scheme = data.scheme
                if (scheme == "sms" || scheme == "smsto" || scheme == "mms" || scheme == "mmsto") {
                    phoneNumber = data.schemeSpecificPart?.replace("-", "")?.replace(" ", "")
                }
            }

            // Extract message body from extras
            messageBody = intent.getStringExtra("sms_body")
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)

            Log.d(TAG, "Compose intent: phone=$phoneNumber, body=${messageBody?.take(50)}")

            // Open ConversationActivity
            val conversationIntent = Intent(this, ConversationActivity::class.java).apply {
                if (!phoneNumber.isNullOrBlank()) {
                    putExtra(ConversationActivity.EXTRA_PHONE_NUMBER, phoneNumber)
                }
                // Note: messageBody could be pre-filled in input field
                // (would require ConversationActivity to handle EXTRA_PREFILL_TEXT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(conversationIntent)
            Log.i(TAG, "Opened conversation for $phoneNumber")

        } catch (e: Exception) {
            Log.e(TAG, "Error handling compose intent", e)
        }
    }
}
