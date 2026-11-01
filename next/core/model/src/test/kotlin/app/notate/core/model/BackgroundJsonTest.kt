package app.notate.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class BackgroundJsonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val cases: List<Background> = listOf(
        Background.Paper(PaperPattern.CORNELL, 20f, marginLeftPt = 120f, headerPt = 60f),
        Background.Pdf(AssetId("abc"), 3, 90),
        Background.Web("ch1.xhtml", 600f, 2),
        Background.None,
    )

    @Test fun roundTrips() {
        for (b in cases) assertThat(json.decodeFromString<Background>(json.encodeToString(b))).isEqualTo(b)
    }

    @Test fun discriminators() {
        val names = cases.map { Regex("\"type\":\"(\\w+)\"").find(json.encodeToString(it))!!.groupValues[1] }
        assertThat(names).containsExactly("paper", "pdf", "web", "none").inOrder()
    }

    @Test fun unknownFieldsIgnored() {
        val b = json.decodeFromString<Background>("""{"type":"pdf","asset":"a","pageIndex":1,"future":true}""")
        assertThat(b).isEqualTo(Background.Pdf(AssetId("a"), 1))
    }

    @Test fun metaRoundTrips() {
        val meta = DocumentMeta(DocId("01ABC"), "T", DocKind.WEB, 1, 2, web = WebDocSettings(textZoom = 120),
            view = ViewState(PageId(2), 1.5f, 1f, 2f, PageLayoutMode.SINGLE))
        assertThat(json.decodeFromString<DocumentMeta>(json.encodeToString(meta))).isEqualTo(meta)
        val tpl = PageTemplate.DEFAULT
        assertThat(json.decodeFromString<PageTemplate>(json.encodeToString(tpl))).isEqualTo(tpl)
    }
}
