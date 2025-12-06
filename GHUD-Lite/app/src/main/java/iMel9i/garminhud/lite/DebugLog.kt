package iMel9i.garminhud.lite

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Внутренний лог для отладки, доступный в Debug Activity
 */
object DebugLog {
    private val logs = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_LOGS = 50
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    data class LogEntry(
        val time: String,
        val tag: String,
        val message: String,
        val level: Level
    )
    
    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }
    
    fun d(tag: String, message: String) {
        add(tag, message, Level.DEBUG)
    }
    
    fun i(tag: String, message: String) {
        add(tag, message, Level.INFO)
    }
    
    fun w(tag: String, message: String) {
        add(tag, message, Level.WARN)
    }
    
    fun e(tag: String, message: String) {
        add(tag, message, Level.ERROR)
    }
    
    private fun add(tag: String, message: String, level: Level) {
        val entry = LogEntry(
            time = timeFormat.format(Date()),
            tag = tag,
            message = message,
            level = level
        )
        logs.add(entry)
        
        // Write to system log as well
        when (level) {
            Level.DEBUG -> android.util.Log.d(tag, message)
            Level.INFO -> android.util.Log.i(tag, message)
            Level.WARN -> android.util.Log.w(tag, message)
            Level.ERROR -> android.util.Log.e(tag, message)
        }
        
        // Keep only last MAX_LOGS entries
        while (logs.size > MAX_LOGS) {
            logs.poll()
        }
    }
    
    fun getAll(): List<LogEntry> = logs.toList()
    
    fun clear() {
        logs.clear()
    }
}
