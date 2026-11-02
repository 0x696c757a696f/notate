package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WebViewTransformTest {
    @Test fun viewAndPointAreInverse() {
        val t = WebViewTransform(scale = 2.5f, scrollXPx = 30f, scrollYPx = 1200f)
        for (v in listOf(0f, 17f, 640f, 1403f)) {
            assertThat(t.ptToViewX(t.viewToPtX(v))).isWithin(1e-3f).of(v)
            assertThat(t.ptToViewY(t.viewToPtY(v))).isWithin(1e-3f).of(v)
        }
    }

    @Test fun scrollMovesTheContentUnderTheInk() {
        val a = WebViewTransform(2f, 0f, 0f)
        val b = WebViewTransform(2f, 0f, 400f)
        // The same page point is 400 px higher on screen after scrolling 400 px.
        assertThat(b.ptToViewY(100f)).isWithin(1e-3f).of(a.ptToViewY(100f) - 400f)
    }

    @Test fun oneCssPixelIsThreeQuartersOfAPoint() {
        val t = WebViewTransform(scale = 2f, scrollXPx = 0f, scrollYPx = 0f)
        assertThat(t.viewToPtX(2f)).isWithin(1e-4f).of(0.75f)
        assertThat(t.pxPerPt).isWithin(1e-4f).of(2f / 0.75f)
    }

    @Test fun initialScaleFitsLayoutToPane() {
        // 1404 px pane at density 2 is 702 dp wide; a 900 css px layout needs 78 %.
        assertThat(WebLayoutLock.initialScalePercent(1404, 900f, 2f)).isEqualTo(78)
        assertThat(WebLayoutLock.initialScalePercent(0, 900f, 2f)).isEqualTo(0)
    }

    @Test fun bannerNeedsReflowAndInk() {
        assertThat(WebLayoutLock.showLayoutChangedBanner(1, true, false)).isFalse()
        assertThat(WebLayoutLock.showLayoutChangedBanner(2, false, false)).isFalse()
        assertThat(WebLayoutLock.showLayoutChangedBanner(2, true, true)).isFalse()
        assertThat(WebLayoutLock.showLayoutChangedBanner(2, true, false)).isTrue()
    }

    @Test fun originParsing() {
        val id = "01HXYZABCDEFGHJKMNPQRSTVWX"
        assertThat(WebOrigin.host(id)).isEqualTo("01hxyzabcdefghjk.webdoc.notate")
        assertThat(WebOrigin.parse(id, WebOrigin.urlFor(id, "OEBPS/ch%201.xhtml#sec2"))).isEqualTo("OEBPS/ch 1.xhtml" to "sec2")
        assertThat(WebOrigin.parse(id, "https://evil.example/x")).isNull()
        assertThat(WebOrigin.parse(id, WebOrigin.base(id))).isEqualTo("" to null)
        assertThat(WebOrigin.isSameOrigin(id, "https://01hxyzabcdefghjk.webdoc.notate.evil.com/")).isFalse()
    }

    @Test fun byteRanges() {
        assertThat(ByteRange.parse("bytes=0-99", 1000)).isEqualTo(0L..99L)
        assertThat(ByteRange.parse("bytes=900-", 1000)).isEqualTo(900L..999L)
        assertThat(ByteRange.parse("bytes=-100", 1000)).isEqualTo(900L..999L)
        assertThat(ByteRange.parse("bytes=0-5000", 1000)).isEqualTo(0L..999L)
        assertThat(ByteRange.parse("bytes=2000-", 1000)).isNull()
        assertThat(ByteRange.parse("items=0-1", 1000)).isNull()
        assertThat(ByteRange.parse("bytes=0-1,5-6", 1000)).isNull()
        assertThat(ByteRange.parse(null, 1000)).isNull()
    }
}
