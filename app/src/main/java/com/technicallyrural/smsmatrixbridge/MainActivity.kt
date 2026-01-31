package com.technicallyrural.smsmatrixbridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main activity for SMS Matrix Bridge configuration.
 *
 * In the pass-through architecture, this activity handles:
 * 1. Matrix configuration (homeserver, user ID, access token)
 * 2. Default SMS app role request (with effective app detection)
 * 3. Permission requests
 * 4. Bridge start/stop controls
 * 5. Effective SMS app selection and recovery
 *
 * Key architectural note:
 * Before requesting the SMS role, we detect and store the user's current
 * default SMS app as the "effective default SMS app". This app continues
 * to handle all messaging UX while we act as a transparent bridge.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_SHOW_RECOVERY = "show_effective_app_recovery"
    }

    private lateinit var statusText: TextView
    private lateinit var homeserverInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var saveConfigButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var requestSmsRoleButton: Button
    private lateinit var toggleBridgeButton: Button
    private lateinit var openSmsAppButton: Button
    private lateinit var selectSmsAppButton: Button

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
            updateStatus()
        } else {
            Log.w(TAG, "Some permissions denied: $permissions")
            Toast.makeText(this, "SMS permissions required", Toast.LENGTH_LONG).show()
        }
    }

    // SMS role request launcher
    private val smsRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "Now default SMS app")
            Toast.makeText(this, "Now default SMS app", Toast.LENGTH_SHORT).show()
            // Verify effective app was stored
            val effectiveApp = EffectiveSmsAppManager.getEffectiveAppName()
            if (effectiveApp != null) {
                Toast.makeText(this, "Messages will appear in $effectiveApp", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "SMS role request denied")
            Toast.makeText(this, "Must be default SMS app to receive messages", Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find views
        statusText = findViewById(R.id.statusText)
        homeserverInput = findViewById(R.id.homeserverInput)
        userIdInput = findViewById(R.id.userIdInput)
        tokenInput = findViewById(R.id.tokenInput)
        saveConfigButton = findViewById(R.id.saveConfigButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        requestSmsRoleButton = findViewById(R.id.requestSmsRoleButton)
        toggleBridgeButton = findViewById(R.id.toggleBridgeButton)

        // Find or create optional buttons for effective app management
        openSmsAppButton = findViewById(R.id.openSmsAppButton) ?: Button(this).apply {
            id = R.id.openSmsAppButton
            text = "Open SMS App"
            visibility = View.GONE
        }
        selectSmsAppButton = findViewById(R.id.selectSmsAppButton) ?: Button(this).apply {
            id = R.id.selectSmsAppButton
            text = "Select SMS App"
            visibility = View.GONE
        }

        // Load current config
        loadConfig()

        // Set up buttons
        saveConfigButton.setOnClickListener { saveConfig() }
        testConnectionButton.setOnClickListener { testConnection() }
        requestSmsRoleButton.setOnClickListener { requestSmsRole() }
        toggleBridgeButton.setOnClickListener { toggleBridge() }
        openSmsAppButton.setOnClickListener { openEffectiveSmsApp() }
        selectSmsAppButton.setOnClickListener { showSelectSmsAppDialog() }

        // Request permissions on first launch
        requestPermissionsIfNeeded()

        // Check if we were launched for recovery
        if (intent.getBooleanExtra(EXTRA_SHOW_RECOVERY, false)) {
            showEffectiveAppRecoveryDialog()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_RECOVERY, false)) {
            showEffectiveAppRecoveryDialog()
        }
    }

    /**
     * Load configuration into input fields.
     */
    private fun loadConfig() {
        homeserverInput.setText(Config.homeserverUrl)
        userIdInput.setText(Config.userId)
        tokenInput.setText(Config.accessToken)
    }

    /**
     * Save configuration from input fields.
     */
    private fun saveConfig() {
        val homeserver = homeserverInput.text.toString().trim()
        val userId = userIdInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()

        if (homeserver.isEmpty() || userId.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "All fields required", Toast.LENGTH_SHORT).show()
            return
        }

        Config.homeserverUrl = homeserver
        Config.userId = userId
        Config.accessToken = token

        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    /**
     * Test the Matrix connection.
     */
    private fun testConnection() {
        if (!Config.isConfigured()) {
            Toast.makeText(this, "Save configuration first", Toast.LENGTH_SHORT).show()
            return
        }

        testConnectionButton.isEnabled = false
        statusText.text = "Testing connection..."

        CoroutineScope(Dispatchers.Main).launch {
            val success = withContext(Dispatchers.IO) {
                MatrixClient().testConnection()
            }

            testConnectionButton.isEnabled = true

            if (success) {
                Toast.makeText(this@MainActivity, "Connection successful!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Connection failed - check config", Toast.LENGTH_LONG).show()
            }

            updateStatus()
        }
    }

    /**
     * Request to become the default SMS app.
     *
     * IMPORTANT: Before requesting the SMS role, we detect and store the current
     * default SMS app. This becomes the "effective default SMS app" that will
     * handle all messaging UX.
     */
    private fun requestSmsRole() {
        // First, detect and store the current default SMS app
        val currentDefault = EffectiveSmsAppManager.detectAndStoreCurrentSmsApp()

        if (currentDefault == null) {
            // No SMS app detected - this is unusual
            Log.w(TAG, "No current default SMS app detected")
            Toast.makeText(this, "No SMS app detected on device", Toast.LENGTH_LONG).show()
        } else if (currentDefault == packageName) {
            // We're already the default - shouldn't happen in normal flow
            Toast.makeText(this, "Already default SMS app", Toast.LENGTH_SHORT).show()
            updateStatus()
            return
        } else {
            val appName = EffectiveSmsAppManager.getEffectiveAppName()
            Log.i(TAG, "Detected current SMS app: $appName ($currentDefault)")
        }

        // Now request the SMS role
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    smsRoleLauncher.launch(intent)
                } else {
                    Toast.makeText(this, "Already default SMS app", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Fallback for older versions (shouldn't happen with minSdk 29)
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivity(intent)
        }
    }

    /**
     * Toggle the bridge service on/off.
     */
    private fun toggleBridge() {
        if (!Config.isConfigured()) {
            Toast.makeText(this, "Configure Matrix settings first", Toast.LENGTH_LONG).show()
            return
        }

        if (!isDefaultSmsApp()) {
            Toast.makeText(this, "Must be default SMS app first", Toast.LENGTH_LONG).show()
            return
        }

        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Grant SMS permissions first", Toast.LENGTH_LONG).show()
            requestPermissionsIfNeeded()
            return
        }

        // Check effective app status (warn but don't block)
        if (!EffectiveSmsAppManager.hasEffectiveApp()) {
            showSelectSmsAppDialog()
            return
        }

        if (!EffectiveSmsAppManager.isEffectiveAppAvailable()) {
            showEffectiveAppRecoveryDialog()
            return
        }

        if (Config.bridgeEnabled) {
            // Stop bridge
            Config.bridgeEnabled = false
            BridgeService.stop(this)
            Toast.makeText(this, "Bridge stopped", Toast.LENGTH_SHORT).show()
        } else {
            // Start bridge
            Config.bridgeEnabled = true
            BridgeService.start(this)
            val effectiveApp = EffectiveSmsAppManager.getEffectiveAppName()
            Toast.makeText(this, "Bridge started - using $effectiveApp for SMS", Toast.LENGTH_LONG).show()
        }

        updateStatus()
    }

    /**
     * Open the effective SMS app.
     */
    private fun openEffectiveSmsApp() {
        val intent = EffectiveSmsAppManager.getEffectiveAppLaunchIntent()
        if (intent != null) {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open SMS app", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No SMS app configured", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show dialog to select a new effective SMS app.
     */
    private fun showSelectSmsAppDialog() {
        val availableApps = EffectiveSmsAppManager.getAvailableSmsApps()

        if (availableApps.isEmpty()) {
            Toast.makeText(this, "No SMS apps found on device", Toast.LENGTH_LONG).show()
            return
        }

        val appNames = availableApps.map { it.second }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select SMS App")
            .setItems(appNames) { _, which ->
                val selectedPackage = availableApps[which].first
                val selectedName = availableApps[which].second
                EffectiveSmsAppManager.setEffectivePackage(selectedPackage)
                Toast.makeText(this, "SMS app set to $selectedName", Toast.LENGTH_SHORT).show()
                updateStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show dialog when the effective SMS app becomes unavailable.
     */
    private fun showEffectiveAppRecoveryDialog() {
        val oldApp = EffectiveSmsAppManager.getEffectiveAppName() ?: "your SMS app"

        AlertDialog.Builder(this)
            .setTitle("SMS App Unavailable")
            .setMessage("$oldApp is no longer available. Please select a different SMS app to continue receiving messages.")
            .setPositiveButton("Select App") { _, _ ->
                showSelectSmsAppDialog()
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    /**
     * Update the status display.
     */
    private fun updateStatus() {
        val sb = StringBuilder()

        // Configuration status
        sb.appendLine("Configuration: ${if (Config.isConfigured()) "✓ Set" else "✗ Not set"}")

        // Default SMS app status
        sb.appendLine("Default SMS App: ${if (isDefaultSmsApp()) "✓ Yes" else "✗ No"}")

        // Effective SMS app status (pass-through model)
        if (isDefaultSmsApp()) {
            val effectiveApp = EffectiveSmsAppManager.getEffectiveAppName()
            val isAvailable = EffectiveSmsAppManager.isEffectiveAppAvailable()
            if (effectiveApp != null) {
                val status = if (isAvailable) "✓" else "✗"
                sb.appendLine("Messages App: $status $effectiveApp")
            } else {
                sb.appendLine("Messages App: ✗ Not configured")
            }
        }

        // Permissions status
        sb.appendLine("Permissions: ${if (hasRequiredPermissions()) "✓ Granted" else "✗ Not granted"}")

        // Bridge status
        sb.appendLine("Bridge: ${if (Config.bridgeEnabled) "✓ Running" else "○ Stopped"}")

        // Room mappings count
        val mappingCount = RoomMapper.getMappingCount()
        sb.appendLine("Mapped contacts: $mappingCount")

        statusText.text = sb.toString()

        // Update button states
        toggleBridgeButton.text = if (Config.bridgeEnabled) "Stop Bridge" else "Start Bridge"
        requestSmsRoleButton.visibility = if (isDefaultSmsApp()) View.GONE else View.VISIBLE

        // Show/hide effective app buttons
        val showEffectiveAppButtons = isDefaultSmsApp() && EffectiveSmsAppManager.hasEffectiveApp()
        openSmsAppButton.visibility = if (showEffectiveAppButtons &&
            EffectiveSmsAppManager.isEffectiveAppAvailable()) View.VISIBLE else View.GONE
        selectSmsAppButton.visibility = if (isDefaultSmsApp()) View.VISIBLE else View.GONE
    }

    /**
     * Check if this app is the default SMS app.
     */
    private fun isDefaultSmsApp(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
    }

    /**
     * Check if all required permissions are granted.
     */
    private fun hasRequiredPermissions(): Boolean {
        val permissions = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request required permissions if not granted.
     */
    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )

        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
