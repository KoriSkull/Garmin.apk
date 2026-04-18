
package iMel9i.garminhud.lite

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlin.math.abs
import kotlin.math.min

class NavigationNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NavNotifListener"
        private const val PREFS_NAME = "HudPrefs"

        var instance: NavigationNotificationListener? = null
        var onNavigationUpdate: ((NavigationData) -> Unit)? = null
        var enabled = true
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

    private data class ArrowCandidate(
        val bitmap: android.graphics.Bitmap,
        val bounds: android.graphics.Rect,
        val ordinal: Int,
        val syntheticBounds: Boolean = false
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

    private data class LogicalLane(
        val candidate: ArrowCandidate,
        val metrics: ArrowMetrics,
        val whitenessScore: Double
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

        if (packageName.contains("yandex") || packageName.contains("maps") || packageName.contains("nav")) {
            DebugLog.i(TAG, "Notification received from: $packageName")
        }

        if (!enabled) return

        if (packageName.startsWith("ru.yandex")) {
            val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
            val yandexEnabled = prefs.getBoolean("yandex_notifications_enabled", true)
            if (!yandexEnabled) {
                DebugLog.d(TAG, "Yandex notifications disabled, skipping")
                return
            }
        }

        var config = configManager.getConfigs().find { it.packageName == packageName && it.enabled }

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
            onNavigationUpdate?.invoke(NavigationData(isNavigating = false))
            lastDetectedLaneMask = null
            HudState.laneAssist = null
            HudService.navDebug.laneMask = "-"
            HudState.notifyUpdate()
        }
    }

    private fun parseNotification(sbn: StatusBarNotification, config: AppConfigManager.AppConfig) {
        val notification = sbn.notification
        val extras = notification.extras

        val allExtras = mutableMapOf<String, String>()
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            allExtras[key] = value?.toString() ?: "null"
        }

        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()

        HudService.navDebug.packageName = sbn.packageName
        HudService.navDebug.title = title ?: ""
        HudService.navDebug.text = text ?: ""
        HudService.navDebug.bigText = bigText ?: ""
        HudService.navDebug.lastUpdateTime =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

        HudState.lastPackageName = sbn.packageName
        HudState.rawData.clear()
        HudState.rawData.putAll(allExtras)

        val distanceKey = config.fields[HudDataType.DISTANCE_TO_TURN.name]
        var distance: String? = null
        if (distanceKey != null) {
            val rawDist = extras.getCharSequence(distanceKey)?.toString()
            distance = extractDistance(rawDist)
        }
        if (distance == null) {
            distance = extractDistance(title) ?: extractDistance(text) ?: extractDistance(bigText)
        }

        val instructionKey = config.fields[HudDataType.NAVIGATION_INSTRUCTION.name]
        var instruction: String? = null
        if (instructionKey != null) {
            instruction = extras.getCharSequence(instructionKey)?.toString()
        }
        if (instruction == null) {
            instruction = title ?: text
        }

        val etaKey = config.fields[HudDataType.ETA.name]
        var eta: String? = null
        if (etaKey != null) {
            eta = extras.getCharSequence(etaKey)?.toString()
        }
        if (eta == null) {
            eta = text
        }

        val timeKey = config.fields[HudDataType.REMAINING_TIME.name]
        var remainingTime: String? = null
        if (timeKey != null) {
            remainingTime = extras.getCharSequence(timeKey)?.toString()
        }

        val trafficKey = config.fields[HudDataType.TRAFFIC_SCORE.name]
        var trafficScore: Int? = null
        if (trafficKey != null) {
            val rawTraffic = extras.getCharSequence(trafficKey)?.toString()
            trafficScore = rawTraffic?.toIntOrNull()
        }

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

        val largeIcon = extras.getParcelable<android.graphics.Bitmap>("android.largeIcon")
            ?: (extras.getParcelable<android.graphics.drawable.Icon>("android.largeIcon")
                ?.loadDrawable(this)?.let { ImageUtils.drawableToBitmap(it) })

        val picture = extras.getParcelable<android.graphics.Bitmap>("android.picture")

        var arrowBitmap: android.graphics.Bitmap? = null
        lastDetectedLaneMask = null
        lastLaneCandidatesInfo = "-"
        clearRecognizedArrowsDebug()
        HudService.navDebug.laneCandidates = "-"
        HudService.navDebug.laneMask = "-"
        HudService.navDebug.laneMaskBeforeExclusion = "-"
        HudService.navDebug.laneMaskAfterExclusion = "-"
        HudService.navDebug.laneExclusionEnabled = false

        if (largeIcon != null) {
            arrowBitmap = largeIcon
        } else if (picture != null) {
            arrowBitmap = picture
        } else {
            arrowBitmap = extractBitmapFromRemoteViews(notification, sbn.packageName)
        }

        if (arrowBitmap != null) {
            HudService.navDebug.lastArrowBitmap = arrowBitmap
            val arrowImage = ArrowImage(arrowBitmap)
            val hash = arrowImage.getArrowValue()

            val arrow = ArrowDirection.recognize(arrowImage)
            if (arrow != ArrowDirection.NONE) {
                HudState.turnIcon = arrow.hudCode
                HudService.navDebug.arrowStatus = "Recognized: ${arrow.name} ($hash)"
            } else {
                HudService.navDebug.arrowStatus = "Not Recognized ($hash)"
            }
        } else {
            HudService.navDebug.lastArrowBitmap = null
            if (instruction != null) {
                val arrow = parseTextToArrow(instruction)
                if (arrow != ArrowDirection.NONE) {
                    HudState.turnIcon = arrow.hudCode
                }
            }
        }

        HudState.laneAssist = lastDetectedLaneMask
        HudService.navDebug.laneCandidates = lastLaneCandidatesInfo
        HudService.navDebug.laneMask = lastDetectedLaneMask ?: "-"
        HudState.notifyUpdate()

        HudService.navDebug.parsedInstruction = instruction ?: ""
        HudService.navDebug.parsedDistance = distance ?: ""
        HudService.navDebug.parsedEta = eta ?: ""

        onNavigationUpdate?.invoke(navData)
        HudState.notifyUpdate()
    }

    private fun parseTextToArrow(text: String): ArrowDirection {
        val t = text.lowercase()
        return when {
            "u-turn" in t || "разворот" in t -> ArrowDirection.SHARP_LEFT
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

    private fun extractBitmapFromRemoteViews(
        notification: android.app.Notification,
        packageName: String
    ): android.graphics.Bitmap? {
        try {
            val views = notification.bigContentView ?: notification.contentView ?: return null
            val inflatedView = views.apply(this, null)

            val candidates = mutableListOf<ArrowCandidate>()
            collectArrowImageCandidates(inflatedView, candidates)

            if (candidates.isEmpty()) {
                lastDetectedLaneMask = null
                HudState.laneAssist = null
                HudService.navDebug.laneMask = "-"
                HudState.notifyUpdate()
                return null
            }

            val wideLaneSource = candidates
                .filter {
                    val aspect = it.bitmap.width.toFloat() / it.bitmap.height.toFloat()
                    it.bitmap.width >= 120 && aspect >= 1.6f
                }
                .maxByOrNull { it.bitmap.width }

            val laneCandidates = if (wideLaneSource != null) {
                splitCompositeLaneBitmap(wideLaneSource, packageName)
            } else {
                expandLaneCandidates(candidates, packageName)
            }

            val arrowCandidates = candidates.filter {
                val aspect = it.bitmap.width.toFloat() / it.bitmap.height.toFloat()
                aspect in 0.45f..1.45f
            }

            val scored = arrowCandidates.map { candidate ->
                candidate to calculateArrowMetrics(candidate, packageName)
            }.sortedByDescending { it.second.totalScore }

            val laneScored = laneCandidates.map { candidate ->
                candidate to calculateArrowMetrics(candidate, packageName)
            }.sortedByDescending { it.second.totalScore }

            val maneuverCandidateOrdinal = scored.firstOrNull()?.first?.ordinal
            updateRecognizedArrowsDebug(scored, maneuverCandidateOrdinal)

            val laneResult = detectLaneMask(laneScored, candidates.size, laneCandidates.size)

            lastDetectedLaneMask = laneResult?.mask
            lastLaneCandidatesInfo = laneResult?.let {
                "source=${it.sourceCandidates}, expanded=${it.expandedCandidates}, raw=${it.rawCandidates}, row=${it.rowCandidates}, slotted=${it.slottedCandidates}, white=${it.whiteCount}, gray=${it.grayCount}, side=${it.whiteSide}"
            } ?: "source=${candidates.size}, expanded=${laneCandidates.size}, raw=0, row=0, slotted=0, white=0, gray=0, side=none"

            HudState.laneAssist = lastDetectedLaneMask
            HudService.navDebug.laneCandidates = lastLaneCandidatesInfo
            HudService.navDebug.laneMask = lastDetectedLaneMask ?: "-"
            HudService.navDebug.laneMaskBeforeExclusion = lastDetectedLaneMask ?: "-"
            HudService.navDebug.laneMaskAfterExclusion = lastDetectedLaneMask ?: "-"
            HudService.navDebug.laneExclusionEnabled = false
            HudState.notifyUpdate()

            val bestBitmap = scored.firstOrNull()?.first?.bitmap
            if (bestBitmap != null) {
                val config = bestBitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888
                return bestBitmap.copy(config, false)
            }

            return null
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to extract bitmap from RemoteViews: ${e.message}")
            e.printStackTrace()
        }

        lastDetectedLaneMask = null
        HudState.laneAssist = null
        HudService.navDebug.laneMask = "-"
        HudState.notifyUpdate()
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
                splits.forEach { result.add(it.copy(ordinal = nextId++)) }
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
        if (bmp.width < 50 || bmp.height < 14) return listOf(candidate)

        val aspect = bmp.width.toFloat() / bmp.height.toFloat()
        if (aspect < 1.25f) return listOf(candidate)

        val sourceLeft = candidate.bounds.left
        val sourceTop = candidate.bounds.top
        val sourceW = candidate.bounds.width().coerceAtLeast(1)
        val sourceH = candidate.bounds.height().coerceAtLeast(1)

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

        val activeThreshold = Math.max(1, (maxCol * 0.08f).toInt())
        val minSegWidth = Math.max(4, (bmp.width * 0.02f).toInt())
        val maxGap = Math.max(6, (bmp.width * 0.05f).toInt())

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

        if (segments.size < 2) {
            val fallbackAllowed =
                bmp.width >= 180 &&
                    bmp.height >= 18 &&
                    (bmp.width.toFloat() / bmp.height.toFloat()) >= 2.2f

            if (!fallbackAllowed) {
                return listOf(candidate)
            }

            val estimatedLanes = 3
            val partWidth = bmp.width / estimatedLanes
            if (partWidth >= 8) {
                val fallbackOut = mutableListOf<ArrowCandidate>()

                for (idx in 0 until estimatedLanes) {
                    val left = idx * partWidth
                    val right = if (idx == estimatedLanes - 1) bmp.width else (idx + 1) * partWidth
                    val w = Math.max(right - left, 1)

                    val sub = android.graphics.Bitmap.createBitmap(bmp, left, 0, w, bmp.height)

                    val relLeft = left.toFloat() / bmp.width.toFloat()
                    val relRight = right.toFloat() / bmp.width.toFloat()
                    val mappedLeft = sourceLeft + (sourceW * relLeft).toInt()
                    val mappedRight = sourceLeft + (sourceW * relRight).toInt()

                    val segRect = android.graphics.Rect(
                        mappedLeft,
                        sourceTop,
                        Math.max(mappedRight, mappedLeft + 1),
                        sourceTop + sourceH
                    )

                    fallbackOut.add(
                        ArrowCandidate(
                            bitmap = sub,
                            bounds = segRect,
                            ordinal = idx,
                            syntheticBounds = candidate.syntheticBounds
                        )
                    )
                }

                return fallbackOut
            }

            return listOf(candidate)
        }

        val out = mutableListOf<ArrowCandidate>()

        segments.forEachIndexed { idx, seg ->
            val pad = 2
            val left = Math.max(seg.first - pad, 0)
            val right = Math.min(seg.last + pad, bmp.width - 1)
            val w = Math.max(right - left + 1, 1)

            val sub = android.graphics.Bitmap.createBitmap(bmp, left, 0, w, bmp.height)

            val relLeft = left.toFloat() / bmp.width.toFloat()
            val relRight = (right + 1).toFloat() / bmp.width.toFloat()
            val mappedLeft = sourceLeft + (sourceW * relLeft).toInt()
            val mappedRight = sourceLeft + (sourceW * relRight).toInt()

            val segRect = android.graphics.Rect(
                mappedLeft,
                sourceTop,
                Math.max(mappedRight, mappedLeft + 1),
                sourceTop + sourceH
            )

            out.add(
                ArrowCandidate(
                    bitmap = sub,
                    bounds = segRect,
                    ordinal = idx,
                    syntheticBounds = candidate.syntheticBounds
                )
            )
        }

        return out
    }

    private fun detectLaneMask(
        scored: List<Pair<ArrowCandidate, ArrowMetrics>>,
        sourceCandidates: Int,
        expandedCandidates: Int
    ): LaneDetectionResult? {
        if (scored.isEmpty()) return null

        val preliminary = scored.sortedBy { it.first.bounds.exactCenterX() }
        if (preliminary.isEmpty()) return null

        val medianY = preliminary.map { it.first.bounds.exactCenterY() }.sorted().let { it[it.size / 2] }
        val medianH = preliminary.map { Math.max(it.first.bounds.height(), 1) }.sorted().let { it[it.size / 2] }.toFloat()
        val yTolerance = Math.max(14f, medianH * 0.55f)

        val rowCandidates = preliminary
            .filter { abs(it.first.bounds.exactCenterY() - medianY) <= yTolerance }
            .ifEmpty { preliminary }

        val orderedForDedup = rowCandidates.sortedBy { it.first.bounds.exactCenterX() }

        val deduped = mutableListOf<Pair<ArrowCandidate, ArrowMetrics>>()
        var i = 0
        while (i < orderedForDedup.size) {
            val current = orderedForDedup[i]

            if (i + 1 < orderedForDedup.size) {
                val next = orderedForDedup[i + 1]
                val currentX = current.first.bounds.exactCenterX()
                val nextX = next.first.bounds.exactCenterX()
                val distanceX = abs(nextX - currentX)

                if (distanceX <= 120f) {
                    val chosen = if (current.second.totalScore >= next.second.totalScore) current else next
                    deduped.add(chosen)
                    i += 2
                    continue
                }
            }

            deduped.add(current)
            i += 1
        }

        if (deduped.isEmpty()) return null

        val logicalLanes = deduped.sortedBy { it.first.bounds.exactCenterX() }
        if (logicalLanes.isEmpty()) return null

        val lanes = logicalLanes.map { (candidate, metrics) ->
            LogicalLane(
                candidate = candidate,
                metrics = metrics,
                whitenessScore = metrics.whiteRatio + metrics.componentDominance * 0.35
            )
        }

        val sortedByWhiteness = lanes.sortedByDescending { it.whitenessScore }
        val bestScore = sortedByWhiteness.first().whitenessScore
        val activeThreshold = bestScore * 0.82

        val laneValues = mutableListOf<Char>()
        var whiteCount = 0
        var grayCount = 0

        for (lane in lanes) {
            val isActive = lane.whitenessScore >= activeThreshold
            val value = if (isActive) '1' else '0'
            laneValues += value
            if (isActive) whiteCount++ else grayCount++
        }

        if (whiteCount > 2) {
            val keepActive = sortedByWhiteness.take(2).map { it.candidate.ordinal }.toSet()
            laneValues.clear()
            whiteCount = 0
            grayCount = 0

            for (lane in lanes) {
                val isActive = lane.candidate.ordinal in keepActive
                val value = if (isActive) '1' else '0'
                laneValues += value
                if (isActive) whiteCount++ else grayCount++
            }
        }

        val normalized = placeLaneValuesIntoMask(laneValues)

        return LaneDetectionResult(
            sourceCandidates = sourceCandidates,
            expandedCandidates = expandedCandidates,
            mask = normalized,
            rawCandidates = scored.size,
            rowCandidates = rowCandidates.size,
            slottedCandidates = min(laneValues.size, 6),
            whiteCount = whiteCount,
            grayCount = grayCount,
            whiteSide = when {
                whiteCount <= 0 -> "none"
                normalized.lastIndexOf('1') <= 2 -> "left"
                normalized.indexOf('1') >= 3 -> "right"
                else -> "mixed"
            }
        )
    }

    private fun placeLaneValuesIntoMask(laneValues: List<Char>): String {
        val mask = CharArray(6) { ' ' }
        val n = laneValues.size.coerceIn(0, 6)
        if (n == 0) return String(mask)

        val start = when (n) {
            1 -> 2
            2 -> 2
            3 -> 1
            4 -> 1
            5 -> 0
            else -> 0
        }

        for (i in 0 until n) {
            mask[start + i] = if (laneValues[i] == '1') '1' else '0'
        }

        return String(mask)
    }

    private fun clearRecognizedArrowsDebug() {
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

                            val useRealBounds = hasRect && rect.width() > 0 && rect.height() > 0
                            val bounds = if (useRealBounds) {
                                rect
                            } else {
                                val syntheticLeft = out.size * 100
                                android.graphics.Rect(
                                    syntheticLeft,
                                    0,
                                    syntheticLeft + Math.max(width, 1),
                                    Math.max(height, 1)
                                )
                            }

                            out.add(
                                ArrowCandidate(
                                    bitmap = bitmap,
                                    bounds = bounds,
                                    ordinal = out.size,
                                    syntheticBounds = !useRealBounds
                                )
                            )
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

    private fun calculateArrowMetrics(candidate: ArrowCandidate, packageName: String): ArrowMetrics {
        val bitmap = candidate.bitmap

        val minV: Float
        val maxS: Float
        when {
            packageName.startsWith("ru.yandex") -> {
                minV = 0.78f
                maxS = 0.26f
            }
            packageName.contains("google") -> {
                minV = 0.82f
                maxS = 0.20f
            }
            else -> {
                minV = 0.80f
                maxS = 0.22f
            }
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

        val minSide = Math.min(bitmap.width, bitmap.height).toDouble()
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
        return hsv[2] >= minV && hsv[1] <= maxS
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

    private fun extractDistance(text: String?): String? {
        if (text == null) return null
        val distanceRegex = """(\d+(?:[.,]\d+)?)\s*(m|м|km|км)""".toRegex(RegexOption.IGNORE_CASE)
        return distanceRegex.find(text)?.value
    }
}
