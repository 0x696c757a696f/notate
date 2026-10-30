package com.alexdremov.notate.ocr.index

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class OcrSearchRow(
    @ColumnInfo(name = "rowid") val rowId: Long,
    val documentId: String,
    val projectId: String?,
    val path: String,
    val documentName: String,
    val source: String,
    val text: String,
    val normalizedText: String,
    val confidence: Float,
    val left: Float?,
    val top: Float?,
    val right: Float?,
    val bottom: Float?,
)

@Dao
interface OcrIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(document: OcrDocumentEntity)

    @Query("SELECT * FROM ocr_documents WHERE documentId = :documentId")
    suspend fun getDocument(documentId: String): OcrDocumentEntity?

    @Query("SELECT * FROM ocr_documents WHERE path = :path LIMIT 1")
    suspend fun getDocumentByPath(path: String): OcrDocumentEntity?

    @Query("SELECT * FROM ocr_documents")
    suspend fun getAllDocuments(): List<OcrDocumentEntity>

    @Query("SELECT regionHash FROM ocr_blocks WHERE documentId = :documentId AND regionId = :regionId LIMIT 1")
    suspend fun getRegionHash(
        documentId: String,
        regionId: String,
    ): String?

    @Query("SELECT DISTINCT regionId FROM ocr_blocks WHERE documentId = :documentId")
    suspend fun getRegionIds(documentId: String): List<String>

    @Query("SELECT * FROM ocr_blocks WHERE documentId = :documentId AND regionId = :regionId")
    suspend fun getBlocksForRegion(
        documentId: String,
        regionId: String,
    ): List<OcrBlockEntity>

    @Query("DELETE FROM ocr_blocks_fts WHERE rowid IN (SELECT rowid FROM ocr_blocks WHERE documentId = :documentId AND regionId = :regionId)")
    suspend fun deleteRegionFts(
        documentId: String,
        regionId: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockRows(blocks: List<OcrBlockEntity>): List<Long>

    @Query("INSERT INTO ocr_blocks_fts(rowid, normalizedText, searchTokens) VALUES (:rowId, :normalizedText, :searchTokens)")
    suspend fun insertFtsRow(
        rowId: Long,
        normalizedText: String,
        searchTokens: String,
    )

    suspend fun insertFtsRows(blocks: List<OcrBlockFts>) {
        blocks.forEach { insertFtsRow(it.rowId, it.normalizedText, it.searchTokens) }
    }

    @Query("DELETE FROM ocr_blocks WHERE documentId = :documentId AND regionId = :regionId")
    suspend fun deleteRegionRows(
        documentId: String,
        regionId: String,
    )

    @Transaction
    suspend fun deleteRegion(
        documentId: String,
        regionId: String,
    ) {
        deleteRegionFts(documentId, regionId)
        deleteRegionRows(documentId, regionId)
    }

    @Transaction
    suspend fun insertBlocks(blocks: List<OcrBlockEntity>) {
        if (blocks.isEmpty()) return
        val rowIds = insertBlockRows(blocks)
        insertFtsRows(
            blocks.zip(rowIds) { block, rowId ->
                OcrBlockFts(rowId, block.normalizedText, block.searchTokens)
            },
        )
    }

    @Transaction
    suspend fun replaceRegion(
        documentId: String,
        regionId: String,
        blocks: List<OcrBlockEntity>,
    ) {
        deleteRegion(documentId, regionId)
        if (blocks.isNotEmpty()) insertBlocks(blocks)
    }

    @Query("DELETE FROM ocr_blocks_fts WHERE rowid IN (SELECT rowid FROM ocr_blocks WHERE documentId = :documentId)")
    suspend fun deleteDocumentFts(documentId: String)

    @Query("DELETE FROM ocr_blocks WHERE documentId = :documentId")
    suspend fun deleteDocumentBlockRows(documentId: String)

    @Transaction
    suspend fun deleteDocumentBlocks(documentId: String) {
        deleteDocumentFts(documentId)
        deleteDocumentBlockRows(documentId)
    }

    @Query("DELETE FROM ocr_documents WHERE documentId = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("DELETE FROM ocr_blocks_fts")
    suspend fun clearFts()

    @Query("DELETE FROM ocr_blocks")
    suspend fun clearBlockRows()

    @Transaction
    suspend fun clearBlocks() {
        clearFts()
        clearBlockRows()
    }

    @Query("DELETE FROM ocr_documents")
    suspend fun clearDocuments()

    @Transaction
    suspend fun clearAll() {
        clearBlocks()
        clearDocuments()
    }

    @Query(
        """
        SELECT b.rowId, b.documentId, d.projectId, d.path, d.name AS documentName,
               b.source, b.text, b.normalizedText, b.confidence,
               b.left, b.top, b.right, b.bottom
        FROM ocr_blocks AS b
        JOIN ocr_blocks_fts ON b.rowId = ocr_blocks_fts.rowid
        JOIN ocr_documents AS d ON d.documentId = b.documentId
        WHERE ocr_blocks_fts MATCH :ftsQuery
        ORDER BY CASE b.source WHEN 'FILENAME' THEN 0 WHEN 'TYPED_TEXT' THEN 1 ELSE 2 END,
                 b.confidence DESC, d.lastModified DESC
        LIMIT :limit
        """,
    )
    suspend fun search(
        ftsQuery: String,
        limit: Int,
    ): List<OcrSearchRow>

    @Query("SELECT COUNT(*) FROM ocr_documents WHERE status = 'INDEXED'")
    fun indexedDocumentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ocr_documents WHERE status = 'INDEXING'")
    fun indexingDocumentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM ocr_documents WHERE status = 'STALE'")
    fun staleDocumentCount(): Flow<Int>

    @Query("SELECT rowid, normalizedText, searchTokens FROM ocr_blocks_fts")
    suspend fun getFtsRows(): List<OcrBlockFts>

}
