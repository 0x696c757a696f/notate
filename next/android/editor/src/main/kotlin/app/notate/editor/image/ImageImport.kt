package app.notate.editor.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picked image into a file ready to become an asset: EXIF rotation applied to the pixels, longest side at most
 * [MAX_SIDE] px, re-encoded as PNG (images with transparency) or JPEG. Blocking; call off the main thread.
 */
object ImageImport {
    const val MAX_SIDE = 4096
    private const val JPEG_QUALITY = 92

    class Prepared(val file: File, val width: Int, val height: Int, val mime: String, val ext: String)

    /** Size after scaling so the longest side is at most [maxSide] (never up). */
    fun scaledSize(w: Int, h: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val m = max(w, h)
        if (m <= maxSide) return w to h
        val k = maxSide.toFloat() / m
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }

    /** Whether the EXIF orientation [exif] swaps width and height (the 90° / 270° cases). */
    fun swapsAxes(exif: Int): Boolean = exif == ExifInterface.ORIENTATION_ROTATE_90 || exif == ExifInterface.ORIENTATION_ROTATE_270 ||
        exif == ExifInterface.ORIENTATION_TRANSPOSE || exif == ExifInterface.ORIENTATION_TRANSVERSE

    /** Matrix that makes pixels upright for EXIF orientation [exif]; identity when it is normal or unknown. */
    fun orientationMatrix(exif: Int): Matrix {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        return m
    }

    fun prepare(context: Context, uri: Uri, outDir: File): Prepared? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val exif = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: java.io.IOException) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val (tw, th) = scaledSize(decoded.width, decoded.height)
        val m = orientationMatrix(exif)
        if (tw != decoded.width) m.preScale(tw.toFloat() / decoded.width, th.toFloat() / decoded.height)
        val upright = if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (upright !== decoded) decoded.recycle()
        val png = upright.hasAlpha() && bounds.outMimeType != "image/jpeg"
        outDir.mkdirs()
        val out = File(outDir, "import-${System.nanoTime()}.${if (png) "png" else "jpg"}")
        out.outputStream().use { upright.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        val w = upright.width
        val h = upright.height
        upright.recycle()
        return Prepared(out, w, h, if (png) "image/png" else "image/jpeg", if (png) "png" else "jpg")
    }
}
