package app.notate.core.legacy

import app.notate.core.model.Background
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyWebReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun stroke(x: Float, tool: String = "PEN", style: String = "FINELINER") =
        """{"tool":"$tool","color":-16777216,"width":4.0,"points":[$x,0.0,${x + 10},20.0],"style":"$style"}"""

    private fun doc(): File = tmp.newFolder("w").also {
        File(it, "content").mkdirs()
        File(it, "meta.json").writeText("""{"id":"web1","title":"Book","kind":"EPUB","pages":[{"href":"c1.xhtml","title":"1"},{"href":"c2.xhtml","title":"2"}],"importedAt":42,"policy":{"networkAllowed":true}}""")
    }

    @Test fun v1AnnotationsAreRead() {
        val d = doc()
        File(d, "annotations.json").writeText("""{"version":1,"strokes":{"c1.xhtml":[${stroke(1f)}]},"lastPage":"c2.xhtml","scrollY":{"c2.xhtml":100.0},"layout":{"widthCss":800.0,"textZoom":120,"gen":2}}""")
        val w = LegacyWebReader.read(d)
        assertThat(w.strokes["c1.xhtml"]).hasSize(1)
        assertThat(w.lastPage).isEqualTo("c2.xhtml"); assertThat(w.layout!!.gen).isEqualTo(2)
        assertThat(w.meta.policy.networkAllowed).isTrue()
    }

    @Test fun v2InkPlusJournalReplay() {
        val d = doc()
        val key1 = LegacyWebReader.keyFor("c1.xhtml")
        File(d, "ink").mkdirs()
        File(d, "ink/$key1.json").writeText("""{"href":"c1.xhtml","strokes":[${stroke(1f)}]}""")
        File(d, "state.json").writeText("""{"version":2,"lastPage":"c1.xhtml","appliedSeq":5,"pages":{"c1.xhtml":"$key1"},"scrollY":{"c1.xhtml":40.0}}""")
        File(d, "ink.journal").writeText(
            """{"s":3,"h":"c1.xhtml","k":"add","st":[${stroke(99f)}]}
{"s":6,"h":"c1.xhtml","k":"add","st":[${stroke(2f, "HIGHLIGHTER")}]}
{"s":7,"h":"c3.xhtml","k":"set","st":[${stroke(3f)}]}
not json
{"s":8,"h":"c2.xhtml","k":"add","st":[${stroke(4f)}]}
{"s":9,"h":"c2.xhtml","k":"add"
""")
        val w = LegacyWebReader.read(d)
        assertThat(w.strokes["c1.xhtml"]).hasSize(2)                     // seq 3 already applied, 6 replayed
        assertThat(w.strokes.keys).containsExactly("c1.xhtml", "c2.xhtml", "c3.xhtml")
        assertThat(w.warnings.single()).contains("2 unreadable")
        assertThat(w.scrollY["c1.xhtml"]).isEqualTo(40f)
    }

    @Test fun journalOnlyWithoutState() {
        val d = doc()
        File(d, "ink.journal").writeText("""{"s":1,"h":"c1.xhtml","k":"add","st":[${stroke(1f)}]}""" + "\n")
        assertThat(LegacyWebReader.read(d).strokes["c1.xhtml"]).hasSize(1)
    }

    @Test fun missingInkFileWarnsAndMissingMetaThrows() {
        val d = doc()
        File(d, "state.json").writeText("""{"pages":{"c1.xhtml":"deadbeef"}}""")
        assertThat(LegacyWebReader.read(d).warnings.single()).contains("c1.xhtml")
        assertThrows(LegacyFormatException::class.java) { LegacyWebReader.read(tmp.newFolder("empty")) }
    }

    @Test fun mapperBuildsWebDocument() {
        val d = doc()
        File(d, "annotations.json").writeText("""{"strokes":{"c2.xhtml":[${stroke(10f)}],"extra.xhtml":[${stroke(1f, "HIGHLIGHTER")}]},"lastPage":"c2.xhtml","scrollY":{"c2.xhtml":200.0},"penMode":"INTERACT","layout":{"widthCss":800.0,"textZoom":110,"gen":3}}""")
        val m = LegacyWebMapper.toDocument(LegacyWebReader.read(d), LegacyFixtures.docIds(), LegacyFixtures.clock)
        assertThat(m.meta.kind).isEqualTo(DocKind.WEB)
        assertThat(m.pages.map { (it.background as Background.Web).href }).containsExactly("c1.xhtml", "c2.xhtml", "extra.xhtml").inOrder()
        assertThat(m.pages[0].width).isEqualTo(600f); assertThat(m.pages[0].height).isNull()
        assertThat((m.pages[0].background as Background.Web).layoutGen).isEqualTo(3)
        assertThat(m.meta.web!!.network).isTrue(); assertThat(m.meta.web!!.textZoom).isEqualTo(110)
        assertThat(m.meta.web!!.penMode.name).isEqualTo("INTERACT")
        assertThat(m.meta.view!!.page).isEqualTo(PageId(2)); assertThat(m.meta.view!!.scrollYPt).isEqualTo(150f)
        val s = m.items[PageId(2)]!!.single() as Stroke
        assertThat(s.points.xy.toList()).containsExactly(7.5f, 0f, 15f, 15f).inOrder()
        assertThat(s.style.widthPt).isEqualTo(3f)
        assertThat((m.items[PageId(3)]!!.single() as Stroke).style.pen).isEqualTo(PenType.HIGHLIGHTER)
        assertThat(m.legacyUuid).isEqualTo("web1"); assertThat(m.webContent).isEqualTo(File(d, "content"))
        assertThat(m.items[PageId(1)]).isEmpty()
        assertThat(m.items.values.flatten().map { it.id.value }).containsNoDuplicates()
    }
}
