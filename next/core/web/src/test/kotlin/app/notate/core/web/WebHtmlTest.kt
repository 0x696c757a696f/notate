package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebHtmlTest {
    private fun run(html: String, w: Int = 800) = String(WebHtml.injectViewport(html.toByteArray(Charsets.UTF_8), w), Charsets.UTF_8)

    @Test fun insertsAfterHeadOpen() {
        val out = run("<html><head><title>x</title></head><body>é</body></html>")
        assertThat(out).contains("<head><meta name=\"viewport\" content=\"width=800")
        assertThat(out).contains("<title>x</title>")
        assertThat(out).contains("é")   // non-ASCII text survives untouched
    }

    @Test fun replacesResponsiveViewport() {
        val out = run("<head><META name='viewport' content='width=device-width, initial-scale=1'></head>")
        assertThat(out).doesNotContain("device-width")
        assertThat(out).contains("width=800")
    }

    @Test fun keepsFixedLayoutViewport() {
        val html = "<head><meta name=\"viewport\" content=\"width=1200,height=1600\"/></head><body></body>"
        assertThat(run(html)).isEqualTo(html)
    }

    @Test fun addsHeadWhenMissing() {
        assertThat(run("<html lang=\"en\"><body>hi</body></html>")).contains("<html lang=\"en\"><head><meta name=\"viewport\"")
        assertThat(run("plain text")).startsWith("<meta name=\"viewport\"")
    }

    @Test fun xhtmlAttributeOrder() {
        val out = run("<head><meta content=\"width=device-width\" name=\"viewport\" /></head>")
        assertThat(out.split("name=\"viewport\"")).hasSize(2)
    }

    @Test fun utf16IsLeftAlone() {
        val bytes = "<head></head>".toByteArray(Charsets.UTF_16LE)
        assertThat(WebHtml.injectViewport(bytes, 800)).isEqualTo(bytes)
    }
}
