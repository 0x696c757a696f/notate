package app.notate.core.doc.library

/** "Untitled Note" -> "Untitled Note 2", "Untitled Note 3", … when [base] is already in [taken] (case-insensitive). */
fun uniqueName(base: String, taken: Collection<String>): String {
    val lower = taken.mapTo(HashSet()) { it.lowercase() }
    if (base.lowercase() !in lower) return base
    var n = 2
    while ("$base $n".lowercase() in lower) n++
    return "$base $n"
}
