package com.alexdremov.notate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PaddleLiteOcrEngine(
    context: Context,
) : PaddleOcrEngine {
    override val modelInfo = OcrModelInfo()

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var predictor: OCRPredictorNative? = null
    private var labels: List<String>? = null
    @Volatile private var unavailableReason: Throwable? = null

    override fun isAvailable(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } && unavailableReason == null

    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                check(isAvailable()) { "PP-OCRv3 is unavailable on this device" }
                val activePredictor = ensureLoaded()
                val activeLabels = checkNotNull(labels)
                val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    activePredictor
                        .runImage(input, MAX_DETECTION_SIDE, 1, 0, 1)
                        .mapNotNull { result ->
                            val text =
                                buildString {
                                    result.wordIndex.forEach { index ->
                                        if (index in activeLabels.indices) append(activeLabels[index])
                                    }
                                }.trim()
                            if (text.isBlank() || !result.confidence.isFinite() || result.confidence < MIN_CONFIDENCE) {
                                return@mapNotNull null
                            }
                            val quad = FloatArray(result.points.size * 2)
                            val bounds = RectF()
                            result.points.forEachIndexed { index, point ->
                                quad[index * 2] = point.x.toFloat()
                                quad[index * 2 + 1] = point.y.toFloat()
                                if (index == 0) bounds.set(point.x.toFloat(), point.y.toFloat(), point.x.toFloat(), point.y.toFloat())
                                else bounds.union(point.x.toFloat(), point.y.toFloat())
                            }
                            OcrBlock(text, result.confidence, quad, bounds)
                        }.sortedWith(compareBy<OcrBlock> { it.bounds.top }.thenBy { it.bounds.left })
                } finally {
                    if (input !== bitmap) input.recycle()
                }
            }
        }

    private fun ensureLoaded(): OCRPredictorNative {
        predictor?.let { return it }
        return try {
            val assets = OcrAssetManager(appContext).prepare()
            labels = buildList {
                add("#")
                addAll(assets.dictionary.readLines())
                add(" ")
            }
            OCRPredictorNative.Config().let { config ->
                config.useOpencl = 0
                config.cpuThreadNum = 4
                config.cpuPower = "LITE_POWER_HIGH"
                config.detModelFilename = assets.detector.absolutePath
                config.recModelFilename = assets.recognizer.absolutePath
                config.clsModelFilename = ""
                OCRPredictorNative(config).also { predictor = it }
            }
        } catch (error: Throwable) {
            unavailableReason = error
            Log.e("PaddleOCR", "Failed to initialize PP-OCRv3", error)
            throw error
        }
    }

    override fun close() {
        predictor?.destroy()
        predictor = null
    }

    companion object {
        const val MAX_DETECTION_SIDE = 960
        const val MIN_CONFIDENCE = 0.5f
    }
}

object PaddleOcrProvider {
    @Volatile private var instance: PaddleLiteOcrEngine? = null

    fun get(context: Context): PaddleLiteOcrEngine =
        instance ?: synchronized(this) {
            instance ?: PaddleLiteOcrEngine(context).also { instance = it }
        }
}
