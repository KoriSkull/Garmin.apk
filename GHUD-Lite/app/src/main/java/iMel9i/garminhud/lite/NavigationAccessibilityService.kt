package iMel9i.garminhud.lite

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import iMel9i.garminhud.lite.DebugLog

class NavigationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NavAccessibility"
        var instance: NavigationAccessibilityService? = null
        var debugDumpMode = true
        var debugToastsEnabled = false
    }

    private lateinit var configManager: AppConfigManager
    private var lastDumpTime = 0L
    private val DUMP_INTERVAL = 5000L

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = AppConfigManager(this)
        DebugLog.i(TAG, "Service created")
        CustomArrowManager.load(this)
        CapturedIconRepository.init(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
        DebugLog.i(TAG, "Service connected, flags updated to include not important views")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        DebugLog.w(TAG, "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val config = configManager.getConfigs().find { it.packageName == packageName && it.enabled } ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            // Try to get the correct root node for the target package
            val rootNode = getRootNode(event, packageName) ?: return
            
            if (debugDumpMode) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastDumpTime > DUMP_INTERVAL) {
                    lastDumpTime = currentTime
                    dumpAllUIElements(rootNode, packageName)
                }
            }
            
            parseUIElements(rootNode, config, packageName)
        }
    }
    
    private fun getRootNode(event: AccessibilityEvent, targetPackage: String): AccessibilityNodeInfo? {
        // 1. Try rootInActiveWindow
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName == targetPackage) {
            return activeRoot
        }
        
        // 2. If active window is mismatch (e.g. we are debugging), try to climb up from event source
        var source = event.source ?: return null
        
        while (true) {
            val parent = source.parent
            if (parent == null) {
                if (source.packageName == targetPackage) {
                    return source
                } else {
                    source.recycle()
                    return null
                }
            }
            source.recycle()
            source = parent
        }
        
        return null
    }

    private fun dumpAllUIElements(rootNode: AccessibilityNodeInfo, packageName: String) {
        DebugLog.i(TAG, "=== UI DUMP START ($packageName) ===")
        dumpNodeRecursive(rootNode, 0)
        DebugLog.i(TAG, "=== UI DUMP END ===")
    }
    
    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val className = node.className?.toString() ?: "null"
        val resourceId = node.viewIdResourceName ?: "null"
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        
        if (resourceId != "null" || text.isNotEmpty() || contentDesc.isNotEmpty() || className.contains("ImageView") || className.contains("TextView")) {
            DebugLog.d(TAG, "$indent[$className] id=$resourceId text='$text' desc='$contentDesc'")
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                dumpNodeRecursive(child, depth + 1)
                child.recycle()
            }
        }
    }

    private fun parseUIElements(rootNode: AccessibilityNodeInfo, config: AppConfigManager.AppConfig, packageName: String) {
        // Double check package to avoid parsing wrong windows
        if (rootNode.packageName != packageName) {
             return
        }
        
        DebugLog.i(TAG, "Parsing UI for ${config.appName}")
        val parsedData = mutableMapOf<String, String?>()
        var recognizedArrow: ArrowDirection? = null
        var arrowFoundNode = false

        config.fields.forEach { (dataTypeName, resourceId) ->
            if (resourceId.isBlank()) return@forEach
            
            // Try exact match first, then partial
            var node = findNodeByResourceId(rootNode, resourceId)
            if (node == null) {
                // Try finding by just the ID part (e.g. "image_maneuverballoon_maneuver")
                val idPart = resourceId.substringAfter(":id/")
                if (idPart.isNotEmpty() && idPart != resourceId) {
                    node = findNodeByResourceId(rootNode, idPart, partial = true)
                }
            }
            
            // Ultimate fallback: Full tree traversal (if still not found and it's important)
            if (node == null && dataTypeName == HudDataType.DIRECTION_ARROW.name) {
                 // DebugLog.w(TAG, "Trying fallback search for $resourceId")
                 node = findNodeRecursiveFallback(rootNode, resourceId)
            }
            
            if (node == null) {
                // DebugLog.w(TAG, "NOT FOUND: $dataTypeName at $resourceId")
                if (dataTypeName == HudDataType.DIRECTION_ARROW.name) {
                    HudService.navDebug.arrowStatus = "Node Not Found"
                }
                return@forEach
            }
            
            // DebugLog.i(TAG, "FOUND: $dataTypeName at $resourceId (class=${node.className})")
            
            val isArrowField = dataTypeName == HudDataType.DIRECTION_ARROW.name
            val isImageView = node.className?.toString()?.contains("ImageView") == true
            
            if (isArrowField || isImageView) {
                arrowFoundNode = true
                DebugLog.i(TAG, "Processing as Image: $resourceId")
                val arrow = recognizeArrowFrom(node)
                if (arrow != null) {
                    recognizedArrow = arrow
                }
            } else {
                val value = node.text?.toString()
                parsedData[dataTypeName] = value
                if (value != null) {
                    // DebugLog.i(TAG, "$dataTypeName = '$value'")
                }
            }
        }
        
        if (debugToastsEnabled) {
            val status = if (recognizedArrow != null) "Arrow: ${recognizedArrow?.name}" 
                         else if (arrowFoundNode) "Arrow: Found Node, No Recog"
                         else "Arrow: Not Found"
            showDebugToast(status)
        }
        
        recognizedArrow?.let { arrow ->
            if (arrow != ArrowDirection.NONE) {
                HudState.turnIcon = arrow.hudCode
                DebugLog.i(TAG, "Set turn icon: ${arrow.name}")
            }
        }

        updateHudState(parsedData, packageName)
    }
    
    private var lastToastTime = 0L
    private var lastArrowCheckTime = 0L
    private val ARROW_CHECK_INTERVAL = 1000L
    private var isProcessingArrow = false

    private fun recognizeArrowFrom(imageNode: AccessibilityNodeInfo): ArrowDirection? {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastArrowCheckTime < ARROW_CHECK_INTERVAL) {
            HudService.navDebug.arrowStatus = "Throttled"
            return null
        }
        
        if (isProcessingArrow) {
            HudService.navDebug.arrowStatus = "Busy"
            return null
        }
        
        lastArrowCheckTime = currentTime
        isProcessingArrow = true

        try {
            // Get bounds of the arrow ImageView
            val rect = android.graphics.Rect()
            imageNode.getBoundsInScreen(rect)
            
            if (rect.width() <= 0 || rect.height() <= 0) {
                HudService.navDebug.arrowStatus = "Invalid bounds"
                isProcessingArrow = false
                return null
            }
            
            DebugLog.i(TAG, "Arrow bounds: $rect")
            
            // Use ScreenCaptureService (MediaProjection API)
            val captureService = ScreenCaptureService.instance
            if (captureService != null) {
                DebugLog.i(TAG, "Attempting screenshot via MediaProjection")
                HudService.navDebug.arrowStatus = "Taking screenshot..."
                
                captureService.captureScreen(rect) { bitmap ->
                    if (bitmap != null) {
                        processArrowBitmap(bitmap)
                    } else {
                        HudService.navDebug.arrowStatus = "Screenshot failed"
                        isProcessingArrow = false
                    }
                }
            } else {
                HudService.navDebug.arrowStatus = "Screen capture not started"
                DebugLog.w(TAG, "ScreenCaptureService not running - start it from MainActivity")
                isProcessingArrow = false
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "recognizeArrowFrom error: ${e.message}")
            HudService.navDebug.arrowStatus = "Error: ${e.message}"
            isProcessingArrow = false
        }
        
        return null
    }
    
    private fun processArrowBitmap(bitmap: android.graphics.Bitmap) {
        try {
            HudService.navDebug.lastArrowBitmap?.recycle()
            HudService.navDebug.lastArrowBitmap = bitmap
            
            val arrowImg = ArrowImage(bitmap)
            val hash = arrowImg.getArrowValue()
            DebugLog.i(TAG, "Arrow Hash: $hash")
            
            // === NEW SYSTEM: Capture & Check Mapping ===
            CapturedIconRepository.saveIcon(this, bitmap, hash)
            val mappedIcon = CapturedIconRepository.getMapping(hash)
            
            if (mappedIcon != null) {
                HudState.activeHudIcon = mappedIcon
                HudService.navDebug.arrowStatus = "Mapped: ${mappedIcon.displayName}"
                HudState.notifyUpdate()
                return
            }
            // ===========================================
            
            // === DEBUG: SAVE IMAGE ===
            saveDebugImage(bitmap, hash)
            // =========================
            
            val recognized = ArrowDirection.recognize(arrowImg)
            if (recognized != ArrowDirection.NONE) {
                HudState.turnIcon = recognized.hudCode
                HudState.activeHudIcon = null // Clear strict mapping so legacy works
                HudService.navDebug.arrowStatus = "Recognized: ${recognized.name} ($hash)"
                HudState.notifyUpdate()
            } else {
                HudService.navDebug.arrowStatus = "Not Recognized ($hash)"
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "processArrowBitmap error: ${e.message}")
            HudService.navDebug.arrowStatus = "Process error"
        } finally {
            isProcessingArrow = false
        }
    }

    private fun saveDebugImage(bitmap: android.graphics.Bitmap, hash: Long) {
        try {
            val dir = java.io.File(getExternalFilesDir(null), "debug_arrows")
            if (!dir.exists()) dir.mkdirs()
            
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(java.util.Date())
            val filename = "arrow_${timestamp}_$hash.png"
            val file = java.io.File(dir, filename)
            
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            DebugLog.i(TAG, "Saved debug arrow: ${file.absolutePath}")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to save debug image: ${e.message}")
        }
    }

    private fun showDebugToast(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime > 2000) { // Throttle toasts
            lastToastTime = now
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Toast error: ${e.message}")
                }
            }
        }
    }

    private fun findNodeByResourceId(node: AccessibilityNodeInfo, resourceId: String, partial: Boolean = false): AccessibilityNodeInfo? {
        val nodeId = node.viewIdResourceName
        if (nodeId != null) {
            if (partial) {
                if (nodeId.contains(resourceId)) return node 
            } else {
                if (nodeId == resourceId) return node
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByResourceId(child, resourceId, partial)
            if (result != null) {
                if (result != child) {
                    child.recycle()
                }
                return result
            }
            child.recycle()
        }
        return null
    }
    
    // Fallback: Heuristic search for arrow if ID not found
    private fun findNodeRecursiveFallback(rootNode: AccessibilityNodeInfo, originalId: String): AccessibilityNodeInfo? {
        // Only run fallback for arrow searches (heuristic based)
        if (!originalId.contains("maneuver") && !originalId.contains("arrow")) return null
        
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        return findArrowNodeHeuristic(rootNode, screenWidth, screenHeight)
    }
    
    private fun findArrowNodeHeuristic(node: AccessibilityNodeInfo, w: Int, h: Int): AccessibilityNodeInfo? {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        
        // Check if this node is a candidate
        
        // 1. Position: Top-Left quadrant (usually)
        // Adjust for status bar (approx 60px)
        val isTopLeft = rect.left < w / 2 && rect.top < h / 3 && rect.top > 50
        
        // 2. Size: Reasonable size for an icon
        val width = rect.width()
        val height = rect.height()
        val validSize = width > 50 && height > 50 && width < 400 && height < 400
        
        // 3. Aspect Ratio: Square-ish
        val ratio = width.toFloat() / height.toFloat()
        val isSquareish = ratio in 0.8f..1.2f
        
        // 4. Type: Image or generic View without text
        val className = node.className?.toString() ?: ""
        val isImage = className.contains("ImageView") || (className.contains("View") && (node.text == null || node.text.isEmpty()))
        
        if (isTopLeft && validSize && isSquareish && isImage) {
            DebugLog.i(TAG, "Heuristic candidate: $className bounds=$rect")
            return node
        }
        
        // Recursion
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findArrowNodeHeuristic(child, w, h)
                if (result != null) {
                    if (result != child) child.recycle()
                    return result
                }
                child.recycle()
            }
        }
        
        return null
    }

    private fun updateHudState(parsedData: Map<String, String?>, packageName: String) {
        HudState.lastPackageName = packageName
        HudState.rawData.clear()
        parsedData.forEach { (key, value) -> if (value != null) HudState.rawData[key] = value }

        parsedData[HudDataType.DISTANCE_TO_TURN.name]?.let { 
            HudState.distanceToTurn = it
            HudState.distanceToTurnMeters = parseDistanceToMeters(it)
        }

        parsedData[HudDataType.NAVIGATION_INSTRUCTION.name]?.let {
            HudState.turnIcon = parseTurnDirection(it)
        }

        parsedData[HudDataType.ETA.name]?.let { HudState.eta = it }
        parsedData[HudDataType.REMAINING_TIME.name]?.let { HudState.remainingTime = it }
        
        parsedData[HudDataType.TRAFFIC_SCORE.name]?.let {
            HudState.trafficScore = it.replace(Regex("[^0-9]"), "").toIntOrNull()
        }

        parsedData[HudDataType.SPEED_LIMIT.name]?.let {
            HudState.speedLimit = it.replace(Regex("[^0-9]"), "").toIntOrNull()
        }

        parsedData[HudDataType.CURRENT_SPEED.name]?.let {
            HudState.currentSpeed = it.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        }

        HudState.isNavigating = !HudState.distanceToTurn.isNullOrBlank() || HudState.eta != null
        HudState.notifyUpdate()
        
        HudService.navDebug.packageName = packageName
        HudService.navDebug.lastUpdateTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        HudService.navDebug.parsedInstruction = HudState.rawData[HudDataType.NAVIGATION_INSTRUCTION.name] ?: ""
        HudService.navDebug.parsedDistance = HudState.distanceToTurn ?: ""
        HudService.navDebug.parsedEta = HudState.eta ?: ""
    }

    private fun parseDistanceToMeters(distanceText: String): Int? {
        return DistanceFormatter.parseDistance(distanceText)?.first
    }

    private fun parseTurnDirection(instruction: String): Int {
        val instr = instruction.lowercase()
        return when {
            "sharp left" in instr || "резко налево" in instr -> 7
            "sharp right" in instr || "резко направо" in instr -> 8
            "turn left" in instr || "поверните налево" in instr || "налево" in instr -> 1
            "turn right" in instr || "поверните направо" in instr || "направо" in instr -> 6
            "keep left" in instr || "левее" in instr || "держаться левее" in instr -> 4
            "keep right" in instr || "правее" in instr || "держаться правее" in instr -> 5
            "easy left" in instr || "плавно налево" in instr -> 2
            "easy right" in instr || "плавно направо" in instr -> 3
            "straight" in instr || "прямо" in instr -> 0
            else -> 0
        }
    }
    
    override fun onInterrupt() {}
}
