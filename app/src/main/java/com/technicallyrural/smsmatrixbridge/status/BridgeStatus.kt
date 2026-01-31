package com.technicallyrural.smsmatrixbridge.status

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.technicallyrural.smsmatrixbridge.BridgeService
import com.technicallyrural.smsmatrixbridge.config.BridgeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Subsystem status indicator.
 */
enum class StatusLevel {
    OK,         // Working normally
    WARNING,    // Degraded but functional
    ERROR,      // Not working
    UNKNOWN     // Status cannot be determined
}

/**
 * Individual status item with level, title, and optional details.
 */
data class StatusItem(
    val level: StatusLevel,
    val title: String,
    val detail: String? = null,
    val action: StatusAction? = null
)

/**
 * Actionable remediation for a status issue.
 */
sealed class StatusAction {
    object OpenSmsRoleSettings : StatusAction()
    object OpenPermissionSettings : StatusAction()
    object OpenBatterySettings : StatusAction()
    object OpenNetworkSettings : StatusAction()
    object ConfigureMatrix : StatusAction()
    object StartBridge : StatusAction()
    object StopBridge : StatusAction()
    data class Custom(val label: String, val action: () -> Unit) : StatusAction()
}

/**
 * Overall bridge status snapshot.
 */
data class BridgeStatusSnapshot(
    val overallLevel: StatusLevel,
    val overallMessage: String,

    // SMS subsystem
    val smsRoleStatus: StatusItem,
    val smsSendCapability: StatusItem,
    val smsReceiveCapability: StatusItem,
    val lastSmsSent: StatusItem,
    val lastSmsReceived: StatusItem,

    // Matrix subsystem
    val matrixConfigured: StatusItem,
    val matrixConnected: StatusItem,
    val matrixSyncActive: StatusItem,
    val lastMatrixEventSent: StatusItem,
    val lastMatrixEventReceived: StatusItem,

    // Permissions
    val smsPermission: StatusItem,
    val phonePermission: StatusItem,
    val notificationPermission: StatusItem,

    // System
    val batteryOptimization: StatusItem,
    val networkStatus: StatusItem,
    val serviceRunning: StatusItem,

    // Timestamp
    val snapshotTime: Long = System.currentTimeMillis()
)

/**
 * Central status reporter that collects and exposes bridge health information.
 *
 * Principles:
 * - Surface truth, not optimism
 * - No ambiguity
 * - No silent failure
 * - Clear actionable remediation
 */
class BridgeStatusReporter private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: BridgeStatusReporter? = null

        fun getInstance(context: Context): BridgeStatusReporter {
            return instance ?: synchronized(this) {
                instance ?: BridgeStatusReporter(context.applicationContext).also { instance = it }
            }
        }

        private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        fun formatTimestamp(timestamp: Long?): String {
            if (timestamp == null || timestamp == 0L) return "Never"
            return dateFormat.format(Date(timestamp))
        }

        fun formatRelativeTime(timestamp: Long?): String {
            if (timestamp == null || timestamp == 0L) return "Never"
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            return when {
                diff < 60_000 -> "Just now"
                diff < 3600_000 -> "${diff / 60_000} min ago"
                diff < 86400_000 -> "${diff / 3600_000} hr ago"
                else -> "${diff / 86400_000} days ago"
            }
        }
    }

    private val config: BridgeConfig by lazy { BridgeConfig.getInstance(context) }

    private val _status = MutableStateFlow<BridgeStatusSnapshot?>(null)
    val status: StateFlow<BridgeStatusSnapshot?> = _status.asStateFlow()

    // Volatile state updated by other components
    @Volatile var matrixConnected: Boolean = false
    @Volatile var matrixSyncActive: Boolean = false
    @Volatile var lastMatrixError: String? = null

    /**
     * Refresh the status snapshot.
     */
    suspend fun refresh() {
        val snapshot = collectStatus()
        _status.value = snapshot
    }

    /**
     * Collect current status from all subsystems.
     */
    suspend fun collectStatus(): BridgeStatusSnapshot {
        val configSnapshot = config.getSnapshot()

        // SMS subsystem
        val smsRoleStatus = checkSmsRole()
        val smsSendCapability = checkSmsSendCapability()
        val smsReceiveCapability = checkSmsReceiveCapability()
        val lastSmsSent = StatusItem(
            level = if (configSnapshot.lastSmsSentTime != null) StatusLevel.OK else StatusLevel.UNKNOWN,
            title = "Last SMS sent",
            detail = formatRelativeTime(configSnapshot.lastSmsSentTime)
        )
        val lastSmsReceived = StatusItem(
            level = if (configSnapshot.lastSmsReceivedTime != null) StatusLevel.OK else StatusLevel.UNKNOWN,
            title = "Last SMS received",
            detail = formatRelativeTime(configSnapshot.lastSmsReceivedTime)
        )

        // Matrix subsystem
        val matrixConfigured = StatusItem(
            level = if (configSnapshot.hasAccessToken && configSnapshot.homeserverUrl.isNotBlank())
                StatusLevel.OK else StatusLevel.ERROR,
            title = "Matrix configured",
            detail = if (configSnapshot.homeserverUrl.isNotBlank())
                configSnapshot.homeserverUrl else "Not configured",
            action = if (!configSnapshot.hasAccessToken) StatusAction.ConfigureMatrix else null
        )
        val matrixConnectedStatus = StatusItem(
            level = when {
                !configSnapshot.hasAccessToken -> StatusLevel.ERROR
                matrixConnected -> StatusLevel.OK
                else -> StatusLevel.ERROR
            },
            title = "Matrix connection",
            detail = when {
                !configSnapshot.hasAccessToken -> "Not configured"
                matrixConnected -> "Connected"
                lastMatrixError != null -> lastMatrixError
                else -> "Disconnected"
            }
        )
        val matrixSyncActiveStatus = StatusItem(
            level = when {
                !configSnapshot.bridgeEnabled -> StatusLevel.WARNING
                !configSnapshot.matrixSyncEnabled -> StatusLevel.WARNING
                matrixSyncActive -> StatusLevel.OK
                else -> StatusLevel.ERROR
            },
            title = "Matrix sync",
            detail = when {
                !configSnapshot.bridgeEnabled -> "Bridge disabled"
                !configSnapshot.matrixSyncEnabled -> "Sync disabled"
                matrixSyncActive -> "Active"
                else -> "Inactive"
            }
        )
        val lastMatrixEventSent = StatusItem(
            level = if (configSnapshot.lastMatrixEventSentTime != null) StatusLevel.OK else StatusLevel.UNKNOWN,
            title = "Last Matrix event sent",
            detail = formatRelativeTime(configSnapshot.lastMatrixEventSentTime)
        )
        val lastMatrixEventReceived = StatusItem(
            level = if (configSnapshot.lastMatrixEventReceivedTime != null) StatusLevel.OK else StatusLevel.UNKNOWN,
            title = "Last Matrix event received",
            detail = formatRelativeTime(configSnapshot.lastMatrixEventReceivedTime)
        )

        // Permissions
        val smsPermission = checkPermission(
            Manifest.permission.RECEIVE_SMS,
            "SMS permission",
            "Required to receive messages"
        )
        val phonePermission = checkPermission(
            Manifest.permission.READ_PHONE_STATE,
            "Phone state",
            "Required for dual SIM support"
        )
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkPermission(
                Manifest.permission.POST_NOTIFICATIONS,
                "Notifications",
                "Required for status indicator"
            )
        } else {
            StatusItem(StatusLevel.OK, "Notifications", "Granted (not required on this Android version)")
        }

        // System
        val batteryOptimization = checkBatteryOptimization()
        val networkStatus = checkNetworkStatus()
        val serviceRunning = StatusItem(
            level = if (BridgeService.isRunning) StatusLevel.OK else StatusLevel.WARNING,
            title = "Bridge service",
            detail = if (BridgeService.isRunning) "Running" else "Stopped",
            action = if (!BridgeService.isRunning && configSnapshot.bridgeEnabled)
                StatusAction.StartBridge else null
        )

        // Calculate overall status
        val (overallLevel, overallMessage) = calculateOverallStatus(
            configSnapshot = configSnapshot,
            smsRoleStatus = smsRoleStatus,
            matrixConfigured = matrixConfigured,
            matrixConnectedStatus = matrixConnectedStatus,
            serviceRunning = serviceRunning,
            smsPermission = smsPermission
        )

        return BridgeStatusSnapshot(
            overallLevel = overallLevel,
            overallMessage = overallMessage,
            smsRoleStatus = smsRoleStatus,
            smsSendCapability = smsSendCapability,
            smsReceiveCapability = smsReceiveCapability,
            lastSmsSent = lastSmsSent,
            lastSmsReceived = lastSmsReceived,
            matrixConfigured = matrixConfigured,
            matrixConnected = matrixConnectedStatus,
            matrixSyncActive = matrixSyncActiveStatus,
            lastMatrixEventSent = lastMatrixEventSent,
            lastMatrixEventReceived = lastMatrixEventReceived,
            smsPermission = smsPermission,
            phonePermission = phonePermission,
            notificationPermission = notificationPermission,
            batteryOptimization = batteryOptimization,
            networkStatus = networkStatus,
            serviceRunning = serviceRunning
        )
    }

    private fun checkSmsRole(): StatusItem {
        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) ?: false
        } else {
            val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
            defaultPackage == context.packageName
        }

        return StatusItem(
            level = if (isDefault) StatusLevel.OK else StatusLevel.ERROR,
            title = "Default SMS app",
            detail = if (isDefault) "Yes" else "Not default - SMS will not be received",
            action = if (!isDefault) StatusAction.OpenSmsRoleSettings else null
        )
    }

    private fun checkSmsSendCapability(): StatusItem {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        return StatusItem(
            level = if (hasPermission) StatusLevel.OK else StatusLevel.ERROR,
            title = "SMS send capability",
            detail = if (hasPermission) "Ready" else "Permission denied",
            action = if (!hasPermission) StatusAction.OpenPermissionSettings else null
        )
    }

    private fun checkSmsReceiveCapability(): StatusItem {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) ?: false
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

        return when {
            !hasPermission -> StatusItem(
                level = StatusLevel.ERROR,
                title = "SMS receive capability",
                detail = "Permission denied",
                action = StatusAction.OpenPermissionSettings
            )
            !isDefault -> StatusItem(
                level = StatusLevel.ERROR,
                title = "SMS receive capability",
                detail = "Not default SMS app",
                action = StatusAction.OpenSmsRoleSettings
            )
            else -> StatusItem(
                level = StatusLevel.OK,
                title = "SMS receive capability",
                detail = "Ready"
            )
        }
    }

    private fun checkPermission(permission: String, title: String, deniedDetail: String): StatusItem {
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

        return StatusItem(
            level = if (granted) StatusLevel.OK else StatusLevel.ERROR,
            title = title,
            detail = if (granted) "Granted" else deniedDetail,
            action = if (!granted) StatusAction.OpenPermissionSettings else null
        )
    }

    private fun checkBatteryOptimization(): StatusItem {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

        return StatusItem(
            level = if (isIgnoring) StatusLevel.OK else StatusLevel.WARNING,
            title = "Battery optimization",
            detail = if (isIgnoring) "Unrestricted" else "May affect background sync",
            action = if (!isIgnoring) StatusAction.OpenBatterySettings else null
        )
    }

    private fun checkNetworkStatus(): StatusItem {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager?.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false

        return when {
            !hasInternet -> StatusItem(
                level = StatusLevel.ERROR,
                title = "Network",
                detail = "No internet connection",
                action = StatusAction.OpenNetworkSettings
            )
            isWifi -> StatusItem(
                level = StatusLevel.OK,
                title = "Network",
                detail = "WiFi connected"
            )
            isCellular -> StatusItem(
                level = StatusLevel.OK,
                title = "Network",
                detail = "Mobile data"
            )
            else -> StatusItem(
                level = StatusLevel.OK,
                title = "Network",
                detail = "Connected"
            )
        }
    }

    private fun calculateOverallStatus(
        configSnapshot: BridgeConfig.ConfigSnapshot,
        smsRoleStatus: StatusItem,
        matrixConfigured: StatusItem,
        matrixConnectedStatus: StatusItem,
        serviceRunning: StatusItem,
        smsPermission: StatusItem
    ): Pair<StatusLevel, String> {
        // Critical errors first
        if (smsRoleStatus.level == StatusLevel.ERROR) {
            return StatusLevel.ERROR to "Not default SMS app - cannot receive messages"
        }
        if (smsPermission.level == StatusLevel.ERROR) {
            return StatusLevel.ERROR to "SMS permission denied"
        }
        if (!configSnapshot.bridgeEnabled) {
            return StatusLevel.WARNING to "Bridge disabled"
        }
        if (matrixConfigured.level == StatusLevel.ERROR) {
            return StatusLevel.WARNING to "Matrix not configured - SMS only mode"
        }
        if (serviceRunning.level != StatusLevel.OK) {
            return StatusLevel.WARNING to "Service not running"
        }

        // Degraded modes
        if (matrixConnectedStatus.level == StatusLevel.ERROR) {
            return StatusLevel.WARNING to "Matrix disconnected - SMS working, sync paused"
        }
        if (!configSnapshot.smsSendingEnabled && !configSnapshot.smsReceivingEnabled) {
            return StatusLevel.WARNING to "SMS sending and receiving disabled"
        }
        if (!configSnapshot.smsSendingEnabled) {
            return StatusLevel.WARNING to "SMS sending disabled"
        }
        if (!configSnapshot.matrixSyncEnabled) {
            return StatusLevel.WARNING to "Matrix sync disabled - local SMS only"
        }

        // All good
        return StatusLevel.OK to "Bridge active - SMS ↔ Matrix sync running"
    }
}
