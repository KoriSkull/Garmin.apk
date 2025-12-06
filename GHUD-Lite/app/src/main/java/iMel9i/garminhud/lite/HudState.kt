package iMel9i.garminhud.lite

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Универсальное хранилище состояния HUD.
 * Содержит все переменные, которые могут быть отображены на экране или использованы в логике.
 */
object HudState {
    // === Vehicle Data ===
    @Volatile var currentSpeed: Int = 0 // km/h
    @Volatile var speedLimit: Int? = null // km/h
    @Volatile var isSpeeding: Boolean = false

    // === System Data ===
    @Volatile var currentTime: String = "--:--"
    
    // === Navigation Data ===
    @Volatile var isNavigating: Boolean = false
    @Volatile var distanceToTurn: String? = null // Raw string, e.g. "500 m"
    @Volatile var distanceToTurnMeters: Int? = null // Parsed value
    @Volatile var turnIcon: Int? = null // Garmin HUD icon code (Legacy)
    @Volatile var activeHudIcon: HudIcon? = null // New System Mapping
    @Volatile var eta: String? = null // Estimated Time of Arrival
    @Volatile var remainingTime: String? = null // Time to destination
    @Volatile var trafficScore: Int? = null // 1-10 (Green/Yellow/Red)
    @Volatile var laneAssist: String? = null // Lane info (TBD)
    
    // === Alerts ===
    @Volatile var cameraDistance: Int? = null // meters
    
    // === Raw Data (for Debug & Mapping) ===
    // Хранит сырые данные из последнего уведомления для привязки
    val rawData = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var lastPackageName: String? = null

    // === Listeners ===
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyUpdate() {
        listeners.forEach { 
            try { it.invoke() } catch (e: Exception) { e.printStackTrace() }
        }
    }
    
    fun resetNavigation() {
        isNavigating = false
        distanceToTurn = null
        distanceToTurnMeters = null
        turnIcon = null
        eta = null
        remainingTime = null
        trafficScore = null
        laneAssist = null
        notifyUpdate()
    }
}
