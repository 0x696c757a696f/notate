package app.notate.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelMiscTest {
    @Test fun fromCodeRoundTripsAndRejectsUnknown() {
        PenType.entries.forEach { assertThat(PenType.fromCode(it.code)).isEqualTo(it) }
        DocKind.entries.forEach { assertThat(DocKind.fromCode(it.code)).isEqualTo(it) }
        Layer.entries.forEach { assertThat(Layer.fromCode(it.code)).isEqualTo(it) }
        ShapeKind.entries.forEach { assertThat(ShapeKind.fromCode(it.code)).isEqualTo(it) }
        assertThrows(CorruptDataException::class.java) { PenType.fromCode(99) }
        assertThrows(CorruptDataException::class.java) { DocKind.fromCode(-1) }
        assertThrows(CorruptDataException::class.java) { Layer.fromCode(7) }
    }

    @Test fun capabilityMatrix() {
        val nb = Capabilities.forKind(DocKind.NOTEBOOK)
        val pdf = Capabilities.forKind(DocKind.PDF)
        val web = Capabilities.forKind(DocKind.WEB)
        assertThat(nb).containsAtLeast(Capability.LASSO, Capability.PAGES, Capability.ADD_BLANK_PAGE, Capability.HAND)
        assertThat(nb).containsNoneOf(Capability.OUTLINE, Capability.PEN_MODE)
        assertThat(pdf).contains(Capability.OUTLINE)
        assertThat(pdf).doesNotContain(Capability.PEN_MODE)
        assertThat(web).containsAtLeast(Capability.PEN_MODE, Capability.OUTLINE, Capability.PEN, Capability.RULER)
        assertThat(web).containsNoneOf(Capability.LASSO, Capability.TEXT, Capability.PAGES, Capability.ADD_BLANK_PAGE)
    }

    @Test fun unitsAndDefaults() {
        assertThat(Units.mm(0.4f)).isWithin(1e-3f).of(1.134f)
        assertThat(Defaults.PALETTE_12).hasSize(12)
        assertThat(Defaults.ERASER_SIZES_DP).hasSize(3)
        assertThat(PageTemplate.DEFAULT.size).isEqualTo(PageSize.A4)
        assertThat((PageTemplate.DEFAULT.background as Background.Paper).spacingPt).isWithin(1e-3f).of(20.126f)
    }
}
