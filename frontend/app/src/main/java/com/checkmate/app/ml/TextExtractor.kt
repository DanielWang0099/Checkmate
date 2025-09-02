package com.checkmate.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Text extraction using ML Kit OCR.
 */
class TextExtractor(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): String {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = textRecognizer.process(image).await()
            
            // Extract text blocks and prioritize important content
            val textBlocks = visionText.textBlocks
            
            if (textBlocks.isEmpty()) {
                return ""
            }
            
            // Sort blocks by importance (size, position, etc.)
            val sortedBlocks = textBlocks.sortedWith(compareByDescending<com.google.mlkit.vision.text.Text.TextBlock> { 
                it.boundingBox?.width()?.times(it.boundingBox?.height() ?: 0) ?: 0
            }.thenBy { 
                it.boundingBox?.top ?: Int.MAX_VALUE
            })
            
            // Extract text with priorities
            val priorityText = mutableListOf<String>()
            
            for (block in sortedBlocks) {
                val blockText = block.text.trim()
                if (blockText.isNotBlank() && blockText.length > 2) {
                    
                    // Prioritize certain types of content
                    val priority = calculateTextPriority(block, blockText)
                    
                    if (priority > 0.3f) {
                        priorityText.add(blockText)
                    }
                }
            }
            
            // Join text blocks with appropriate separators
            priorityText.joinToString(separator = " | ").take(1200)
            
        } catch (e: Exception) {
            Timber.e(e, "Error extracting text from image")
            ""
        }
    }
    
    private fun calculateTextPriority(
        block: com.google.mlkit.vision.text.Text.TextBlock,
        text: String
    ): Float {
        var priority = 0.5f
        
        // Size factor
        val boundingBox = block.boundingBox
        if (boundingBox != null) {
            val area = boundingBox.width() * boundingBox.height()
            priority += (area / 10000f).coerceAtMost(0.3f)
        }
        
        // Content type factors
        when {
            // Headlines and titles (all caps, short)
            text.isAllCaps() && text.length < 50 -> priority += 0.4f
            
            // Numbers and statistics
            text.contains(Regex("\\d+%|\\$\\d+|\\d+\\.\\d+")) -> priority += 0.3f
            
            // Captions and labels
            text.startsWith("Caption:", ignoreCase = true) -> priority += 0.4f
            text.startsWith("By ", ignoreCase = true) -> priority += 0.3f
            
            // URLs and handles
            text.contains("@") || text.contains("http") -> priority += 0.2f
            
            // Very short text (likely UI elements)
            text.length < 5 -> priority -= 0.3f
            
            // Very long text (likely body content)
            text.length > 200 -> priority += 0.1f
        }
        
        return priority.coerceIn(0f, 1f)
    }
    
    private fun String.isAllCaps(): Boolean {
        return this.isNotBlank() && this == this.uppercase() && this.any { it.isLetter() }
    }
    
    fun cleanup() {
        textRecognizer.close()
    }
}
