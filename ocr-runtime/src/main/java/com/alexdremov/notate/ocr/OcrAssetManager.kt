package com.alexdremov.notate.ocr

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal class OcrAssetManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, "ocr/ppocrv3")

    data class PreparedAssets(
        val detector: File,
        val recognizer: File,
        val dictionary: File,
    )

    @Synchronized
    fun prepare(): PreparedAssets {
        if (!modelDir.exists()) check(modelDir.mkdirs()) { "Unable to create OCR model directory" }

        val detector = copyVerified("det_db.nb", DETECTOR_SHA256)
        val recognizer = copyVerified("rec_crnn.nb", RECOGNIZER_SHA256)
        val dictionary = copyVerified("ppocr_keys_v1.txt", DICTIONARY_SHA256)
        return PreparedAssets(detector, recognizer, dictionary)
    }

    private fun copyVerified(
        name: String,
        expectedSha256: String,
    ): File {
        val destination = File(modelDir, name)
        if (!destination.exists() || sha256(destination) != expectedSha256) {
            val temporary = File(modelDir, "$name.tmp")
            appContext.assets.open("ocr/ppocrv3/$name").use { input ->
                temporary.outputStream().use(input::copyTo)
            }
            check(sha256(temporary) == expectedSha256) { "OCR asset checksum failed: $name" }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Unable to install OCR asset: $name" }
        }
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DETECTOR_SHA256 = "998632e7fc99a962a5012caaf76065ce8260e6500996b63364f243ee41b34093"
        const val RECOGNIZER_SHA256 = "6280cd7a336390c4c2f31f5688f4621052c450f8f3cb8e35cca7222aa47efad2"
        const val DICTIONARY_SHA256 = "a1c84d9bdb9ab29043c58896224d32941783eb821629618416dcb08f12886492"
    }
}
