package com.checkmate.app.services

import android.app.*
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
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.checkmate.app.R
import com.checkmate.app.data.AppConfig
import com.checkmate.app.data.CaptureType
import com.checkmate.app.data.ContentType
import com.checkmate.app.ui.MainActivity
import com.checkmate.app.utils.SessionManager
import com.checkmate.app.utils.CapturePipeline
import kotlinx.coroutines.*
import timber.log.Timber
import java.nio.ByteBuffer
import com.checkmate.app.debug.PipelineDebugger

/**
 * Service that handles screen capture using MediaProjection API.
 */
class MediaProjectionService : Service() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pipelineDebugger = PipelineDebugger.getInstance()
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var sessionManager: SessionManager? = null
    private var capturePipeline: CapturePipeline? = null
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    private var isCapturing = false
    private var lastCaptureTime = 0L

    override fun onCreate() {
        super.onCreate()
        
        pipelineDebugger.logStageStart(PipelineDebugger.STAGE_MEDIA_PROJECTION, mapOf(
            "service" to "MediaProjectionService",
            "thread" to Thread.currentThread().name
        ))
        
        sessionManager = SessionManager.getInstance(this)
        capturePipeline = CapturePipeline.getInstance(this)
        
        try {
            initializeScreenMetrics()
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Service created successfully")
        } catch (e: Exception) {
            pipelineDebugger.logError(PipelineDebugger.STAGE_MEDIA_PROJECTION, e, "Error during service creation")
        }
        
        Timber.i("MediaProjectionService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "onStartCommand: ${intent?.action}")
        
        // CRITICAL: Start foreground service immediately - required for MediaProjection on Android 14+
        startForegroundService()
        
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Starting media projection")
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                // RESULT_OK is -1, so we check for == -1, not != -1
                if (resultCode == -1 && data != null) {
                    pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Valid projection data received", mapOf(
                        "result_code" to resultCode,
                        "has_data" to (data != null)
                    ))
                    startScreenProjection(resultCode, data)
                } else {
                    pipelineDebugger.logError(PipelineDebugger.STAGE_MEDIA_PROJECTION, 
                        Exception("Invalid media projection data"), 
                        "Invalid media projection intent data",
                        mapOf("result_code" to resultCode, "data_null" to (data == null))
                    )
                    Timber.e("Invalid media projection intent data - resultCode: $resultCode, data: ${data != null}")
                    stopSelf()
                }
            }
            
            ACTION_CAPTURE_SCREEN -> {
                pipelineDebugger.logInfo(PipelineDebugger.STAGE_SCREEN_CAPTURE, "Manual screen capture requested")
                captureScreenshot()
            }
            
            ACTION_STOP_PROJECTION -> {
                pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Stopping projection")
                stopScreenProjection()
                stopSelf()
            }
            
            else -> {
                pipelineDebugger.logWarning(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Unknown action: ${intent?.action}")
                Timber.w("Unknown action: ${intent?.action}")
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun initializeScreenMetrics() {
        val windowManager = getSystemService<WindowManager>()
        val displayMetrics = DisplayMetrics()
        
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi
        
        Timber.d("Screen metrics: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    private fun startScreenProjection(resultCode: Int, data: Intent) {
        pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Setting up screen projection")
        
        try {
            val mediaProjectionManager = getSystemService<MediaProjectionManager>()
            if (mediaProjectionManager == null) {
                val error = Exception("MediaProjectionManager not available")
                pipelineDebugger.logError(PipelineDebugger.STAGE_MEDIA_PROJECTION, error, "MediaProjectionManager service unavailable")
                Timber.e("MediaProjectionManager not available")
                stopSelf()
                return
            }
            
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Creating MediaProjection")
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                val error = Exception("MediaProjection creation failed")
                pipelineDebugger.logError(PipelineDebugger.STAGE_MEDIA_PROJECTION, error, "Failed to create MediaProjection", mapOf(
                    "result_code" to resultCode
                ))
                Timber.e("Failed to create MediaProjection")
                stopSelf()
                return
            }
            
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "MediaProjection created, registering callback")
            
            // CRITICAL: Register callback before creating virtual display (Android 14+ requirement)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.d("MediaProjection stopped by system")
                    pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "MediaProjection stopped by system")
                    stopScreenProjection()
                }
                
                override fun onCapturedContentResize(width: Int, height: Int) {
                    Timber.d("MediaProjection content resized: ${width}x${height}")
                    pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Content resized: ${width}x${height}")
                }
                
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    Timber.d("MediaProjection content visibility changed: $isVisible")
                    pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Content visibility: $isVisible")
                }
            }, null)
            
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "MediaProjection callback registered, sharing with CapturePipeline")
            
            // CRITICAL FIX: Share MediaProjection with the singleton CapturePipeline
            capturePipeline?.setMediaProjection(mediaProjection!!)
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "MediaProjection shared successfully")
            
            setupImageReader()
            setupVirtualDisplay()
            
            pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_MEDIA_PROJECTION, true, mapOf(
                "screen_width" to screenWidth,
                "screen_height" to screenHeight,
                "screen_density" to screenDensity
            ))
            
            Timber.i("Screen projection started successfully")
            
        } catch (e: Exception) {
            pipelineDebugger.logError(PipelineDebugger.STAGE_MEDIA_PROJECTION, e, "Critical error starting screen projection")
            pipelineDebugger.logStageEnd(PipelineDebugger.STAGE_MEDIA_PROJECTION, false, mapOf("error" to (e.message ?: "Unknown error")))
            Timber.e(e, "Error starting screen projection")
            stopSelf()
        }
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2 // Buffer 2 images
        )
        
        imageReader?.setOnImageAvailableListener({ reader ->
            serviceScope.launch(Dispatchers.IO) {
                processAvailableImage(reader)
            }
        }, null)
    }

    private fun setupVirtualDisplay() {
        val mediaProjection = this.mediaProjection ?: return
        val imageReader = this.imageReader ?: return
        
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CheckmateScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
    }

    private suspend fun processAvailableImage(reader: ImageReader) {
        if (isCapturing) return
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < AppConfig.CAPTURE_DEBOUNCE_MS) {
            return
        }
        
        isCapturing = true
        lastCaptureTime = currentTime
        
        try {
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                
                if (bitmap != null) {
                    // Process the captured bitmap
                    capturePipeline?.captureScreenshot(
                        bitmap = bitmap,
                        contentType = ContentType.OTHER, // Will be determined by content analysis
                        captureType = CaptureType.AUTOMATIC_SCREEN
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing captured image")
        } finally {
            isCapturing = false
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
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
            if (rowPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error converting image to bitmap")
            null
        }
    }

    private fun captureScreenshot() {
        serviceScope.launch {
            try {
                // Trigger a manual capture by requesting the next available image
                // The image will be processed in processAvailableImage
                Timber.d("Manual screenshot capture requested")
            } catch (e: Exception) {
                Timber.e(e, "Error capturing screenshot")
            }
        }
    }

    private fun stopScreenProjection() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            mediaProjection?.stop()
            mediaProjection = null
            
            Timber.i("Screen projection stopped")
            
        } catch (e: Exception) {
            Timber.e(e, "Error stopping screen projection")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        
        stopScreenProjection()
        
        capturePipeline?.cleanup()
        sessionManager?.cleanup()
        
        super.onDestroy()
        Timber.i("MediaProjectionService destroyed")
    }
    
    /**
     * Start foreground service with notification - REQUIRED for MediaProjection on Android 14+
     */
    private fun startForegroundService() {
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Timber.d("MediaProjectionService started in foreground")
        } catch (e: Exception) {
            Timber.e(e, "Error starting foreground service")
            stopSelf()
        }
    }
    
    /**
     * Create notification for foreground service
     */
    private fun createNotification(): android.app.Notification {
        val channelId = "media_projection_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create notification channel for Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Checkmate screen capture service for fact-checking"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags
        )
        
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Checkmate Screen Capture")
            .setContentText("Screen capture service is active for fact-checking")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Using system camera icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START_PROJECTION = "com.checkmate.app.START_PROJECTION"
        const val ACTION_CAPTURE_SCREEN = "com.checkmate.app.CAPTURE_SCREEN"
        const val ACTION_STOP_PROJECTION = "com.checkmate.app.STOP_PROJECTION"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        private const val NOTIFICATION_ID = 1001
        
        var instance: MediaProjectionService? = null
            private set
            
        // Track when MediaProjection permission was last granted
        private var lastPermissionGrantedTime: Long = 0
        
        /**
         * Mark that MediaProjection permission was just granted.
         */
        fun markPermissionGranted() {
            lastPermissionGrantedTime = System.currentTimeMillis()
            Timber.d("MediaProjection permission marked as granted")
        }
        
        /**
         * Check if MediaProjection permission was recently granted (within last 30 seconds).
         */
        fun hasRecentlyGrantedPermission(): Boolean {
            val timeSinceGrant = System.currentTimeMillis() - lastPermissionGrantedTime
            return timeSinceGrant < 30_000 // 30 seconds
        }
            
        /**
         * Check if MediaProjection permission is granted and active.
         */
        fun hasActiveMediaProjection(): Boolean {
            return try {
                instance?.mediaProjection != null
            } catch (e: Exception) {
                Timber.e(e, "Error checking MediaProjection status")
                false
            }
        }
    }

    init {
        instance = this
    }
}
