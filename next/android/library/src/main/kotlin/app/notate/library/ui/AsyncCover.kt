package app.notate.library.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.notate.core.model.DocId
import app.notate.core.store.library.DocumentEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decodes `thumbs/cover.webp` files and keeps recent ones in a small memory cache keyed by document and cover revision. */
class CoverLoader(private val fileFor: (DocId) -> File) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun key(id: DocId, rev: Long) = "${id.value}#$rev"

    fun cached(id: DocId, rev: Long): Bitmap? = cache.get(key(id, rev))

    /** Decodes the cover on IO; null when the file is missing or unreadable. */
    suspend fun load(id: DocId, rev: Long): Bitmap? = withContext(Dispatchers.IO) {
        val file = fileFor(id)
        if (!file.isFile) return@withContext null
        val bmp = BitmapFactory.decodeFile(file.path) ?: return@withContext null
        cache.put(key(id, rev), bmp)
        bmp
    }

    private companion object { const val CACHE_KB = 24 * 1024 }
}

/** Cover image of a document, reloaded whenever [DocumentEntry.coverRev] changes; blank white until it exists. */
@Composable
fun AsyncCover(entry: DocumentEntry, loader: CoverLoader, modifier: Modifier = Modifier) {
    var bitmap by remember(entry.id, entry.coverRev) { mutableStateOf(loader.cached(entry.id, entry.coverRev)) }
    LaunchedEffect(entry.id, entry.coverRev) {
        if (bitmap == null) bitmap = loader.load(entry.id, entry.coverRev)
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, modifier = modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
    } else {
        Box(modifier.fillMaxSize().background(Color.White))
    }
}
