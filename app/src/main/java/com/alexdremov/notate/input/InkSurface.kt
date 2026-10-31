package com.alexdremov.notate.input

/** The calls that really reach the Onyx pen driver. Only [InkArbiter] invokes them. */
interface DriverPort {
    /** Opens a raw-drawing session over the surface (and puts the panel into scribble mode). */
    fun open()

    /** Ends the session: disables raw drawing, closes it, leaves scribble mode. */
    fun close()

    /** Turns the driver's inking on or off within an open session (also the panel's pen state). */
    fun setActive(on: Boolean)
}

/** Something the pen can write on: a note canvas or a web page overlay. */
interface InkSurface {
    val driver: DriverPort

    /** True if the driver session must be closed while another surface owns the pen (note canvas). */
    val closesWhenNotOwner: Boolean

    /** The surface must (or may again) ignore the pen entirely, see [BlockReason.suspendsInk]. */
    fun onInkSuspended(suspended: Boolean) {}

    /** The driver was switched off; a stroke it was drawing is cut and will never report its end. */
    fun onDriverDeactivated() {}
}
