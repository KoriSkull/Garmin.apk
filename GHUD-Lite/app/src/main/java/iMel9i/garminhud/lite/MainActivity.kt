package iMel9i.garminhud.lite

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.content.ComponentName
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.akexorcist.bluetotohspp.library.BluetoothState

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MEDIA_PROJECTION_REQUEST_CODE = 1002
    }
    
    private lateinit var statusText: TextView
    private lateinit var btnSelectHud: Button
    private lateinit var btnRestartHud: Button
    private lateinit var btnDebug: Button
    private lateinit var switchTime: android.widget.Switch
    private lateinit var switchSpeed: android.widget.Switch
    private lateinit var switchManualSpeedLimit: android.widget.Switch
    private lateinit var layoutSpeedLimitControl: LinearLayout
    private lateinit var seekBarSpeedLimit: SeekBar
    private lateinit var textSpeedLimitValue: TextView
    private lateinit var seekBarSpeedingThreshold: SeekBar
    private lateinit var textSpeedingThresholdValue: TextView
    private lateinit var switchAutoBrightness: android.widget.Switch
    private lateinit var switchGmaps: android.widget.Switch
    private lateinit var switchYandexNotifications: android.widget.Switch
    private lateinit var switchService: android.widget.Switch
    
    private lateinit var hud: GarminHudLite
    private var closeReceiver: android.content.BroadcastReceiver? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        statusText = findViewById(R.id.statusText)
        btnSelectHud = findViewById(R.id.btnSelectHud)
        btnRestartHud = findViewById(R.id.btnRestartHud)
        btnDebug = findViewById(R.id.btnDebug)
        switchTime = findViewById(R.id.switchTime)
        switchSpeed = findViewById(R.id.switchSpeed)
        switchManualSpeedLimit = findViewById(R.id.switchManualSpeedLimit)
        
        layoutSpeedLimitControl = findViewById(R.id.layoutSpeedLimitControl)
        seekBarSpeedLimit = findViewById(R.id.seekBarSpeedLimit)
        textSpeedLimitValue = findViewById(R.id.textSpeedLimitValue)
        seekBarSpeedingThreshold = findViewById(R.id.seekBarSpeedingThreshold)
        textSpeedingThresholdValue = findViewById(R.id.textSpeedingThresholdValue)
        switchAutoBrightness = findViewById(R.id.switchAutoBrightness)
        switchGmaps = findViewById(R.id.switchGmaps)
        switchYandexNotifications = findViewById(R.id.switchYandexNotifications)
        switchService = findViewById(R.id.switchService)
        
        hud = GarminHudLite(this)
        
        // Register broadcast receiver for app close
        closeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent?.action == "CLOSE_APP") {
                    finish()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, android.content.IntentFilter("CLOSE_APP"), android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, android.content.IntentFilter("CLOSE_APP"))
        }
        
        loadPreferences()
        
        btnSelectHud.setOnClickListener {
            if (checkPermissions()) {
                hud.scanForDevice()
            } else {
                requestPermissions()
            }
        }
        
        btnRestartHud.setOnClickListener {
            // Send broadcast to service to restart HUD
            val intent = Intent(this, HudService::class.java)
            intent.action = "RESTART_HUD"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Команда перезагрузки отправлена", Toast.LENGTH_SHORT).show()
        }
        
        // Listeners for switches
        val switchListener = { _: android.widget.CompoundButton, _: Boolean -> savePreferences() }
        switchTime.setOnCheckedChangeListener(switchListener)
        switchSpeed.setOnCheckedChangeListener(switchListener)
        switchAutoBrightness.setOnCheckedChangeListener(switchListener)
        
        switchManualSpeedLimit.setOnCheckedChangeListener { _, isChecked ->
            layoutSpeedLimitControl.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            savePreferences()
        }
        
        seekBarSpeedLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textSpeedLimitValue.text = "$progress км/ч"
                if (fromUser) savePreferences()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                savePreferences()
            }
        })
        
        seekBarSpeedingThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textSpeedingThresholdValue.text = "+$progress км/ч"
                if (fromUser) savePreferences()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                savePreferences()
            }
        })
        
        btnDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
        
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }
        
        findViewById<Button>(R.id.btnOsmSettings).setOnClickListener {
            startActivity(Intent(this, OsmSettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_open_icon_mapping).setOnClickListener {
            startActivity(Intent(this, IconMappingActivity::class.java))
        }

        findViewById<Button>(R.id.btn_open_gallery).setOnClickListener {
            startActivity(Intent(this, HudGalleryActivity::class.java))
        }
        
        findViewById<Button>(R.id.btnLayoutEditor).setOnClickListener {
            startActivity(Intent(this, LayoutEditorActivity::class.java))
        }
        
        findViewById<Button>(R.id.btnStartScreenCapture).setOnClickListener {
            startScreenCapture()
        }
        
        switchGmaps.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Проверяем разрешение на доступ к уведомлениям
                if (!isNotificationServiceEnabled()) {
                    // Просим пользователя предоставить доступ
                    val message = "Для интеграции с навигацией необходим доступ к уведомлениям.\n\nПожалуйста, включите доступ для GHUD Lite в настройках."
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Требуется доступ")
                        .setMessage(message)
                        .setPositiveButton("Открыть настройки") { _, _ ->
                            openNotificationSettings()
                        }
                        .setNegativeButton("Отмена") { _, _ ->
                            switchGmaps.isChecked = false
                        }
                        .show()
                }
            }
            savePreferences()
        }
        
        switchYandexNotifications.setOnCheckedChangeListener { _, _ ->
            savePreferences()
        }
        
        switchService.setOnCheckedChangeListener { _, isChecked ->
            savePreferences()
            if (isChecked) {
                if (checkPermissions()) {
                    startHudService()
                } else {
                    switchService.isChecked = false
                    requestPermissions()
                }
            } else {
                stopHudService()
            }
        }
        
        // Handle connection state changes
        hud.onConnectionStateChanged = { connected, deviceName ->
            runOnUiThread {
                if (connected && deviceName != null) {
                    val deviceAddress = hud.getConnectedDeviceAddress()
                    if (deviceAddress != null) {
                        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
                        prefs.edit()
                            .putString("device_name", deviceName)
                            .putString("device_address", deviceAddress)
                            .apply()
                        
                        updateStatusConnected(deviceName)
                    }
                } else {
                    updateStatusDisconnected()
                }
            }
        }
        
        // Проверяем разрешения при запуске
        if (!checkPermissions()) {
            requestPermissions()
        } else {
            // If permissions granted, check if service is running (simple check via prefs for now)
            // Ideally we should check actual service status, but for now let's trust the switch state from prefs
            if (switchService.isChecked) {
                startHudService()
            }
        }
        
        updateStatus()
    }
    
    private fun loadPreferences() {
        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        switchTime.isChecked = prefs.getBoolean("show_time", true)
        switchSpeed.isChecked = prefs.getBoolean("show_speed", true)
        
        val manualLimitEnabled = prefs.getBoolean("manual_speed_limit_enabled", false)
        switchManualSpeedLimit.isChecked = manualLimitEnabled
        layoutSpeedLimitControl.visibility = if (manualLimitEnabled) android.view.View.VISIBLE else android.view.View.GONE
        
        val speedLimit = prefs.getInt("speed_limit", 71)
        seekBarSpeedLimit.progress = speedLimit
        textSpeedLimitValue.text = "$speedLimit км/ч"
        
        val speedingThreshold = prefs.getInt("speeding_threshold", 10)
        seekBarSpeedingThreshold.progress = speedingThreshold
        textSpeedingThresholdValue.text = "+$speedingThreshold км/ч"
        
        switchAutoBrightness.isChecked = prefs.getBoolean("auto_brightness", true)
        switchGmaps.isChecked = prefs.getBoolean("gmaps_integration", false)
        switchYandexNotifications.isChecked = prefs.getBoolean("yandex_notifications_enabled", true)
        switchService.isChecked = prefs.getBoolean("service_active", false)
    }
    
    private fun savePreferences() {
        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        prefs.edit()
            .putBoolean("show_time", switchTime.isChecked)
            .putBoolean("show_speed", switchSpeed.isChecked)
            .putBoolean("manual_speed_limit_enabled", switchManualSpeedLimit.isChecked)
            .putInt("speed_limit", seekBarSpeedLimit.progress)
            .putInt("speeding_threshold", seekBarSpeedingThreshold.progress)
            .putBoolean("auto_brightness", switchAutoBrightness.isChecked)
            .putBoolean("gmaps_integration", switchGmaps.isChecked)
            .putBoolean("yandex_notifications_enabled", switchYandexNotifications.isChecked)
            .putBoolean("service_active", switchService.isChecked)
            .apply()
    }
    
    private fun updateStatus() {
        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        val deviceName = prefs.getString("device_name", null)
        
        if (!checkPermissions()) {
            updateStatusError("Необходимы разрешения!")
        } else if (deviceName != null) {
            updateStatusSaved(deviceName)
        } else {
            updateStatusNeutral("HUD не выбран")
        }
    }
    
    private fun updateStatusConnected(deviceName: String) {
        statusText.text = "✓ Подключено: $deviceName"
        statusText.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
        statusText.setTextColor(Color.WHITE)
    }
    
    private fun updateStatusDisconnected() {
        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        val deviceName = prefs.getString("device_name", null)
        if (deviceName != null) {
            statusText.text = "Отключено: $deviceName"
            statusText.setBackgroundColor(Color.parseColor("#FFA726")) // Orange
            statusText.setTextColor(Color.WHITE)
        } else {
            updateStatusNeutral("HUD не выбран")
        }
    }
    
    private fun updateStatusSaved(deviceName: String) {
        statusText.text = "Сохранено: $deviceName"
        statusText.setBackgroundColor(Color.parseColor("#E0E0E0")) // Light gray
        statusText.setTextColor(Color.BLACK)
    }
    
    private fun updateStatusError(message: String) {
        statusText.text = "⚠ $message"
        statusText.setBackgroundColor(Color.parseColor("#F44336")) // Red
        statusText.setTextColor(Color.WHITE)
    }
    
    private fun updateStatusNeutral(message: String) {
        statusText.text = message
        statusText.setBackgroundColor(Color.parseColor("#E0E0E0")) // Light gray
        statusText.setTextColor(Color.BLACK)
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= 31) { // Android 12 (S)
            permissions.add("android.permission.BLUETOOTH_CONNECT")
            permissions.add("android.permission.BLUETOOTH_SCAN")
        }
        
        if (Build.VERSION.SDK_INT >= 33) { // Android 13 (Tiramisu)
            permissions.add("android.permission.POST_NOTIFICATIONS")
        }
        
        return permissions.all { 
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= 31) { // Android 12 (S)
            permissions.add("android.permission.BLUETOOTH_CONNECT")
            permissions.add("android.permission.BLUETOOTH_SCAN")
        }
        
        if (Build.VERSION.SDK_INT >= 33) { // Android 13 (Tiramisu)
            permissions.add("android.permission.POST_NOTIFICATIONS")
        }
        
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                updateStatus() // Update status to remove warning
                if (switchService.isChecked) {
                    startHudService()
                }
            } else {
                statusText.text = "Необходимы все разрешения для работы"
                switchService.isChecked = false
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        
        // Restore manual speed limit switch
        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        switchManualSpeedLimit.isChecked = prefs.getBoolean("manual_speed_limit_enabled", false)
        if (switchManualSpeedLimit.isChecked) {
            layoutSpeedLimitControl.visibility = android.view.View.VISIBLE
        }
    }
    
    private fun updateServiceStatus() {
        // 1. Notification Listener
        val isNotifEnabled = isNotificationServiceEnabled()
        findViewById<android.view.View>(R.id.indicatorNotification).setBackgroundColor(
            if (isNotifEnabled) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FF5252")
        )
        findViewById<Button>(R.id.btnEnableNotification).text = if (isNotifEnabled) "Настроено" else "Настроить"
        
        // 2. Accessibility Service
        val isAccessEnabled = NavigationAccessibilityService.instance != null
         findViewById<android.view.View>(R.id.indicatorAccessibility).setBackgroundColor(
            if (isAccessEnabled) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FF5252")
        )
        findViewById<Button>(R.id.btnEnableAccessibility).text = if (isAccessEnabled) "Включено" else "Включить"
        
        // 3. Screen Capture
        val isCaptureRunning = ScreenCaptureService.isRunning()
        findViewById<android.view.View>(R.id.indicatorScreenCapture).setBackgroundColor(
            if (isCaptureRunning) android.graphics.Color.parseColor("#4CAF50") else android.graphics.Color.parseColor("#FF5252")
        )
        findViewById<Button>(R.id.btnEnableScreenCapture).text = if (isCaptureRunning) "Работает" else "Запуск"
        findViewById<Button>(R.id.btnEnableScreenCapture).isEnabled = !isCaptureRunning
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Start ScreenCaptureService
                val intent = Intent(this, ScreenCaptureService::class.java)
                intent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                intent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                
                Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()
                updateServiceStatus()
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (hud.handleActivityResult(requestCode, resultCode, data)) {
            // Connection logic is handled inside hud.handleActivityResult
            // and the result will be reported via onConnectionStateChanged
        }
    }
    
    private fun startScreenCapture() {
        if (ScreenCaptureService.isRunning()) {
            Toast.makeText(this, "Screen capture already running", Toast.LENGTH_SHORT).show()
            return
        }
        
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, MEDIA_PROJECTION_REQUEST_CODE)
    }
    
    private fun startHudService() {
        val prefs = getSharedPreferences("HudPrefs", MODE_PRIVATE)
        val deviceAddress = prefs.getString("device_address", null)
        
        if (deviceAddress == null) {
            statusText.text = "Сначала выберите HUD"
            switchService.isChecked = false
            return
        }
        
        val intent = Intent(this, HudService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // statusText.text = "Сервис запущен" // Let status be handled by connection callback
    }
    
    private fun stopHudService() {
        val intent = Intent(this, HudService::class.java)
        stopService(intent)
        statusText.text = "Сервис остановлен"
    }
    
    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = android.provider.Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        if (flat != null) {
            val names = flat.split(":")
            return names.any { it.contains(pkgName) }
        }
        return false
    }
    
    private fun openNotificationSettings() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        this.startActivity(intent)
    }
}
