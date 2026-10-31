package com.alexdremov.notate.input

/**
 * Why the pen driver is not inking. [suspendsInk] reasons mean the pen must not ink at all (something
 * else is on top of the page and the pen operates it); the others only move the driver aside while the
 * pen may still write through the software path or simply has nothing to do (a pan, a refresh).
 */
enum class BlockReason(
    val suspendsInk: Boolean,
) {
    TOOLBAR_TOUCH(true),
    TOOL_POPUP(true),
    SIDEBAR(true),
    PAGE_GRID(true),
    TOOLBAR_EDIT_MODE(true),

    /** Dragging the split-view divider. */
    SPLIT_DIVIDER(true),

    /** Any focusable popup, menu or dialog of the window took focus. */
    WINDOW_UNFOCUSED(true),

    /** A popup opened by a pane itself (pen settings, reader menus). */
    POPUP(true),
    EXPORT(true),
    READ_ONLY(true),

    /** A finger is panning/zooming: the page must be able to redraw. */
    FINGER_GESTURE(false),
    PAN(false),
    GUIDE_DRAG(false),

    /** The panel is being refreshed: the driver's layer would hold the update back. */
    REFRESH(false),

    /** "Interact" mode: the pen operates the page instead of writing. */
    INTERACT_MODE(false),

    /** The current tool needs a live software preview (drag shapes, ruler). */
    SOFTWARE_TOOL(false),
}
