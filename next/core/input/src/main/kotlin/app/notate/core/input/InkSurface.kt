package app.notate.core.input

/** The only door to one surface's pen-driver session. Implemented by `OnyxDriverPort` (or a no-op on other devices). */
interface DriverPort {
    /** Opens a raw-drawing session over the surface (and puts the panel into scribble mode). */
    fun open()

    /** Ends the session: disables raw drawing, closes it, leaves scribble mode. */
    fun close()

    /** Turns the driver's inking on or off within an open session (also the panel's pen state). */
    fun setActive(on: Boolean)
}
