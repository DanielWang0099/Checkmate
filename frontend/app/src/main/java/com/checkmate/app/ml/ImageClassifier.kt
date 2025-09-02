package com.checkmate.app.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import timber.log.Timber
import java.nio.ByteBuffer

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
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(IMAGE_SIZE, IMAGE_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(modelBuffer)
            Timber.d("MobileNetV2 model loaded successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load MobileNetV2 model")
        }
    }

    /**
     * Determines if the image contains significant visual content
     * (as opposed to mostly text/UI elements).
     */
    fun hasSignificantImage(bitmap: Bitmap): Boolean {
        return try {
            val interpreter = this.interpreter ?: return false
            
            // Preprocess image
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = imageProcessor.process(tensorImage)
            
            // Prepare input buffer
            val inputBuffer = processedImage.buffer
            
            // Prepare output buffer
            val outputBuffer = ByteBuffer.allocateDirect(4 * 1000) // 1000 classes * 4 bytes
            outputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
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
            false
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
            
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = imageProcessor.process(tensorImage)
            
            val inputBuffer = processedImage.buffer
            val outputBuffer = ByteBuffer.allocateDirect(4 * 1000)
            outputBuffer.order(java.nio.ByteOrder.nativeOrder())
            
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
