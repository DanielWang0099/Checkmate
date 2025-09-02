package com.checkmate.app.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import com.checkmate.app.data.AppConfig
import com.checkmate.app.data.CaptureType
import com.checkmate.app.data.ContentType
import com.checkmate.app.managers.SessionManager
import com.checkmate.app.utils.CapturePipeline
import kotlinx.coroutines.*
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Service that handles screen capture using MediaProjection API.
 */
class MediaProjectionService : Service(), LifecycleOwner {
    
    private val dispatcher = ServiceLifecycleDispatcher(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
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

    override fun getLifecycle() = dispatcher.lifecycle

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        super.onCreate()
        
        sessionManager = SessionManager(this)
        capturePipeline = CapturePipeline(this)
        
        initializeScreenMetrics()
        
        Timber.i("MediaProjectionService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dispatcher.onServicePreSuperOnStart()
        
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                if (resultCode != -1 && data != null) {
                    startScreenProjection(resultCode, data)
                } else {
                    Timber.e("Invalid media projection intent data")
                    stopSelf()
                }
            }
            
            ACTION_CAPTURE_SCREEN -> {
                captureScreenshot()
            }
            
            ACTION_STOP_PROJECTION -> {
                stopScreenProjection()
                stopSelf()
            }
            
            else -> {
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
        try {
            val mediaProjectionManager = getSystemService<MediaProjectionManager>()
            if (mediaProjectionManager == null) {
                Timber.e("MediaProjectionManager not available")
                stopSelf()
                return
            }
            
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Timber.e("Failed to create MediaProjection")
                stopSelf()
                return
            }
            
            setupImageReader()
            setupVirtualDisplay()
            
            Timber.i("Screen projection started successfully")
            
        } catch (e: Exception) {
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
        dispatcher.onServicePreSuperOnDestroy()
        serviceScope.cancel()
        
        stopScreenProjection()
        
        capturePipeline?.cleanup()
        sessionManager?.cleanup()
        
        super.onDestroy()
        Timber.i("MediaProjectionService destroyed")
    }

    companion object {
        const val ACTION_START_PROJECTION = "com.checkmate.app.START_PROJECTION"
        const val ACTION_CAPTURE_SCREEN = "com.checkmate.app.CAPTURE_SCREEN"
        const val ACTION_STOP_PROJECTION = "com.checkmate.app.STOP_PROJECTION"
        
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        var instance: MediaProjectionService? = null
            private set
    }

    init {
        instance = this
    }
}
