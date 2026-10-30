package com.alexdremov.notate.ocr.index

import android.content.Context
import android.graphics.RectF
import com.alexdremov.notate.ocr.OcrTextSource
import kotlinx.coroutines.flow.Flow

data class OcrSearchResult(
    val documentId: String,
    val projectId: String?,
    val path: String,
    val documentName: String,
    val source: OcrTextSource,
    val text: String,
    val snippet: String,
    val confidence: Float,
    val bounds: RectF?,
)

class OcrSearchRepository private constructor(
    private val dao: OcrIndexDao,
) {
    val indexedDocumentCount: Flow<Int> = dao.indexedDocumentCount()
    val indexingDocumentCount: Flow<Int> = dao.indexingDocumentCount()
    val staleDocumentCount: Flow<Int> = dao.staleDocumentCount()

    suspend fun search(
        query: String,
        limit: Int = 100,
    ): List<OcrSearchResult> {
        val normalizedQuery = OcrSearchNormalizer.normalize(query)
        val ftsQuery = OcrSearchNormalizer.ftsQuery(query)
        if (normalizedQuery.isBlank() || ftsQuery.isBlank()) return emptyList()

        return dao
            .search(ftsQuery, limit * 2)
            .asSequence()
            .filter { row ->
                val documentName = OcrSearchNormalizer.normalize(row.documentName)
                if (OcrSearchNormalizer.containsCjk(normalizedQuery)) {
                    row.normalizedText.contains(normalizedQuery) || documentName.contains(normalizedQuery)
                } else {
                    val queryTokens = OcrSearchNormalizer.tokens(normalizedQuery)
                    val candidateTokens = OcrSearchNormalizer.tokens(row.normalizedText + " " + documentName)
                    queryTokens.all { queryToken -> candidateTokens.any { it.startsWith(queryToken) } }
                }
            }.distinctBy { Triple(it.documentId, it.source, it.text) }
            .take(limit)
            .map { row ->
                OcrSearchResult(
                    documentId = row.documentId,
                    projectId = row.projectId,
                    path = row.path,
                    documentName = row.documentName,
                    source = OcrTextSource.valueOf(row.source),
                    text = row.text,
                    snippet = snippet(row.text, query),
                    confidence = row.confidence,
                    bounds =
                        if (row.left != null && row.top != null && row.right != null && row.bottom != null) {
                            RectF(row.left, row.top, row.right, row.bottom)
                        } else {
                            null
                        },
                )
            }.toList()
    }

    suspend fun clear() = dao.clearAll()

    private fun snippet(
        text: String,
        query: String,
    ): String {
        val match = text.indexOf(query, ignoreCase = true)
        if (match < 0 || text.length <= 100) return text.take(100)
        val start = (match - 35).coerceAtLeast(0)
        val end = (match + query.length + 65).coerceAtMost(text.length)
        return (if (start > 0) "…" else "") + text.substring(start, end) + if (end < text.length) "…" else ""
    }

    companion object {
        @Volatile private var instance: OcrSearchRepository? = null

        fun get(context: Context): OcrSearchRepository =
            instance ?: synchronized(this) {
                instance ?: OcrSearchRepository(OcrIndexDatabase.get(context).dao()).also { instance = it }
            }
    }
}
