package app.notate.core.legacy

import app.notate.core.model.Background
import app.notate.core.model.DocKind
import app.notate.core.model.ImageItem
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.PageSize
import app.notate.core.model.PaperPattern
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyMapperTest {
    @get:Rule val tmp = TemporaryFolder()
    private val f = 72f / 227f

    @Test fun factorsForPapers() {
        assertThat(LegacyMapper.factor(LegacyCanvasType.INFINITE, 0f, 0f)).isWithin(1e-6f).of(72f / 227f)
        assertThat(LegacyMapper.factor(LegacyCanvasType.FIXED_PAGES, 2480f, 3508f)).isWithin(1e-6f).of(595.28f / 2480f)
        assertThat(LegacyMapper.factor(LegacyCanvasType.FIXED_PAGES, 1748f, 2480f)).isWithin(1e-6f).of(419.53f / 1748f)
        assertThat(LegacyMapper.factor(LegacyCanvasType.FIXED_PAGES, 3508f, 4961f)).isWithin(1e-6f).of(841.89f / 3508f)
        // Landscape A4 keeps the long side as width.
        val land = LegacyMapper.pageSpec(LegacyCanvasType.FIXED_PAGES, 3508f, 2480f)
        assertThat(land.widthPt).isWithin(0.01f).of(841.89f)
        assertThat(land.heightPt).isWithin(0.01f).of(595.28f)
        // Screen-sized (Note X 1404x1872): width maps to A4 width, height by ratio.
        val screen = LegacyMapper.pageSpec(LegacyCanvasType.FIXED_PAGES, 1404f, 1872f)
        assertThat(screen.widthPt).isWithin(0.01f).of(595.28f)
        assertThat(screen.heightPt).isWithin(0.1f).of(793.7f)
        assertThat(screen.paper).isEqualTo(PageSize.SCREEN)
    }

    @Test fun penMapping() {
        val m = LegacyStrokeType.entries.associateWith { LegacyMapper.penOf(it) }
        assertThat(m[LegacyStrokeType.BRUSH]).isEqualTo(PenType.FOUNTAIN)
        assertThat(m[LegacyStrokeType.CHARCOAL]).isEqualTo(PenType.PENCIL)
        assertThat(m[LegacyStrokeType.DASH]).isEqualTo(PenType.DASHED)
        assertThat(m[LegacyStrokeType.DOT]).isEqualTo(PenType.DOTTED)
        assertThat(m[LegacyStrokeType.HIGHLIGHTER]).isEqualTo(PenType.HIGHLIGHTER)
        assertThat(m[LegacyStrokeType.BALLPOINT]).isEqualTo(PenType.BALLPOINT)
    }

    @Test fun backgroundSpacingScaled() {
        val g = LegacyMapper.backgroundOf(LegacyBackground.Grid(spacing = 100f), 0.5f)
        assertThat(g.pattern).isEqualTo(PaperPattern.GRID); assertThat(g.spacingPt).isEqualTo(50f)
        assertThat(LegacyMapper.backgroundOf(LegacyBackground.Dots(spacing = 40f), 0.25f).spacingPt).isEqualTo(10f)
        assertThat(LegacyMapper.backgroundOf(LegacyBackground.Lines(), 1f).pattern).isEqualTo(PaperPattern.LINED)
        assertThat(LegacyMapper.backgroundOf(LegacyBackground.Blank(), 1f).pattern).isEqualTo(PaperPattern.BLANK)
    }

    private fun map(manifest: LegacyCanvasData, regions: List<LegacyRegionProto>, extra: Map<String, ByteArray> = emptyMap()): MappedDocument =
        LegacyFixtures.zip(tmp.newFile(), manifest, regions, extra).use {
            LegacyMapper.toDocument(LegacyNotateReader.read(it), LegacyFixtures.docIds(), LegacyFixtures.clock, title = "T", createdAt = 10, modifiedAt = 20)
        }

    @Test fun infiniteCanvasIsOneWhiteboardPage() {
        val s = LegacyFixtures.stroke(listOf(Triple(227f, 454f, 4096f), Triple(454f, 454f, 2048f)), LegacyStrokeType.BRUSH, width = 11.35f, order = 7)
        val d = map(LegacyCanvasData(uuid = "u", backgroundStyle = LegacyBackground.Lines(spacing = 227f)),
            listOf(LegacyFixtures.region(0, 0, strokes = listOf(s)), LegacyFixtures.region(0, -1, strokes = listOf(
                LegacyFixtures.stroke(listOf(Triple(-227f, -227f, 0f), Triple(0f, 0f, 0f)), order = 2)))))
        assertThat(d.meta.kind).isEqualTo(DocKind.NOTEBOOK)
        assertThat(d.meta.title).isEqualTo("T"); assertThat(d.meta.createdAt).isEqualTo(10); assertThat(d.meta.modifiedAt).isEqualTo(20)
        assertThat(d.legacyUuid).isEqualTo("u")
        assertThat(d.pages).hasSize(1)
        val p = d.pages.single()
        assertThat(p.height).isNull(); assertThat(p.width).isEqualTo(PageSize.WHITEBOARD.widthPt)
        assertThat((p.background as Background.Paper).spacingPt).isWithin(1e-4f).of(72f)
        val items = d.items[p.id]!!
        assertThat(items.map { it.order }).containsExactly(2L, 7L).inOrder()
        assertThat(items.map { it.id.value }).containsExactly(1L, 2L).inOrder()
        val st = items[1] as Stroke
        assertThat(st.points.xy[0]).isWithin(1e-3f).of(72f)
        assertThat(st.points.xy[1]).isWithin(1e-3f).of(144f)
        assertThat(st.style.widthPt).isWithin(1e-3f).of(11.35f * f)
        assertThat(st.style.pen).isEqualTo(PenType.FOUNTAIN)
        assertThat(st.points.pressure!![0]).isEqualTo(1f); assertThat(st.points.pressure!![1]).isEqualTo(0.5f)
        assertThat(st.points.tMs!!.toList()).containsExactly(0, 10).inOrder()
        val neg = items[0] as Stroke
        assertThat(neg.points.pressure).isNull()            // pressure 0 = unknown
        assertThat(neg.points.xy[0]).isWithin(1e-3f).of(-72f)
        assertThat(st.bounds.l).isLessThan(72f)
    }

    @Test fun fixedPagesAssignByCenterAndSubtractOffset() {
        val h = 3508f; val full = h + 100f
        fun at(page: Int, y: Float) = LegacyFixtures.stroke(listOf(Triple(100f, page * full + y, 0f), Triple(200f, page * full + y + 10f, 0f)), order = page * 10L + 1)
        val img = LegacyImageData("images/p.png", 0f, 2 * full + 100f, 2480f, 3000f, 0f, 5, locked = true)
        val text = LegacyTextData("hi", 50f, full + 10f, 600f, 100f, 40f, 0xFF000000.toInt(), 0f, 3, alignment = 2)
        val link = LegacyLinkData("go", "uuid-2", 10f, 10f, 200f, 50f, 0f, 4, 0)
        val ext = LegacyLinkData("web", "https://x.org", 10f, 100f, 200f, 50f, 0f, 6, 0, type = LegacyLinkType.EXTERNAL_URL)
        val d = map(
            LegacyCanvasData(canvasType = LegacyCanvasType.FIXED_PAGES, pageWidth = 2480f, pageHeight = h),
            listOf(
                LegacyFixtures.region(0, 0, strokes = listOf(at(0, 100f), at(1, 200f)), links = listOf(link, ext)),
                LegacyFixtures.region(0, 1, strokes = listOf(at(2, 300f)), images = listOf(img), texts = listOf(text)),
            ),
            extra = mapOf("images/p.png" to byteArrayOf(1)),
        )
        assertThat(d.pages).hasSize(3)
        val k = 595.28f / 2480f
        assertThat(d.pages.map { it.width }.distinct()).containsExactly(595.28f)
        assertThat(d.pages[0].height).isWithin(0.01f).of(841.89f)
        assertThat(d.meta.newPageTemplate.size).isEqualTo(PageSize.A4)
        assertThat(d.items[PageId(1)]!!.map { it::class.simpleName }).containsExactly("Stroke", "LinkItem", "LinkItem")
        assertThat(d.items[PageId(2)]!!.map { it::class.simpleName }).containsExactly("Stroke", "TextBox")
        val s3 = d.items[PageId(3)]!!.filterIsInstance<Stroke>().single()
        assertThat(s3.points.xy[1]).isWithin(0.01f).of(300f * k)
        val im = d.items[PageId(3)]!!.filterIsInstance<ImageItem>().single()
        assertThat(im.locked).isTrue(); assertThat(im.frame.t).isWithin(0.01f).of(100f * k)
        assertThat(d.assets.single().uri).isEqualTo("images/p.png")
        val tb = d.items[PageId(2)]!!.filterIsInstance<TextBox>().single()
        assertThat(tb.align).isEqualTo(TextAlign.CENTER); assertThat(tb.fontSizePt).isWithin(0.01f).of(40f * k)
        assertThat(tb.frame.t).isWithin(0.01f).of(10f * k)
        val links = d.items[PageId(1)]!!.filterIsInstance<LinkItem>()
        assertThat((links[0].target as LinkTarget.Url).url).isEqualTo("notate-legacy://uuid-2")
        assertThat(links[1].target).isEqualTo(LinkTarget.Url("https://x.org"))
    }

    @Test fun itemIdsAreUniquePerDocumentAndMissingImageWarns() {
        val d = map(LegacyCanvasData(), listOf(LegacyFixtures.region(0, 0,
            strokes = listOf(LegacyFixtures.stroke(listOf(Triple(1f, 1f, 0f), Triple(2f, 2f, 0f)))),
            images = listOf(LegacyImageData("content://nope", 0f, 0f, 1f, 1f, 0f, 2)))))
        assertThat(d.items.values.flatten()).hasSize(1)
        assertThat(d.warnings.any { it.contains("content://nope") }).isTrue()
        assertThat(d.assets).isEmpty()
    }

    @Test fun tagsMappedByDefinition() {
        val d = map(LegacyCanvasData(tagIds = listOf("a", "zz"), tagDefinitions = listOf(LegacyTag("a", "Math", 5), LegacyTag("b", "Other", 6))), emptyList())
        assertThat(d.tags).containsExactly("Math"); assertThat(d.tagColors).containsExactly("Math", 5)
        assertThat(d.pages).hasSize(1)
    }

    @Test fun emptyOrBrokenStrokesSkipped() {
        val bad = LegacyStrokeData(null, null, 0, 1f, LegacyStrokeType.DOT)
        val d = map(LegacyCanvasData(), listOf(LegacyFixtures.region(0, 0, strokes = listOf(bad))))
        assertThat(d.items.values.flatten()).isEmpty()
        assertThat(d.warnings).isNotEmpty()
    }
}
