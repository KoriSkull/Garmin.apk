package iMel9i.garminhud.lite

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import app.akexorcist.bluetotohspp.library.BluetoothSPP
import app.akexorcist.bluetotohspp.library.BluetoothState
import app.akexorcist.bluetotohspp.library.DeviceList

/**
 * Упрощенная реализация протокола Garmin HUD для тестирования
 */
class GarminHudLite(private val context: Context) : HudEngine {
    
    companion object {
        private const val TAG = "GarminHudLite"
        private const val DEBUG = true
        private const val MAX_UPDATES_PER_SECOND = 6
    }
    
    private var bt: BluetoothSPP? = null
    private var updateCount = 0
    private var lastUpdateClearTime = System.currentTimeMillis()
    private var connected = false
    private var connectedDeviceName: String? = null
    private var connectedDeviceAddress: String? = null
    
    override var onConnectionStateChanged: ((Boolean, String?) -> Unit)? = null
    
    init {
        // Не инициализируем Bluetooth сразу, чтобы избежать ошибок разрешений
    }
    
    private var isConnecting = false
    
    override fun initBluetooth() {
        if (bt != null) return
        
        bt = BluetoothSPP(context)
        
        bt?.setBluetoothConnectionListener(object : BluetoothSPP.BluetoothConnectionListener {
            override fun onDeviceConnected(name: String, address: String) {
                Log.d(TAG, "Connected: $name ($address)")
                connected = true
                isConnecting = false
                connectedDeviceName = name
                connectedDeviceAddress = address
                onConnectionStateChanged?.invoke(true, name)
            }
            
            override fun onDeviceDisconnected() {
                Log.d(TAG, "Disconnected")
                connected = false
                isConnecting = false
                connectedDeviceName = null
                connectedDeviceAddress = null
                onConnectionStateChanged?.invoke(false, null)
            }
            
            override fun onDeviceConnectionFailed() {
                Log.d(TAG, "Connection failed")
                connected = false
                isConnecting = false
                connectedDeviceName = null
                connectedDeviceAddress = null
                onConnectionStateChanged?.invoke(false, null)
            }
        })
        
        if (bt?.isServiceAvailable == false) {
            bt?.setupService()
            bt?.startService(BluetoothState.DEVICE_OTHER)
        }
    }
    
    override fun connectToDevice(address: String) {
        if (isConnecting || isConnected()) {
            Log.d(TAG, "Already connecting or connected, ignoring connect request")
            return
        }
        
        initBluetooth()
        isConnecting = true
        bt?.connect(address)
    }
    
    override fun scanForDevice() {
        initBluetooth()
        
        if (bt?.isBluetoothAvailable == false) {
            Toast.makeText(context, R.string.bt_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        
        bt?.setDeviceTarget(BluetoothState.DEVICE_OTHER)
        val intent = Intent(context, DeviceList::class.java)
        (context as Activity).startActivityForResult(intent, BluetoothState.REQUEST_CONNECT_DEVICE)
    }
    
    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return when (requestCode) {
            BluetoothState.REQUEST_CONNECT_DEVICE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    bt?.connect(data)
                }
                true
            }
            BluetoothState.REQUEST_ENABLE_BT -> {
                if (resultCode == Activity.RESULT_OK) {
                    bt?.setupService()
                    bt?.startService(BluetoothState.DEVICE_OTHER)
                } else {
                    Toast.makeText(context, R.string.bt_not_enabled, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> false
        }
    }
    
    override fun disconnect() {
        bt?.stopAutoConnect()
        bt?.stopService()
        connected = false
    }
    
    override fun isConnected(): Boolean = connected
    
    override fun getConnectedDeviceName(): String? = connectedDeviceName
    override fun getConnectedDeviceAddress(): String? = connectedDeviceAddress
    
    // ========== Протокол Garmin HUD ==========
    
    private fun isUpdatable(): Boolean {
        val now = System.currentTimeMillis()
        val interval = now - lastUpdateClearTime
        if (interval > 1000) {
            lastUpdateClearTime = now
            updateCount = 0
        }
        return updateCount < MAX_UPDATES_PER_SECOND
    }
    
    private fun toDigit(n: Int): Char {
        val digit = n % 10
        return if (digit == 0) 10.toChar() else digit.toChar()
    }
    
    private fun sendPacket(pBuf: CharArray): Boolean {
        if (!isUpdatable() || bt?.isServiceAvailable != true) {
            return false
        }
        
        updateCount++
        
        val packet = ByteArray(pBuf.size) { pBuf[it].code.toByte() }
        
        if (DEBUG) {
            Log.d(TAG, "Sending packet: ${packet.joinToString(" ") { "%02X".format(it) }}")
        }
        
        bt?.send(packet, false)
        return true
    }
    
    private fun sendToHud(pBuf: CharArray) {
        val sendBuf = CharArray(255)
        var len = 0
        var stuffingCount = 0
        
        // Header
        sendBuf[len++] = 0x10.toChar()
        sendBuf[len++] = 0x7b.toChar()
        sendBuf[len++] = (pBuf.size + 6).toChar()
        
        if (pBuf.size == 0x0a) {
            sendBuf[len++] = 0x10.toChar()
            stuffingCount++
        }
        
        sendBuf[len++] = pBuf.size.toChar()
        sendBuf[len++] = 0x00.toChar()
        sendBuf[len++] = 0x00.toChar()
        sendBuf[len++] = 0x00.toChar()
        sendBuf[len++] = 0x55.toChar()
        sendBuf[len++] = 0x15.toChar()
        
        // Data with byte stuffing
        for (c in pBuf) {
            sendBuf[len++] = c
            if (c.code == 0x10) {
                sendBuf[len++] = 0x10.toChar()
                stuffingCount++
            }
        }
        
        // CRC
        var nCrc = 0
        for (i in 1 until len) {
            nCrc += sendBuf[i].code
        }
        nCrc -= stuffingCount * 0x10
        
        sendBuf[len++] = ((-nCrc) and 0xff).toChar()
        sendBuf[len++] = 0x10.toChar()
        sendBuf[len++] = 0x03.toChar()
        
        sendPacket(sendBuf.copyOf(len))
    }
    
    // ========== Команды HUD ==========
    
    /**
     * Установить время
     */
    override fun setTime(hour: Int, minute: Int) {
        val arr = charArrayOf(
            0x05.toChar(),              // Command: Time
            0x00.toChar(),              // Traffic flag
            toDigit(hour / 10),         // Hour tens
            toDigit(hour),              // Hour ones
            0xff.toChar(),              // Colon
            toDigit(minute / 10),       // Minute tens
            toDigit(minute),            // Minute ones
            0x00.toChar(),              // 'h' suffix
            0x00.toChar()               // Flag
        )
        sendToHud(arr)
    }

    override fun setTimeRaw(traffic: Int, h1: Int, h2: Int, colon: Int, m1: Int, m2: Int, flag: Int) {
        val arr = charArrayOf(
            0x05.toChar(),
            traffic.toChar(),
            h1.toChar(),
            h2.toChar(),
            colon.toChar(),
            m1.toChar(),
            m2.toChar(),
            flag.toChar(),
            0x00.toChar()
        )
        sendToHud(arr)
    }
    
    /**
     * Установить направление (стрелку)
     * @param type: 0x01=Lane, 0x02=LongerLane, 0x04=LeftRoundabout, 0x08=RightRoundabout, 0x80=ArrowOnly
     * @param angle: 0x10=Straight, 0x20=EasyLeft, 0x40=Left, 0x80=SharpLeft...
     */
    override fun setDirection(type: Int, angle: Int) {
        val arr = charArrayOf(
            0x01.toChar(),  // Command: Direction
            type.toChar(),  // Type
            0x00.toChar(),  // Roundabout (unused/extended)
            angle.toChar()  // Direction angle
        )
        sendToHud(arr)
    }

    /**
     * Shortcut for Simple Arrow.
     * Accepts either protocol angle masks (0x10, 0x40, ...) or legacy arrow codes (0..8).
     */
    override fun setArrow(angle: Int) {
        val protocolAngle = if (angle in 0..8) legacyArrowCodeToProtocolAngle(angle) else angle
        setDirection(0x80, protocolAngle)
    }

    override fun setLanes(arrowMask: Int, outlineMask: Int) {
        val normalizedOutline = outlineMask and 0x7E
        val normalizedArrow = arrowMask and 0x7E
        val arr = charArrayOf(
            0x02.toChar(),
            normalizedOutline.toChar(),
            normalizedArrow.toChar()
        )
        sendToHud(arr)
    }

    override fun showCameraIcon() {
        setSpeedWithLimit(0, null, false, true)
    }

    override fun showGpsLabel() {
        setGpsLabelEnabled(true)
    }

    override fun setGpsLabelEnabled(enabled: Boolean) {
        val arr = charArrayOf(
            0x07.toChar(),
            if (enabled) 0x01.toChar() else 0x00.toChar()
        )
        sendToHud(arr)
    }

    private fun legacyArrowCodeToProtocolAngle(code: Int): Int {
        return when (code) {
            0 -> 0x10 // STRAIGHT
            1 -> 0x40 // LEFT
            2 -> 0x20 // EASY_LEFT
            3 -> 0x08 // EASY_RIGHT
            4 -> 0x20 // KEEP_LEFT (closest HUD representation)
            5 -> 0x08 // KEEP_RIGHT (closest HUD representation)
            6 -> 0x04 // RIGHT
            7 -> 0x80 // SHARP_LEFT
            8 -> 0x02 // SHARP_RIGHT
            else -> 0x10
        }
    }

    fun legacyArrowCodeToProtocolAnglePublic(code: Int): Int = legacyArrowCodeToProtocolAngle(code)
    
    /**
     * Установить скорость
     */
    fun setSpeed(speed: Int, showIcon: Boolean = true) {
        val hundredsDigit = if (speed < 10) 0.toChar() else ((speed / 100) % 10).toChar()
        val tensDigit = if (speed < 10) 0.toChar() else toDigit(speed / 10)
        val onesDigit = toDigit(speed)
        
        val arr = charArrayOf(
            0x06.toChar(),      // Command: Speed
            0x00.toChar(),      // Speed hundreds
            0x00.toChar(),      // Speed tens
            0x00.toChar(),      // Speed ones
            0x00.toChar(),      // Slash
            hundredsDigit,      // Limit hundreds
            tensDigit,          // Limit tens
            onesDigit,          // Limit ones
            0x00.toChar(),      // Speeding
            if (showIcon) 0xff.toChar() else 0x00.toChar()  // Icon
        )
        sendToHud(arr)
    }
    
    /**
     * Установить скорость с предупреждением о превышении лимита
     * @param currentSpeed текущая скорость
     * @param speedLimit лимит скорости (если null, то лимит не отображается)
     * @param showSpeedingIcon показывать ли иконку превышения скорости
     */
    override fun setSpeedWithLimit(currentSpeed: Int, speedLimit: Int?, showSpeedingIcon: Boolean, showCameraIcon: Boolean) {
        val speedHundreds = ((currentSpeed / 100) % 10).toChar()
        val speedTens = if (currentSpeed < 10) 0.toChar() else toDigit(currentSpeed / 10)
        val speedOnes = toDigit(currentSpeed)
        
        val limitHundreds: Char
        val limitTens: Char
        val limitOnes: Char
        val showSlash: Boolean
        
        if (speedLimit != null && speedLimit > 0) {
            limitHundreds = ((speedLimit / 100) % 10).toChar()
            limitTens = if (speedLimit < 10) 0.toChar() else toDigit(speedLimit / 10)
            limitOnes = toDigit(speedLimit)
            showSlash = true
        } else {
            limitHundreds = 0.toChar()
            limitTens = 0.toChar()
            limitOnes = 0.toChar()
            showSlash = false
        }
        
        val arr = charArrayOf(
            0x06.toChar(),      // Command: Speed
            speedHundreds,      // Current speed hundreds
            speedTens,          // Current speed tens
            speedOnes,          // Current speed ones
            if (showSlash) 0xff.toChar() else 0x00.toChar(),  // Slash separator
            limitHundreds,      // Limit hundreds
            limitTens,          // Limit tens
            limitOnes,          // Limit ones
            if (showSpeedingIcon) 0xff.toChar() else 0x00.toChar(),  // Speeding warning icon
            if (showCameraIcon) 0xff.toChar() else 0x00.toChar()     // Camera icon
        )
        sendToHud(arr)
    }
    
    /**
     * Установить расстояние
     */
    /**
     * Установить расстояние (целое число)
     */
    override fun setDistance(distance: Int, unit: Int) {
        val arr = charArrayOf(
            0x03.toChar(),              // Command: Distance
            toDigit(distance / 1000),   // Thousands
            toDigit(distance / 100),    // Hundreds
            toDigit(distance / 10),     // Tens
            0x00.toChar(),              // Decimal point (OFF)
            toDigit(distance),          // Ones
            unit.toChar()               // Unit (1=km, 2=m)
        )
        sendToHud(arr)
    }

    /**
     * Установить расстояние (дробное число)
     * Например: 1.2 км
     */
    fun setDistance(distance: Float, unit: Int) {
        // Форматируем число, чтобы оно влезло в 4 цифры с точкой перед последней
        // Формат HUD: [D1][D2][D3].[D4]
        
        val d1: Char
        val d2: Char
        val d3: Char
        val d4: Char
        val decimalPoint: Char
        
        if (distance >= 1000) {
            // Если больше 1000, показываем как целое (1234)
            val i = distance.toInt()
            d1 = toDigit(i / 1000)
            d2 = toDigit(i / 100)
            d3 = toDigit(i / 10)
            d4 = toDigit(i)
            decimalPoint = 0x00.toChar()
        } else {
            // Если меньше 1000, пробуем показать с десятичной частью
            // Нам нужно число вида XXX.Y
            // Умножаем на 10, чтобы получить целые цифры
            val val10 = (distance * 10).toInt()
            
            if (val10 >= 10000) {
                // Слишком большое для формата XXX.Y (>= 1000.0), показываем как целое
                val i = distance.toInt()
                d1 = toDigit(i / 1000)
                d2 = toDigit(i / 100)
                d3 = toDigit(i / 10)
                d4 = toDigit(i)
                decimalPoint = 0x00.toChar()
            } else {
                // Формат XXX.Y
                // 1.2 -> 12 -> 001.2
                // 12.5 -> 125 -> 012.5
                // 123.4 -> 1234 -> 123.4
                
                val thousands = val10 / 1000
                val hundreds = (val10 / 100) % 10
                val tens = (val10 / 10) % 10
                val ones = val10 % 10
                
                // Используем 0.toChar() для ведущих нулей (пробелов), если число маленькое
                // Ноль (0) в протоколе это 10 (0x0A)
                // Пробел (Space) это 0 (0x00)
                
                d1 = if (thousands == 0) 0.toChar() else toDigit(thousands)
                d2 = if (thousands == 0 && hundreds == 0) 0.toChar() else toDigit(hundreds)
                d3 = toDigit(tens) // Десятки всегда показываем (например 0.5 -> 0.5)
                d4 = toDigit(ones)
                decimalPoint = 0xff.toChar() // Decimal point ON
            }
        }

        val arr = charArrayOf(
            0x03.toChar(),      // Command: Distance
            d1,                 // Thousands
            d2,                 // Hundreds
            d3,                 // Tens
            decimalPoint,       // Decimal point
            d4,                 // Ones
            unit.toChar()       // Unit
        )
        sendToHud(arr)
    }
    
    override fun clearDistance() {
        sendToHud(charArrayOf(
            0x03.toChar(), 0x00.toChar(), 0x00.toChar(), 0x00.toChar(),
            0x00.toChar(), 0x00.toChar(), 0x00.toChar()
        ))
    }

    /**
     * Очистить экран
     */
    fun clear() {
        // Clear direction
        sendToHud(charArrayOf(0x01.toChar(), 0x00.toChar(), 0x00.toChar(), 0x00.toChar()))
        
        // Clear speed
        sendToHud(charArrayOf(
            0x06.toChar(), 0x00.toChar(), 0x00.toChar(), 0x00.toChar(), 0x00.toChar(),
            0x00.toChar(), 0x00.toChar(), 0x00.toChar(), 0x00.toChar(), 0x00.toChar()
        ))
        
        // Clear distance
        clearDistance()
    }

    /**
     * Установить яркость
     * @param brightness: 0 = Auto, 1-10 = Manual levels
     */
    override fun setBrightness(level: Int) {
        // 0x04 = Brightness command
        // 0x00 = Unused?
        // 0x00 = Unused?
        // Level
        
        // Note: The exact protocol for brightness might vary. 
        // Standard observation:
        // Cmd: 0x04
        // Data: [0x00, 0x00, Level]
        // Level: 0x00? for Auto? Or maybe specific bit?
        // Let's try sending just the level byte.
        
        // Based on some reverse engineering:
        // Packet: 0x04, 0x00, 0x00, Level
        // Level: 1-10. 
        // For Auto, it might be a different command or specific value.
        // Let's assume 0 is Auto for now, but if that fails we might need to investigate.
        // Actually, often it's just setting it to a value. 
        // Let's try implementing basic level setting.
        
        val resolvedLevel = when {
            level <= 0 -> 0 // Auto? Or min?
            level > 10 -> 10
            else -> level
        }
        
        val arr = charArrayOf(
            0x04.toChar(),
            0x00.toChar(),
            0x00.toChar(),
            resolvedLevel.toChar()
        )
        sendToHud(arr)
    }

    fun sendRawPacket(packet: ByteArray): Boolean {
        if (!isUpdatable() || bt?.isServiceAvailable != true) {
            return false
        }

        updateCount++
        bt?.send(packet, false)
        return true
    }
}
