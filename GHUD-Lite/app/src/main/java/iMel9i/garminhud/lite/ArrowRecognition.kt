package iMel9i.garminhud.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * Утилиты для работы с изображениями (портировано из GoogleMaps_HUD)
 */
object ImageUtils {
    
    fun getGreenAlpha(pixel: Int): Int {
        val alpha = (pixel shr 24) and 0xff
        val green = (pixel shr 8) and 0xff
        return (green * alpha) shr 8
    }
    
    fun toBinaryImage(bitmap: Bitmap, treatAsWhite: Int = 200) {
        for (h in 0 until bitmap.height) {
            for (w in 0 until bitmap.width) {
                val p = bitmap.getPixel(w, h)
                val greenAlpha = getGreenAlpha(p)
                bitmap.setPixel(w, h, if (greenAlpha > treatAsWhite) 0xffffffff.toInt() else 0)
            }
        }
    }
    
    fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        
        if (drawable is BitmapDrawable) {
            drawable.bitmap?.let { return it }
        }
        
        val width = if (drawable.intrinsicWidth <= 0) 1 else drawable.intrinsicWidth
        val height = if (drawable.intrinsicHeight <= 0) 1 else drawable.intrinsicHeight
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        
        return bitmap
    }
}

/**
 * Класс для распознавания стрелок направления
 * Портировано из GoogleMaps_HUD ArrowImage.java
 */
class ArrowImage(bitmap: Bitmap) {
    
    companion object {
        private const val TAG = "ArrowImage"
        const val IMAGE_LEN = 8 // 8x8 grid
        const val CONTENT_LEN = IMAGE_LEN * IMAGE_LEN
        private const val TREAT_AS_WHITE = 200
        private const val ALPHA_AS_WHITE = 254
        private const val STANDARD_IMG_SIZE = 132
    }
    
    val content = BooleanArray(CONTENT_LEN)
    var leftValue: Long = 0L
    var binaryImage: Bitmap? = null
    
    init {
        // Create a MUTABLE copy to avoid IllegalStateException on setPixel
        // Notification bitmaps are often immutable
        val mutableBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        
        ImageUtils.toBinaryImage(mutableBitmap, TREAT_AS_WHITE)
        val resized = resizeImage(mutableBitmap, STANDARD_IMG_SIZE)
        
        if (resized != null) {
            binaryImage = resized
            calculateHash(resized)
        }
        
        // If we created a copy, we should recycle it after use (unless resized uses it)
        // Note: resized might be the same instance if size matches
        if (mutableBitmap !== bitmap && mutableBitmap !== resized) {
            mutableBitmap.recycle()
        }
    }
    
    private fun resizeImage(bitmap: Bitmap, newLength: Int): Bitmap? {
        if (bitmap.width == newLength && bitmap.height == newLength) {
            return bitmap
        }
        if (bitmap.width != bitmap.height) {
            Log.w(TAG, "Bitmap is not square: ${bitmap.width}x${bitmap.height}")
            return null
        }
        
        val orgLength = bitmap.height
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val resized = Bitmap.createBitmap(newLength, newLength, config)
        val ratio = bitmap.height / (newLength * 1.0f)
        
        for (h in 0 until newLength) {
            val h0 = minOf((h * ratio).toInt(), orgLength - 1)
            for (w in 0 until newLength) {
                val w0 = minOf((w * ratio).toInt(), orgLength - 1)
                val pixel = bitmap.getPixel(w0, h0)
                resized.setPixel(w, h, pixel)
            }
        }
        
        return resized
    }
    
    private fun calculateHash(resized: Bitmap) {
        val interval = resized.width / IMAGE_LEN
        var index = 0
        leftValue = 0L
        
        for (h0 in 0 until IMAGE_LEN) {
            val h = h0 * interval
            for (w0 in 0 until IMAGE_LEN) {
                val w = w0 * interval
                val p = resized.getPixel(w, h)
                val greenAlpha = ImageUtils.getGreenAlpha(p)
                val bit = greenAlpha >= ALPHA_AS_WHITE
                
                content[h0 * IMAGE_LEN + w0] = bit
                val shiftValue = (if (bit) 1L else 0L) shl index
                leftValue = leftValue or shiftValue
                index++
            }
        }
        
        Log.d(TAG, "Arrow hash: $leftValue")
    }
    
    fun getSAD(magicNumber: Long): Int {
        var sad = 0
        val length = if (IMAGE_LEN == 8) CONTENT_LEN - 1 else CONTENT_LEN
        
        for (x in 0 until length) {
            val bit = 1L == ((magicNumber shr x) and 1L)
            if (content[x] != bit) sad++
        }
        
        return sad
    }
    
    fun getArrowValue(): Long = leftValue

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Arrow Hash: $leftValue\n")
        val interval = if (IMAGE_LEN > 0) IMAGE_LEN else 8
        for (h in 0 until interval) {
            for (w in 0 until interval) {
                val index = h * interval + w
                if (index < content.size) {
                    sb.append(if (content[index]) "# " else ". ")
                }
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}

/**
 * Enum стрелок направления (из оригинального GoogleMaps_HUD)
 */
enum class ArrowDirection(val value: Long, val hudCode: Int) {
    STRAIGHT(1157442765411848192L, 0),
    LEFT(4665941148185659392L, 1),
    EASY_LEFT(4629771062305364992L, 2),
    EASY_RIGHT(289360692026306560L, 3),
    KEEP_LEFT(1157442799634484224L, 4),
    KEEP_RIGHT(1157442903121879040L, 5),
    RIGHT(289360692970790912L, 6),
    SHARP_LEFT(4629771114118135808L, 7),
    SHARP_RIGHT(289361105548739584L, 8),
    NONE(0L, 0);
    
    companion object {
        private const val TAG = "ArrowDirection"
        private const val MAX_SAD_THRESHOLD = 10 // Максимальная допустимая разница
        
        fun recognize(arrowImage: ArrowImage): ArrowDirection {
            val hash = arrowImage.getArrowValue()
            var bestMatch = NONE
            var minSAD = Int.MAX_VALUE
            
            // 1. Check Custom Mappings first
            for ((customHash, direction) in CustomArrowManager.getList()) {
                val sad = arrowImage.getSAD(customHash)
                if (sad < minSAD) {
                    minSAD = sad
                    bestMatch = direction
                }
            }
            
            // 2. Check Standard Mappings if no perfect custom match (or to find better standard match)
            // If we found a very good custom match (SAD=0), we can skip standard
            if (minSAD > 0) {
                 for (arrow in values()) {
                    if (arrow == NONE) continue
                    
                    val sad = arrowImage.getSAD(arrow.value)
                    if (sad < minSAD) {
                        minSAD = sad
                        bestMatch = arrow
                    }
                }
            }
            
            return if (minSAD <= MAX_SAD_THRESHOLD) {
                Log.d(TAG, "Recognized: $bestMatch (SAD=$minSAD, hash=$hash)")
                bestMatch
            } else {
                Log.w(TAG, "No match found (minSAD=$minSAD, hash=$hash)")
                NONE
            }
        }
    }
}

object CustomArrowManager {
    private const val PREFS_NAME = "CustomArrows"
    private val mappings = mutableListOf<Pair<Long, ArrowDirection>>()
    private var isLoaded = false

    fun load(context: android.content.Context) {
        if (isLoaded) return
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        mappings.clear()
        prefs.all.forEach { (key, value) ->
            try {
                val hash = key.toLong()
                val directionName = value as String
                val direction = ArrowDirection.valueOf(directionName)
                mappings.add(hash to direction)
            } catch (e: Exception) {
                Log.e("CustomArrowManager", "Error loading mapping: $key", e)
            }
        }
        isLoaded = true
        Log.i("CustomArrowManager", "Loaded ${mappings.size} custom arrows")
    }

    fun add(context: android.content.Context, hash: Long, direction: ArrowDirection) {
        // Remove existing mapping for this hash if any
        mappings.removeAll { it.first == hash }
        mappings.add(hash to direction)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(hash.toString(), direction.name).apply()
        Log.i("CustomArrowManager", "Added custom arrow: $hash -> $direction")
    }
    
    fun remove(context: android.content.Context, hash: Long) {
        mappings.removeAll { it.first == hash }
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(hash.toString()).apply()
    }
    
    fun getList(): List<Pair<Long, ArrowDirection>> = mappings
}
