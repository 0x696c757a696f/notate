package app.notate.core.model

/** A named palette colour (ARGB). Names are English UI labels. */
data class NamedColor(val name: String, val argb: Int)

/** Default tool constants from 04-ux-spec §3.2. */
object Defaults {
    val PEN_COLORS: List<Int> = listOf(0xFF000000.toInt(), 0xFF1A3D8F.toInt(), 0xFFB3261E.toInt())
    val PEN_WIDTHS_MM: List<Float> = listOf(0.25f, 0.4f, 0.7f)
    const val DEFAULT_PEN_WIDTH_MM = 0.4f
    val HIGHLIGHTER_COLORS: List<Int> = listOf(0x80FFE14D.toInt(), 0x8077DD77.toInt(), 0x80A0A0A0.toInt())
    val HIGHLIGHTER_WIDTHS_MM: List<Float> = listOf(3f, 5f, 8f)
    private const val DP_PER_MM = 160f / 25.4f
    /** Eraser diameters (2, 5, 10 mm of physical screen size) expressed in dp. */
    val ERASER_SIZES_DP: List<Float> = listOf(2f * DP_PER_MM, 5f * DP_PER_MM, 10f * DP_PER_MM)
    val PALETTE_12: List<NamedColor> = listOf(
        NamedColor("Black", 0xFF000000.toInt()), NamedColor("Dark gray", 0xFF3C3C3C.toInt()), NamedColor("Gray", 0xFF808080.toInt()),
        NamedColor("Blue", 0xFF2F6FDE.toInt()), NamedColor("Dark blue", 0xFF1A3D8F.toInt()), NamedColor("Red", 0xFFB3261E.toInt()),
        NamedColor("Orange", 0xFFE8710A.toInt()), NamedColor("Yellow", 0xFFF2C200.toInt()), NamedColor("Green", 0xFF2E7D32.toInt()),
        NamedColor("Cyan", 0xFF00A3B4.toInt()), NamedColor("Purple", 0xFF7B1FA2.toInt()), NamedColor("Brown", 0xFF6D4C41.toInt()),
    )
}
