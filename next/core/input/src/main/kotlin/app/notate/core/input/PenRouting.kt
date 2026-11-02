package app.notate.core.input

/** Which of the two pen paths (driver points or touch events) feeds a pane (02 §5.3). */
object PenRouting {
    /**
     * True when a pen event that reached the pane as a touch event should be inked. With the driver active the points come
     * from the driver and touch events only feed palm rejection, except
     * - a stroke that started from touch events goes on that way, and
     * - the first stroke after a focus switch: the pen came down on an unfocused pane, the driver was opened for that pane
     *   only just now and never saw the touch, so nothing but the touch events can carry it ([PenEvent.Down.focusSwitched]).
     */
    fun inkFromTouch(driverActive: Boolean, touchStrokeInProgress: Boolean, ev: PenEvent): Boolean =
        !driverActive || touchStrokeInProgress || (ev is PenEvent.Down && ev.focusSwitched)
}
