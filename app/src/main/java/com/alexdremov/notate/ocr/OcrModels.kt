package com.alexdremov.notate.ocr

import android.graphics.RectF

data class OcrModelInfo(
    val id: String = "ppocrv3-zh-en-mobile",
    val preprocessingVersion: Int = 1,
    val dictionaryVersion: String = "ppocr_keys_v1",
) {
    val indexVersion: String = "$id:$preprocessingVersion:$dictionaryVersion"
}

data class OcrBlock(
    val text: String,
    val confidence: Float,
    val quadrilateral: FloatArray,
    val bounds: RectF,
)

interface PaddleOcrEngine : AutoCloseable {
    val modelInfo: OcrModelInfo

    suspend fun recognize(bitmap: android.graphics.Bitmap): List<OcrBlock>

    fun isAvailable(): Boolean
}

enum class OcrTextSource {
    FILENAME,
    TYPED_TEXT,
    INK_OCR,
}
