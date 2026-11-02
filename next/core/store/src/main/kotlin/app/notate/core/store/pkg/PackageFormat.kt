package app.notate.core.store.pkg

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `manifest.json` of a `.notate` package. */
@Serializable
data class PackageManifest(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val docId: String,
    val title: String,
    val tags: List<String> = emptyList(),
    val exportedAt: Long,
    val modifiedAt: Long = 0,
    val kind: String = "",
) {
    companion object {
        const val FORMAT = "notate-package"
        const val VERSION = 1
        const val MANIFEST = "manifest.json"
        const val DB = "doc.db"
        const val ASSETS = "assets/"
        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** What [PackageReader.inspect] reports without extracting. */
data class PackageInfo(
    val docId: String,
    val title: String,
    val tags: List<String>,
    val exportedAt: Long,
    val modifiedAt: Long,
    val dbBytes: Long,
    val assetBytes: Long,
)

class PackageException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { NOT_A_PACKAGE, UNSUPPORTED_VERSION, UNSAFE, TOO_LARGE }
}
