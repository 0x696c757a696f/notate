package app.notate.core.testing

import app.notate.core.model.Clock

/** Manually advanced [Clock]. */
class FakeClock(var now: Long = 0L) : Clock {
    override fun now(): Long = now
    fun advance(ms: Long) { now += ms }
}
