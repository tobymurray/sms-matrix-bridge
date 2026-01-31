package com.technicallyrural.smsmatrixbridge

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log

/**
 * Manages the "effective default SMS app" - the user's actual SMS app that handles
 * all messaging UX while this bridge acts as a transparent shim.
 *
 * The bridge acquires the system Default SMS role ONLY to access SMS/MMS streams.
 * All actual messaging functionality is delegated to the effective default SMS app.
 *
 * This class handles:
 * - Detection of the user's previous SMS app before role acquisition
 * - Persistent storage of the effective app package name
 * - Validation that the effective app is still available
 * - Recovery when the effective app becomes unavailable
 */
object EffectiveSmsAppManager {
    private const val TAG = "EffectiveSmsApp"
    private const val PREFS_NAME = "effective_sms_app_prefs"
    private const val KEY_EFFECTIVE_PACKAGE = "effective_sms_app_package"
    private const val KEY_EFFECTIVE_APP_NAME = "effective_sms_app_name"
    private const val KEY_DETECTION_TIMESTAMP = "detection_timestamp"

    // Common SMS app package names for reference (not exhaustive)
    private val KNOWN_SMS_APPS = setOf(
        "com.google.android.apps.messaging",    // Google Messages
        "com.android.messaging",                 // AOSP/Emulator Messages
        "com.samsung.android.messaging",         // Samsung Messages
        "com.android.mms",                       // AOSP Messaging (legacy)
        "com.oneplus.mms",                       // OnePlus Messages
        "com.sonyericsson.conversations",        // Sony Messages
        "org.thoughtcrime.securesms",            // Signal
        "com.textra",                            // Textra
        "com.moez.QKSMS",                        // QKSMS
        "xyz.klinker.messenger",                 // Pulse SMS
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    // Callback for when user needs to select a new effective app
    var onEffectiveAppUnavailable: (() -> Unit)? = null

    /**
     * Initialize the manager with application context.
     * Must be called before any other methods.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "Initialized. Current effective app: ${getEffectivePackage()}")
    }

    /**
     * Detects and stores the current default SMS app BEFORE this app requests the SMS role.
     * This should be called once on first launch or when no effective app is configured.
     *
     * @return The package name of the detected SMS app, or null if none found
     */
    fun detectAndStoreCurrentSmsApp(): String? {
        var currentDefault = getCurrentSystemDefaultSmsApp()

        // If system doesn't report a default, try to find one from available SMS apps
        if (currentDefault == null) {
            Log.w(TAG, "No default SMS app reported by system, searching for available SMS apps")
            val availableApps = getAvailableSmsApps()
            if (availableApps.isNotEmpty()) {
                // Prefer known apps, otherwise take the first available
                currentDefault = availableApps.firstOrNull { KNOWN_SMS_APPS.contains(it.first) }?.first
                    ?: availableApps.first().first
                Log.i(TAG, "Found available SMS app: $currentDefault")
            }
        }

        if (currentDefault == null) {
            Log.w(TAG, "No SMS app detected on system")
            return null
        }

        // Don't store ourselves as the effective app
        if (currentDefault == appContext.packageName) {
            Log.d(TAG, "Current default is already this bridge app, not storing")
            return getEffectivePackage() // Return previously stored if any
        }

        val appName = getAppNameForPackage(currentDefault)
        storeEffectiveApp(currentDefault, appName)
        Log.i(TAG, "Detected and stored effective SMS app: $currentDefault ($appName)")

        return currentDefault
    }

    /**
     * Gets the current system default SMS app package name.
     */
    fun getCurrentSystemDefaultSmsApp(): String? {
        return try {
            Telephony.Sms.getDefaultSmsPackage(appContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get default SMS package", e)
            null
        }
    }

    /**
     * Stores the effective SMS app package name.
     */
    private fun storeEffectiveApp(packageName: String, appName: String?) {
        prefs.edit()
            .putString(KEY_EFFECTIVE_PACKAGE, packageName)
            .putString(KEY_EFFECTIVE_APP_NAME, appName ?: packageName)
            .putLong(KEY_DETECTION_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /**
     * Gets the stored effective SMS app package name.
     *
     * @return Package name or null if not configured
     */
    fun getEffectivePackage(): String? {
        return prefs.getString(KEY_EFFECTIVE_PACKAGE, null)
    }

    /**
     * Gets the human-readable name of the effective SMS app.
     */
    fun getEffectiveAppName(): String? {
        return prefs.getString(KEY_EFFECTIVE_APP_NAME, null)
    }

    /**
     * Checks if an effective SMS app is configured.
     */
    fun hasEffectiveApp(): Boolean {
        return getEffectivePackage() != null
    }

    /**
     * Validates that the effective SMS app is still installed and enabled.
     *
     * @return true if the app is available, false otherwise
     */
    fun isEffectiveAppAvailable(): Boolean {
        val packageName = getEffectivePackage() ?: return false
        return isPackageAvailable(packageName)
    }

    /**
     * Checks if a package is installed and enabled.
     */
    private fun isPackageAvailable(packageName: String): Boolean {
        return try {
            val pm = appContext.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            info.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Package $packageName not found")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking package availability: $packageName", e)
            false
        }
    }

    /**
     * Gets the human-readable app name for a package.
     */
    private fun getAppNameForPackage(packageName: String): String? {
        return try {
            val pm = appContext.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app name for $packageName", e)
            null
        }
    }

    /**
     * Gets a list of available SMS-capable apps on the device.
     * Used when the user needs to select a new effective app.
     *
     * @return List of pairs (packageName, appName)
     */
    fun getAvailableSmsApps(): List<Pair<String, String>> {
        val pm = appContext.packageManager
        val smsApps = mutableListOf<Pair<String, String>>()

        // Query for apps that can handle SMS intents
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("smsto:")
        }

        // Use MATCH_ALL to include all matching apps
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
        } else {
            null
        }

        val resolveInfos = if (flags != null) {
            pm.queryIntentActivities(intent, flags)
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

        Log.d(TAG, "queryIntentActivities returned ${resolveInfos.size} results")

        for (info in resolveInfos) {
            val packageName = info.activityInfo.packageName
            Log.d(TAG, "Found SMS app: $packageName")

            // Don't include ourselves
            if (packageName == appContext.packageName) continue

            val appName = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                packageName
            }

            if (!smsApps.any { it.first == packageName }) {
                smsApps.add(packageName to appName)
            }
        }

        // Also check known SMS apps directly in case query missed them
        for (knownPackage in KNOWN_SMS_APPS) {
            if (smsApps.any { it.first == knownPackage }) continue
            if (knownPackage == appContext.packageName) continue

            if (isPackageAvailable(knownPackage)) {
                val appName = getAppNameForPackage(knownPackage) ?: knownPackage
                smsApps.add(knownPackage to appName)
                Log.d(TAG, "Added known SMS app: $knownPackage")
            }
        }

        Log.d(TAG, "Total SMS apps found: ${smsApps.size}")

        // Sort known apps first, then alphabetically
        return smsApps.sortedWith(compareBy(
            { if (KNOWN_SMS_APPS.contains(it.first)) 0 else 1 },
            { it.second }
        ))
    }

    /**
     * Manually sets the effective SMS app package.
     * Used when user selects a new effective app.
     */
    fun setEffectivePackage(packageName: String) {
        if (!isPackageAvailable(packageName)) {
            Log.e(TAG, "Attempted to set unavailable package as effective app: $packageName")
            return
        }

        val appName = getAppNameForPackage(packageName)
        storeEffectiveApp(packageName, appName)
        Log.i(TAG, "Manually set effective SMS app: $packageName ($appName)")
    }

    /**
     * Clears the stored effective SMS app.
     * Used when resetting configuration or during testing.
     */
    fun clearEffectiveApp() {
        prefs.edit()
            .remove(KEY_EFFECTIVE_PACKAGE)
            .remove(KEY_EFFECTIVE_APP_NAME)
            .remove(KEY_DETECTION_TIMESTAMP)
            .apply()
        Log.i(TAG, "Cleared effective SMS app")
    }

    /**
     * Validates the effective app is available, triggering recovery if not.
     * Should be called periodically (e.g., on bridge service start).
     *
     * @return true if validation passed, false if recovery is needed
     */
    fun validateOrRecover(): Boolean {
        if (!hasEffectiveApp()) {
            Log.w(TAG, "No effective app configured")
            onEffectiveAppUnavailable?.invoke()
            return false
        }

        if (!isEffectiveAppAvailable()) {
            val pkg = getEffectivePackage()
            Log.w(TAG, "Effective app no longer available: $pkg")
            onEffectiveAppUnavailable?.invoke()
            return false
        }

        return true
    }

    /**
     * Checks if we are currently the system default SMS app.
     */
    fun isThisAppSystemDefault(): Boolean {
        return getCurrentSystemDefaultSmsApp() == appContext.packageName
    }

    /**
     * Gets a launch intent for the effective SMS app.
     *
     * @return Launch intent or null if app unavailable
     */
    fun getEffectiveAppLaunchIntent(): Intent? {
        val packageName = getEffectivePackage() ?: return null
        return appContext.packageManager.getLaunchIntentForPackage(packageName)
    }

    /**
     * Debug info for logging.
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== EffectiveSmsAppManager Debug ===")
            appendLine("Effective package: ${getEffectivePackage()}")
            appendLine("Effective app name: ${getEffectiveAppName()}")
            appendLine("Is available: ${isEffectiveAppAvailable()}")
            appendLine("System default: ${getCurrentSystemDefaultSmsApp()}")
            appendLine("This app is default: ${isThisAppSystemDefault()}")
            appendLine("Detection timestamp: ${prefs.getLong(KEY_DETECTION_TIMESTAMP, 0)}")
        }
    }
}
