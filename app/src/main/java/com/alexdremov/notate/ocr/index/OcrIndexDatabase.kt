package com.alexdremov.notate.ocr.index

import android.content.Context
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(
    tableName = "ocr_documents",
    indices = [Index(value = ["path"], unique = true), Index("projectId")],
)
data class OcrDocumentEntity(
    @PrimaryKey val documentId: String,
    val projectId: String?,
    val path: String,
    val name: String,
    val lastModified: Long,
    val modelVersion: String,
    val status: String,
    val errorMessage: String? = null,
    val indexedAt: Long = 0L,
)

@Entity(
    tableName = "ocr_blocks",
    indices = [
        Index(value = ["stableId"], unique = true),
        Index(value = ["documentId", "regionId"]),
        Index("regionHash"),
    ],
)
data class OcrBlockEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val stableId: String,
    val documentId: String,
    val regionId: String,
    val regionHash: String,
    val source: String,
    val text: String,
    val normalizedText: String,
    val searchTokens: String,
    val confidence: Float,
    val left: Float?,
    val top: Float?,
    val right: Float?,
    val bottom: Float?,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "ocr_blocks_fts")
data class OcrBlockFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val normalizedText: String,
    val searchTokens: String,
)

@Database(
    entities = [OcrDocumentEntity::class, OcrBlockEntity::class, OcrBlockFts::class],
    version = 1,
    exportSchema = false,
)
abstract class OcrIndexDatabase : RoomDatabase() {
    abstract fun dao(): OcrIndexDao

    companion object {
        @Volatile private var instance: OcrIndexDatabase? = null

        fun get(context: Context): OcrIndexDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(context.applicationContext, OcrIndexDatabase::class.java, "notate_ocr_index.db")
                    .build()
                    .also { instance = it }
            }
    }
}
