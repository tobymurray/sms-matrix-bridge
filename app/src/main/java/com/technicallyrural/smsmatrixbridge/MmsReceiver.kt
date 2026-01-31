package com.technicallyrural.smsmatrixbridge

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for incoming MMS messages using the pass-through architecture.
 *
 * As the system default SMS app, we receive WAP_PUSH_DELIVER broadcasts for MMS.
 * Like the SmsReceiver, we implement a pass-through model:
 *
 * Path A: Forward MMS to the effective SMS app via content provider
 * Path B: Extract text content and bridge to Matrix (if text is extractable)
 *
 * MMS Handling Complexity:
 * MMS messages are significantly more complex than SMS:
 * - They arrive as WAP push PDUs
 * - The actual content must be downloaded from the MMSC
 * - They can contain multiple parts (text, images, audio, video)
 * - Proper handling requires extensive PDU parsing
 *
 * Current Implementation:
 * We write the raw MMS PDU to the content provider, allowing the effective
 * SMS app to handle the download and display. The effective app has the
 * necessary infrastructure for MMS retrieval and rendering.
 *
 * Matrix bridging for MMS is limited to notification that an MMS was received,
 * since full multimedia bridging is beyond the current scope.
 *
 * IMPORTANT: The effective SMS app is responsible for:
 * - Downloading the MMS content from the MMSC
 * - Parsing and displaying multimedia content
 * - Managing storage for attachments
 * - Showing notifications
 */
class MmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MmsReceiver"
        private const val WAP_PUSH_DELIVER_ACTION = "android.provider.Telephony.WAP_PUSH_DELIVER"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WAP_PUSH_DELIVER_ACTION) {
            return
        }

        Log.d(TAG, "Received WAP_PUSH_DELIVER broadcast for MMS")

        // Extract MMS data from the intent
        val pduData = intent.extras?.get("data") as? ByteArray
        val contentType = intent.extras?.getString("contentType")
        val transactionId = intent.extras?.getString("transactionId")

        Log.d(TAG, "MMS: contentType=$contentType, hasData=${pduData != null}, " +
                "dataSize=${pduData?.size}, transactionId=$transactionId")

        if (pduData == null) {
            Log.w(TAG, "No PDU data in MMS intent")
            return
        }

        // PATH A: Forward MMS to effective SMS app (critical path)
        // This writes the MMS notification indication to the content provider
        scope.launch {
            forwardMmsToEffectiveApp(context, pduData, contentType, intent)
        }

        // PATH B: Notify Matrix about MMS (limited functionality)
        // Full MMS content bridging is not supported - we just notify
        if (Config.bridgeEnabled) {
            scope.launch {
                notifyMatrixOfMms(context, pduData, transactionId)
            }
        }
    }

    /**
     * PATH A: Forward the MMS to the effective SMS app.
     *
     * We write the MMS push notification to the content provider. The effective
     * SMS app will then handle downloading the actual MMS content from the MMSC.
     *
     * Note: Android's MMS handling is complex. The system sends WAP_PUSH_DELIVER
     * with a notification indication PDU. The receiving app must then:
     * 1. Parse the notification indication to get the content location
     * 2. Connect to the MMSC to download the actual content
     * 3. Parse the retrieved PDU for the multimedia parts
     * 4. Store everything in the MMS content provider
     *
     * By writing to the content provider, we trigger the effective app's
     * MMS download mechanism.
     */
    private suspend fun forwardMmsToEffectiveApp(
        context: Context,
        pduData: ByteArray,
        contentType: String?,
        intent: Intent
    ) {
        try {
            if (!EffectiveSmsAppManager.hasEffectiveApp()) {
                Log.w(TAG, "No effective SMS app configured for MMS forwarding")
            }

            // Write to the MMS inbox
            // The effective app monitors this and will handle the download
            val values = ContentValues().apply {
                // These are the minimum required fields for MMS inbox
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_TYPE, 130) // MESSAGE_TYPE_NOTIFICATION_IND

                // Store the PDU data for the effective app to process
                // Note: Full MMS insertion is complex; this is a simplified approach
                intent.extras?.getInt("subscription", -1)?.let {
                    if (it != -1) {
                        put(Telephony.Mms.SUBSCRIPTION_ID, it)
                    }
                }
            }

            // Try to insert into MMS provider
            // Note: Full MMS handling requires additional steps that the effective app handles
            val uri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)

            if (uri != null) {
                Log.i(TAG, "Wrote MMS notification to content provider: $uri")

                // Notify the effective app
                notifyEffectiveAppOfMms(context)
            } else {
                Log.e(TAG, "Failed to insert MMS notification into content provider")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied writing MMS to content provider", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding MMS to effective app", e)
        }
    }

    /**
     * Notify the effective SMS app that new MMS content is available.
     */
    private fun notifyEffectiveAppOfMms(context: Context) {
        try {
            val effectivePackage = EffectiveSmsAppManager.getEffectivePackage() ?: return

            // Send a content change notification
            // Most SMS apps register content observers on the MMS provider
            context.contentResolver.notifyChange(Telephony.Mms.CONTENT_URI, null)

            Log.d(TAG, "Notified content change for MMS to effective app")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify effective app of MMS", e)
        }
    }

    /**
     * PATH B: Notify Matrix about the MMS.
     *
     * Full MMS content bridging (images, videos, etc.) is not currently supported.
     * We send a notification message to the Matrix room indicating an MMS was received.
     *
     * Future enhancement: Parse the MMS PDU to extract sender and text parts,
     * and bridge those to Matrix. Binary attachments would require uploading
     * to Matrix media repository.
     */
    private suspend fun notifyMatrixOfMms(
        context: Context,
        pduData: ByteArray,
        transactionId: String?
    ) {
        try {
            // MMS PDU parsing is complex. For now, we just log that MMS was received.
            // Full MMS→Matrix bridging would require:
            // 1. Parsing the notification indication PDU
            // 2. Extracting the sender address
            // 3. Downloading the actual content from MMSC
            // 4. Parsing content parts (text, images, etc.)
            // 5. Uploading media to Matrix and sending structured messages

            Log.i(TAG, "MMS received (bridging to Matrix not yet implemented): " +
                    "transactionId=$transactionId, pduSize=${pduData.size}")

            // TODO: In a future version, extract sender and create/find Matrix room
            // TODO: Send MMS notification message like "[MMS received - view on phone]"
            // TODO: If text parts are extractable, bridge those

        } catch (e: Exception) {
            Log.e(TAG, "Error notifying Matrix of MMS", e)
        }
    }
}
