package iMel9i.garminhud.lite

interface HudEngine {
    var onConnectionStateChanged: ((Boolean, String?) -> Unit)?

    fun initBluetooth()
    fun connectToDevice(address: String)
    fun scanForDevice()
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?): Boolean
    fun disconnect()

    fun isConnected(): Boolean
    fun getConnectedDeviceName(): String?
    fun getConnectedDeviceAddress(): String?

    fun setDirection(type: Int, angle: Int)
    fun setArrow(angle: Int)
    fun setLanes(arrowMask: Int, outlineMask: Int)
    fun showCameraIcon()
    fun sendCameraPayloadRaw()
    fun showGpsLabel()
    fun setGpsLabelEnabled(enabled: Boolean)

    fun setTime(hour: Int, minute: Int)
    fun setTimeRaw(traffic: Int, h1: Int, h2: Int, colon: Int, m1: Int, m2: Int, flag: Int)
    fun setSpeedWithLimit(currentSpeed: Int, speedLimit: Int?, showSpeedingIcon: Boolean = false, showCameraIcon: Boolean = false)
    fun setDistance(distance: Int, unit: Int = 1)
    fun clearDistance()
    fun setBrightness(level: Int)
}
