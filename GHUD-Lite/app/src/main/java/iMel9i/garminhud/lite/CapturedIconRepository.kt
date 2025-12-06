package iMel9i.garminhud.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the storage and retrieval of captured notification icons.
 * Stores bitmaps primarily on disk and metadata in SharedPreferences/JSON (simplified here).
 */
object CapturedIconRepository {
    private const val TAG = "CapturedIconRepo"
    private const val DIR_NAME = "captured_icons"
    private const val MAPPING_PREFS = "IconMappings"

    data class CapturedIcon(
        val hash: Long,
        val timestamp: Long,
        val bitmapPath: String,
        var mappedIcon: HudIcon? = null
    )

    private val cache = mutableListOf<CapturedIcon>()
    private var isLoaded = false

    fun init(context: Context) {
        if (isLoaded) return
        loadMappings(context)
        isLoaded = true
    }

    /**
     * Saves a captured icon if it's new (unique hash).
     */
    fun saveIcon(context: Context, bitmap: Bitmap, hash: Long) {
        if (cache.any { it.hash == hash }) return // Already captured

        val fileName = "$hash.png"
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, fileName)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Saved new icon: $fileName")
            
            val icon = CapturedIcon(hash, System.currentTimeMillis(), file.absolutePath, null)
            cache.add(0, icon) // Add to top
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save icon", e)
        }
    }

    fun getIcons(): List<CapturedIcon> {
        return cache
    }

    fun updateMapping(context: Context, hash: Long, hudIcon: HudIcon) {
        val item = cache.find { it.hash == hash }
        item?.mappedIcon = hudIcon
        
        val prefs = context.getSharedPreferences(MAPPING_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(hash.toString(), hudIcon.id).apply()
        Log.d(TAG, "Mapped $hash -> ${hudIcon.displayName}")
    }

    fun getMapping(hash: Long): HudIcon? {
        return cache.find { it.hash == hash }?.mappedIcon
    }

    private fun loadMappings(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return

        val prefs = context.getSharedPreferences(MAPPING_PREFS, Context.MODE_PRIVATE)
        val files = dir.listFiles() ?: return

        // Load all files
        for (file in files) {
            try {
                val name = file.nameWithoutExtension // hash is filename
                val hash = name.toLong()
                
                val mappedId = prefs.getString(name, null)
                val mappedIcon = if (mappedId != null) HudIcon.fromId(mappedId) else null
                
                cache.add(CapturedIcon(hash, file.lastModified(), file.absolutePath, mappedIcon))
            } catch (e: NumberFormatException) {
                // Ignore non-hash files
            }
        }
        // Sort by newest
        cache.sortByDescending { it.timestamp }
    }
}
