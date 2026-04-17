package iMel9i.garminhud.lite

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

class HudService : Service(), LocationListener {
    
    companion object {
        private const val TAG = "HudService"
        private const val CHANNEL_ID = "HudServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "HudPrefs"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        
        // Debug data for UI
        data class OsmDebugData(
            var lastQuery: String = "",
            var lastResponse: String = "",
            var currentSpeedLimit: Int? = null,
            var camerasFound: Int = 0,
            var nearestCameraDistance: Int? = null,
            var lastUpdateTime: String = "",
            var lastLocation: String = ""
        )
        data class NavigationDebugData(
            var packageName: String = "",
            var title: String = "",
            var text: String = "",
            var bigText: String = "",
            var lastUpdateTime: String = "",
            var parsedInstruction: String = "",
            var parsedDistance: String = "",
            var parsedEta: String = "",
            var lastArrowBitmap: android.graphics.Bitmap? = null,
            var recognizedArrowBitmaps: MutableList<android.graphics.Bitmap> = mutableListOf(),
            var maneuverArrowOrdinal: Int? = null,
            var arrowStatus: String = "Waiting...",
            var laneMask: String = "-",
            var laneCandidates: String = "-",
            var lanePayload: String = "-",
            var laneMaskBeforeExclusion: String = "-",
            var laneMaskAfterExclusion: String = "-",
            var laneExclusionEnabled: Boolean = true
        )
        
        data class HudDebugData(
            var lastCommand: String = "",
            var currentSpeed: Int = 0,
            var displayedSpeedLimit: Int? = null,
            var showingSpeedingIcon: Boolean = false,
            var showingCameraIcon: Boolean = false,
            var currentDirection: String = "",
            var currentDistance: String = "",
            var lastUpdateTime: String = ""
        )
        
        val osmDebug = OsmDebugData()
        val navDebug = NavigationDebugData()
        val hudDebug = HudDebugData()
    }
    
    private lateinit var hud: HudEngine
    private lateinit var locationManager: LocationManager
    private var currentSpeed: Float = 0f
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var updateTimer: Timer? = null
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isServiceRunning = false
    private var isNavigating = false
    private var currentNavigationData: NavigationNotificationListener.NavigationData? = null
    
    private val reconnectRunnable = Runnable {
        if (isServiceRunning && !hud.isConnected()) {
            autoConnectToSavedDevice()
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        isServiceRunning = true
        
        createNotificationChannel()
        
        val notification = createNotification("Инициализация...")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: Check for Bluetooth permissions before using connectedDevice type
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    startForeground(
                        NOTIFICATION_ID, 
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or 
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    // Fallback to just location if bluetooth permission missing
                    Log.w(TAG, "Missing BLUETOOTH_CONNECT permission, starting with LOCATION type only")
                    startForeground(
                        NOTIFICATION_ID, 
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-11
                startForeground(
                    NOTIFICATION_ID, 
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                // Android 9 and below
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            // Ultimate fallback
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Critical failure starting foreground service", e2)
            }
        }
        
        hud = HudEngineFactory.create(this)
        hud.onConnectionStateChanged = { connected, deviceName ->
            if (connected) {
                updateNotification("Подключено: $deviceName")
                saveDeviceInfo(hud.getConnectedDeviceName(), hud.getConnectedDeviceAddress())
                
                // Stop any pending reconnection attempts
                reconnectHandler.removeCallbacks(reconnectRunnable)
                
                // Immediately start updates
                startUpdates()
                applyBrightness()
                
                // Force an immediate update to clear "OK" or default state
                updateHud()
                
            } else {
                updateNotification("Отключено. Попытка подключения...")
                stopUpdates()
                
                // Start reconnection loop if service is still running
                if (isServiceRunning) {
                    reconnectHandler.removeCallbacks(reconnectRunnable) // Ensure we don't have multiple callbacks
                    reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
                }
            }
        }
        
        setupLocationManager()
        setupNavigationListener()
        autoConnectToSavedDevice()
    }
    
    private fun setupNavigationListener() {
        // Подключаемся к NavigationNotificationListener
        NavigationNotificationListener.onNavigationUpdate = { navData ->
            currentNavigationData = navData
            isNavigating = navData.isNavigating
            
            if (isNavigating) {
                Log.d(TAG, "Navigation active: ${navData.instruction}, ${navData.distance}")
            } else {
                Log.d(TAG, "Navigation inactive")
            }
        }
    }
    
    private fun applyBrightness() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoBrightness = prefs.getBoolean("auto_brightness", true)
        if (autoBrightness) {
            hud.setBrightness(0) // Auto
        } else {
            hud.setBrightness(10) // Max manual
        }
    }
    
    private fun setupLocationManager() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000, // 1 second
                0f,   // 0 meters
                this
            )
        }
    }
    
    private fun autoConnectToSavedDevice() {
        if (hud.isConnected()) return
        
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, null)
        val deviceName = prefs.getString(KEY_DEVICE_NAME, null)
        
        if (deviceAddress != null) {
            Log.d(TAG, "Connecting to saved device: $deviceName ($deviceAddress)")
            hud.connectToDevice(deviceAddress)
        } else {
            updateNotification("Нет сохраненного устройства")
        }
    }
    
    private fun saveDeviceInfo(name: String?, address: String?) {
        if (name != null && address != null) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_DEVICE_NAME, name)
                .putString(KEY_DEVICE_ADDRESS, address)
                .apply()
            Log.d(TAG, "Saved device: $name ($address)")
        }
    }
    
    private fun startUpdates() {
        updateTimer = Timer()
        updateTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                updateHud()
            }
        }, 0, 1000) // Update every second
    }
    
    private fun stopUpdates() {
        updateTimer?.cancel()
        updateTimer = null
    }
    
    private val osmClient = OsmClient()
    private var currentOsmSpeedLimit: Int? = null
    private var nearbyCameras: List<OsmClient.CameraLocation> = emptyList()
    private var lastOsmUpdateLocation: Location? = null
    private val OSM_UPDATE_DISTANCE_METERS = 500f
    private var distanceToCamera: Int? = null
    
    private fun updateHud() {
        if (!hud.isConnected()) return
        
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gmapsEnabled = prefs.getBoolean("gmaps_integration", false)
        
        // Determine mode based on navigation state
        val mode = if (gmapsEnabled && HudState.isNavigating) {
            if (HudState.lastPackageName?.contains("yandex") == true) "YANDEX" else "GOOGLE"
        } else {
            "IDLE"
        }
        
        // Load layout profile
        val layoutManager = LayoutConfigManager(this)
        val profile = layoutManager.getProfile(mode)
        
        // 1. Direction Arrow
        val arrowType = profile.slots[HudSlot.DIRECTION_ARROW]
        if (arrowType == HudDataType.DISTANCE_TO_TURN || arrowType == HudDataType.NONE) {
             // Priority to new System
             val icon = HudState.activeHudIcon
             if (HudState.isNavigating && icon != null && icon != HudIcon.NONE) {
                 hud.setDirection(icon.type, icon.angle)
             } else if (HudState.isNavigating && HudState.turnIcon != null) {
                 // Fallback to legacy
                 hud.setArrow(HudState.turnIcon!!)
             } else {
                 hud.setDirection(0, 0) // Clear
             }
        }

        if (HudState.isNavigating && !HudState.laneAssist.isNullOrBlank()) {
            val (laneArrowMask, laneOutlineMask) = buildLaneMasksFromString(HudState.laneAssist!!)
            navDebug.laneMask = HudState.laneAssist!!
            navDebug.lanePayload = "02 ${laneOutlineMask.toHexByte()} ${laneArrowMask.toHexByte()}"
            if (laneOutlineMask != 0) {
                hud.setLanes(laneArrowMask, laneOutlineMask)
            } else {
                navDebug.lanePayload += " (skip: outline=00)"
            }
        } else {
            navDebug.laneMask = HudState.laneAssist ?: "-"
            navDebug.lanePayload = "-"
        }
        
        // 2. Main Number (Distance)
        val mainType = profile.slots[HudSlot.MAIN_NUMBER]
        if (mainType == HudDataType.DISTANCE_TO_TURN && HudState.distanceToTurnMeters != null) {
            // Форматируем расстояние правильно (как в оригинальном приложении)
            val (value, unit) = DistanceFormatter.formatDistance(HudState.distanceToTurnMeters!!)
            hud.setDistance(value, unit.hudValue)
        } else if (mainType == HudDataType.DISTANCE_TO_CAMERA && HudState.cameraDistance != null) {
            val (value, unit) = DistanceFormatter.formatDistance(HudState.cameraDistance!!)
            hud.setDistance(value, unit.hudValue)
        } else if (mainType == HudDataType.CURRENT_SPEED) {
            // Если хотим скорость — просто число
            hud.setDistance(HudState.currentSpeed, DistanceUnit.NONE.hudValue)
        } else {
            hud.clearDistance()
        }

        // 3. Speed / Secondary
        val showSpeed = profile.slots[HudSlot.MAIN_NUMBER] == HudDataType.CURRENT_SPEED || 
                        profile.slots[HudSlot.SECONDARY_NUMBER] == HudDataType.CURRENT_SPEED
                        
        val showTime = profile.slots[HudSlot.SECONDARY_NUMBER] == HudDataType.CURRENT_TIME
        
        if (showTime) {
            val calendar = Calendar.getInstance()
            hud.setTime(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
        }
        
        // Speed Logic
        val speedKmh = HudState.currentSpeed
        val limit = HudState.speedLimit
        val speeding = HudState.isSpeeding
        val camera = HudState.cameraDistance != null || (HudState.activeHudIcon?.isCamera == true)
        
        // If the layout requests speed, we show it.
        if (showSpeed || HudState.isSpeeding) { 
             hud.setSpeedWithLimit(speedKmh, limit, speeding, camera)
        } else {
             hud.setSpeedWithLimit(0, null, false, camera) // Clear speed
        }
        
        // Update debug data
        hudDebug.currentSpeed = speedKmh
        hudDebug.displayedSpeedLimit = limit
        hudDebug.showingSpeedingIcon = speeding
        hudDebug.showingCameraIcon = camera
        hudDebug.lastCommand = "Speed: $speedKmh, Limit: $limit, Speeding: $speeding"
        hudDebug.lastUpdateTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

private fun buildLaneMasksFromString(value: String): Pair<Int, Int> {
    val raw = value.filter { it == '0' || it == '1' || it == ' ' }.take(6)

    val normalized = when {
        raw.length >= 6 -> raw.take(6)
        else -> {
            val totalPad = 6 - raw.length
            val leftPad = totalPad / 2
            val rightPad = totalPad - leftPad
            " ".repeat(leftPad) + raw + " ".repeat(rightPad)
        }
    }

    var outlineMask = 0
    var arrowMask = 0

    for (index in normalized.indices) {
        val ch = normalized[index]
        val bit = 1 shl (6 - index)
        if (ch == '0' || ch == '1') outlineMask = outlineMask or bit
        if (ch == '1') arrowMask = arrowMask or bit
    }
    return arrowMask to outlineMask
}

    private fun Int.toHexByte(): String = this.and(0xFF).toString(16).uppercase().padStart(2, '0')

    private fun updateOsmData(location: Location) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        osmDebug.lastLocation = String.format("%.6f, %.6f", location.latitude, location.longitude)
        osmDebug.lastUpdateTime = timeFormat.format(Date())

        osmClient.getSpeedLimit(location.latitude, location.longitude) { limit ->
            currentOsmSpeedLimit = limit
            osmDebug.currentSpeedLimit = limit

            // Update Universal State
            HudState.speedLimit = limit
            checkSpeeding()
        }
        
        osmClient.getCameras(location.latitude, location.longitude, 1000) { cameras ->
            nearbyCameras = cameras
            osmDebug.camerasFound = cameras.size
        }
    }

    private fun checkSpeeding() {
        val limit = HudState.speedLimit
        val speed = HudState.currentSpeed
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threshold = prefs.getInt("speeding_threshold", 10)
        
        if (limit != null) {
            HudState.isSpeeding = speed >= (limit + threshold)
        } else {
            HudState.isSpeeding = false
        }
    }
    
    private fun checkCameras(location: Location) {
        var minDist: Float? = null
        
        for (cam in nearbyCameras) {
            val res = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, cam.lat, cam.lon, res)
            val dist = res[0]
            if (minDist == null || dist < minDist) {
                minDist = dist
            }
        }
        
        // Показываем предупреждение о камере если расстояние <= 300м
        distanceToCamera = if (minDist != null && minDist <= 300) minDist.toInt() else null
        osmDebug.nearestCameraDistance = distanceToCamera
        
        // Update Universal State
        HudState.cameraDistance = distanceToCamera
    }
    
    // Helper for parseDirection is now in ArrowRecognition or handled by AccessibilityService
    // But we might need a fallback for text-based parsing if Accessibility fails to get image
    private fun parseDirection(instruction: String?): Int {
        if (instruction == null) return 0
        val instr = instruction.lowercase()
        return when {
            "sharp left" in instr || "резко налево" in instr -> 7
            "turn left" in instr || "поверните налево" in instr || "налево" in instr -> 1
            "keep left" in instr || "левее" in instr -> 4
            "sharp right" in instr || "резко направо" in instr -> 8
            "turn right" in instr || "поверните направо" in instr || "направо" in instr -> 6
            "keep right" in instr || "правее" in instr -> 5
            else -> 0
        }
    }

    override fun onLocationChanged(location: Location) {
        currentSpeed = location.speed
        
        // Update Universal State
        HudState.currentSpeed = (location.speed * 3.6f).toInt()
        checkSpeeding()
        
        val lastLoc = lastOsmUpdateLocation
        if (lastLoc == null || location.distanceTo(lastLoc) > OSM_UPDATE_DISTANCE_METERS) {
            lastOsmUpdateLocation = location
            updateOsmData(location)
        }
        
        checkCameras(location)
    }
    
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HUD Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновый сервис для Garmin HUD"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = android.app.PendingIntent.getActivity(
            this, 0, contentIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, HudService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GHUD Lite")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выход", stopPendingIntent)
            .build()
    }
    
    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "RESTART_HUD") {
            restartHud()
        } else if (intent?.action == ACTION_STOP_SERVICE) {
            val closeIntent = Intent("CLOSE_APP")
            closeIntent.setPackage(packageName)
            sendBroadcast(closeIntent)
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        return START_STICKY
    }
    
    private fun restartHud() {
        if (hud.isConnected()) {
            hud.disconnect()
            Toast.makeText(this, "HUD перезагружается...", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        isServiceRunning = false
        stopUpdates()
        reconnectHandler.removeCallbacks(reconnectRunnable)
        locationManager.removeUpdates(this)
        NavigationNotificationListener.onNavigationUpdate = null
        hud.disconnect()
    }
}
