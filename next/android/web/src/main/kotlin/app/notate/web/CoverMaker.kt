package app.notate.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Writes `thumbs/cover.webp` for imported books: the EPUB cover image, or a black title card. Any thread. */
internal object CoverMaker {
    const val WIDTH = 600
    const val HEIGHT = 800

    /** Decodes [image] scaled to [WIDTH] wide and writes it; false if it is not a decodable image. */
    fun fromImage(image: File, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val opts = BitmapFactory.Options().apply { inSampleSize = maxOf(1, bounds.outWidth / WIDTH) }
        val decoded = BitmapFactory.decodeFile(image.path, opts) ?: return false
        val scaled = if (decoded.width > WIDTH) {
            Bitmap.createScaledBitmap(decoded, WIDTH, maxOf(1, decoded.height * WIDTH / decoded.width), true)
        } else decoded
        if (scaled !== decoded) decoded.recycle()
        return try { saveWebp(scaled, out); true } finally { scaled.recycle() }
    }

    /** White title (and author) on black. */
    fun titleCard(title: String, author: String?, out: File) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val pad = 48
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 52f; isFakeBoldText = true }
            val titleLayout = layout(title, titlePaint, WIDTH - 2 * pad, 5)
            canvas.save(); canvas.translate(pad.toFloat(), 120f); titleLayout.draw(canvas); canvas.restore()
            if (!author.isNullOrBlank()) {
                val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f }
                val authorLayout = layout(author, authorPaint, WIDTH - 2 * pad, 2)
                canvas.save(); canvas.translate(pad.toFloat(), HEIGHT - 120f - authorLayout.height); authorLayout.draw(canvas); canvas.restore()
            }
            saveWebp(bitmap, out)
        } finally {
            bitmap.recycle()
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

    private fun saveWebp(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            check(bitmap.compress(format, 80, out)) { "WebP encoding failed for ${file.name}" }
        }
    }
}
