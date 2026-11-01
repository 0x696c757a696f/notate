package app.notate.core.model

/** Unit conversions; all model lengths are in pt (1/72 inch). */
object Units {
    const val PT_PER_MM = 72f / 25.4f
    const val PT_PER_CSS_PX = 0.75f
    fun mm(v: Float): Float = v * PT_PER_MM
}
