package app.notate.core.model

import app.notate.core.model.Units.mm
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TemplateCatalogTest {
    @Test fun hasTheEightTemplatesInOrder() {
        assertThat(TemplateCatalog.ALL.map { it.name }).containsExactly(
            "Blank", "Lined", "Wide lined", "Grid 5 mm", "Dots 5 mm", "Engineering", "Cornell", "Isometric",
        ).inOrder()
    }

    @Test fun patternsAndSpacingsMatchTheSpec() {
        fun bg(o: TemplateOption) = o.background.pattern to o.background.spacingPt
        assertThat(bg(TemplateCatalog.BLANK).first).isEqualTo(PaperPattern.BLANK)
        assertThat(bg(TemplateCatalog.LINED)).isEqualTo(PaperPattern.LINED to mm(7.1f))
        assertThat(bg(TemplateCatalog.WIDE_LINED)).isEqualTo(PaperPattern.LINED to mm(8.7f))
        assertThat(bg(TemplateCatalog.GRID_5)).isEqualTo(PaperPattern.GRID to mm(5f))
        assertThat(bg(TemplateCatalog.DOTS_5)).isEqualTo(PaperPattern.DOTS to mm(5f))
        assertThat(bg(TemplateCatalog.ENGINEERING)).isEqualTo(PaperPattern.ENGINEERING to mm(5f))
        assertThat(bg(TemplateCatalog.ISOMETRIC)).isEqualTo(PaperPattern.ISOMETRIC to mm(5f))
        assertThat(TemplateCatalog.CORNELL.background.pattern).isEqualTo(PaperPattern.CORNELL)
        assertThat(TemplateCatalog.CORNELL.background.marginLeftPt).isEqualTo(mm(60f))
    }

    @Test fun defaultTemplateIsLined() {
        assertThat(PageTemplate.DEFAULT.background).isEqualTo(TemplateCatalog.LINED.background)
    }

    @Test fun templateIgnoresLandscapeForWhiteboards() {
        assertThat(TemplateCatalog.template(PageSize.WHITEBOARD, true, TemplateCatalog.GRID_5).landscape).isFalse()
        assertThat(TemplateCatalog.template(PageSize.A4, true, TemplateCatalog.GRID_5).landscape).isTrue()
    }

    @Test fun findMatchesCatalogEntries() {
        assertThat(TemplateCatalog.find(TemplateCatalog.GRID_5.background)).isEqualTo(TemplateCatalog.GRID_5)
        assertThat(TemplateCatalog.find(Background.None)).isNull()
    }
}
