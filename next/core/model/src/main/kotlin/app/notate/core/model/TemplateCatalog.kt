package app.notate.core.model

import app.notate.core.model.Units.mm

/** One paper template offered when creating a notebook or changing a page's template. */
data class TemplateOption(val name: String, val background: Background.Paper)

/** The 8 paper templates of 04-ux-spec §2.1. Names are English UI labels. */
object TemplateCatalog {
    val BLANK = TemplateOption("Blank", Background.Paper(PaperPattern.BLANK, mm(5f)))
    val LINED = TemplateOption("Lined", Background.Paper(PaperPattern.LINED, mm(7.1f)))
    val WIDE_LINED = TemplateOption("Wide lined", Background.Paper(PaperPattern.LINED, mm(8.7f)))
    val GRID_5 = TemplateOption("Grid 5 mm", Background.Paper(PaperPattern.GRID, mm(5f)))
    val DOTS_5 = TemplateOption("Dots 5 mm", Background.Paper(PaperPattern.DOTS, mm(5f)))
    val ENGINEERING = TemplateOption("Engineering", Background.Paper(PaperPattern.ENGINEERING, mm(5f)))
    val CORNELL = TemplateOption(
        "Cornell",
        Background.Paper(PaperPattern.CORNELL, mm(7.1f), marginLeftPt = mm(60f)),
    )
    val ISOMETRIC = TemplateOption("Isometric", Background.Paper(PaperPattern.ISOMETRIC, mm(5f)))

    /** In display order. */
    val ALL: List<TemplateOption> = listOf(BLANK, LINED, WIDE_LINED, GRID_5, DOTS_5, ENGINEERING, CORNELL, ISOMETRIC)

    /** The catalog entry whose pattern and spacing equal [background], if any. */
    fun find(background: Background): TemplateOption? {
        val paper = background as? Background.Paper ?: return null
        return ALL.firstOrNull { it.background.pattern == paper.pattern && it.background.spacingPt == paper.spacingPt }
    }

    /** Template for new pages: [size] and [landscape] (ignored for whiteboards) with [option]'s paper. */
    fun template(size: PageSize, landscape: Boolean, option: TemplateOption): PageTemplate =
        PageTemplate(size, landscape && size.heightPt != null, option.background)
}
