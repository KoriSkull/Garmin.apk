package iMel9i.garminhud.lite

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer

/**
 * Service for capturing screen using MediaProjection API
 */
class ScreenCaptureService : Service() {
    
    companion object {
        private const val TAG = "ScreenCapture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        
        var instance: ScreenCaptureService? = null
        
        fun isRunning(): Boolean = instance != null
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        DebugLog.i(TAG, "Service created: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
        
        // Start as foreground service
        startForeground()
    }
    
    private fun startForeground() {
        val channelId = "screen_capture_channel"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Screen Capture",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Arrow recognition screen capture"
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            android.app.Notification.Builder(this)
        }
            .setContentTitle("Arrow Recognition Active")
            .setContentText("Capturing screen for navigation arrows")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        
        startForeground(2, notification)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            DebugLog.e(TAG, "Intent is null")
            stopSelf()
            return START_NOT_STICKY
        }
        
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        
        if (resultCode == -1 || resultData == null) {
            DebugLog.e(TAG, "Invalid result code or data")
            stopSelf()
            return START_NOT_STICKY
        }
        
        startMediaProjection(resultCode, resultData)
        return START_STICKY
    }
    
    private fun startMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            
            if (mediaProjection == null) {
                DebugLog.e(TAG, "Failed to create MediaProjection")
                stopSelf()
                return
            }
            
            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
            
            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )
            
            DebugLog.i(TAG, "MediaProjection started successfully")
            
        } catch (e: Exception) {
            DebugLog.e(TAG, "Error starting MediaProjection: ${e.message}")
            stopSelf()
        }
    }
    
    /**
     * Capture a screenshot and crop to specified bounds
     */
    fun captureScreen(bounds: android.graphics.Rect, callback: (Bitmap?) -> Unit) {
        if (imageReader == null) {
            DebugLog.e(TAG, "ImageReader is null")
            callback(null)
            return
        }
        
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                DebugLog.w(TAG, "No image available")
                callback(null)
                return
            }
            
            val bitmap = imageToBitmap(image)
            image.close()
            
            if (bitmap == null) {
                DebugLog.e(TAG, "Failed to convert image to bitmap")
                callback(null)
                return
            }
            
            // Crop to bounds
            val left = bounds.left.coerceAtLeast(0)
            val top = bounds.top.coerceAtLeast(0)
            val width = bounds.width().coerceAtMost(bitmap.width - left)
            val height = bounds.height().coerceAtMost(bitmap.height - top)
            
            if (width <= 0 || height <= 0) {
                DebugLog.e(TAG, "Invalid crop bounds")
                bitmap.recycle()
                callback(null)
                return
            }
            
            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
            bitmap.recycle()
            
            DebugLog.i(TAG, "Screenshot captured and cropped: ${width}x${height}")
            callback(cropped)
            
        } catch (e: Exception) {
            DebugLog.e(TAG, "Error capturing screen: ${e.message}")
            callback(null)
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Crop to actual screen size if there's padding
            return if (rowPadding != 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Error converting image to bitmap: ${e.message}")
            return null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        instance = null
        
        DebugLog.i(TAG, "Service destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
