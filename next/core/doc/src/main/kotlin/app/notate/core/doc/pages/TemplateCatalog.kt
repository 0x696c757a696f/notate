package app.notate.core.doc.pages

import app.notate.core.model.Background
import app.notate.core.model.PaperPattern
import app.notate.core.model.Units.mm

/** The paper offered by the template picker and the New notebook dialog (04 §2.1); no UI copy in core. */
object TemplateCatalog {
    /** [id] is a stable key (`blank`, `lined`, …); the UI maps it to a localized name. */
    class Entry(val id: String, val background: Background.Paper)

    val entries: List<Entry> = listOf(
        Entry("blank", Background.Paper(PaperPattern.BLANK, mm(5f))),
        Entry("lined", Background.Paper(PaperPattern.LINED, mm(7.1f))),
        Entry("wide_lined", Background.Paper(PaperPattern.LINED, mm(8.7f))),
        Entry("grid_5mm", Background.Paper(PaperPattern.GRID, mm(5f))),
        Entry("dots_5mm", Background.Paper(PaperPattern.DOTS, mm(5f))),
        Entry("engineering", Background.Paper(PaperPattern.ENGINEERING, mm(5f))),
        Entry("cornell", Background.Paper(PaperPattern.CORNELL, mm(7.1f), marginLeftPt = mm(60f), headerPt = mm(25f))),
        Entry("isometric", Background.Paper(PaperPattern.ISOMETRIC, mm(5f))),
    )
}
