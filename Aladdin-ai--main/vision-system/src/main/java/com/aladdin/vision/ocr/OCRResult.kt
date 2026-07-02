package com.aladdin.vision.ocr

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

data class OCRResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val language: String,
    val confidence: Float
) {
    val isEmpty: Boolean get() = fullText.isBlank()

    companion object {
        fun from(text: Text): OCRResult {
            val blocks = text.textBlocks.map { block ->
                TextBlock(
                    text = block.text,
                    boundingBox = block.boundingBox,
                    lines = block.lines.map { line ->
                        TextLine(
                            text = line.text,
                            boundingBox = line.boundingBox,
                            elements = line.elements.map { element ->
                                TextElement(
                                    text = element.text,
                                    boundingBox = element.boundingBox,
                                    confidence = element.confidence ?: 1f
                                )
                            }
                        )
                    }
                )
            }

            val avgConfidence = blocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .mapNotNull { it.confidence.takeIf { c -> c > 0 } }
                .average()
                .toFloat()
                .takeIf { !it.isNaN() } ?: 1f

            return OCRResult(
                fullText = text.text,
                blocks = blocks,
                language = "auto",
                confidence = avgConfidence
            )
        }
    }
}

data class TextBlock(
    val text: String,
    val boundingBox: Rect?,
    val lines: List<TextLine>
)

data class TextLine(
    val text: String,
    val boundingBox: Rect?,
    val elements: List<TextElement>
)

data class TextElement(
    val text: String,
    val boundingBox: Rect?,
    val confidence: Float
)
