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
        private const val PREFS_NAME = "HudPrefs"
        private const val KEY_LANE_EXCLUSION = "lane_exclusion_enabled"
        
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
    private var lastDetectedLaneMask: String? = null
    private var lastLaneCandidatesInfo: String = "-"

    private data class LaneDetectionResult(
        val sourceCandidates: Int,
        val expandedCandidates: Int,
        val mask: String,
        val rawCandidates: Int,
        val rowCandidates: Int,
        val slottedCandidates: Int,
        val whiteCount: Int,
        val grayCount: Int,
        val whiteSide: String
    )
    
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
        lastDetectedLaneMask = null
        lastLaneCandidatesInfo = "-"
        val laneExclusionEnabled = isLaneExclusionEnabled()
        clearRecognizedArrowsDebug()
        HudService.navDebug.laneCandidates = "-"
        HudService.navDebug.laneMask = "-"
        HudService.navDebug.laneMaskBeforeExclusion = "-"
        HudService.navDebug.laneMaskAfterExclusion = "-"
        HudService.navDebug.laneExclusionEnabled = laneExclusionEnabled
        
        // Try to get bitmap from largeIcon first
        if (largeIcon != null) {
            Log.d(TAG, "Found LargeIcon in notification: ${largeIcon.width}x${largeIcon.height}")
            arrowBitmap = largeIcon
        } else if (picture != null) {
            Log.d(TAG, "Found Picture in notification: ${picture.width}x${picture.height}")
            arrowBitmap = picture
        } else {
            // Try to extract bitmap from RemoteViews (like old Google Maps approach)
            arrowBitmap = extractBitmapFromRemoteViews(notification, sbn.packageName)
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

        HudState.laneAssist = lastDetectedLaneMask
        HudService.navDebug.laneCandidates = lastLaneCandidatesInfo
        HudService.navDebug.laneMask = lastDetectedLaneMask ?: "-"
        
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
    
    private data class ArrowCandidate(
        val bitmap: android.graphics.Bitmap,
        val bounds: android.graphics.Rect,
        val ordinal: Int
    )

    private data class ArrowMetrics(
        val whiteRatio: Double,
        val foregroundRatio: Double,
        val componentDominance: Double,
        val sizeScore: Double,
        val aspectPenalty: Double,
        val positionScore: Double,
        val totalScore: Double
    )

    private fun extractBitmapFromRemoteViews(
        notification: android.app.Notification,
        packageName: String
    ): android.graphics.Bitmap? {
        try {
            // Try bigContentView first (Yandex uses this), then contentView
            val views = notification.bigContentView ?: notification.contentView ?: return null

            DebugLog.i(TAG, "Attempting to extract bitmap from RemoteViews by applying to View")

            // Apply RemoteViews to actual View hierarchy
            val context = this
            val inflatedView = views.apply(context, null)

            // Collect all candidate ImageViews, then choose the whitest one
            val candidates = mutableListOf<ArrowCandidate>()
            collectArrowImageCandidates(inflatedView, candidates)

            if (candidates.isEmpty()) {
                DebugLog.w(TAG, "Could not find arrow ImageView in notification")
                return null
            }

            val laneCandidates = expandLaneCandidates(candidates, packageName)
            val scored = laneCandidates.map { candidate ->
                candidate to calculateArrowMetrics(candidate, packageName)
            }.sortedByDescending { it.second.totalScore }

            val maneuverCandidateOrdinal = scored.firstOrNull()?.first?.ordinal
            updateRecognizedArrowsDebug(scored, maneuverCandidateOrdinal)

            val laneResultBeforeExclusion = detectLaneMask(scored, candidates.size, laneCandidates.size)
            val laneExclusionEnabled = isLaneExclusionEnabled()
            val laneScoredAfterExclusion =
                if (laneExclusionEnabled && maneuverCandidateOrdinal != null) {
                    scored.filter { it.first.ordinal != maneuverCandidateOrdinal }
                } else {
                    scored
                }
            val laneResultAfterExclusion = detectLaneMask(
                laneScoredAfterExclusion,
                candidates.size,
                laneCandidates.size
            )

            val laneResult = when {
                laneExclusionEnabled && laneResultAfterExclusion != null -> laneResultAfterExclusion
                laneExclusionEnabled -> laneResultBeforeExclusion // fallback
                else -> laneResultBeforeExclusion
            }

            lastDetectedLaneMask = laneResult?.mask
            lastLaneCandidatesInfo = laneResult?.let {
                "source=${it.sourceCandidates}, expanded=${it.expandedCandidates}, raw=${it.rawCandidates}, row=${it.rowCandidates}, slotted=${it.slottedCandidates}, white=${it.whiteCount}, gray=${it.grayCount}, side=${it.whiteSide}, exclusion=${if (laneExclusionEnabled) "on" else "off"}"
            } ?: "source=${candidates.size}, expanded=${laneCandidates.size}, raw=0, row=0, slotted=0, white=0, gray=0, side=none, exclusion=${if (laneExclusionEnabled) "on" else "off"}"
            HudService.navDebug.laneCandidates = lastLaneCandidatesInfo
            HudService.navDebug.laneMask = lastDetectedLaneMask ?: "-"
            HudService.navDebug.laneMaskBeforeExclusion = laneResultBeforeExclusion?.mask ?: "-"
            HudService.navDebug.laneMaskAfterExclusion = laneResultAfterExclusion?.mask ?: "-"
            HudService.navDebug.laneExclusionEnabled = laneExclusionEnabled
            HudService.navDebug.maneuverArrowOrdinal = maneuverCandidateOrdinal

            // Debug top 3 candidates
            scored.take(3).forEachIndexed { i, (candidate, metrics) ->
                DebugLog.d(
                    TAG,
                    "Top${i + 1}: ${candidate.bitmap.width}x${candidate.bitmap.height}, " +
                        "white=${"%.3f".format(metrics.whiteRatio)}, " +
                        "comp=${"%.3f".format(metrics.componentDominance)}, " +
                        "pos=${"%.3f".format(metrics.positionScore)}, " +
                        "score=${"%.3f".format(metrics.totalScore)}"
                )
                saveCandidateDebugBitmap(candidate.bitmap, i + 1, metrics.totalScore)
            }

            val bestBitmap = scored.firstOrNull()?.first?.bitmap

            // Recycle non-selected candidates to avoid bitmap leaks
            for (candidate in laneCandidates) {
                if (candidate.bitmap !== bestBitmap && !candidate.bitmap.isRecycled) {
                    candidate.bitmap.recycle()
                }
            }

            if (bestBitmap != null) {
                val bestMetrics = scored.first().second
                DebugLog.i(
                    TAG,
                    "Selected white arrow: ${bestBitmap.width}x${bestBitmap.height}, " +
                        "white=${"%.3f".format(bestMetrics.whiteRatio)}, score=${"%.3f".format(bestMetrics.totalScore)}"
                )
            }

            return bestBitmap
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to extract bitmap from RemoteViews: ${e.message}")
            e.printStackTrace()
        }

        return null
    }

    private fun expandLaneCandidates(
        source: List<ArrowCandidate>,
        packageName: String
    ): List<ArrowCandidate> {
        val result = mutableListOf<ArrowCandidate>()
        var nextId = 0
        source.forEach { candidate ->
            val splits = splitCompositeLaneBitmap(candidate, packageName)
            if (splits.size > 1) {
                // Original composite candidate is replaced by its segments
                if (!candidate.bitmap.isRecycled) candidate.bitmap.recycle()
                splits.forEach {
                    result.add(it.copy(ordinal = nextId++))
                }
            } else {
                result.add(candidate.copy(ordinal = nextId++))
            }
        }
        return result
    }

    private fun splitCompositeLaneBitmap(
        candidate: ArrowCandidate,
        packageName: String
    ): List<ArrowCandidate> {
        val bmp = candidate.bitmap
if (bmp.width < 80 || bmp.height < 18) return listOf(candidate)
val aspect = bmp.width.toFloat() / bmp.height.toFloat()
if (aspect < 1.6f) return listOf(candidate)

        val (minV, maxS) = when {
            packageName.startsWith("ru.yandex") -> 0.74f to 0.30f
            packageName.contains("google") -> 0.80f to 0.24f
            else -> 0.76f to 0.28f
        }

        val col = IntArray(bmp.width)
        var maxCol = 0
        for (x in 0 until bmp.width) {
            var count = 0
            var y = 0
            while (y < bmp.height) {
                val p = bmp.getPixel(x, y)
                val a = (p ushr 24) and 0xFF
                if (a > 30) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (r + g + b >= 120) {
                        count++
                    }
                }
                y += 1
            }
            col[x] = count
            if (count > maxCol) maxCol = count
        }

        if (maxCol <= 0) return listOf(candidate)
        val activeThreshold = maxOf(1, (maxCol * 0.15f).toInt())
val minSegWidth = maxOf(8, (bmp.width * 0.045f).toInt())
val maxGap = maxOf(4, (bmp.width * 0.03f).toInt())

        val segments = mutableListOf<IntRange>()
        var start = -1
        var gap = 0
        for (x in 0 until bmp.width) {
            val active = col[x] >= activeThreshold
            if (active) {
                if (start < 0) start = x
                gap = 0
            } else if (start >= 0) {
                gap++
                if (gap > maxGap) {
                    val end = x - gap
                    if (end - start + 1 >= minSegWidth) segments.add(start..end)
                    start = -1
                    gap = 0
                }
            }
        }
        if (start >= 0) {
            val end = bmp.width - 1
            if (end - start + 1 >= minSegWidth) segments.add(start..end)
        }

        if (segments.size < 2) return listOf(candidate)

        val out = mutableListOf<ArrowCandidate>()
        val sourceLeft = candidate.bounds.left
        val sourceTop = candidate.bounds.top
        val sourceW = candidate.bounds.width().coerceAtLeast(1)
        val sourceH = candidate.bounds.height().coerceAtLeast(1)

        segments.forEachIndexed { idx, seg ->
            val pad = 2
            val left = (seg.first - pad).coerceAtLeast(0)
            val right = (seg.last + pad).coerceAtMost(bmp.width - 1)
            val w = (right - left + 1).coerceAtLeast(1)
            val sub = android.graphics.Bitmap.createBitmap(bmp, left, 0, w, bmp.height)

            val relLeft = left.toFloat() / bmp.width.toFloat()
            val relRight = (right + 1).toFloat() / bmp.width.toFloat()
            val mappedLeft = sourceLeft + (sourceW * relLeft).toInt()
            val mappedRight = sourceLeft + (sourceW * relRight).toInt()
            val segRect = android.graphics.Rect(
                mappedLeft,
                sourceTop,
                mappedRight.coerceAtLeast(mappedLeft + 1),
                sourceTop + sourceH
            )
            out.add(ArrowCandidate(sub, segRect, idx))
        }

        DebugLog.d(TAG, "Split composite lanes: ${bmp.width}x${bmp.height} -> ${out.size} segments")
        return out
    }

    private fun detectLaneMask(
        scored: List<Pair<ArrowCandidate, ArrowMetrics>>,
        sourceCandidates: Int,
        expandedCandidates: Int
    ): LaneDetectionResult? {
        if (scored.isEmpty()) return null

        val preliminary = scored
            .filter { (_, metrics) ->
                metrics.whiteRatio >= 0.015 ||
                metrics.componentDominance >= 0.08 ||
                metrics.foregroundRatio >= 0.10
            }
            .sortedBy { it.first.bounds.exactCenterX() }

        if (preliminary.isEmpty()) return null

        val medianY = preliminary
            .map { it.first.bounds.exactCenterY() }
            .sorted()
            .let { it[it.size / 2] }
        val medianH = preliminary
            .map { it.first.bounds.height().coerceAtLeast(1) }
            .sorted()
            .let { it[it.size / 2] }
            .toFloat()
        val yTolerance = maxOf(14f, medianH * 0.55f)

        val rowCandidates = preliminary
            .filter { kotlin.math.abs(it.first.bounds.exactCenterY() - medianY) <= yTolerance }
            .ifEmpty { preliminary }

        // Remove near-duplicate candidates by X proximity, keep higher score of the pair
        val deduped = mutableListOf<Pair<ArrowCandidate, ArrowMetrics>>()
        for (candidate in rowCandidates) {
            val prev = deduped.lastOrNull()
            if (prev == null) {
                deduped.add(candidate)
                continue
            }
            val prevX = prev.first.bounds.exactCenterX()
            val curX = candidate.first.bounds.exactCenterX()
            val minWidth = minOf(
                prev.first.bounds.width().coerceAtLeast(1),
                candidate.first.bounds.width().coerceAtLeast(1)
            ).toFloat()
            val duplicateThreshold = maxOf(6f, minWidth * 0.22f)
            if (kotlin.math.abs(curX - prevX) <= duplicateThreshold) {
                if (candidate.second.totalScore > prev.second.totalScore) {
                    deduped[deduped.lastIndex] = candidate
                }
            } else {
                deduped.add(candidate)
            }
        }

        if (deduped.isEmpty()) return null

        val ordered = deduped.sortedBy { it.first.bounds.exactCenterX() }
        val lanesOrdered = if (ordered.size <= 6) {
            ordered
        } else {
    ordered
        .sortedByDescending { it.second.totalScore }
        .take(6)
        .sortedBy { it.first.bounds.exactCenterX() }
            }
            sampled
        }

        val slotted = Array<Pair<ArrowCandidate, ArrowMetrics>?>(6) { null }
        val startSlot = kotlin.math.round((6 - lanesOrdered.size) / 2f)
            .toInt()
            .coerceIn(0, 5)
        lanesOrdered.forEachIndexed { idx, entry ->
            val slot = (startSlot + idx).coerceIn(0, 5)
            slotted[slot] = entry
        }

        val maskChars = CharArray(6) { ' ' }
        var whiteCount = 0
        var grayCount = 0
        val slotMetrics = mutableListOf<Pair<Int, ArrowMetrics>>()
        for (i in slotted.indices) {
            val entry = slotted[i] ?: continue
            val metrics = entry.second
            slotMetrics.add(i to metrics)
        }

        val whiteThreshold = 0.12
        val whiteDominanceThreshold = 0.28
        for ((slotIndex, metrics) in slotMetrics) {
            val isWhite = metrics.whiteRatio >= whiteThreshold ||
                (metrics.whiteRatio >= 0.09 && metrics.componentDominance >= whiteDominanceThreshold)

            if (isWhite) {
                maskChars[slotIndex] = '1'
                whiteCount++
            } else {
                // All other recognized arrows are lane-contour arrows by spec.
                maskChars[slotIndex] = '0'
                grayCount++
            }
        }

        if (whiteCount == 0 && grayCount == 0) return null
        val normalized = String(maskChars)

        val whiteLeftMost = normalized.indexOf('1')
        val whiteRightMost = normalized.lastIndexOf('1')
        val whiteSide = when {
            whiteCount <= 0 -> "none"
            whiteLeftMost < 0 || whiteRightMost < 0 -> "none"
            whiteRightMost <= 2 -> "left"
            whiteLeftMost >= 3 -> "right"
            else -> "mixed"
        }
        val slottedCount = slotted.count { it != null }

        DebugLog.i(
            TAG,
            "Lane detect: mask='$normalized', raw=${scored.size}, row=${rowCandidates.size}, slotted=$slottedCount, white=$whiteCount, gray=$grayCount, whiteLeft=$whiteLeftMost, whiteRight=$whiteRightMost, side=$whiteSide"
        )

        return LaneDetectionResult(
            sourceCandidates = sourceCandidates,
            expandedCandidates = expandedCandidates,
            mask = normalized,
            rawCandidates = scored.size,
            rowCandidates = rowCandidates.size,
            slottedCandidates = slottedCount,
            whiteCount = whiteCount,
            grayCount = grayCount,
            whiteSide = whiteSide
        )
    }

    private fun isLaneExclusionEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_LANE_EXCLUSION, true)
    }

    private fun clearRecognizedArrowsDebug() {
        HudService.navDebug.recognizedArrowBitmaps.forEach { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        HudService.navDebug.recognizedArrowBitmaps.clear()
        HudService.navDebug.maneuverArrowOrdinal = null
    }

    private fun updateRecognizedArrowsDebug(
        scored: List<Pair<ArrowCandidate, ArrowMetrics>>,
        maneuverOrdinal: Int?
    ) {
        clearRecognizedArrowsDebug()
        val preview = scored.take(8).map { it.first }
        preview.forEach { candidate ->
            val src = candidate.bitmap
            if (!src.isRecycled) {
                val thumb = android.graphics.Bitmap.createScaledBitmap(src, 72, 72, true)
                HudService.navDebug.recognizedArrowBitmaps.add(thumb)
            }
        }
        HudService.navDebug.maneuverArrowOrdinal =
            if (maneuverOrdinal == null) null else preview.indexOfFirst { it.ordinal == maneuverOrdinal }.takeIf { it >= 0 }
    }

private fun collectArrowImageCandidates(view: android.view.View, out: MutableList<ArrowCandidate>) {
    if (view is android.widget.ImageView) {
        val drawable = view.drawable
        if (drawable != null) {
            val bitmap = ImageUtils.drawableToBitmap(drawable)
            if (bitmap != null) {
                val width = bitmap.width
                val height = bitmap.height

                if (width > 20 && height > 20 && width < 1200 && height < 600) {
                    val aspectRatio = width.toFloat() / height.toFloat()

                    val acceptable =
                        aspectRatio in 0.35f..5.5f ||
                        (width >= 120 && aspectRatio >= 2.0f)

                    if (acceptable) {
                        val rect = android.graphics.Rect()
                        val hasRect = view.getGlobalVisibleRect(rect)
                        val bounds = if (hasRect) rect else android.graphics.Rect(0, 0, 0, 0)

                        DebugLog.d(
                            TAG,
                            "Found arrow/lane candidate: ${width}x${height}, ratio=${"%.2f".format(aspectRatio)} @ $bounds"
                        )

                        out.add(ArrowCandidate(bitmap, bounds, out.size))
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                } else {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            collectArrowImageCandidates(view.getChildAt(i), out)
        }
    }
}

        // Recursively search children
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                collectArrowImageCandidates(view.getChildAt(i), out)
            }
        }
    }

    private fun calculateArrowMetrics(candidate: ArrowCandidate, packageName: String): ArrowMetrics {
        val bitmap = candidate.bitmap
        val (minV, maxS) = when {
            packageName.startsWith("ru.yandex") -> 0.78f to 0.26f
            packageName.contains("google") -> 0.82f to 0.20f
            else -> 0.80f to 0.22f
        }

        val step = if (bitmap.width * bitmap.height > 120_000) 2 else 1
        val whiteMask = Array((bitmap.height + step - 1) / step) { BooleanArray((bitmap.width + step - 1) / step) }

        var whitePixels = 0
        var opaquePixels = 0
        var foregroundPixels = 0

        var yi = 0
        var y = 0
        while (y < bitmap.height) {
            var xi = 0
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val a = (p ushr 24) and 0xff
                if (a > 30) {
                    opaquePixels++
                    val r = (p shr 16) and 0xff
                    val g = (p shr 8) and 0xff
                    val b = p and 0xff
                    if (r + g + b >= 120) {
                        foregroundPixels++
                    }
                    if (isWhitePixelHsv(p, minV, maxS)) {
                        whiteMask[yi][xi] = true
                        whitePixels++
                    }
                }
                xi++
                x += step
            }
            yi++
            y += step
        }

        val whiteRatio = if (opaquePixels == 0) 0.0 else whitePixels.toDouble() / opaquePixels.toDouble()
        val foregroundRatio = if (opaquePixels == 0) 0.0 else foregroundPixels.toDouble() / opaquePixels.toDouble()
        val componentDominance = calculateLargestComponentDominance(whiteMask, whitePixels)

        val minSide = minOf(bitmap.width, bitmap.height).toDouble()
        val sizeScore = (minSide / 220.0).coerceIn(0.0, 1.0)
        val aspectRatio = bitmap.width.toDouble() / bitmap.height.toDouble()
        val aspectPenalty = kotlin.math.abs(aspectRatio - 1.0).coerceAtMost(1.0)

        val positionScore = calculatePositionScore(candidate.bounds, packageName)

        val totalScore =
            (whiteRatio * 0.55) +
            (componentDominance * 0.25) +
            (positionScore * 0.20) +
            (foregroundRatio * 0.10) +
            (sizeScore * 0.12) -
            (aspectPenalty * 0.12)

        return ArrowMetrics(
            whiteRatio = whiteRatio,
            foregroundRatio = foregroundRatio,
            componentDominance = componentDominance,
            sizeScore = sizeScore,
            aspectPenalty = aspectPenalty,
            positionScore = positionScore,
            totalScore = totalScore
        )
    }

    private fun isWhitePixelHsv(pixel: Int, minV: Float, maxS: Float): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(pixel, hsv)
        val value = hsv[2]
        val saturation = hsv[1]
        return value >= minV && saturation <= maxS
    }

    private fun calculateLargestComponentDominance(mask: Array<BooleanArray>, whitePixels: Int): Double {
        if (whitePixels <= 0) return 0.0

        val h = mask.size
        val w = if (h == 0) 0 else mask[0].size
        if (w == 0) return 0.0

        val visited = Array(h) { BooleanArray(w) }
        var largest = 0

        val qx = IntArray(h * w)
        val qy = IntArray(h * w)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y][x] || visited[y][x]) continue

                var head = 0
                var tail = 0
                qx[tail] = x
                qy[tail] = y
                tail++
                visited[y][x] = true

                var size = 0
                while (head < tail) {
                    val cx = qx[head]
                    val cy = qy[head]
                    head++
                    size++

                    val neighbors = arrayOf(
                        cx - 1 to cy,
                        cx + 1 to cy,
                        cx to cy - 1,
                        cx to cy + 1
                    )

                    for ((nx, ny) in neighbors) {
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        if (!mask[ny][nx] || visited[ny][nx]) continue
                        visited[ny][nx] = true
                        qx[tail] = nx
                        qy[tail] = ny
                        tail++
                    }
                }

                if (size > largest) largest = size
            }
        }

        return largest.toDouble() / whitePixels.toDouble()
    }

    private fun calculatePositionScore(bounds: android.graphics.Rect, packageName: String): Double {
        if (bounds.width() <= 0 || bounds.height() <= 0) return 0.5

        // Soft priors by app: active maneuver icon is usually in top area.
        val centerY = bounds.exactCenterY()
        val height = bounds.bottom.toFloat().coerceAtLeast(1f)
        val yRatio = (centerY / height).coerceIn(0f, 1f)

        val preferred = when {
            packageName.startsWith("ru.yandex") -> 0.22f
            packageName.contains("google") -> 0.28f
            else -> 0.30f
        }

        val distance = kotlin.math.abs(yRatio - preferred)
        return (1.0 - (distance * 2.0)).coerceIn(0.0, 1.0)
    }

    private fun saveCandidateDebugBitmap(bitmap: android.graphics.Bitmap, rank: Int, score: Double) {
        try {
            val dir = java.io.File(getExternalFilesDir(null), "debug_arrow_candidates")
            if (!dir.exists()) dir.mkdirs()

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                .format(java.util.Date())
            val file = java.io.File(dir, "cand_${timestamp}_r${rank}_s${"%.3f".format(score)}.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
            // Ignore debug saving errors
        }
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
