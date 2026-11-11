package app.notate.core.doc.links

import app.notate.core.doc.tools.ToolRig
import app.notate.core.geometry.Rect
import app.notate.core.model.DocId
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LinkResolverTest {
    private val here = DocId("01HERE0000000000000000000A")
    private val other = DocId("01OTHER000000000000000000B")

    @Test fun samePageTargetJumps() {
        assertThat(LinkResolver.resolve(LinkTarget.Page(here, PageId(3)), here)).isEqualTo(LinkAction.GoToPage(PageId(3)))
    }

    @Test fun otherDocumentOpens() {
        assertThat(LinkResolver.resolve(LinkTarget.Page(other, PageId(2)), here)).isEqualTo(LinkAction.OpenDocument(other, PageId(2)))
    }

    @Test fun urlAsksBrowser() {
        assertThat(LinkResolver.resolve(LinkTarget.Url("https://a.b"), here)).isEqualTo(LinkAction.OpenUrl("https://a.b"))
    }

    @Test fun createAndHit() = runTest {
        val r = ToolRig(this)
        val link = LinkResolver.create(r.editor, r.p1, Rect(10f, 10f, 100f, 50f), LinkTarget.Page(here, PageId(1)))!!
        assertThat(r.items()).containsExactly(link)
        assertThat(LinkResolver.hit(r.editor, r.p1, 50f, 30f)).isEqualTo(link)
        assertThat(LinkResolver.hit(r.editor, r.p1, 200f, 30f)).isNull()
        r.editor.undo()
        assertThat(r.items()).isEmpty()
    }

    @Test fun topmostLinkWins() = runTest {
        val r = ToolRig(this)
        LinkResolver.create(r.editor, r.p1, Rect(0f, 0f, 100f, 100f), LinkTarget.Url("https://a.b"))
        val top = LinkResolver.create(r.editor, r.p1, Rect(0f, 0f, 100f, 100f), LinkTarget.Url("https://c.d"))!!
        assertThat((LinkResolver.hit(r.editor, r.p1, 5f, 5f) as LinkItem).id).isEqualTo(top.id)
    }

    @Test fun unsafeUrlsAndEmptyFramesRejected() = runTest {
        val r = ToolRig(this)
        assertThat(LinkResolver.create(r.editor, r.p1, Rect(0f, 0f, 10f, 10f), LinkTarget.Url("javascript:alert(1)"))).isNull()
        assertThat(LinkResolver.create(r.editor, r.p1, Rect(0f, 0f, 10f, 10f), LinkTarget.Url("file:///etc/passwd"))).isNull()
        assertThat(LinkResolver.create(r.editor, r.p1, Rect.EMPTY, LinkTarget.Url("https://a.b"))).isNull()
        assertThat(r.items()).isEmpty()
    }
}
