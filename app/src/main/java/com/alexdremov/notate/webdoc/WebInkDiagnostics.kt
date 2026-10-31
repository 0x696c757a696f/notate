package com.alexdremov.notate.webdoc

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Text of the "Ink diagnostics" section: lets the user tell, on the device, why handwriting went missing. */
object WebInkDiagnostics {
    fun format(
        href: String?,
        memoryStrokes: Int,
        diskStrokes: Int?,
        stats: WebAnnotationStore.SaveStats,
        driver: String,
        scale: Float,
        contentWidth: Int,
        recordedLayoutWidth: Float?,
    ): String =
        buildString {
            appendLine("Ink diagnostics")
            appendLine("Page: ${href ?: "-"}")
            val disk = diskStrokes?.toString() ?: "unreadable"
            val verdict =
                when {
                    diskStrokes == null -> "⚠ file unreadable"
                    stats.unsaved -> "unsaved changes pending"
                    diskStrokes == memoryStrokes -> "✓ in sync"
                    else -> "⚠ MISMATCH"
                }
            appendLine("Strokes: memory $memoryStrokes / disk $disk  ($verdict)")
            val whenText =
                if (stats.lastSaveAtMs == 0L) {
                    "never in this session"
                } else {
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(stats.lastSaveAtMs)) + ", ${stats.lastSaveDurationMs} ms"
                }
            appendLine("Last save: $whenText (${stats.saveCount} saves, ${stats.holders} open view(s))")
            if (stats.lastError != null) appendLine("⚠ Last save error: ${stats.lastError}")
            appendLine(driver)
            appendLine("Zoom: ${"%.2f".format(Locale.US, scale)}  Viewport width: $contentWidth dp")
            appendLine("Width when first written: ${recordedLayoutWidth?.let { "%.0f".format(Locale.US, it) } ?: "-"}")
        }.trimEnd()
}
