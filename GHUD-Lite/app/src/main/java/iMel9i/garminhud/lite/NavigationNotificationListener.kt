package iMel9i.garminhud.lite

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Служба для мониторинга уведомлений от приложений навигации
 * (Google Maps, Yandex Maps, Yandex Navigator)
 */
class NavigationNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NavNotifListener"
        
        var instance: NavigationNotificationListener? = null
        var onNavigationUpdate: ((NavigationData) -> Unit)? = null
        var enabled = true // ВКЛЮЧЕНО по умолчанию, так как AccessibilityService screenshot не работает
    }

    
    data class NavigationData(
        val distance: String? = null,
        val distanceMeters: Int? = null,
        val instruction: String? = null,
        val eta: String? = null,
        val speed: Int? = null,
        val speedLimit: Int? = null,
        val isNavigating: Boolean = false
    )
    
    private lateinit var configManager: AppConfigManager
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = AppConfigManager(this)
        DebugLog.i(TAG, "Navigation notification listener created")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        DebugLog.i(TAG, "Navigation notification listener destroyed")
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // Log EVERY notification from navigation apps to debug
        if (packageName.contains("yandex") || packageName.contains("maps") || packageName.contains("nav")) {
            DebugLog.i(TAG, "Notification received from: $packageName")
        }
        
        if (!enabled) return // Пропускаем если выключено
        
        // Check if Yandex notifications are disabled
        if (packageName.startsWith("ru.yandex")) {
            val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
            val yandexEnabled = prefs.getBoolean("yandex_notifications_enabled", true)
            if (!yandexEnabled) {
                DebugLog.d(TAG, "Yandex notifications disabled, skipping")
                return
            }
        }
        
        var config = configManager.getConfigs().find { it.packageName == packageName && it.enabled }
        
        // Fallback for Yandex if not in config
        if (config == null && packageName.startsWith("ru.yandex")) {
            config = AppConfigManager.DEFAULT_CONFIGS.find { it.packageName == packageName }
            if (config != null) {
                DebugLog.i(TAG, "Using default config for $packageName")
            }
        }
        
        if (config != null) {
            parseNotification(sbn, config)
        }
    }

    
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val config = configManager.getConfigs().find { it.packageName == packageName && it.enabled }
        
        if (config != null) {
            DebugLog.i(TAG, "Navigation notification removed from: $packageName")
            // Сообщаем, что навигация завершена
            onNavigationUpdate?.invoke(NavigationData(isNavigating = false))
        }
    }
    
    private fun parseNotification(sbn: StatusBarNotification, config: AppConfigManager.AppConfig) {
        val notification = sbn.notification
        val extras = notification.extras
        
        DebugLog.i(TAG, "=== PARSING NOTIFICATION FROM ${sbn.packageName} ===")
        
        // LOG ALL EXTRAS KEYS for debugging
        val allExtras = mutableMapOf<String, String>()
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            allExtras[key] = value?.toString() ?: "null"
            DebugLog.d(TAG, "  Extra: $key = $value")
        }
        
        // Update Debug Raw Data with standard fields
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val subText = extras.getCharSequence("android.subText")?.toString()
        val infoText = extras.getCharSequence("android.infoText")?.toString()
        
        HudService.navDebug.packageName = sbn.packageName
        HudService.navDebug.title = title ?: ""
        HudService.navDebug.text = text ?: ""
        HudService.navDebug.bigText = bigText ?: ""
        HudService.navDebug.lastUpdateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        
        HudState.lastPackageName = sbn.packageName
        HudState.rawData.clear()
        HudState.rawData.putAll(allExtras) // Store ALL extras for debugging
        
        // --- Parsing based on Configured Mappings ---
        
        // 1. Distance
        val distanceKey = config.fields[HudDataType.DISTANCE_TO_TURN.name]
        var distance: String? = null
        if (distanceKey != null) {
            val rawDist = extras.getCharSequence(distanceKey)?.toString()
            // If user mapped it, try to extract distance from it
            distance = extractDistance(rawDist)
        }
        
        if (distance == null) {
            // Fallback: try standard fields
            distance = extractDistance(title) ?: extractDistance(text) ?: extractDistance(bigText)
        }
        
        // 2. Instruction / Direction
        val instructionKey = config.fields[HudDataType.NAVIGATION_INSTRUCTION.name]
        var instruction: String? = null
        if (instructionKey != null) {
            instruction = extras.getCharSequence(instructionKey)?.toString()
        }
        
        if (instruction == null) {
            // Fallback
            instruction = title ?: text
        }
        
        // 3. ETA
        val etaKey = config.fields[HudDataType.ETA.name]
        var eta: String? = null
        if (etaKey != null) {
            eta = extras.getCharSequence(etaKey)?.toString()
        }
        
        if (eta == null) {
            // Fallback: usually text contains ETA if title contains instruction
            eta = text
        }
        
        // 4. Remaining Time
        val timeKey = config.fields[HudDataType.REMAINING_TIME.name]
        var remainingTime: String? = null
        if (timeKey != null) {
            remainingTime = extras.getCharSequence(timeKey)?.toString()
        }
        
        // 5. Traffic Score
        val trafficKey = config.fields[HudDataType.TRAFFIC_SCORE.name]
        var trafficScore: Int? = null
        if (trafficKey != null) {
            val rawTraffic = extras.getCharSequence(trafficKey)?.toString()
            trafficScore = rawTraffic?.toIntOrNull()
        }
        
        // 6. Speed Limit (if available in notif)
        val limitKey = config.fields[HudDataType.SPEED_LIMIT.name]
        var speedLimit: Int? = null
        if (limitKey != null) {
            val rawLimit = extras.getCharSequence(limitKey)?.toString()
            speedLimit = rawLimit?.toIntOrNull()
        }
        
        if (instruction == null && distance == null) return
        
        val navData = NavigationData(
            instruction = instruction,
            distance = distance,
            eta = eta,
            speedLimit = speedLimit,
            isNavigating = true
        )
        
        // Update Universal State
        HudState.isNavigating = true
        HudState.distanceToTurn = distance
        if (distance != null) {
            val parsed = DistanceFormatter.parseDistance(distance)
            if (parsed != null) {
                HudState.distanceToTurnMeters = parsed.first
            }
        }
        HudState.eta = eta
        HudState.remainingTime = remainingTime
        HudState.trafficScore = trafficScore
        if (speedLimit != null) HudState.speedLimit = speedLimit
        
        // 7. Arrow Image (Large Icon or Picture)
        val largeIcon = extras.getParcelable<android.graphics.Bitmap>("android.largeIcon") 
            ?: (extras.getParcelable<android.graphics.drawable.Icon>("android.largeIcon")?.loadDrawable(this)?.let { ImageUtils.drawableToBitmap(it) })
        
        val picture = extras.getParcelable<android.graphics.Bitmap>("android.picture")
        
        var arrowBitmap: android.graphics.Bitmap? = null
        
        // Try to get bitmap from largeIcon first
        if (largeIcon != null) {
            Log.d(TAG, "Found LargeIcon in notification: ${largeIcon.width}x${largeIcon.height}")
            arrowBitmap = largeIcon
        } else if (picture != null) {
            Log.d(TAG, "Found Picture in notification: ${picture.width}x${picture.height}")
            arrowBitmap = picture
        } else {
            // Try to extract bitmap from RemoteViews (like old Google Maps approach)
            arrowBitmap = extractBitmapFromRemoteViews(notification)
        }
        
        if (arrowBitmap != null) {
            HudService.navDebug.lastArrowBitmap = arrowBitmap
            val arrowImage = ArrowImage(arrowBitmap)
            val hash = arrowImage.getArrowValue()
            Log.d(TAG, "Arrow Hash: $hash")
            
            val arrow = ArrowDirection.recognize(arrowImage)
            if (arrow != ArrowDirection.NONE) {
                HudState.turnIcon = arrow.hudCode
                HudService.navDebug.arrowStatus = "Recognized: ${arrow.name} ($hash)"
                Log.d(TAG, "Recognized arrow: $arrow")
            } else {
                HudService.navDebug.arrowStatus = "Not Recognized ($hash)"
                Log.d(TAG, "Arrow not recognized, hash: $hash")
            }
        } else {
            HudService.navDebug.lastArrowBitmap = null
            // Fallback: Parse arrow from text instruction
            if (instruction != null) {
                val arrow = parseTextToArrow(instruction)
                if (arrow != ArrowDirection.NONE) {
                    HudState.turnIcon = arrow.hudCode
                    Log.d(TAG, "Parsed arrow from text '$instruction': $arrow")
                }
            }
        }
        
        HudService.navDebug.parsedInstruction = instruction ?: ""
        HudService.navDebug.parsedDistance = distance ?: ""
        HudService.navDebug.parsedEta = eta ?: ""
        
        Log.d(TAG, "Parsed navigation data: $navData")
        onNavigationUpdate?.invoke(navData)
        HudState.notifyUpdate()
    }
    
    private fun parseTextToArrow(text: String): ArrowDirection {
        val t = text.lowercase()
        return when {
            "u-turn" in t || "разворот" in t -> ArrowDirection.SHARP_LEFT // Or specific code if available
            "sharp left" in t || "резко налево" in t -> ArrowDirection.SHARP_LEFT
            "sharp right" in t || "резко направо" in t -> ArrowDirection.SHARP_RIGHT
            "left" in t || "налево" in t -> ArrowDirection.LEFT
            "right" in t || "направо" in t -> ArrowDirection.RIGHT
            "keep left" in t || "левее" in t -> ArrowDirection.KEEP_LEFT
            "keep right" in t || "правее" in t -> ArrowDirection.KEEP_RIGHT
            "straight" in t || "прямо" in t -> ArrowDirection.STRAIGHT
            else -> ArrowDirection.NONE
        }
    }
    
    private fun extractBitmapFromRemoteViews(notification: android.app.Notification): android.graphics.Bitmap? {
        try {
            // Try bigContentView first (Yandex uses this), then contentView
            val views = notification.bigContentView ?: notification.contentView ?: return null
            
            DebugLog.i(TAG, "Attempting to extract bitmap from RemoteViews by applying to View")
            
            // Apply RemoteViews to actual View hierarchy
            val context = this
            val inflatedView = views.apply(context, null)
            
            // Search for ImageView with arrow
            val arrowBitmap = findArrowImageView(inflatedView)
            
            if (arrowBitmap != null) {
                DebugLog.i(TAG, "Found arrow in ImageView: ${arrowBitmap.width}x${arrowBitmap.height}")
                return arrowBitmap
            }
            
            DebugLog.w(TAG, "Could not find arrow ImageView in notification")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to extract bitmap from RemoteViews: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    private fun findArrowImageView(view: android.view.View): android.graphics.Bitmap? {
        // If this is an ImageView, check if it contains an arrow
        if (view is android.widget.ImageView) {
            val drawable = view.drawable
            if (drawable != null) {
                val bitmap = ImageUtils.drawableToBitmap(drawable)
                if (bitmap != null) {
                    // Check if this looks like an arrow (square-ish, reasonable size)
                    val width = bitmap.width
                    val height = bitmap.height
                    
                    if (width > 30 && height > 30 && width < 500 && height < 500) {
                        val aspectRatio = width.toFloat() / height.toFloat()
                        if (aspectRatio in 0.5f..2.0f) {
                            DebugLog.d(TAG, "Found potential arrow ImageView: ${width}x${height}")
                            return bitmap
                        }
                    }
                }
            }
        }
        
        // Recursively search children
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findArrowImageView(child)
                if (result != null) {
                    return result
                }
            }
        }
        
        return null
    }
    
    private fun extractDistance(text: String?): String? {
        if (text == null) return null
        
        // Ищем паттерны: "500 m", "500 м", "1.5 km", "1.5 км"
        // Added support for comma/dot decimal separator
        val distanceRegex = """(\d+(?:[.,]\d+)?)\s*(m|м|km|км)""".toRegex(RegexOption.IGNORE_CASE)
        val match = distanceRegex.find(text)
        
        return match?.value
    }
}
