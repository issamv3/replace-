package com.example.service

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback helper using ML Kit OCR for areas where no accessibility nodes are exposed (e.g. Canvas/games).
 */
object OcrFallbackHelper {

    data class OcrDetectedBlock(
        val text: String,
        val bounds: Rect
    )

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeText(bitmap: Bitmap, targetArea: Rect? = null): List<OcrDetectedBlock> {
        return withContext(Dispatchers.Default) {
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = Tasks.await(recognizer.process(inputImage))

                val results = mutableListOf<OcrDetectedBlock>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        if (targetArea == null || Rect.intersects(targetArea, box)) {
                            results.add(
                                OcrDetectedBlock(
                                    text = line.text.trim(),
                                    bounds = box
                                )
                            )
                        }
                    }
                }
                results
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }
    }
}
