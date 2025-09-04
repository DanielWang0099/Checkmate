package com.checkmate.app.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Image classifier using MobileNetV2 to detect significant images.
 * Uses the pre-trained model in assets/models/
 */
class ImageClassifier(private val context: Context) {

    companion object {
        private const val MODEL_FILE = "models/mobilenet_screenshot_classifier.tflite"
        private const val IMAGE_SIZE = 224
        private const val SIGNIFICANT_IMAGE_THRESHOLD = 0.5f
    }

    private var interpreter: Interpreter? = null

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(MODEL_FILE)
            val modelBuffer = ByteBuffer.allocateDirect(inputStream.available())
            val bytes = ByteArray(inputStream.available())
            inputStream.read(bytes)
            modelBuffer.put(bytes)
            modelBuffer.rewind()
            
            interpreter = Interpreter(modelBuffer)
            Timber.d("MobileNetV2 model loaded successfully")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load MobileNetV2 model - using fallback classification")
            // Model will remain null, fallback classification will be used
        }
    }

    /**
     * Determines if the image contains significant visual content
     * (as opposed to mostly text/UI elements).
     */
    fun hasSignificantImage(bitmap: Bitmap): Boolean {
        return try {
            val interpreter = this.interpreter
            
            if (interpreter == null) {
                // Fallback: simple heuristic-based classification
                return performFallbackClassification(bitmap)
            }
            
            // Preprocess image manually
            val inputBuffer = preprocessBitmap(bitmap)
            
            // Prepare output buffer
            val outputBuffer = ByteBuffer.allocateDirect(4 * 1000) // 1000 classes * 4 bytes
            outputBuffer.order(ByteOrder.nativeOrder())
            
            // Run inference
            interpreter.run(inputBuffer, outputBuffer)
            
            // Analyze results
            outputBuffer.rewind()
            val probabilities = FloatArray(1000)
            outputBuffer.asFloatBuffer().get(probabilities)
            
            // Check for image-related classes
            val imageScore = calculateImageScore(probabilities)
            
            Timber.d("Image classification score: $imageScore")
            imageScore > SIGNIFICANT_IMAGE_THRESHOLD
            
        } catch (e: Exception) {
            Timber.e(e, "Error classifying image")
            // Fallback to simple heuristic
            performFallbackClassification(bitmap)
        }
    }

    /**
     * Manually preprocess bitmap for TensorFlow Lite input
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        // Resize bitmap to model input size
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        
        // Create ByteBuffer for model input
        val inputBuffer = ByteBuffer.allocateDirect(4 * IMAGE_SIZE * IMAGE_SIZE * 3) // 4 bytes per float, RGB
        inputBuffer.order(ByteOrder.nativeOrder())
        
        // Convert bitmap to normalized float array
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resizedBitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        
        for (pixel in pixels) {
            // Extract RGB values and normalize to [-1, 1] (MobileNet input range)
            val r = ((pixel shr 16) and 0xFF) / 127.5f - 1.0f
            val g = ((pixel shr 8) and 0xFF) / 127.5f - 1.0f  
            val b = (pixel and 0xFF) / 127.5f - 1.0f
            
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
        
        return inputBuffer
    }

    /**
     * Simple fallback classification when ML model is not available.
     */
    private fun performFallbackClassification(bitmap: Bitmap): Boolean {
        try {
            // Simple heuristics based on image properties
            val width = bitmap.width
            val height = bitmap.height
            val area = width * height
            
            // Very small images are likely UI elements
            if (area < 10000) return false
            
            // Analyze color diversity as a proxy for image content
            val colorDiversity = analyzeColorDiversity(bitmap)
            val aspectRatio = width.toFloat() / height.toFloat()
            
            // Score based on multiple factors
            var score = 0f
            
            // Size factor
            score += (area / 100000f).coerceAtMost(0.3f)
            
            // Color diversity factor
            score += colorDiversity * 0.4f
            
            // Aspect ratio factor (photos tend to have certain ratios)
            if (aspectRatio in 0.5f..2.0f) {
                score += 0.2f
            }
            
            Timber.d("Fallback image classification score: $score")
            return score > SIGNIFICANT_IMAGE_THRESHOLD
            
        } catch (e: Exception) {
            Timber.e(e, "Error in fallback classification")
            return false
        }
    }

    /**
     * Analyze color diversity in the image.
     */
    private fun analyzeColorDiversity(bitmap: Bitmap): Float {
        try {
            // Sample pixels from the image
            val sampleSize = 50
            val stepX = bitmap.width / sampleSize
            val stepY = bitmap.height / sampleSize
            
            val colors = mutableSetOf<Int>()
            var totalBrightness = 0f
            var pixelCount = 0
            
            for (x in 0 until bitmap.width step stepX) {
                for (y in 0 until bitmap.height step stepY) {
                    if (x < bitmap.width && y < bitmap.height) {
                        val pixel = bitmap.getPixel(x, y)
                        colors.add(pixel and 0xFFFFFF) // Remove alpha channel
                        
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        totalBrightness += (r + g + b) / 3f
                        pixelCount++
                    }
                }
            }
            
            val uniqueColors = colors.size
            val averageBrightness = if (pixelCount > 0) totalBrightness / pixelCount else 128f
            
            // Normalize diversity score
            val diversityScore = (uniqueColors / (sampleSize * sampleSize).toFloat()).coerceAtMost(1f)
            
            // Adjust for brightness variation (very bright or dark images are often text/UI)
            val brightnessAdjustment = if (averageBrightness < 50 || averageBrightness > 200) 0.7f else 1f
            
            return diversityScore * brightnessAdjustment
            
        } catch (e: Exception) {
            Timber.e(e, "Error analyzing color diversity")
            return 0.5f
        }
    }

    private fun calculateImageScore(probabilities: FloatArray): Float {
        // ImageNet classes that indicate significant visual content
        // These are approximate indices for common image classes in MobileNetV2
        val imageClassIndices = listOf(
            // People
            281, 282, 283, 284, 285,
            // Animals  
            150, 151, 152, 153, 154, 155, 156, 157, 158, 159,
            // Vehicles
            404, 407, 436, 444, 511, 609, 627, 656, 661, 705,
            // Food
            923, 924, 925, 926, 927, 928, 929, 930, 931, 932,
            // Nature/Landscapes
            970, 971, 972, 973, 974, 975, 976, 977, 978, 979,
            // Objects
            417, 418, 419, 420, 421, 422, 423, 424, 425, 426
        )
        
        var maxImageScore = 0f
        var totalImageScore = 0f
        
        for (index in imageClassIndices) {
            if (index < probabilities.size) {
                val score = probabilities[index]
                maxImageScore = maxOf(maxImageScore, score)
                totalImageScore += score
            }
        }
        
        // Combine max score and total score
        return (maxImageScore * 0.7f + (totalImageScore / imageClassIndices.size) * 0.3f)
    }

    /**
     * Get detailed classification results for debugging.
     */
    fun classifyImage(bitmap: Bitmap): Map<String, Float> {
        return try {
            val interpreter = this.interpreter ?: return emptyMap()
            
            val inputBuffer = preprocessBitmap(bitmap)
            val outputBuffer = ByteBuffer.allocateDirect(4 * 1000)
            outputBuffer.order(ByteOrder.nativeOrder())
            
            interpreter.run(inputBuffer, outputBuffer)
            
            outputBuffer.rewind()
            val probabilities = FloatArray(1000)
            outputBuffer.asFloatBuffer().get(probabilities)
            
            // Return top 5 classifications
            val results = mutableMapOf<String, Float>()
            val sortedIndices = probabilities.indices.sortedByDescending { probabilities[it] }
            
            for (i in 0 until 5) {
                val index = sortedIndices[i]
                val className = getClassName(index)
                results[className] = probabilities[index]
            }
            
            results
            
        } catch (e: Exception) {
            Timber.e(e, "Error in detailed classification")
            emptyMap()
        }
    }

    private fun getClassName(index: Int): String {
        // Simplified class name mapping
        // In a full implementation, you'd load the labels from a file
        return when (index) {
            in 281..285 -> "person"
            in 150..200 -> "animal"
            in 400..450 -> "vehicle"
            in 920..950 -> "food"
            in 970..999 -> "nature"
            else -> "class_$index"
        }
    }

    fun cleanup() {
        interpreter?.close()
        interpreter = null
    }
}
