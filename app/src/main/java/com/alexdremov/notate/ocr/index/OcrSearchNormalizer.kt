package com.alexdremov.notate.ocr.index

import java.text.Normalizer
import java.util.Locale

object OcrSearchNormalizer {
    fun normalize(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(value: String): List<String> {
        val normalized = normalize(value)
        val result = linkedSetOf<String>()
        Regex("[\\p{L}\\p{N}]+").findAll(normalized).forEach { match ->
            val token = match.value
            if (token.any(::isCjk)) {
                val chars = token.map(Char::toString)
                result.addAll(chars)
                chars.zipWithNext { first, second -> first + second }.forEach(result::add)
            } else {
                result.add(token)
            }
        }
        return result.toList()
    }

    fun searchableTokens(value: String): String = tokens(value).joinToString(" ", transform = ::ftsToken)

    fun containsCjk(value: String): Boolean = normalize(value).any(::isCjk)

    fun ftsQuery(value: String): String =
        tokens(value).joinToString(" AND ") { token ->
            val encoded = ftsToken(token)
            val escaped = encoded.replace("\"", "\"\"")
            if (token.any(::isCjk)) "\"$escaped\"" else "\"$escaped\"*"
        }

    private fun ftsToken(token: String): String =
        if (token.any(::isCjk)) {
            "z" + token.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
        } else {
            token
        }

    private fun isCjk(char: Char): Boolean =
        Character.UnicodeScript.of(char.code) in CJK_SCRIPTS

    private val CJK_SCRIPTS =
        setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
        )
}
