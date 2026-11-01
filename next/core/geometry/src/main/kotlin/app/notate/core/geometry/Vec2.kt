package app.notate.core.geometry

import kotlin.math.sqrt

/** Immutable 2D vector / point in page or screen space. Thread-safe. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun length(): Float = sqrt(x * x + y * y)
    fun dot(o: Vec2): Float = x * o.x + y * o.y
    /** Unit vector; the zero vector is returned unchanged. */
    fun normalized(): Vec2 {
        val l = length()
        return if (l == 0f) this else Vec2(x / l, y / l)
    }
}
