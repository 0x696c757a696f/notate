package app.notate.core.store.codec

import app.notate.core.model.Background
import app.notate.core.model.CorruptDataException
import app.notate.core.model.DocumentMeta
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** JSON encoding of `Background` and `DocumentMeta` (stored as TEXT). */
object JsonCodecs {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "type" }

    fun encodeBackground(b: Background): String = json.encodeToString(Background.serializer(), b)
    fun decodeBackground(s: String): Background = guard { json.decodeFromString(Background.serializer(), s) }
    fun encodeMeta(m: DocumentMeta): String = json.encodeToString(DocumentMeta.serializer(), m)
    fun decodeMeta(s: String): DocumentMeta = guard { json.decodeFromString(DocumentMeta.serializer(), s) }

    private inline fun <T> guard(block: () -> T): T =
        try { block() } catch (e: SerializationException) { throw CorruptDataException("Corrupt JSON: ${e.message}") }
}
