package com.alexdremov.notate.ocr

import com.alexdremov.notate.ocr.index.OcrSearchNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OcrSearchNormalizerTest {
    @Test
    fun normalizesWidthCaseAndWhitespace() {
        assertThat(OcrSearchNormalizer.normalize("  Ｈｅｌｌｏ   WORLD ")).isEqualTo("hello world")
    }

    @Test
    fun englishQueryUsesSafePrefixTerms() {
        assertThat(OcrSearchNormalizer.ftsQuery("hand writ")).isEqualTo("\"hand\"* AND \"writ\"*")
    }

    @Test
    fun chineseTokensContainUnigramsAndBigrams() {
        assertThat(OcrSearchNormalizer.tokens("手写笔记")).containsAtLeast("手", "写", "笔", "记", "手写", "写笔", "笔记")
        assertThat(OcrSearchNormalizer.ftsQuery("写笔")).isEqualTo("\"z5199\" AND \"z7b14\" AND \"z51997b14\"")
    }

    @Test
    fun punctuationCannotEscapeFtsQuery() {
        assertThat(OcrSearchNormalizer.ftsQuery("hello OR title:evil")).isEqualTo("\"hello\"* AND \"or\"* AND \"title\"* AND \"evil\"*")
    }
}
