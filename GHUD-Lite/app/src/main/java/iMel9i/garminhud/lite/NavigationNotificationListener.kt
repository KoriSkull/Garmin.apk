package iMel9i.garminhud.lite

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlin.math.abs
import kotlin.math.round

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
        }
    }

    private fun parseNotification(sbn: StatusBarNotification, config: AppConfigManager.AppConfig) {
        val notification = sbn.notification
        val extras = notification.extras

        DebugLog.i(TAG, "=== PARSING NOTIFICATION FROM ${sbn.packageName} ===")

        val allExtras = mutableMapOf<String, String>()
        extras.keySet().forEach { key ->
            val value = extras.get(key)
            allExtras[key] = value?.toString() ?: "null"
            DebugLog.d(TAG, "  Extra: $key = $value")
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
            Log.d(TAG, "Found LargeIcon in notification: ${largeIcon.width}x${largeIcon.height}")
            arrowBitmap = largeIcon
        } else if (picture != null) {
            Log.d(TAG, "Found Picture in notification: ${picture.width}x${picture.height}")
            arrowBitmap = picture
        } else {
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

            DebugLog.i(TAG, "Attempting to extract bitmap from RemoteViews by applying to View")

            val inflatedView = views.apply(this, null)

            val candidates = mutableListOf<ArrowCandidate>()
            collectArrowImageCandidates(inflatedView, candidates)

            if (candidates.isEmpty()) {
                DebugLog.w(TAG, "Could not find arrow ImageView in notification")
                return null
            }

            DebugLog.i(TAG, "Arrow candidates found: ${candidates.size}")
            candidates.forEachIndexed { index, candidate ->
                val aspect = candidate.bitmap.width.toFloat() / candidate.bitmap.height.toFloat()
                DebugLog.i(
                    TAG,
                    "Candidate[$index]: bmp=${candidate.bitmap.width}x${candidate.bitmap.height}, " +
                        "bounds=${candidate.bounds}, aspect=${"%.2f".format(aspect)}, synthetic=${candidate.syntheticBounds}"
                )
            }

            val wideLaneSource = candidates
                .filter {
                    val aspect = it.bitmap.width.toFloat() / it.bitmap.height.toFloat()
                    it.bitmap.width >= 120 && aspect >= 1.6f
                }
                .maxByOrNull { it.bitmap.width }

            val laneCandidates = if (wideLaneSource != null) {
                DebugLog.i(
                    TAG,
                    "Lane source selected: ${wideLaneSource.bitmap.width}x${wideLaneSource.bitmap.height}, bounds=${wideLaneSource.bounds}"
                )
                splitCompositeLaneBitmap(wideLaneSource, packageName)
            } else {
                DebugLog.i(TAG, "Lane source fallback: using expanded general candidates")
                expandLaneCandidates(candidates, packageName)
            }

            DebugLog.i(TAG, "Expanded lane candidates: ${laneCandidates.size}")

            val arrowCandidates = candidates.filter {
                val aspect = it.bitmap.width.toFloat() / it.bitmap.height.toFloat()
                aspect in 0.45f..1.45f
            }

            DebugLog.i(TAG, "Arrow candidates for maneuver arrow: ${arrowCandidates.size}")

            val scored = arrowCandidates.map { candidate ->
                candidate to calculateArrowMetrics(candidate, packageName)
            }.sortedByDescending { it.second.totalScore }

            scored.forEachIndexed { index, (candidate, metrics) ->
                DebugLog.i(
                    TAG,
                    "ScoredArrow[$index]: bmp=${candidate.bitmap.width}x${candidate.bitmap.height}, " +
                        "bounds=${candidate.bounds}, white=${"%.3f".format(metrics.whiteRatio)}, " +
                        "fg=${"%.3f".format(metrics.foregroundRatio)}, comp=${"%.3f".format(metrics.componentDominance)}, " +
                        "score=${"%.3f".format(metrics.totalScore)}"
                )
            }

            val laneScored = laneCandidates.map { candidate ->
                candidate to calculateArrowMetrics(candidate, packageName)
            }.sortedByDescending { it.second.totalScore }

            val maneuverCandidateOrdinal = scored.firstOrNull()?.first?.ordinal
            updateRecognizedArrowsDebug(scored, maneuverCandidateOrdinal)

            val laneResultBeforeExclusion = detectLaneMask(laneScored, candidates.size, laneCandidates.size)
            val laneResultAfterExclusion = laneResultBeforeExclusion
            val laneResult = laneResultBeforeExclusion

            lastDetectedLaneMask = laneResult?.mask
            lastLaneCandidatesInfo = laneResult?.let {
                "source=${it.sourceCandidates}, expanded=${it.expandedCandidates}, raw=${it.rawCandidates}, row=${it.rowCandidates}, slotted=${it.slottedCandidates}, white=${it.whiteCount}, gray=${it.grayCount}, side=${it.whiteSide}, exclusion=forced_off"
            } ?: "source=${candidates.size}, expanded=${laneCandidates.size}, raw=0, row=0, slotted=0, white=0, gray=0, side=none, exclusion=forced_off"

            HudService.navDebug.laneCandidates = lastLaneCandidatesInfo
            HudService.navDebug.laneMask = lastDetectedLaneMask ?: "-"
            HudService.navDebug.laneMaskBeforeExclusion = laneResultBeforeExclusion?.mask ?: "-"
            HudService.navDebug.laneMaskAfterExclusion = laneResultAfterExclusion?.mask ?: "-"
            HudService.navDebug.laneExclusionEnabled = false
            HudService.navDebug.maneuverArrowOrdinal = null

            scored.take(3).forEachIndexed { i, (candidate, metrics) ->
                DebugLog.d(
                    TAG,
                    "TopArrow${i + 1}: ${candidate.bitmap.width}x${candidate.bitmap.height}, " +
                        "white=${"%.3f".format(metrics.whiteRatio)}, " +
                        "comp=${"%.3f".format(metrics.componentDominance)}, " +
                        "pos=${"%.3f".format(metrics.positionScore)}, " +
                        "score=${"%.3f".format(metrics.totalScore)}"
                )
                saveCandidateDebugBitmap(candidate.bitmap, i + 1, metrics.totalScore)
            }

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
                if (isArrowForegroundPixel(p, alphaThreshold = 30)) {
                    count++
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

            out.add(
                ArrowCandidate(
                    bitmap = sub,
                    bounds = segRect,
                    ordinal = idx,
                    syntheticBounds = candidate.syntheticBounds
                )
            )
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

        val preliminary = scored.sortedBy { it.first.bounds.exactCenterX() }
        DebugLog.i(TAG, "Lane detect: scored=${scored.size}, preliminary=${preliminary.size}")
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
            .filter { abs(it.first.bounds.exactCenterY() - medianY) <= yTolerance }
            .ifEmpty { preliminary }

        DebugLog.i(TAG, "Lane detect: rowCandidates=${rowCandidates.size}, medianY=$medianY, yTolerance=$yTolerance")

        rowCandidates.forEachIndexed { index, (candidate, metrics) ->
            DebugLog.i(
                TAG,
                "Row[$index]: bounds=${candidate.bounds}, centerX=${candidate.bounds.exactCenterX()}, " +
                    "centerY=${candidate.bounds.exactCenterY()}, width=${candidate.bounds.width()}, " +
                    "white=${"%.3f".format(metrics.whiteRatio)}, fg=${"%.3f".format(metrics.foregroundRatio)}, " +
                    "comp=${"%.3f".format(metrics.componentDominance)}, score=${"%.3f".format(metrics.totalScore)}"
            )
        }

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

                    DebugLog.i(TAG, "Lane dedup pair: i=$i, distanceX=$distanceX -> merged")
                    i += 2
                    continue
                }
            }

            deduped.add(current)
            i += 1
        }

        DebugLog.i(TAG, "Lane detect: deduped=${deduped.size}")
        if (deduped.isEmpty()) return null

        val ordered = deduped.sortedBy { it.first.bounds.exactCenterX() }
        if (ordered.isEmpty()) return null

        val capped = if (ordered.size <= 6) {
            ordered
        } else {
            ordered
                .sortedByDescending { it.second.totalScore }
                .take(6)
                .sortedBy { it.first.bounds.exactCenterX() }
        }

        val slotted = Array<Pair<ArrowCandidate, ArrowMetrics>?>(6) { null }
        val laneCount = capped.size.coerceAtMost(6)
        val startSlot = ((6 - laneCount) / 2).coerceAtLeast(0)

        for (slotIndex in 0 until laneCount) {
            slotted[startSlot + slotIndex] = capped[slotIndex]
        }

        DebugLog.i(TAG, "Lane slotting: ordered=${ordered.size}, capped=${capped.size}, startSlot=$startSlot")
        capped.forEachIndexed { idx, entry ->
            DebugLog.i(
                TAG,
                "Lane ordered[$idx]: bounds=${entry.first.bounds}, centerX=${entry.first.bounds.exactCenterX()}, score=${"%.3f".format(entry.second.totalScore)}"
            )
        }

        for (slotIndex in slotted.indices) {
            val entry = slotted[slotIndex]
            if (entry == null) {
                DebugLog.d(TAG, "Lane slot[$slotIndex]: empty")
            } else {
                val b = entry.first.bounds
                val m = entry.second
                DebugLog.d(
                    TAG,
                    "Lane slot[$slotIndex]: bounds=$b, white=${"%.3f".format(m.whiteRatio)}, " +
                        "fg=${"%.3f".format(m.foregroundRatio)}, comp=${"%.3f".format(m.componentDominance)}, " +
                        "score=${"%.3f".format(m.totalScore)}"
                )
            }
        }

        val maskChars = CharArray(6) { ' ' }
        var whiteCount = 0
        var grayCount = 0
        val slotMetrics = mutableListOf<Pair<Int, ArrowMetrics>>()

        for (slotIndex in slotted.indices) {
            val entry = slotted[slotIndex] ?: continue
            val metrics = entry.second
            slotMetrics.add(slotIndex to metrics)
        }

        if (slotMetrics.isNotEmpty()) {
            val sortedByWhiteness = slotMetrics.sortedByDescending { (_, metrics) ->
                metrics.whiteRatio + (metrics.componentDominance * 0.35)
            }

            val bestScore = sortedByWhiteness.first().second.whiteRatio +
                (sortedByWhiteness.first().second.componentDominance * 0.35)

            val activeThreshold = bestScore * 0.82

            for ((slotIndex, metrics) in slotMetrics) {
                val whitenessScore = metrics.whiteRatio + (metrics.componentDominance * 0.35)
                val isActive = whitenessScore >= activeThreshold

                if (isActive) {
                    maskChars[slotIndex] = '1'
                    whiteCount++
                } else {
                    maskChars[slotIndex] = '0'
                    grayCount++
                }

                DebugLog.d(
                    TAG,
                    "Lane classify: slot=$slotIndex, white=${"%.3f".format(metrics.whiteRatio)}, " +
                        "comp=${"%.3f".format(metrics.componentDominance)}, " +
                        "score=${"%.3f".format(whitenessScore)}, activeThreshold=${"%.3f".format(activeThreshold)}, " +
                        "isActive=$isActive"
                )
            }
        }

        if (whiteCount > 2) {
            val ranked = slotMetrics
                .map { (slotIndex, metrics) ->
                    slotIndex to (metrics.whiteRatio + metrics.componentDominance * 0.35)
                }
                .sortedByDescending { it.second }

            val keepActive = ranked.take(2).map { it.first }.toSet()

            whiteCount = 0
            grayCount = 0

            for ((slotIndex, _) in slotMetrics) {
                if (slotIndex in keepActive) {
                    maskChars[slotIndex] = '1'
                    whiteCount++
                } else {
                    maskChars[slotIndex] = '0'
                    grayCount++
                }
            }

            DebugLog.i(TAG, "Lane classify: reduced active lanes to top-2")
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

                        val arrowLike = if (acceptable) {
                            runCatching { isArrowLikeFast(bitmap) }
                                .onFailure { err ->
                                    DebugLog.e(TAG, "Arrow-shape filter failed: ${err.message}")
                                }
                                .getOrDefault(true) // fail-open to avoid dropping all arrows/crashing flow
                        } else {
                            false
                        }

                        if (arrowLike) {
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
                                    syntheticLeft + width.coerceAtLeast(1),
                                    height.coerceAtLeast(1)
                                )
                            }

                            DebugLog.d(
                                TAG,
                                "Candidate bounds resolved: bmp=${width}x${height}, bounds=$bounds, globalRectValid=$useRealBounds"
                            )

                            out.add(
                                ArrowCandidate(
                                    bitmap = bitmap,
                                    bounds = bounds,
                                    ordinal = out.size,
                                    syntheticBounds = !useRealBounds
                                )
                            )
                        } else {
                            DebugLog.d(
                                TAG,
                                "Candidate rejected by arrow-shape filter: bmp=${width}x${height}, aspect=${"%.2f".format(aspectRatio)}"
                            )
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

    private fun isArrowLikeFast(bitmap: android.graphics.Bitmap): Boolean {
        val maxSide = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val adaptiveStep = (maxSide / 160).coerceAtLeast(1)
        val step = if (bitmap.width * bitmap.height > 160_000) maxOf(2, adaptiveStep) else adaptiveStep
        val h = (bitmap.height + step - 1) / step
        val w = (bitmap.width + step - 1) / step
        if (w <= 2 || h <= 2) return false

        val mask = Array(h) { BooleanArray(w) }
        var fg = 0
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        var yi = 0
        var y = 0
        while (y < bitmap.height) {
            var xi = 0
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (isArrowForegroundPixel(p, alphaThreshold = 35)) {
                    mask[yi][xi] = true
                    fg++
                    if (xi < minX) minX = xi
                    if (yi < minY) minY = yi
                    if (xi > maxX) maxX = xi
                    if (yi > maxY) maxY = yi
                }
                xi++
                x += step
            }
            yi++
            y += step
        }

        if (fg < 10 || maxX < 0 || maxY < 0) return false

        val bbW = (maxX - minX + 1).coerceAtLeast(1)
        val bbH = (maxY - minY + 1).coerceAtLeast(1)
        val bbArea = bbW * bbH
        if (bbArea <= 0) return false

        val aspect = bbW.toDouble() / bbH.toDouble()
        if (aspect !in 0.35..4.8) return false

        val occupancy = fg.toDouble() / bbArea.toDouble()
        if (occupancy !in 0.03..0.85) return false

        var endPoints = 0
        for (yy in minY..maxY) {
            for (xx in minX..maxX) {
                if (!mask[yy][xx]) continue
                var neighbors = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = xx + dx
                        val ny = yy + dy
                        if (nx < minX || ny < minY || nx > maxX || ny > maxY) continue
                        if (mask[ny][nx]) neighbors++
                    }
                }
                if (neighbors in 1..2) endPoints++
            }
        }

        return endPoints in 0..160
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
        val foregroundMask = Array((bitmap.height + step - 1) / step) { BooleanArray((bitmap.width + step - 1) / step) }

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
                    if (isArrowForegroundPixel(p, alphaThreshold = 30)) {
                        foregroundMask[yi][xi] = true
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
        val componentDominance = calculateLargestComponentDominance(foregroundMask, foregroundPixels)

        val minSide = minOf(bitmap.width, bitmap.height).toDouble()
        val sizeScore = (minSide / 220.0).coerceIn(0.0, 1.0)
        val aspectRatio = bitmap.width.toDouble() / bitmap.height.toDouble()
        val aspectPenalty = kotlin.math.abs(aspectRatio - 1.0).coerceAtMost(1.0)

        val positionScore = calculatePositionScore(candidate.bounds, packageName)

        val totalScore =
            (whiteRatio * 0.35) +
                (componentDominance * 0.35) +
                (positionScore * 0.20) +
                (foregroundRatio * 0.20) +
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

    private fun isArrowForegroundPixel(pixel: Int, alphaThreshold: Int): Boolean {
        val a = (pixel ushr 24) and 0xff
        if (a <= alphaThreshold) return false
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        val luma = (r * 299 + g * 587 + b * 114) / 1000
        return luma >= 35
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
        }
    }

    private fun extractDistance(text: String?): String? {
        if (text == null) return null
        val distanceRegex = """(\d+(?:[.,]\d+)?)\s*(m|м|km|км)""".toRegex(RegexOption.IGNORE_CASE)
        val match = distanceRegex.find(text)
        return match?.value
    }
}
