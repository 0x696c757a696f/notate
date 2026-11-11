package app.notate.core.doc.export

/** Parses page selections such as "1-3, 5, 8-" typed by the user (1-based) into 0-based page indexes. Pure; any thread. */
object PageRangeParser {
    const val ERROR_MESSAGE = "Use page numbers like 1-3, 5"

    sealed interface Result {
        /** Ascending, de-duplicated, 0-based page indexes inside `0 until pageCount`. */
        data class Ok(val indexes: List<Int>) : Result
        data class Error(val message: String) : Result
    }

    /**
     * Accepts comma (or semicolon/space) separated numbers and ranges: `5`, `2-4`, `8-` (to the last page) and `-3` (from the
     * first page). Numbers beyond [pageCount] are clipped; a selection with no page left in the document is an error.
     */
    fun parse(spec: String, pageCount: Int): Result {
        if (pageCount <= 0) return Result.Error(ERROR_MESSAGE)
        val parts = spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return Result.Error(ERROR_MESSAGE)
        val picked = sortedSetOf<Int>()
        for (part in parts) {
            val dash = part.indexOf('-')
            val (from, to) = if (dash < 0) {
                val n = number(part) ?: return Result.Error(ERROR_MESSAGE)
                n to n
            } else {
                val a = part.substring(0, dash).trim()
                val b = part.substring(dash + 1).trim()
                if (a.isEmpty() && b.isEmpty()) return Result.Error(ERROR_MESSAGE)
                val lo = if (a.isEmpty()) 1 else number(a) ?: return Result.Error(ERROR_MESSAGE)
                val hi = if (b.isEmpty()) pageCount else number(b) ?: return Result.Error(ERROR_MESSAGE)
                if (lo > hi) return Result.Error(ERROR_MESSAGE)
                lo to hi
            }
            for (n in from..minOf(to, pageCount)) picked += n - 1
        }
        return if (picked.isEmpty()) Result.Error(ERROR_MESSAGE) else Result.Ok(picked.toList())
    }

    private fun number(s: String): Int? = s.takeIf { it.length <= 9 && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it >= 1 }
}
