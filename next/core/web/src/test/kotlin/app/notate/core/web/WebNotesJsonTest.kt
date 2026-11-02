package app.notate.core.web

import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebNotesJsonTest {
    private val pts = floatArrayOf(0f, 0f, 75f, 150f)

    @Test fun structureAndCoordinatePairs() {
        val json = WebNotesJson.build(
            "My \"Book\"\n", listOf(NotesToc("Intro", "ch1.xhtml#a", 0), NotesToc("Part", null, 1)),
            listOf(ChapterNotes("ch1.xhtml", 800f, listOf(stroke(7, points = pts, widthPt = 1.5f)))),
        )
        assertThat(json).startsWith("{\"format\":\"notate-web-notes\",\"version\":1,\"title\":\"My \\\"Book\\\"\\n\"")
        assertThat(json).contains("\"toc\":[{\"title\":\"Intro\",\"href\":\"ch1.xhtml#a\",\"depth\":0},{\"title\":\"Part\",\"href\":null,\"depth\":1}]")
        assertThat(json).contains("\"pages\":[{\"href\":\"ch1.xhtml\",\"layoutWidthCss\":800,\"items\":[{\"id\":7")
        assertThat(json).contains("\"type\":\"stroke\"")
        assertThat(json).contains("\"pointsPt\":[[0,0],[75,150]]")
        assertThat(json).contains("\"pointsCss\":[[0,0],[100,200]]")      // x_css = x_pt / 0.75
        assertThat(json).contains("\"widthPt\":1.5")
        assertThat(json).contains("\"widthCss\":2")
    }

    @Test fun bracesAndBracketsBalance() {
        val json = WebNotesJson.build("t", emptyList(), listOf(ChapterNotes("a", 600f, listOf(stroke(1), stroke(2)))))
        assertThat(json.count { it == '{' }).isEqualTo(json.count { it == '}' })
        assertThat(json.count { it == '[' }).isEqualTo(json.count { it == ']' })
    }

    @Test fun emptyDocument() {
        assertThat(WebNotesJson.build("x", emptyList(), emptyList())).endsWith("\"toc\":[],\"pages\":[]}")
    }
}
