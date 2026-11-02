package app.notate.core.legacy

import app.notate.core.model.DocId
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyLinkFixerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun note(uuid: String, vararg targets: String): MappedDocument {
        val links = targets.mapIndexed { i, t -> LegacyLinkData("l$i", t, 0f, 0f, 10f, 10f, 0f, i + 1L, 0) }
        return LegacyFixtures.zip(tmp.newFile(), LegacyCanvasData(uuid = uuid), listOf(LegacyFixtures.region(0, 0, links = links))).use {
            LegacyMapper.toDocument(LegacyNotateReader.read(it), LegacyFixtures.docIds(), LegacyFixtures.clock)
        }
    }

    private fun links(d: MappedDocument) = d.items.values.flatten().filterIsInstance<LinkItem>()

    @Test fun resolvesWithinBatchAndKnownAndInvalidates() {
        val a = note("A", "B", "C", "GONE")
        val b = note("B")
        val c = note("C")
        val out = LegacyLinkFixer.fix(listOf(a, b), known = mapOf("C" to DocId("OLDC")))
        val ls = links(out[0])
        assertThat(ls[0].target).isEqualTo(LinkTarget.Page(b.meta.id, PageId(1)))
        assertThat(ls[1].target).isEqualTo(LinkTarget.Page(DocId("OLDC"), PageId(1)))
        assertThat(ls[2].target).isEqualTo(LinkTarget.Url(LegacyLinkFixer.INVALID_URL))
        assertThat(out[0].warnings.single()).contains("GONE")
        assertThat(out[1]).isSameInstanceAs(b)
        assertThat(c.legacyUuid).isEqualTo("C")
    }
}
