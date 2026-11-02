package app.notate.core.store.schema

/** DDL of `doc.db` (03-data-model §5). */
object DocSchema {
    const val VERSION = 1

    val DDL: List<String> = listOf(
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        """CREATE TABLE page (
          id         INTEGER PRIMARY KEY,
          ord        REAL    NOT NULL,
          width      REAL    NOT NULL,
          height     REAL,
          background TEXT    NOT NULL,
          ink_rev    INTEGER NOT NULL DEFAULT 0,
          flags      INTEGER NOT NULL DEFAULT 0,
          modified   INTEGER NOT NULL
        )""",
        "CREATE INDEX page_ord ON page(ord)",
        """CREATE TABLE item (
          id      INTEGER PRIMARY KEY,
          page_id INTEGER NOT NULL REFERENCES page(id) ON DELETE CASCADE,
          kind    INTEGER NOT NULL,
          layer   INTEGER NOT NULL,
          ord     INTEGER NOT NULL,
          l REAL NOT NULL, t REAL NOT NULL, r REAL NOT NULL, b REAL NOT NULL,
          data    BLOB    NOT NULL
        )""",
        "CREATE INDEX item_page_t ON item(page_id, t)",
        "CREATE INDEX item_page_b ON item(page_id, b)",
        """CREATE TABLE asset (
          id    TEXT PRIMARY KEY,
          kind  INTEGER NOT NULL,
          mime  TEXT NOT NULL,
          path  TEXT NOT NULL,
          bytes INTEGER NOT NULL
        )""",
        """CREATE TABLE outline (
          id INTEGER PRIMARY KEY, parent_id INTEGER, ord INTEGER NOT NULL,
          title TEXT NOT NULL, page_id INTEGER REFERENCES page(id) ON DELETE SET NULL, y_pt REAL,
          anchor TEXT
        )""",
        """CREATE TABLE ocr (
          page_id INTEGER PRIMARY KEY REFERENCES page(id) ON DELETE CASCADE,
          ink_rev INTEGER NOT NULL, engine TEXT NOT NULL, words BLOB NOT NULL
        )""",
    )

    val MIGRATIONS: List<Migration> = listOf(Migration(1) { d -> DDL.forEach { d.execute(it) } })
}

/** DDL of `library.db` (03-data-model §7). */
object LibrarySchema {
    const val VERSION = 1

    val DDL: List<String> = listOf(
        """CREATE TABLE document (
          id TEXT PRIMARY KEY, title TEXT NOT NULL, kind INTEGER NOT NULL,
          folder_id TEXT REFERENCES folder(id) ON DELETE SET NULL,
          created INTEGER NOT NULL, modified INTEGER NOT NULL, opened INTEGER,
          page_count INTEGER NOT NULL, cover_rev INTEGER NOT NULL DEFAULT 0,
          pinned INTEGER NOT NULL DEFAULT 0, trashed INTEGER
        )""",
        "CREATE INDEX document_folder ON document(folder_id, modified)",
        """CREATE TABLE folder (id TEXT PRIMARY KEY, parent_id TEXT REFERENCES folder(id) ON DELETE CASCADE,
                     name TEXT NOT NULL, ord REAL NOT NULL, created INTEGER NOT NULL)""",
        "CREATE TABLE tag (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, color INTEGER NOT NULL)",
        """CREATE TABLE doc_tag (doc_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
                      tag_id TEXT NOT NULL REFERENCES tag(id) ON DELETE CASCADE, PRIMARY KEY (doc_id, tag_id))""",
        "CREATE VIRTUAL TABLE search USING fts4(doc_id, page_id, body)",
        "CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    )

    val MIGRATIONS: List<Migration> = listOf(Migration(1) { d -> DDL.forEach { d.execute(it) } })
}
