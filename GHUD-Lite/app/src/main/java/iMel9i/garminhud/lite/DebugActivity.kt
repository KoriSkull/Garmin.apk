package iMel9i.garminhud.lite

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.media.projection.MediaProjectionManager
import android.content.Context
import androidx.core.content.ContextCompat

class DebugActivity : AppCompatActivity() {
    
    private val updateHandler = Handler(Looper.getMainLooper())
    private var currentArrowHash: Long? = null
    private lateinit var hud: HudEngine
    private data class LaneOption(val label: String, val mask: Int)
    private data class ByteOption(val label: String, val value: Int)
    
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDebugInfo()
            updateHandler.postDelayed(this, 1000)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        
        title = "Debug Information"

        hud = HudEngineFactory.create(this)
        
        val btnCapture = findViewById<Button>(R.id.btnStartScreenCapture)
        btnCapture.setOnClickListener {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), 1001)
        }
        
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { updateDebugInfo() }
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener { startActivity(Intent(this, AppSettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnClearLogs).setOnClickListener { DebugLog.clear(); updateDebugInfo() }
        
        findViewById<Button>(R.id.btnToggleDump).setOnClickListener {
            NavigationAccessibilityService.debugDumpMode = !NavigationAccessibilityService.debugDumpMode
            updateDebugInfo()
        }
        
        findViewById<Button>(R.id.btnToggleNotifications).setOnClickListener {
            NavigationNotificationListener.enabled = !NavigationNotificationListener.enabled
            updateDebugInfo()
        }
        
        findViewById<Button>(R.id.btnResetConfigs).setOnClickListener {
            val configManager = AppConfigManager(this)
            configManager.saveConfigs(AppConfigManager.DEFAULT_CONFIGS)
            android.widget.Toast.makeText(this, "Configs reset!", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        findViewById<Button>(R.id.btnToggleToasts).setOnClickListener {
            NavigationAccessibilityService.debugToastsEnabled = !NavigationAccessibilityService.debugToastsEnabled
            android.widget.Toast.makeText(this, "Toasts: ${NavigationAccessibilityService.debugToastsEnabled}", android.widget.Toast.LENGTH_SHORT).show()
            updateDebugInfo()
        }

        findViewById<Button>(R.id.btnToggleLaneExclusion).setOnClickListener {
            val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
            val current = prefs.getBoolean("lane_exclusion_enabled", true)
            val next = !current
            prefs.edit().putBoolean("lane_exclusion_enabled", next).apply()
            android.widget.Toast.makeText(this, "Lane exclusion: ${if (next) "ON" else "OFF"}", android.widget.Toast.LENGTH_SHORT).show()
            updateDebugInfo()
        }
        
        // Arrow Training Setup
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerArrowTypes)
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, ArrowDirection.values())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        findViewById<Button>(R.id.btnTrainArrow).setOnClickListener {
            val hash = currentArrowHash
            if (hash != null) {
                val selectedArrow = spinner.selectedItem as ArrowDirection
                CustomArrowManager.add(this, hash, selectedArrow)
                android.widget.Toast.makeText(this, "Saved: $selectedArrow for hash $hash", android.widget.Toast.LENGTH_SHORT).show()
                updateDebugInfo()
            } else {
                 android.widget.Toast.makeText(this, "No arrow hash available", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        val hudIconSpinner = findViewById<android.widget.Spinner>(R.id.spinnerHudIcons)
        val hudIconAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            HudIcon.values().map { "${it.displayName} (type=0x${it.type.toString(16)}, angle=0x${it.angle.toString(16)})" }
        )
        hudIconAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        hudIconSpinner.adapter = hudIconAdapter

        findViewById<Button>(R.id.btnSendHudIcon).setOnClickListener {
            val selectedIcon = HudIcon.values()[hudIconSpinner.selectedItemPosition]

            if (!hud.isConnected()) {
                val started = tryConnectSavedHud(showToastOnFail = true)
                if (started) {
                    android.widget.Toast.makeText(this, "Connecting to HUD... tap send again", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            if (selectedIcon.isCamera) {
                hud.showCameraIcon()
            } else {
                hud.setDirection(selectedIcon.type, selectedIcon.angle)
            }
            android.widget.Toast.makeText(
                this,
                "Sent ${selectedIcon.displayName} (type=0x${Integer.toHexString(selectedIcon.type)}, angle=0x${Integer.toHexString(selectedIcon.angle)})",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        val laneOptions = listOf(
            LaneOption("NONE (0x00)", 0x00),
            LaneOption("DotsRight (0x01)", 0x01),
            LaneOption("OuterRight (0x02)", 0x02),
            LaneOption("MiddleRight (0x04)", 0x04),
            LaneOption("InnerRight (0x08)", 0x08),
            LaneOption("InnerLeft (0x10)", 0x10),
            LaneOption("MiddleLeft (0x20)", 0x20),
            LaneOption("OuterLeft (0x40)", 0x40),
            LaneOption("DotsLeft (0x80)", 0x80)
        )
        val laneArrowSpinner = findViewById<android.widget.Spinner>(R.id.spinnerLaneArrow)
        val laneOutlineSpinner = findViewById<android.widget.Spinner>(R.id.spinnerLaneOutline)
        val laneAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            laneOptions.map { it.label }
        )
        laneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        laneArrowSpinner.adapter = laneAdapter
        laneOutlineSpinner.adapter = laneAdapter
        laneOutlineSpinner.setSelection(1) // OuterRight is a reasonable default outline
        val laneStringInput = findViewById<android.widget.EditText>(R.id.inputLaneBinary)
        laneStringInput.filters = arrayOf(InputFilter.LengthFilter(6))

        findViewById<Button>(R.id.btnSendLaneMasks).setOnClickListener {
            val arrowMask = laneOptions[laneArrowSpinner.selectedItemPosition].mask
            val outlineMask = laneOptions[laneOutlineSpinner.selectedItemPosition].mask

            if (!hud.isConnected()) {
                val started = tryConnectSavedHud(showToastOnFail = true)
                if (started) {
                    android.widget.Toast.makeText(this, "Connecting to HUD... tap send again", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            hud.setLanes(arrowMask, outlineMask)
            android.widget.Toast.makeText(
                this,
                "Sent lanes arrow=0x${Integer.toHexString(arrowMask)} outline=0x${Integer.toHexString(outlineMask)}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnSendLaneString).setOnClickListener {
            val laneString = laneStringInput.text?.toString().orEmpty()
            val masks = buildLaneMasksFromBinaryString(laneString)

            if (!hud.isConnected()) {
                val started = tryConnectSavedHud(showToastOnFail = true)
                if (started) {
                    android.widget.Toast.makeText(this, "Connecting to HUD... tap send again", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            val (arrowMask, outlineMask) = masks
            hud.setLanes(arrowMask, outlineMask)
            android.widget.Toast.makeText(
                this,
                "Sent from string '$laneString' -> arrow=0x${Integer.toHexString(arrowMask)} outline=0x${Integer.toHexString(outlineMask)}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        
        findViewById<Button>(R.id.btnSendManualDirection).setOnClickListener {
            val typeText = findViewById<android.widget.EditText>(R.id.inputManualType).text?.toString()?.trim().orEmpty()
            val angleText = findViewById<android.widget.EditText>(R.id.inputManualAngle).text?.toString()?.trim().orEmpty()

            val type = parseFlexibleInt(typeText)
            val angle = parseFlexibleInt(angleText)

            if (type == null || angle == null) {
                android.widget.Toast.makeText(this, "Invalid type/angle. Use decimal or 0xHEX", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!hud.isConnected()) {
                val started = tryConnectSavedHud(showToastOnFail = true)
                if (started) {
                    android.widget.Toast.makeText(this, "Connecting to HUD... tap send again", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            hud.setDirection(type, angle)
            android.widget.Toast.makeText(this, "Sent type=0x${Integer.toHexString(type)} angle=0x${Integer.toHexString(angle)}", android.widget.Toast.LENGTH_SHORT).show()
        }

        val boolByteOptions = listOf(
            ByteOption("0x00", 0x00),
            ByteOption("0x01", 0x01),
            ByteOption("0xFF", 0xFF)
        )
        val digitByteOptions = listOf(
            ByteOption("0x00", 0x00),
            ByteOption("0x01", 0x01),
            ByteOption("0x02", 0x02),
            ByteOption("0x03", 0x03),
            ByteOption("0x04", 0x04),
            ByteOption("0x05", 0x05),
            ByteOption("0x06", 0x06),
            ByteOption("0x07", 0x07),
            ByteOption("0x08", 0x08),
            ByteOption("0x09", 0x09),
            ByteOption("0x0A", 0x0A)
        )

        val boolAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            boolByteOptions.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val digitAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            digitByteOptions.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val spinnerTraffic = findViewById<android.widget.Spinner>(R.id.spinnerSetTimeTraffic)
        val spinnerH1 = findViewById<android.widget.Spinner>(R.id.spinnerSetTimeH1)
        val spinnerH2 = findViewById<android.widget.Spinner>(R.id.spinnerSetTimeH2)
        val spinnerColon = findViewById<android.widget.Spinner>(R.id.spinnerSetTimeColon)
        val spinnerM1 = findViewById<android.widget.Spinner>(R.id.spinnerSetTimeM1)
        val spinnerM2 = findViewById<android.widget.Spinner>(R.id.spinnerSetTimeM2)
        val spinnerFlag = findViewById<android.widget.Spinner>(R.id.spinnerSetTimeFlag)

        spinnerTraffic.adapter = boolAdapter
        spinnerColon.adapter = boolAdapter
        spinnerFlag.adapter = boolAdapter

        spinnerH1.adapter = digitAdapter
        spinnerH2.adapter = digitAdapter
        spinnerM1.adapter = digitAdapter
        spinnerM2.adapter = digitAdapter

        spinnerColon.setSelection(2) // default 0xFF

        findViewById<Button>(R.id.btnSendSetTimeRaw).setOnClickListener {
            if (!hud.isConnected()) {
                val started = tryConnectSavedHud(showToastOnFail = true)
                if (started) {
                    android.widget.Toast.makeText(this, "Connecting to HUD... tap send again", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            val traffic = boolByteOptions[spinnerTraffic.selectedItemPosition].value
            val h1 = digitByteOptions[spinnerH1.selectedItemPosition].value
            val h2 = digitByteOptions[spinnerH2.selectedItemPosition].value
            val colon = boolByteOptions[spinnerColon.selectedItemPosition].value
            val m1 = digitByteOptions[spinnerM1.selectedItemPosition].value
            val m2 = digitByteOptions[spinnerM2.selectedItemPosition].value
            val flag = boolByteOptions[spinnerFlag.selectedItemPosition].value

            hud.setTimeRaw(traffic, h1, h2, colon, m1, m2, flag)
            android.widget.Toast.makeText(
                this,
                "Sent SetTime: 05 ${hex(traffic)} ${hex(h1)} ${hex(h2)} ${hex(colon)} ${hex(m1)} ${hex(m2)} ${hex(flag)} 00",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnSendGpsLabelFlag).setOnClickListener {
            val rawValue = findViewById<android.widget.EditText>(R.id.inputGpsLabelFlag).text?.toString()?.trim().orEmpty()
            val enabled = when (rawValue.lowercase()) {
                "true" -> true
                "false" -> false
                else -> {
                    android.widget.Toast.makeText(this, "Use true or false", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            if (!hud.isConnected()) {
                val started = tryConnectSavedHud(showToastOnFail = true)
                if (started) {
                    android.widget.Toast.makeText(this, "Connecting to HUD... tap send again", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            hud.setGpsLabelEnabled(enabled)
            android.widget.Toast.makeText(
                this,
                "Sent GPS Label payload 07 ${if (enabled) "01" else "00"}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        findViewById<Button>(R.id.btnSendCameraIcon).setOnClickListener {
            if (!hud.isConnected()) {
                val started = tryConnectSavedHud(showToastOnFail = true)
                if (started) {
                    android.widget.Toast.makeText(this, "Connecting to HUD... tap send again", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            hud.showCameraIcon()
            android.widget.Toast.makeText(
                this,
                "Sent ShowCameraIcon payload 04 01",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        updateDebugInfo()
    }

    override fun onResume() {
        super.onResume()
        updateHandler.post(updateRunnable)
    }
    
    override fun onPause() {
        super.onPause()
        updateHandler.removeCallbacks(updateRunnable)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            android.widget.Toast.makeText(this, "Screen Capture Started", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun parseFlexibleInt(input: String): Int? {
        if (input.isBlank()) return null
        return try {
            if (input.startsWith("0x", true)) input.substring(2).toInt(16) else input.toInt()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun hex(value: Int): String = "0x" + value.toString(16).uppercase().padStart(2, '0')

    private fun tryConnectSavedHud(showToastOnFail: Boolean): Boolean {
        if (hud.isConnected()) return true

        if (Build.VERSION.SDK_INT >= 31) {
            val hasBtConnect = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasBtConnect) {
                if (showToastOnFail) {
                    android.widget.Toast.makeText(this, "Bluetooth permission is missing", android.widget.Toast.LENGTH_SHORT).show()
                }
                return false
            }
        }

        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        val address = prefs.getString("device_address", null)
        if (address.isNullOrBlank()) {
            if (showToastOnFail) {
                android.widget.Toast.makeText(this, "Select HUD device in Main screen first", android.widget.Toast.LENGTH_SHORT).show()
            }
            return false
        }

        hud.connectToDevice(address)
        return true
    }

    /**
     * SetLanes(CString strLanes) logic:
     * - keep only '0', '1' and ' '
     * - take up to 6 symbols
     * - if shorter than 6: pad with spaces alternating right/left (as in GarminHudDemo)
     * - outline gets bit for '0' and '1'
     * - arrow gets bit for '1' only
     * Bit mapping: i=0 -> 1<<6, i=1 -> 1<<5, ..., i=5 -> 1<<1
     */
    private fun buildLaneMasksFromBinaryString(value: String): Pair<Int, Int> {
        var normalized = value.filter { it == '0' || it == '1' || it == ' ' }.take(6)
        while (normalized.length < 6) {
            normalized = if (normalized.length and 1 == 1) " $normalized" else "$normalized "
        }

        var outlineMask = 0
        var arrowMask = 0
        for (index in normalized.indices) {
            val ch = normalized[index]
            val bit = 1 shl (6 - index)
            if (ch == '0' || ch == '1') {
                outlineMask = outlineMask or bit
            }
            if (ch == '1') {
                arrowMask = arrowMask or bit
            }
        }
        return arrowMask to outlineMask
    }

    private fun updateDebugInfo() {
        // OSM Debug
        findViewById<TextView>(R.id.osmLocation).text = "Location: ${HudService.osmDebug.lastLocation}"
        findViewById<TextView>(R.id.osmSpeedLimit).text = "Speed Limit: ${HudState.speedLimit ?: "none"} km/h"
        findViewById<TextView>(R.id.osmCameras).text = "Cameras Found: ${HudService.osmDebug.camerasFound}"
        findViewById<TextView>(R.id.osmNearestCamera).text = "Nearest Camera: ${HudState.cameraDistance?.let { "${it}m" } ?: "none"}"
        findViewById<TextView>(R.id.osmUpdateTime).text = "Last Update: ${HudService.osmDebug.lastUpdateTime}"
        
        // Navigation Debug
        findViewById<TextView>(R.id.navPackage).text = "Package: ${HudState.lastPackageName}"
        
        val allExtras = HudState.rawData.entries.joinToString("\n") { (key, value) -> "  $key: $value" }
        findViewById<TextView>(R.id.navTitle).text = "All Extras:\n$allExtras"
        findViewById<TextView>(R.id.navText).text = "Parsed Instruction: ${HudService.navDebug.parsedInstruction}"
        findViewById<TextView>(R.id.navBigText).text = "Parsed Distance: ${HudState.distanceToTurn}"
        
        findViewById<TextView>(R.id.navParsedInstruction).text = "Parsed ETA: ${HudState.eta}"
        findViewById<TextView>(R.id.navParsedDistance).text = "Traffic Score: ${HudState.trafficScore ?: "none"}"
        findViewById<TextView>(R.id.navParsedEta).text = "Remaining Time: ${HudState.remainingTime ?: "none"}"
        findViewById<TextView>(R.id.navUpdateTime).text = "Last Update: ${HudService.navDebug.lastUpdateTime}"
        findViewById<TextView>(R.id.navLaneMask).text = "Lane Mask: ${HudService.navDebug.laneMask}"
        findViewById<TextView>(R.id.navLaneCandidates).text = "Lane Candidates: ${HudService.navDebug.laneCandidates}"
        findViewById<TextView>(R.id.navLanePayload).text = "Lane Payload: ${HudService.navDebug.lanePayload}"
        findViewById<TextView>(R.id.navLaneBeforeExclusion).text = "Lane Before Exclusion: ${HudService.navDebug.laneMaskBeforeExclusion}"
        findViewById<TextView>(R.id.navLaneAfterExclusion).text = "Lane After Exclusion: ${HudService.navDebug.laneMaskAfterExclusion}"
        findViewById<Button>(R.id.btnToggleLaneExclusion).text =
            "Lane Exclusion: ${if (HudService.navDebug.laneExclusionEnabled) "ON" else "OFF"}"
        
        // Extracted Arrow Image
        val arrowImg = findViewById<android.widget.ImageView>(R.id.navArrowImage)
        if (HudService.navDebug.lastArrowBitmap != null) {
            arrowImg.setImageBitmap(HudService.navDebug.lastArrowBitmap)
            arrowImg.visibility = android.view.View.VISIBLE
        } else {
            arrowImg.setImageDrawable(null)
            arrowImg.visibility = android.view.View.GONE
        }

        val arrowGallery = findViewById<android.widget.LinearLayout>(R.id.navArrowGallery)
        arrowGallery.removeAllViews()
        HudService.navDebug.recognizedArrowBitmaps.forEachIndexed { idx, bmp ->
            val iv = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(80, 80).also { lp ->
                    lp.marginEnd = 8
                }
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(
                    if (HudService.navDebug.maneuverArrowOrdinal == idx) android.graphics.Color.parseColor("#66FF9800")
                    else android.graphics.Color.parseColor("#33000000")
                )
                setPadding(3, 3, 3, 3)
            }
            arrowGallery.addView(iv)
        }
        
        // HUD Debug
        findViewById<TextView>(R.id.hudSpeed).text = "Current Speed: ${HudState.currentSpeed} km/h"
        findViewById<TextView>(R.id.hudSpeedLimit).text = "Displayed Speed Limit: ${HudState.speedLimit?.let { "${it} km/h" } ?: "none"}"
        findViewById<TextView>(R.id.hudSpeedingIcon).text = "Speeding Icon: ${if (HudState.isSpeeding) "ON" else "OFF"}"
        findViewById<TextView>(R.id.hudCameraIcon).text = "Camera Icon: ${if (HudState.cameraDistance != null) "ON" else "OFF"}"
        
        val arrowInfo = if (HudState.turnIcon != null) "Code ${HudState.turnIcon}" else "none"
        findViewById<TextView>(R.id.hudDirection).text = "Direction: $arrowInfo"
        findViewById<TextView>(R.id.hudDistance).text = "Distance: ${HudState.distanceToTurnMeters?.let { "${it}m" } ?: "none"}"
        findViewById<TextView>(R.id.hudLastCommand).text = "Navigation: ${if (HudState.isNavigating) "ACTIVE" else "IDLE"}"
        findViewById<TextView>(R.id.hudUpdateTime).text = "Last Update: ${HudService.hudDebug.lastUpdateTime}"
        
        // Logs
        val logs = DebugLog.getAll()
        val servicesStatus = "Accessibility: ${if (NavigationAccessibilityService.instance != null) "RUNNING" else "STOPPED"}\n" +
                             "Dump Mode: ${if (NavigationAccessibilityService.debugDumpMode) "ON" else "OFF"}\n" +
                             "Toasts: ${if (NavigationAccessibilityService.debugToastsEnabled) "ON" else "OFF"}\n\n"
        
        val logsText = logs.takeLast(50).reversed().joinToString("\n") { "${it.time} [${it.tag}] ${it.message}" }
        findViewById<TextView>(R.id.debugLogs).text = servicesStatus + logsText
        
        // Maneuver Details
        val turnIcon = HudState.turnIcon
        findViewById<TextView>(R.id.maneuverDirection).text = "Direction Code: ${turnIcon ?: "none"}"
        findViewById<TextView>(R.id.maneuverDirectionName).text = "Direction Name: ${turnIcon ?: "none"}" // Simplified
        findViewById<TextView>(R.id.maneuverDistanceMeters).text = "Distance (meters): ${HudState.distanceToTurnMeters ?: "none"}"
        findViewById<TextView>(R.id.maneuverDistanceFormatted).text = "Distance (formatted): ${HudState.distanceToTurn ?: "none"}"
        findViewById<TextView>(R.id.maneuverInstructionText).text = "Instruction: ${HudService.navDebug.parsedInstruction}"
        
        // Arrow Status
        findViewById<TextView>(R.id.maneuverArrowHash).text = "Arrow Status: ${HudService.navDebug.arrowStatus}"
        
        val arrowBitmapForHash = HudService.navDebug.lastArrowBitmap
        if (arrowBitmapForHash != null) {
             try {
                val arrowImage = ArrowImage(arrowBitmapForHash.copy(android.graphics.Bitmap.Config.ARGB_8888, true))
                val hash = arrowImage.getArrowValue()
                
                currentArrowHash = hash
                findViewById<TextView>(R.id.trainingArrowHashDisplay).text = "Hash: $hash"
                
                val recognized = ArrowDirection.recognize(arrowImage)
                findViewById<TextView>(R.id.maneuverArrowRecognized).text = "Recognized: ${if (recognized != ArrowDirection.NONE) recognized.name else "NO"} ($hash)"
             } catch (e: Exception) {
                 currentArrowHash = null
                 findViewById<TextView>(R.id.trainingArrowHashDisplay).text = "Hash: Error"
                 findViewById<TextView>(R.id.maneuverArrowRecognized).text = "Error hashing: ${e.message}"
             }
        } else {
            currentArrowHash = null
            findViewById<TextView>(R.id.trainingArrowHashDisplay).text = "Hash: -"
            findViewById<TextView>(R.id.maneuverArrowRecognized).text = "Recognized: NO IMAGE"
        }
        
        updateVirtualHud()
    }
    
    private fun updateVirtualHud() {
        val vDirection = findViewById<TextView>(R.id.vHudDirection)
        if (HudState.isNavigating && HudState.turnIcon != null) {
             vDirection.text = when (HudState.turnIcon) {
                1 -> "↰"
                6 -> "↱"
                else -> "↑"
            }
            vDirection.setTextColor(android.graphics.Color.GREEN)
        } else {
            vDirection.text = "↑"
            vDirection.setTextColor(android.graphics.Color.GRAY)
        }
        
        val vDistance = findViewById<TextView>(R.id.vHudDistance)
        val vDistanceUnit = findViewById<TextView>(R.id.vHudDistanceUnit)
        if (HudState.distanceToTurnMeters != null) {
            val meters = HudState.distanceToTurnMeters!!
            val (value, unit) = DistanceFormatter.formatDistance(meters)
            vDistance.text = "$value"
            vDistanceUnit.text = if (unit == DistanceUnit.KILOMETRES) "km" else "m"
        } else {
            vDistance.text = "-"
            vDistanceUnit.text = ""
        }
        
        val vEta = findViewById<TextView>(R.id.vHudEta)
        vEta.text = HudState.eta ?: "00:00"
        
        val vSpeed = findViewById<TextView>(R.id.vHudSpeed)
        vSpeed.text = "${HudState.currentSpeed}"
        
        val vSpeedLimit = findViewById<TextView>(R.id.vHudSpeedLimit)
        vSpeedLimit.text = "${HudState.speedLimit ?: "-"}"
        
        findViewById<android.view.View>(R.id.vHudIconSpeeding).visibility = 
            if (HudState.isSpeeding) android.view.View.VISIBLE else android.view.View.INVISIBLE
            
        findViewById<android.view.View>(R.id.vHudIconCamera).visibility = 
            if (HudState.cameraDistance != null) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }
}
