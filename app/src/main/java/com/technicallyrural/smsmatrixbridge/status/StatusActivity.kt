package com.technicallyrural.smsmatrixbridge.status

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.technicallyrural.smsmatrixbridge.BridgeService
import com.technicallyrural.smsmatrixbridge.MainActivity
import com.technicallyrural.smsmatrixbridge.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity displaying comprehensive bridge status and health information.
 *
 * Design principles:
 * - Surface truth, not optimism
 * - No ambiguity
 * - No silent failure
 * - Clear actionable remediation hints
 */
class StatusActivity : AppCompatActivity() {

    private val statusReporter by lazy { BridgeStatusReporter.getInstance(this) }

    private lateinit var overallStatusCard: MaterialCardView
    private lateinit var overallStatusIcon: ImageView
    private lateinit var overallStatusTitle: TextView
    private lateinit var overallStatusDetail: TextView

    private lateinit var smsSection: LinearLayout
    private lateinit var matrixSection: LinearLayout
    private lateinit var permissionsSection: LinearLayout
    private lateinit var systemSection: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        setupViews()
        observeStatus()

        // Initial refresh
        lifecycleScope.launch {
            statusReporter.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            statusReporter.refresh()
        }
    }

    private fun setupViews() {
        overallStatusCard = findViewById(R.id.overallStatusCard)
        overallStatusIcon = findViewById(R.id.overallStatusIcon)
        overallStatusTitle = findViewById(R.id.overallStatusTitle)
        overallStatusDetail = findViewById(R.id.overallStatusDetail)

        smsSection = findViewById(R.id.smsSection)
        matrixSection = findViewById(R.id.matrixSection)
        permissionsSection = findViewById(R.id.permissionsSection)
        systemSection = findViewById(R.id.systemSection)

        findViewById<MaterialButton>(R.id.refreshButton).setOnClickListener {
            lifecycleScope.launch {
                statusReporter.refresh()
            }
        }

        findViewById<MaterialButton>(R.id.backButton).setOnClickListener {
            finish()
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            statusReporter.status.collectLatest { snapshot ->
                snapshot?.let { updateUi(it) }
            }
        }
    }

    private fun updateUi(snapshot: BridgeStatusSnapshot) {
        // Overall status
        updateOverallStatus(snapshot)

        // SMS section
        smsSection.removeAllViews()
        addStatusItem(smsSection, snapshot.smsRoleStatus)
        addStatusItem(smsSection, snapshot.smsSendCapability)
        addStatusItem(smsSection, snapshot.smsReceiveCapability)
        addStatusItem(smsSection, snapshot.lastSmsSent)
        addStatusItem(smsSection, snapshot.lastSmsReceived)

        // Matrix section
        matrixSection.removeAllViews()
        addStatusItem(matrixSection, snapshot.matrixConfigured)
        addStatusItem(matrixSection, snapshot.matrixConnected)
        addStatusItem(matrixSection, snapshot.matrixSyncActive)
        addStatusItem(matrixSection, snapshot.lastMatrixEventSent)
        addStatusItem(matrixSection, snapshot.lastMatrixEventReceived)

        // Permissions section
        permissionsSection.removeAllViews()
        addStatusItem(permissionsSection, snapshot.smsPermission)
        addStatusItem(permissionsSection, snapshot.phonePermission)
        addStatusItem(permissionsSection, snapshot.notificationPermission)

        // System section
        systemSection.removeAllViews()
        addStatusItem(systemSection, snapshot.serviceRunning)
        addStatusItem(systemSection, snapshot.batteryOptimization)
        addStatusItem(systemSection, snapshot.networkStatus)
    }

    private fun updateOverallStatus(snapshot: BridgeStatusSnapshot) {
        val (bgColor, iconRes) = when (snapshot.overallLevel) {
            StatusLevel.OK -> Pair(
                ContextCompat.getColor(this, android.R.color.holo_green_dark),
                android.R.drawable.ic_dialog_info
            )
            StatusLevel.WARNING -> Pair(
                ContextCompat.getColor(this, android.R.color.holo_orange_dark),
                android.R.drawable.ic_dialog_alert
            )
            StatusLevel.ERROR -> Pair(
                ContextCompat.getColor(this, android.R.color.holo_red_dark),
                android.R.drawable.ic_dialog_alert
            )
            StatusLevel.UNKNOWN -> Pair(
                ContextCompat.getColor(this, android.R.color.darker_gray),
                android.R.drawable.ic_dialog_info
            )
        }

        overallStatusCard.setCardBackgroundColor(bgColor)
        overallStatusIcon.setImageResource(iconRes)
        overallStatusTitle.text = when (snapshot.overallLevel) {
            StatusLevel.OK -> "All Systems Operational"
            StatusLevel.WARNING -> "Degraded"
            StatusLevel.ERROR -> "Issues Detected"
            StatusLevel.UNKNOWN -> "Unknown"
        }
        overallStatusDetail.text = snapshot.overallMessage
    }

    private fun addStatusItem(container: LinearLayout, item: StatusItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_status, container, false)

        val icon = view.findViewById<ImageView>(R.id.statusIcon)
        val title = view.findViewById<TextView>(R.id.statusTitle)
        val detail = view.findViewById<TextView>(R.id.statusDetail)
        val actionButton = view.findViewById<MaterialButton>(R.id.actionButton)

        // Set icon based on level
        val iconRes = when (item.level) {
            StatusLevel.OK -> android.R.drawable.presence_online
            StatusLevel.WARNING -> android.R.drawable.presence_away
            StatusLevel.ERROR -> android.R.drawable.presence_busy
            StatusLevel.UNKNOWN -> android.R.drawable.presence_invisible
        }
        icon.setImageResource(iconRes)

        val iconTint = when (item.level) {
            StatusLevel.OK -> android.R.color.holo_green_dark
            StatusLevel.WARNING -> android.R.color.holo_orange_dark
            StatusLevel.ERROR -> android.R.color.holo_red_dark
            StatusLevel.UNKNOWN -> android.R.color.darker_gray
        }
        icon.setColorFilter(ContextCompat.getColor(this, iconTint))

        title.text = item.title
        detail.text = item.detail ?: ""
        detail.visibility = if (item.detail != null) View.VISIBLE else View.GONE

        // Setup action button
        if (item.action != null) {
            actionButton.visibility = View.VISIBLE
            actionButton.text = getActionLabel(item.action)
            actionButton.setOnClickListener {
                handleAction(item.action)
            }
        } else {
            actionButton.visibility = View.GONE
        }

        container.addView(view)
    }

    private fun getActionLabel(action: StatusAction): String {
        return when (action) {
            is StatusAction.OpenSmsRoleSettings -> "Set as default"
            is StatusAction.OpenPermissionSettings -> "Grant permission"
            is StatusAction.OpenBatterySettings -> "Disable optimization"
            is StatusAction.OpenNetworkSettings -> "Open settings"
            is StatusAction.ConfigureMatrix -> "Configure"
            is StatusAction.StartBridge -> "Start"
            is StatusAction.StopBridge -> "Stop"
            is StatusAction.Custom -> action.label
        }
    }

    private fun handleAction(action: StatusAction) {
        when (action) {
            is StatusAction.OpenSmsRoleSettings -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    startActivity(intent)
                } else {
                    val intent = Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                    intent.putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                    startActivity(intent)
                }
            }
            is StatusAction.OpenPermissionSettings -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            is StatusAction.OpenBatterySettings -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            is StatusAction.OpenNetworkSettings -> {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                startActivity(intent)
            }
            is StatusAction.ConfigureMatrix -> {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
            }
            is StatusAction.StartBridge -> {
                BridgeService.start(this)
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(500)
                    statusReporter.refresh()
                }
            }
            is StatusAction.StopBridge -> {
                BridgeService.stop(this)
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(500)
                    statusReporter.refresh()
                }
            }
            is StatusAction.Custom -> action.action()
        }
    }
}
