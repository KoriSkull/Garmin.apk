package iMel9i.garminhud.lite

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.media.projection.MediaProjectionManager
import android.content.Context

class DebugActivity : AppCompatActivity() {
    
    private val updateHandler = Handler(Looper.getMainLooper())
    private var currentArrowHash: Long? = null
    
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
        
        // Extracted Arrow Image
        val arrowImg = findViewById<android.widget.ImageView>(R.id.navArrowImage)
        if (HudService.navDebug.lastArrowBitmap != null) {
            arrowImg.setImageBitmap(HudService.navDebug.lastArrowBitmap)
            arrowImg.visibility = android.view.View.VISIBLE
        } else {
            arrowImg.setImageDrawable(null)
            arrowImg.visibility = android.view.View.GONE
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
