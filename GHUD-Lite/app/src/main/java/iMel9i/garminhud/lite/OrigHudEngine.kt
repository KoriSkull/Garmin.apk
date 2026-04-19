package iMel9i.garminhud.lite

import android.content.Context
import com.navigon.hud.GarminHudClient
import java.io.IOException

class OrigHudEngine(context: Context) : HudEngine {
    private val legacy = GarminHudLite(context)

    private val client = GarminHudClient { packet ->
        if (!legacy.sendRawPacket(packet)) {
            throw IOException("Bluetooth transport unavailable")
        }
    }

    override var onConnectionStateChanged: ((Boolean, String?) -> Unit)?
        get() = legacy.onConnectionStateChanged
        set(value) {
            legacy.onConnectionStateChanged = value
        }

    override fun initBluetooth() = legacy.initBluetooth()
    override fun connectToDevice(address: String) = legacy.connectToDevice(address)
    override fun scanForDevice() = legacy.scanForDevice()
    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) =
        legacy.handleActivityResult(requestCode, resultCode, data)
    override fun disconnect() = legacy.disconnect()
    override fun isConnected() = legacy.isConnected()
    override fun getConnectedDeviceName() = legacy.getConnectedDeviceName()
    override fun getConnectedDeviceAddress() = legacy.getConnectedDeviceAddress()

    override fun setDirection(type: Int, angle: Int) {
        runCatching {
            val outType = mapOutType(type)
            val outAngle = mapOutAngle(angle)
            client.setDirection(outAngle, outType, GarminHudClient.OutAngle.AS_DIRECTION)
        }.onFailure { legacy.setDirection(type, angle) }
    }

    override fun setArrow(angle: Int) {
        val resolved = if (angle in 0..8) legacy.legacyArrowCodeToProtocolAnglePublic(angle) else angle
        setDirection(0x80, resolved)
    }

    override fun setLanes(arrowMask: Int, outlineMask: Int) {
        runCatching { client.setLanes(arrowMask, outlineMask) }
    }

    override fun showCameraIcon() {
        runCatching { client.showCameraIcon() }
    }

    override fun showGpsLabel() {
        setGpsLabelEnabled(true)
    }

    override fun setGpsLabelEnabled(enabled: Boolean) {
        runCatching { client.setGpsLabel(enabled) }
            .onFailure { legacy.setGpsLabelEnabled(enabled) }
    }

    override fun setTime(hour: Int, minute: Int) {
        runCatching { client.setTime(hour, minute, true, true, false) }
            .onFailure { legacy.setTime(hour, minute) }
    }

    override fun setTimeRaw(traffic: Int, h1: Int, h2: Int, colon: Int, m1: Int, m2: Int, flag: Int) {
        runCatching { client.setTimeRaw(traffic, h1, h2, colon, m1, m2, flag, 0x00) }
            .onFailure { legacy.setTimeRaw(traffic, h1, h2, colon, m1, m2, flag) }
    }

    override fun setSpeedWithLimit(currentSpeed: Int, speedLimit: Int?, showSpeedingIcon: Boolean, showCameraIcon: Boolean) {
        val limit = speedLimit ?: 0
        runCatching {
            client.setSpeedWarning(currentSpeed, limit, showSpeedingIcon, showCameraIcon, speedLimit != null)
        }.onFailure {
            legacy.setSpeedWithLimit(currentSpeed, speedLimit, showSpeedingIcon, showCameraIcon)
        }
    }

    override fun setDistance(distance: Int, unit: Int) {
        runCatching {
            client.setDistance(distance, mapUnits(unit), false, false)
        }.onFailure { legacy.setDistance(distance, unit) }
    }

    override fun clearDistance() = legacy.clearDistance()
    override fun setBrightness(level: Int) = legacy.setBrightness(level)

    private fun mapOutType(type: Int) = when (type) {
        0x01 -> GarminHudClient.OutType.LANE
        0x02 -> GarminHudClient.OutType.LONGER_LANE
        0x04 -> GarminHudClient.OutType.LEFT_ROUNDABOUT
        0x08 -> GarminHudClient.OutType.RIGHT_ROUNDABOUT
        0x80 -> GarminHudClient.OutType.ARROW_ONLY
        else -> GarminHudClient.OutType.OFF
    }

    private fun mapOutAngle(angle: Int) = when (angle) {
        0x01 -> GarminHudClient.OutAngle.DOWN
        0x02 -> GarminHudClient.OutAngle.SHARP_RIGHT
        0x04 -> GarminHudClient.OutAngle.RIGHT
        0x08 -> GarminHudClient.OutAngle.EASY_RIGHT
        0x10 -> GarminHudClient.OutAngle.STRAIGHT
        0x20 -> GarminHudClient.OutAngle.EASY_LEFT
        0x40 -> GarminHudClient.OutAngle.LEFT
        0x80 -> GarminHudClient.OutAngle.SHARP_LEFT
        0x81 -> GarminHudClient.OutAngle.LEFT_DOWN
        0x82 -> GarminHudClient.OutAngle.RIGHT_DOWN
        else -> GarminHudClient.OutAngle.STRAIGHT
    }

    private fun mapUnits(unit: Int) = when (unit) {
        1, 2 -> GarminHudClient.Units.METRES
        3 -> GarminHudClient.Units.KILOMETRES
        5 -> GarminHudClient.Units.MILES
        8 -> GarminHudClient.Units.FOOT
        else -> GarminHudClient.Units.NONE
    }
}
