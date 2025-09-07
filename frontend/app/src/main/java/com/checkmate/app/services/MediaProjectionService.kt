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
import com.checkmate.app.data.AppConfig
import com.checkmate.app.data.CaptureType
import com.checkmate.app.data.ContentType
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
        
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "Starting media projection")
                
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                if (resultCode != -1 && data != null) {
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
                    Timber.e("Invalid media projection intent data")
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
            
            pipelineDebugger.logInfo(PipelineDebugger.STAGE_MEDIA_PROJECTION, "MediaProjection created, setting up capture")
            
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
