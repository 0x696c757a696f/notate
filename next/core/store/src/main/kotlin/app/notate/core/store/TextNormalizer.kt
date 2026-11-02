package app.notate.core.store

import java.text.Normalizer

/** Text normalisation shared by the search index (R6-1) and queries: accent folding, lower case, CJK split per character. */
object TextNormalizer {
    private val WS = Regex("\\s+")

    private fun isSplitScript(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }

    /** NFKD, strip combining accents (U+0300–U+036F), NFC, lower-case, spaces around each CJK character, collapse whitespace. */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length + 8)
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x0300..0x036F) continue
            if (isSplitScript(cp)) sb.append(' ').appendCodePoint(cp).append(' ') else sb.appendCodePoint(cp)
        }
        val composed = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
        return WS.replace(composed, " ").trim()
    }

    /**
     * Builds an FTS4 MATCH expression from user input: each whitespace-separated word becomes a quoted phrase with a
     * prefix star on its last token, e.g. `integ 积分` → `"integ*" "积 分*"`. Double quotes are dropped. Empty when nothing is searchable.
     */
    fun toMatchQuery(userQuery: String): String {
        val phrases = ArrayList<String>()
        for (word in userQuery.split(WS)) {
            val n = normalize(word.replace("\"", " "))
            if (n.isNotEmpty()) phrases.add("\"$n*\"")
        }
        return phrases.joinToString(" ")
    }
}
