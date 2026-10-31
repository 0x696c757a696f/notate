package com.alexdremov.notate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasType
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.model.InfiniteCanvasModel
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.model.StylusButtonAction
import com.alexdremov.notate.model.TextItem
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.model.TwoFingerTapAction
import com.alexdremov.notate.ui.controller.CanvasControllerImpl
import com.alexdremov.notate.ui.controller.ViewportController
import com.alexdremov.notate.input.Handedness
import com.alexdremov.notate.input.InputClassifier
import com.alexdremov.notate.input.PalmRejector
import com.alexdremov.notate.ui.input.PenInputHandler
import com.alexdremov.notate.ui.interaction.ViewportInteractor
import com.alexdremov.notate.ui.render.CanvasRenderer
import com.alexdremov.notate.ui.render.RenderQuality
import com.alexdremov.notate.ui.render.SelectionOverlayDrawer
import com.alexdremov.notate.ui.selection.SelectionInteractor
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.TouchHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.LinkedList

class OnyxCanvasView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {
        // --- Components ---
        private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val debugRamHistory = LinkedList<Float>()
        private val MAX_RAM_HISTORY_POINTS = 60
        private var touchHelper: TouchHelper? = null
        private val canvasModel = InfiniteCanvasModel()
        private val canvasRenderer = CanvasRenderer(canvasModel, context.applicationContext, viewScope) { invalidateCanvas() }
        private val canvasController = CanvasControllerImpl(context.applicationContext, canvasModel, canvasRenderer)

        // --- Drawers ---
        private val selectionOverlayDrawer = SelectionOverlayDrawer(canvasController.getSelectionManager(), canvasRenderer)

        // --- Interaction Handlers ---
        private val matrix = Matrix()
        private val inverseMatrix = Matrix()
        var onViewportChanged: (() -> Unit)? = null

        private var drawScheduled = false
        private val frameCallback =
            Choreographer.FrameCallback {
                drawScheduled = false
                drawContent()
            }

        private val viewportInteractor =
            ViewportInteractor(
                context,
                matrix,
                invalidateCallback = {
                    onViewportChanged?.invoke()
                    invalidateCanvas()
                },
                onScaleChanged = { updateTouchHelperTool() },
                onInteractionStart = {
                    touchHelper?.setRawDrawingEnabled(false)
                    EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)
                    canvasRenderer.setInteracting(true)
                },
                onInteractionEnd = {
                    canvasRenderer.setInteracting(false)
                    // Clear the pan's screen updates while the pen is still paused, not under the next stroke.
                    minimapDrawer?.hideNow()
                    resumeRawDrawing()
                },
                lastStrokeEndTimeProvider = { lastStrokeEndTime },
            )

        private val selectionInteractor = SelectionInteractor(this, canvasController, viewScope, matrix, inverseMatrix)

        // --- Pen Input ---
        private val penInputHandler: PenInputHandler

        // --- State ---

        // Two-Finger Tap Detection (Undo)
        private var twoFingerTapDownTime = 0L
        private var lastTwoFingerTapTime = 0L
        private var isTwoFingerTapCheck = false
        private var twoFingerStartPt1 = floatArrayOf(0f, 0f)
        private var twoFingerStartPt2 = floatArrayOf(0f, 0f)
        private var twoFingerPointerId1 = -1
        private var twoFingerPointerId2 = -1

        // Three-Finger Tap Detection (Distraction-free toggle)
        private var threeFingerTapDownTime = 0L
        private var isThreeFingerTapCheck = false
        private val threeFingerStartPts = Array(3) { floatArrayOf(0f, 0f) }
        private val threeFingerPointerIds = intArrayOf(-1, -1, -1)

        @Volatile
        private var twoFingerTapAction: TwoFingerTapAction = PreferencesManager.getTwoFingerTapAction(context)

        @Volatile
        private var stylusButtonAction: StylusButtonAction = PreferencesManager.getStylusButtonAction(context)

        private var lastStrokeEndTime = 0L

        /** Shared with the other panes of the window (the Activity may replace it right after construction). */
        var palmRejector: PalmRejector = InputClassifier.newRejector(context)

        private var currentTool: PenTool = PenTool.defaultPens()[0]
        private var isReadOnly = false

        @Volatile
        private var fingerOff: Boolean = PreferencesManager.isFingerOff(context)

        private val exclusionRects = ArrayList<Rect>()

        // Exclusion rects from the activity (toolbar, sidebar), in view coordinates, before the
        // zoom window's control strip is merged in.
        private var externalExclusionRects: List<Rect> = emptyList()

        // --- Zoom writing window ---
        private val zoomWindow = com.alexdremov.notate.ui.zoom.ZoomWindow()
        private val zoomMatrix = Matrix()
        private var zoomDrag: ZoomDrag? = null

        /** Finger drag of the zoom target: [worldPerPx] converts screen px to world units (signed). */
        private class ZoomDrag(
            var lastX: Float,
            var lastY: Float,
            val worldPerPx: Float,
        )

        /** Called when the zoom writing window opens or closes. */
        var onZoomWindowChanged: ((Boolean) -> Unit)? = null

        // --- Ruler / protractor (screen-space guides) ---
        private val ruler = com.alexdremov.notate.ui.guide.Ruler()
        private val protractor = com.alexdremov.notate.ui.guide.Protractor()

        @Volatile
        var isRulerVisible = false
            private set

        @Volatile
        var isProtractorVisible = false
            private set
        private var guideGesture: GuideGesture? = null

        // A stroke starting this close (dp) to a guide's edge follows the guide.
        private val guideSnapDp = 20f

        /** Finger gesture on a guide: one finger moves it, two rotate it (and resize the protractor). */
        private class GuideGesture(
            val isRuler: Boolean,
            var lastX: Float,
            var lastY: Float,
        ) {
            var twoFinger = false
            var startPointerAngle = 0f
            var startGuideAngle = 0f
            var startPointerDist = 1f
            var startRadius = 0f
            var lastMidX = 0f
            var lastMidY = 0f
        }

        // Immutable snapshot for the raw-input thread (exclusionRects is mutated on main).
        @Volatile
        private var exclusionSnapshot: List<Rect> = emptyList()

        @Volatile
        private var isRawDrawingActive = false

        // Optimization: Pre-allocated RectF for sync updates
        private val selectionBoundsCache = RectF()
        private var searchHighlightBounds: RectF? = null

        var onStrokeStarted: (() -> Unit)? = null

        var onContentChanged: (() -> Unit)? = null

        var minimapDrawer: com.alexdremov.notate.ui.render.MinimapDrawer? = null

        var onRequestInsertImage: (() -> Unit)? = null
        var onRequestInsertPdf: (() -> Unit)? = null
        var onBrowseFiles: ((onResult: (name: String, uuid: String) -> Unit) -> Unit)? = null
        var onSelectFile: ((onResult: (name: String, path: String) -> Unit) -> Unit)? = null
        var onLinkActivated: ((com.alexdremov.notate.model.LinkItem) -> Unit)? = null

        var onThreeFingerTap: (() -> Unit)? = null

        private var contextMenu: com.alexdremov.notate.ui.dialog.CanvasContextMenu? = null
        private lateinit var gestureDetector: android.view.GestureDetector

        init {
            holder.addCallback(this)
            setZOrderOnTop(false)
            holder.setFormat(android.graphics.PixelFormat.OPAQUE)

            viewScope.launch {
                while (isActive) {
                    if (CanvasConfig.DEBUG_ENABLE_PROFILING) {
                        com.alexdremov.notate.util.PerformanceProfiler
                            .printReport()
                    }
                    delay(CanvasConfig.PROFILING_INTERVAL_MS)
                }
            }

            viewScope.launch {
                while (isActive) {
                    if (CanvasConfig.DEBUG_SHOW_RAM_USAGE) {
                        val runtime = Runtime.getRuntime()
                        val used = (runtime.totalMemory() - runtime.freeMemory()).toFloat()
                        val max = runtime.maxMemory().toFloat()
                        val percent = if (max > 0) used / max else 0f

                        debugRamHistory.add(percent)
                        if (debugRamHistory.size > MAX_RAM_HISTORY_POINTS) {
                            debugRamHistory.removeFirst()
                        }

                        invalidateCanvas()
                    }
                    delay(500)
                }
            }

            // Setup Viewport Controller to bridge Controller -> Interactor
            canvasController.setViewportController(
                object : ViewportController {
                    override fun scrollTo(
                        x: Float,
                        y: Float,
                    ) {
                        matrix.reset()
                        val scale = viewportInteractor.getCurrentScale()
                        matrix.postScale(scale, scale)
                        matrix.postTranslate(-x * scale, -y * scale)
                        invalidateCanvas()
                        updateTouchHelperTool()
                    }

                    override fun getViewportOffset(): Pair<Float, Float> {
                        val values = FloatArray(9)
                        matrix.getValues(values)
                        val tx = values[Matrix.MTRANS_X]
                        val ty = values[Matrix.MTRANS_Y]
                        val scale = viewportInteractor.getCurrentScale()
                        return Pair(-tx / scale, -ty / scale)
                    }
                },
            )

            penInputHandler =
                PenInputHandler(
                    canvasController,
                    this,
                    viewScope,
                    matrix,
                    inverseMatrix,
                    onStrokeStarted = {
                        palmRejector.onPenDown(SystemClock.uptimeMillis())
                        onStrokeStarted?.invoke()
                    },
                    onStrokeFinished = {
                        minimapDrawer?.setDirty()
                        drawContent()
                        onContentChanged?.invoke()
                    },
                )
            penInputHandler.zoomMatrixAt = { x, y ->
                if (zoomWindow.isInWriting(x, y)) zoomWindow.worldToWriting() else null
            }
            penInputHandler.guideSnapAt = { x, y -> guideSnapAt(x, y) }
            penInputHandler.onPenCut = { palmRejector.reset() }
            penInputHandler.onZoomStrokeFinished = { worldBounds ->
                if (zoomWindow.advanceAfterStroke(worldBounds)) onZoomTargetMoved()
            }

            setupGestureDetectors()

            canvasController.setOnContentChangedListener {
                onContentChanged?.invoke()
                showActionPopup()
            }
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            viewportInteractor.setViewWidth(w)
            if (zoomWindow.isOpen) {
                zoomWindow.layout(w, h)
                applyExclusionRects()
            }
            // Rotation / split resize: the pen driver still holds the old capture area.
            if (w != oldw || h != oldh) post { refreshPenLimitRect() }
        }

        private fun setupGestureDetectors() {
            gestureDetector =
                android.view.GestureDetector(
                    context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onLongPress(e: MotionEvent) {
                            if (viewportInteractor.isBusy() || isReadOnly) return

                            viewScope.launch {
                                // Compute fresh inverse to ensure hit test is accurate
                                val inv = Matrix()
                                matrix.invert(inv)
                                val pts = floatArrayOf(e.x, e.y)
                                inv.mapPoints(pts)
                                val worldX = pts[0]
                                val worldY = pts[1]

                                val item = canvasController.getItemAt(worldX, worldY)

                                if (item != null) {
                                    canvasController.clearSelection()
                                    canvasController.selectItem(item)
                                    // Hand off to Interactor to start drag
                                    selectionInteractor.onLongPressDragStart(e.x, e.y)
                                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                } else {
                                    showInsertMenu(worldX, worldY, e.x, e.y)
                                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                        }

                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (viewportInteractor.isBusy() || isReadOnly) return false

                            viewScope.launch {
                                val inv = Matrix()
                                matrix.invert(inv)
                                val pts = floatArrayOf(e.x, e.y)
                                inv.mapPoints(pts)
                                val worldX = pts[0]
                                val worldY = pts[1]

                                val item = canvasController.getItemAt(worldX, worldY)
                                if (item is com.alexdremov.notate.model.LinkItem) {
                                    showLinkDialog(worldX, worldY, item)
                                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            viewScope.launch {
                                val inv = Matrix()
                                matrix.invert(inv)
                                val pts = floatArrayOf(e.x, e.y)
                                inv.mapPoints(pts)
                                val worldX = pts[0]
                                val worldY = pts[1]

                                val item = canvasController.getItemAt(worldX, worldY)

                                if (item is com.alexdremov.notate.model.LinkItem) {
                                    onLinkActivated?.invoke(item)
                                    return@launch
                                }

                                if (isReadOnly) return@launch

                                if (currentTool.type == ToolType.TEXT) {
                                    if (item is TextItem) {
                                        showTextEditor(item)
                                    } else {
                                        showTextEditor(null, worldX, worldY)
                                    }
                                    return@launch
                                } else if (currentTool.type == ToolType.SELECT && item is TextItem) {
                                    showTextEditor(item)
                                    return@launch
                                }
                            }

                            if (currentTool.type == ToolType.TEXT) return true
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
        }

        /**
         * The toolbar's Insert button: the same menu as a long-press on empty canvas,
         * with pasted or linked content landing in the middle of the visible area.
         */
        fun showInsertMenu() {
            if (isReadOnly) return
            val inv = Matrix()
            matrix.invert(inv)
            val pts = floatArrayOf(width / 2f, height / 2f)
            inv.mapPoints(pts)
            showInsertMenu(pts[0], pts[1], width / 2f, height / 2f)
        }

        private fun showInsertMenu(
            worldX: Float,
            worldY: Float,
            viewX: Float,
            viewY: Float,
        ) {
            contextMenu =
                com.alexdremov.notate.ui.dialog.CanvasContextMenu(
                    context,
                    onPaste = {
                        viewScope.launch { canvasController.paste(worldX, worldY) }
                    },
                    onPasteImage = { onRequestInsertImage?.invoke() },
                    onInsertPdf = { onRequestInsertPdf?.invoke() },
                    onInsertLink = { showLinkDialog(worldX, worldY) },
                )
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            contextMenu?.show(this, viewX + origin[0], viewY + origin[1])
        }

        private fun showLinkDialog(
            x: Float,
            y: Float,
            existingItem: com.alexdremov.notate.model.LinkItem? = null,
        ) {
            val dialog =
                com.alexdremov.notate.ui.dialog.InsertLinkDialog(
                    context,
                    existingItem = existingItem,
                    onConfirm = { label, target, type ->
                        viewScope.launch {
                            if (existingItem != null) {
                                canvasController.updateLink(existingItem, label, target, type)
                            } else {
                                canvasController.addLink(
                                    label,
                                    target,
                                    type,
                                    x,
                                    y,
                                    fontSize = 24f, // Default font size
                                    color = Color.BLACK,
                                )
                            }
                        }
                    },
                    onBrowse = { callback ->
                        onBrowseFiles?.invoke(callback)
                    },
                    onSelectFile = { callback ->
                        onSelectFile?.invoke(callback)
                    },
                )
            dialog.show()
        }

        private fun showTextEditor(
            item: TextItem?,
            x: Float = 0f,
            y: Float = 0f,
        ) {
            val initialText = item?.text ?: ""
            val fontSize = item?.fontSize ?: currentTool.width
            val color = item?.color ?: currentTool.color

            val dialog =
                com.alexdremov.notate.ui.dialog.TextEditDialog(
                    context,
                    initialText,
                    fontSize,
                    color,
                ) { newText ->
                    viewScope.launch {
                        if (item != null) {
                            canvasController.updateText(item, newText)
                        } else {
                            if (newText.isNotBlank()) {
                                canvasController.addText(newText, x, y, fontSize, color)
                            }
                        }
                    }
                }
            dialog.show()
        }

        // --- Touch Routing ---
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
            if (isStylus) return false // Handled by RawInputCallback

            if (fingerOff) return true // User chose "Off (stylus only)": fingers do nothing on the page


            if (handleZoomWindowTouch(event)) return true

            if (handleGuideTouch(event)) return true

            // After the zoom window and guides: a finger may hold the ruler while the pen draws along it.
            if (rejectPalm(event)) {
                // Two/three-finger taps (undo, redo, ...) still work while the pen hovers; a palm never taps.
                if (!isReadOnly && !palmRejector.palmSizedGesture) {
                    detectTwoFingerTap(event)
                    detectThreeFingerTap(event)
                }
                return true
            }

            if (!isReadOnly) detectTwoFingerTap(event)

            if (!isReadOnly) detectThreeFingerTap(event)

            // Reserve 3+ finger gestures for the distraction-free toggle (no pan/zoom/select).
            if (!isReadOnly && event.pointerCount >= 3) return true

            // 1. Gesture Detector (Long Press, Tap)
            if (gestureDetector.onTouchEvent(event)) {
                return true
            }

            // 2. Selection Interaction (High Priority)
            if (!isReadOnly) {
                val action = event.actionMasked
                if (action == MotionEvent.ACTION_DOWN) {
                    if (selectionInteractor.onDown(event.x, event.y)) {
                        return true
                    }
                } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    selectionInteractor.onPointerDown(event)
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (selectionInteractor.onMove(event)) {
                        return true
                    }
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    selectionInteractor.onUp()
                }
            }

            // 3. Viewport Interaction (Pan/Zoom) - Only if selection didn't consume
            if (!selectionInteractor.isInteracting()) {
                if (viewportInteractor.onTouchEvent(event)) {
                    return true
                }
            }

            return true
        }

        /**
         * Drops finger gestures made by the writing hand (see [PalmRejector]). A pan already under way
         * when the pen arrives is stopped.
         */
        private fun rejectPalm(event: MotionEvent): Boolean {
            val decision = palmRejector.onTouch(InputClassifier.sample(event), canReject = !selectionInteractor.isInteracting())
            if (decision == PalmRejector.Decision.ACCEPT) return false
            if (decision == PalmRejector.Decision.CANCEL_ONGOING) {
                val cancel = InputClassifier.cancelCopy(event)
                gestureDetector.onTouchEvent(cancel)
                cancel.recycle()
                viewportInteractor.cancel()
            }
            return true
        }

        private fun detectTwoFingerTap(event: MotionEvent) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
                twoFingerTapDownTime = System.currentTimeMillis()
                isTwoFingerTapCheck = true
                twoFingerPointerId1 = event.getPointerId(0)
                twoFingerPointerId2 = event.getPointerId(1)
                twoFingerStartPt1[0] = event.getX(0)
                twoFingerStartPt1[1] = event.getY(0)
                twoFingerStartPt2[0] = event.getX(1)
                twoFingerStartPt2[1] = event.getY(1)
            } else if (action == MotionEvent.ACTION_POINTER_UP && event.pointerCount == 2) {
                if (isTwoFingerTapCheck) {
                    val now = System.currentTimeMillis()
                    val duration = now - twoFingerTapDownTime
                    if (duration < CanvasConfig.TWO_FINGER_TAP_MAX_DELAY) {
                        if (!isTapSlopExceeded(event)) {
                            if (now - lastTwoFingerTapTime < CanvasConfig.TWO_FINGER_TAP_DOUBLE_TIMEOUT) {
                                when (twoFingerTapAction) {
                                    TwoFingerTapAction.UNDO -> viewScope.launch { undo() }
                                    TwoFingerTapAction.REDO -> viewScope.launch { redo() }
                                    TwoFingerTapAction.PASTE -> {
                                        val inv = Matrix()
                                        matrix.invert(inv)
                                        val pts =
                                            floatArrayOf(
                                                (twoFingerStartPt1[0] + twoFingerStartPt2[0]) / 2f,
                                                (twoFingerStartPt1[1] + twoFingerStartPt2[1]) / 2f,
                                            )
                                        inv.mapPoints(pts)
                                        viewScope.launch { canvasController.paste(pts[0], pts[1]) }
                                    }
                                    TwoFingerTapAction.NONE -> {}
                                }
                                lastTwoFingerTapTime = 0L
                            } else {
                                lastTwoFingerTapTime = now
                            }
                        }
                    }
                    isTwoFingerTapCheck = false
                }
            } else if (action == MotionEvent.ACTION_MOVE && isTwoFingerTapCheck) {
                if (event.pointerCount == 2) {
                    if (isTapSlopExceeded(event)) {
                        isTwoFingerTapCheck = false
                    }
                } else {
                    isTwoFingerTapCheck = false
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isTwoFingerTapCheck = false
            }
        }

        private fun isTapSlopExceeded(event: MotionEvent): Boolean {
            val index1 = event.findPointerIndex(twoFingerPointerId1)
            val index2 = event.findPointerIndex(twoFingerPointerId2)

            if (index1 == -1 || index2 == -1) return true

            val d1 = distSq(event.getX(index1), event.getY(index1), twoFingerStartPt1[0], twoFingerStartPt1[1])
            val d2 = distSq(event.getX(index2), event.getY(index2), twoFingerStartPt2[0], twoFingerStartPt2[1])
            return d1 > CanvasConfig.TWO_FINGER_TAP_SLOP_SQ || d2 > CanvasConfig.TWO_FINGER_TAP_SLOP_SQ
        }

        private fun distSq(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ) = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)

        fun setFingerOff(off: Boolean) {
            fingerOff = off
        }

        fun setHandedness(handedness: Handedness) {
            palmRejector.handedness = handedness
        }

        private fun detectThreeFingerTap(event: MotionEvent) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 3) {
                threeFingerTapDownTime = System.currentTimeMillis()
                isThreeFingerTapCheck = true
                // Cancel any pending two-finger tap so 3 fingers don't also trigger undo.
                isTwoFingerTapCheck = false
                for (i in 0 until 3) {
                    threeFingerPointerIds[i] = event.getPointerId(i)
                    threeFingerStartPts[i][0] = event.getX(i)
                    threeFingerStartPts[i][1] = event.getY(i)
                }
            } else if (action == MotionEvent.ACTION_POINTER_UP && event.pointerCount == 3) {
                if (isThreeFingerTapCheck) {
                    val duration = System.currentTimeMillis() - threeFingerTapDownTime
                    if (duration < CanvasConfig.TWO_FINGER_TAP_MAX_DELAY && !isThreeTapSlopExceeded(event)) {
                        onThreeFingerTap?.invoke()
                    }
                    isThreeFingerTapCheck = false
                }
            } else if (action == MotionEvent.ACTION_MOVE && isThreeFingerTapCheck) {
                if (event.pointerCount == 3) {
                    if (isThreeTapSlopExceeded(event)) {
                        isThreeFingerTapCheck = false
                    }
                } else {
                    isThreeFingerTapCheck = false
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isThreeFingerTapCheck = false
            }
        }

        private fun isThreeTapSlopExceeded(event: MotionEvent): Boolean {
            for (i in 0 until 3) {
                val index = event.findPointerIndex(threeFingerPointerIds[i])
                if (index == -1) return true
                val d = distSq(event.getX(index), event.getY(index), threeFingerStartPts[i][0], threeFingerStartPts[i][1])
                if (d > CanvasConfig.TWO_FINGER_TAP_SLOP_SQ) return true
            }
            return false
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    palmRejector.onHover(SystemClock.uptimeMillis(), event.rawX, event.rawY)
                    val toolType = event.getToolType(0)
                    val isEraserTail = toolType == MotionEvent.TOOL_TYPE_ERASER
                    val isStylusButton = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 &&
                        stylusButtonAction == StylusButtonAction.TEMPORARY_ERASER
                    if (isEraserTail || isStylusButton) penInputHandler.prepareEraser() else penInputHandler.finishEraser()
                    penInputHandler.onHoverMove(event)
                }

                MotionEvent.ACTION_HOVER_ENTER -> {
                    palmRejector.onHover(SystemClock.uptimeMillis(), event.rawX, event.rawY)
                    // The pen is about to write: redraw what is pending now rather than mid-stroke.
                    minimapDrawer?.hideNow()
                    penInputHandler.onHoverEnter()
                }

                MotionEvent.ACTION_HOVER_EXIT -> {
                    palmRejector.onHoverExit(SystemClock.uptimeMillis())
                    penInputHandler.onHoverExit()
                }
            }
            return super.onGenericMotionEvent(event)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            viewScope.cancel()
            minimapDrawer?.detach()
            canvasRenderer.destroy()
        }

        // --- Lifecycle & Drawing ---
        override fun surfaceCreated(holder: SurfaceHolder) {
            drawContent()
            setupTouchHelper()
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            setupTouchHelper()
            drawContent()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            EpdController.leaveScribbleMode(this)
            touchHelper?.closeRawDrawing()
        }

        private fun invalidateCanvas() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (!drawScheduled) {
                    drawScheduled = true
                    Choreographer.getInstance().postFrameCallback(frameCallback)
                }
            } else {
                post { invalidateCanvas() }
            }
        }

        private var pendingEpdUpdateMode: UpdateMode? = null

        fun requestEpdRefresh(mode: UpdateMode = UpdateMode.GC) {
            pendingEpdUpdateMode = mode
            invalidateCanvas()
        }

        private fun drawContent() {
            com.alexdremov.notate.util.PerformanceProfiler.trace("OnyxCanvasView.drawContent") {
                val cv =
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            holder.lockHardwareCanvas()
                        } else {
                            holder.lockCanvas()
                        }
                    } catch (e: Exception) {
                        com.alexdremov.notate.util.Logger
                            .e("OnyxCanvasView", "Failed to lock canvas", e)
                        null
                    } ?: return

                try {
                    val bgColor =
                        if (canvasModel.canvasType == CanvasType.FIXED_PAGES) {
                            CanvasConfig.FIXED_PAGE_CANVAS_BG_COLOR
                        } else {
                            Color.WHITE
                        }
                    cv.drawColor(bgColor)

                    val visibleRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
                    matrix.invert(inverseMatrix)
                    inverseMatrix.mapRect(visibleRect)

                    val values = FloatArray(9)
                    matrix.getValues(values)
                    val currentScale = values[Matrix.MSCALE_X]

                    canvasRenderer.render(cv, matrix, visibleRect, RenderQuality.HIGH, currentScale)
                    if (zoomWindow.isOpen) drawZoomTargetOutline(cv, currentScale)
                    selectionOverlayDrawer.draw(cv, matrix, currentScale)
                    searchHighlightBounds?.let { worldBounds ->
                        val screenBounds = RectF(worldBounds)
                        matrix.mapRect(screenBounds)
                        val highlightPaint =
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.BLACK
                                style = Paint.Style.STROKE
                                strokeWidth = 5f
                                pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 10f), 0f)
                            }
                        cv.drawRect(screenBounds, highlightPaint)
                    }

                    if (isRulerVisible) drawRuler(cv)
                    if (isProtractorVisible) drawProtractor(cv)
                    if (zoomWindow.isOpen) drawZoomWindow(cv)

                    if (CanvasConfig.DEBUG_SHOW_RAM_USAGE) {
                        val runtime = Runtime.getRuntime()
                        val usedMem = (runtime.totalMemory() - runtime.freeMemory())
                        val text = "RAM: ${usedMem / 1048576L}MB"
                        val debugPaint =
                            Paint().apply {
                                color = Color.RED
                                textSize = 40f
                                style = Paint.Style.FILL
                            }
                        cv.drawText(text, 20f, height - 130f, debugPaint)

                        val graphWidth = 300f
                        val graphHeight = 100f
                        val graphX = 20f
                        val graphY = height - 20f - graphHeight

                        val bgPaint =
                            Paint().apply {
                                color = Color.parseColor("#ffffffc7")
                                style = Paint.Style.FILL
                            }
                        cv.drawRect(graphX, graphY, graphX + graphWidth, graphY + graphHeight, bgPaint)

                        if (debugRamHistory.isNotEmpty()) {
                            val path = Path()
                            val stepX = graphWidth / MAX_RAM_HISTORY_POINTS
                            debugPaint.style = Paint.Style.STROKE
                            debugPaint.strokeWidth = 3f
                            var first = true
                            val historySnapshot = ArrayList(debugRamHistory)
                            historySnapshot.forEachIndexed { index, percent ->
                                val x = graphX + index * stepX
                                val y = graphY + graphHeight - (percent * graphHeight)
                                if (first) {
                                    path.moveTo(x, y)
                                    first = false
                                } else {
                                    path.lineTo(x, y)
                                }
                            }
                            cv.drawPath(path, debugPaint)
                        }
                    }
                } finally {
                    holder.unlockCanvasAndPost(cv)
                }

                // FRAME SYNC: Update action popup position immediately AFTER unlocking the canvas.
                // This ensures View hierarchy updates (translation) don't happen while Surface lock is held.
                updateActionPopup()

                val mode = pendingEpdUpdateMode
                pendingEpdUpdateMode = null
                if (mode != null) {
                    if (mode == UpdateMode.GC) {
                        performHardRefresh()
                    } else {
                        EpdController.invalidate(this@OnyxCanvasView, mode)
                    }
                }
            }
        }

        // --- Public API ---
        fun getController() = canvasController

        fun getModel() = canvasModel

        fun getRenderer() = canvasRenderer

        fun getViewportMatrix(outMatrix: Matrix) {
            outMatrix.set(matrix)
        }

        fun getCurrentScale() = viewportInteractor.getCurrentScale()

        fun focusSearchMatch(bounds: RectF) {
            if (width <= 0 || height <= 0) {
                post { focusSearchMatch(bounds) }
                return
            }
            val scale = viewportInteractor.getCurrentScale().coerceAtLeast(0.01f)
            val left = bounds.centerX() - width / (2f * scale)
            val top = bounds.centerY() - height / (2f * scale)
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(-left * scale, -top * scale)
            onViewportChanged?.invoke()
            searchHighlightBounds = RectF(bounds)
            invalidateCanvas()
            viewScope.launch {
                delay(2200)
                searchHighlightBounds = null
                invalidateCanvas()
            }
        }

        fun scrollByOffset(
            dx: Float,
            dy: Float,
        ) {
            matrix.postTranslate(dx, dy)
            onViewportChanged?.invoke()
            invalidateCanvas()
        }

        suspend fun getCanvasData(): CanvasData {
            val values = FloatArray(9)
            matrix.getValues(values)
            canvasModel.viewportOffsetX = values[Matrix.MTRANS_X]
            canvasModel.viewportOffsetY = values[Matrix.MTRANS_Y]
            canvasModel.viewportScale = viewportInteractor.getCurrentScale()
            // Capture state from model capturing its mutex
            return canvasModel.toCanvasData()
        }

        suspend fun loadMetadata(data: CanvasData) {
            canvasModel.loadFromCanvasData(data)
            matrix.reset()
            matrix.postScale(data.zoomLevel, data.zoomLevel)
            matrix.postTranslate(data.offsetX, data.offsetY)
            viewportInteractor.setScale(data.zoomLevel)
            viewportInteractor.setCanvasWidth(data.pageWidth)

            canvasRenderer.updateLayoutStrategy()
            canvasRenderer.clearTiles()
            drawContent()
        }

        fun setFixedPageCenterHorizontal(enabled: Boolean) {
            viewportInteractor.setFixedPageCenterHorizontal(enabled)
        }

        fun setTool(tool: PenTool) {
            this.currentTool = tool
            penInputHandler.setTool(tool)
            // No need for performHardRefresh() here, it causes flickering on every tool switch.
            // penInputHandler will handle hardware tool updates if needed.
        }

        fun setEraser(tool: PenTool) {
            penInputHandler.setEraserTool(tool)
        }

        fun setCursorView(view: CursorView) {
            penInputHandler.setCursorView(view)
        }

        fun setTwoFingerTapAction(action: TwoFingerTapAction) {
            twoFingerTapAction = action
        }

        fun setStylusButtonAction(action: StylusButtonAction) {
            stylusButtonAction = action
        }

        suspend fun setBackgroundStyle(style: com.alexdremov.notate.model.BackgroundStyle) {
            canvasModel.setBackground(style)
            invalidateCanvas()
            performHardRefresh() // Still needed for background style change
            onContentChanged?.invoke()
        }

        fun getBackgroundStyle() = canvasModel.backgroundStyle

        fun setDrawingEnabled(enabled: Boolean) {
            penInputHandler.inkBlocked = !enabled
            if (enabled) {
                setupTouchHelper()
            } else {
                // Keep touchHelper active but disable hardware inking to maintain palm rejection
                touchHelper?.setRawDrawingRenderEnabled(false)
                touchHelper?.setRawDrawingEnabled(false)
                isRawDrawingActive = false
                EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)
            }
        }

        /**
         * Split view: whether this pane owns the pen. An inactive pane closes its raw-drawing
         * session entirely (two open sessions fight over the digitizer); it still pans and zooms
         * with fingers. Re-activate with [setDrawingEnabled] after setting this to true.
         */
        var isPaneActive = true
            private set

        fun setPaneActive(active: Boolean) {
            if (isPaneActive == active) return
            isPaneActive = active
            if (!active) {
                palmRejector.reset()
                dismissActionPopup()
                touchHelper?.setRawDrawingRenderEnabled(false)
                touchHelper?.setRawDrawingEnabled(false)
                touchHelper?.closeRawDrawing()
                isRawDrawingActive = false
                EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)
                EpdController.leaveScribbleMode(this)
            }
        }

        fun setReadOnly(readOnly: Boolean) {
            isReadOnly = readOnly
            setDrawingEnabled(!readOnly)
            if (readOnly) {
                viewScope.launch { canvasController.clearSelection() }
            }
        }

        /**
         * Areas (toolbar, open sidebar) where the stylus must only click, never ink.
         * [rects] are in screen coordinates; the TouchHelper and raw touch points use
         * view-local coordinates, so they are translated here.
         */
        fun setExclusionRects(rects: List<Rect>) {
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            externalExclusionRects = rects.filter { !it.isEmpty }.map { Rect(it).apply { offset(-origin[0], -origin[1]) } }
            applyExclusionRects()
        }

        /** Pushes the activity's exclusion rects plus the zoom window's control strip to the pen driver. */
        private fun applyExclusionRects() {
            val local = ArrayList(externalExclusionRects)
            if (zoomWindow.isOpen) {
                val c = zoomWindow.controls
                local.add(Rect(c.left.toInt(), c.top.toInt(), c.right.toInt(), c.bottom.toInt()))
            }
            if (local == exclusionRects) return

            exclusionRects.clear()
            exclusionRects.addAll(local)
            exclusionSnapshot = local
            touchHelper?.let {
                val limit = Rect()
                getLocalVisibleRect(limit)
                if (isRawDrawingActive) {
                    // The driver only picks up a new limit/exclude set when raw drawing restarts;
                    // otherwise ink keeps landing on a toolbar that moved or expanded.
                    it.setRawDrawingEnabled(false)
                    it.setLimitRect(limit, exclusionRects)
                    it.setRawDrawingEnabled(true)
                } else {
                    it.setLimitRect(limit, exclusionRects)
                }
            }
        }

        /** Re-sends the capture area to the pen driver after the view changed size (rotation, split divider). */
        private fun refreshPenLimitRect() {
            val helper = touchHelper ?: return
            if (width == 0 || height == 0) return
            val limit = Rect()
            getLocalVisibleRect(limit)
            if (isRawDrawingActive) {
                helper.setRawDrawingEnabled(false)
                helper.setLimitRect(limit, exclusionRects)
                helper.setRawDrawingEnabled(true)
            } else {
                helper.setLimitRect(limit, exclusionRects)
            }
        }

        /** True if a stylus point (view coordinates) falls on the toolbar or another UI-only area. */
        fun isInExclusionZone(
            x: Float,
            y: Float,
        ): Boolean = exclusionSnapshot.any { it.contains(x.toInt(), y.toInt()) }

        /** Called on the main thread after a stylus stroke on a UI-only area was discarded. */
        var onExcludedStrokeDiscarded: (() -> Unit)? = null

        suspend fun clear() {
            canvasModel.clear()
            canvasRenderer.clearTiles()
            minimapDrawer?.setDirty()
            drawContent()
            performHardRefresh()
            onContentChanged?.invoke()
        }

        suspend fun undo() {
            val sm = canvasController.getSelectionManager()
            if (sm.hasSelection() && !sm.getTransform().isIdentity) {
                // Undo pending transform (Reset visual state without committing)
                sm.resetTransform()
                canvasRenderer.invalidate()
                return
            }

            canvasModel.undo()?.let {
                canvasRenderer.invalidateTiles(it)
                refreshAfterEdit(it)
            }
            onContentChanged?.invoke()
        }

        suspend fun redo() {
            canvasModel.redo()?.let {
                canvasRenderer.invalidateTiles(it)
                refreshAfterEdit(it)
            }
            onContentChanged?.invoke()
        }

        private var actionPopup: com.alexdremov.notate.ui.dialog.SelectionActionPopup? = null
        private var actionPopupContainer: android.widget.FrameLayout? = null
        private var isOcrRunning = false

        private suspend fun recognizeSelection() {
            if (isOcrRunning) return
            val selectionBounds = canvasController.getSelectionManager().getTransformedBounds()
            val strokes = canvasController.getSelectedStrokesForOcr()
            if (strokes.isEmpty()) {
                android.widget.Toast.makeText(context, "Select handwriting to recognize", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            isOcrRunning = true
            android.widget.Toast.makeText(context, "Recognizing text…", android.widget.Toast.LENGTH_SHORT).show()
            try {
                val raster =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        com.alexdremov.notate.ocr.StrokeOcrRasterizer.render(strokes)
                    }
                if (raster == null) {
                    android.widget.Toast.makeText(context, "Selection could not be rendered", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                val blocks =
                    try {
                        com.alexdremov.notate.ocr.PaddleOcrProvider.get(context).recognize(raster.bitmap)
                    } finally {
                        raster.bitmap.recycle()
                    }
                val recognizedText = com.alexdremov.notate.ocr.OcrConversionPlanner.orderedText(blocks)
                if (recognizedText.isEmpty()) {
                    android.widget.Toast.makeText(context, "No text recognized", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }

                val textY =
                    com.alexdremov.notate.ocr.OcrConversionPlanner.insertionY(
                        selection = selectionBounds,
                        text = recognizedText,
                        fontSize = 24f,
                        fixedPageHeight = if (canvasModel.canvasType == com.alexdremov.notate.data.CanvasType.FIXED_PAGES) canvasModel.pageHeight else null,
                        pageSpacing = com.alexdremov.notate.config.CanvasConfig.PAGE_SPACING,
                    )

                com.alexdremov.notate.ui.dialog.TextEditDialog(
                    context,
                    recognizedText,
                    24f,
                    android.graphics.Color.BLACK,
                ) { confirmedText ->
                    if (confirmedText.isNotBlank()) {
                        viewScope.launch {
                            canvasController.addText(
                                confirmedText,
                                selectionBounds.left,
                                textY,
                                24f,
                                android.graphics.Color.BLACK,
                            )
                        }
                    }
                }.show()
            } catch (error: Throwable) {
                com.alexdremov.notate.util.Logger.e("PaddleOCR", "Text recognition failed", error, showToUser = true)
                android.widget.Toast.makeText(context, "Text recognition is unavailable", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                isOcrRunning = false
            }
        }

        fun setActionPopupContainer(container: android.widget.FrameLayout) {
            actionPopup?.destroy()
            actionPopup = null
            actionPopupContainer = container
        }

        fun showActionPopup() {
            val sm = canvasController.getSelectionManager()
            if (sm.hasSelection()) {
                if (actionPopup == null) {
                    val container = actionPopupContainer ?: return
                    actionPopup =
                        com.alexdremov.notate.ui.dialog.SelectionActionPopup(
                            context,
                            container,
                            onCopy = { viewScope.launch { canvasController.copySelection() } },
                            onRecognize = { viewScope.launch { recognizeSelection() } },
                            onDelete = { viewScope.launch { canvasController.deleteSelection() } },
                            onDismiss = { },
                        )
                }
                if (!selectionInteractor.isInteracting()) {
                    sm.getTransformedBounds(selectionBoundsCache)
                    if (!selectionBoundsCache.isEmpty && selectionBoundsCache.width() > 1f && selectionBoundsCache.height() > 1f) {
                        actionPopup?.show(selectionBoundsCache, popupMatrix())
                    } else {
                        actionPopup?.dismiss()
                    }
                }
            } else {
                actionPopup?.dismiss()
            }
        }

        private val popupMatrixCache = Matrix()

        /** View matrix shifted into the popup container's coordinates (the view may sit in a split pane). */
        private fun popupMatrix(): Matrix {
            popupMatrixCache.set(matrix)
            val container = actionPopupContainer ?: return popupMatrixCache
            val a = IntArray(2)
            val b = IntArray(2)
            getLocationOnScreen(a)
            container.getLocationOnScreen(b)
            popupMatrixCache.postTranslate((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())
            return popupMatrixCache
        }

        fun updateActionPopup() {
            val sm = canvasController.getSelectionManager()
            if (sm.hasSelection() && actionPopup?.isShowing() == true) {
                sm.getTransformedBounds(selectionBoundsCache)
                if (!selectionBoundsCache.isEmpty && selectionBoundsCache.width() > 1f && selectionBoundsCache.height() > 1f) {
                    actionPopup?.updatePosition(selectionBoundsCache, popupMatrix())
                }
            }
        }

        fun dismissActionPopup() {
            actionPopup?.dismiss()
            contextMenu?.dismiss()
        }

        fun refreshScreen() {
            performHardRefresh()
        }

        private fun updateTouchHelperTool() {
            penInputHandler.setScale(viewportInteractor.getCurrentScale())
        }

        /** Hands the screen back to the pen after a pan or guide drag, unless a popup still blocks ink. */
        private fun resumeRawDrawing() {
            if (penInputHandler.inkBlocked) return
            if (isPaneActive) touchHelper?.setRawDrawingEnabled(true)
            EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_DRAWING)
            updateTouchHelperTool()
        }

        private fun performHardRefresh() {
            val wasEnabled = isPaneActive && touchHelper?.isRawDrawingInputEnabled() == true
            if (wasEnabled) {
                touchHelper?.setRawDrawingEnabled(false)
                EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)
            }
            EpdController.invalidate(this, UpdateMode.GC)
            if (wasEnabled) {
                updateTouchHelperTool()
                EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_DRAWING)
                touchHelper?.setRawDrawingEnabled(true)
            }
        }

        private fun setupTouchHelper() {
            // In split view only the focused pane may own the pen driver.
            if (!isPaneActive) return
            var isFirstInit = false
            if (touchHelper == null) {
                // Revert to Mode 2 (true) which handles surface interactions better for panning
                // Use SFTouchRender (Mode 2) for better ink quality and panning support
                touchHelper = TouchHelper.create(this, true, penInputHandler)
                penInputHandler.setTouchHelper(touchHelper!!)
                isFirstInit = true
            }
            com.alexdremov.notate.util.OnyxSystemHelper
                .ignoreSystemSideButton(this)
            val limit = Rect()
            getLocalVisibleRect(limit)
            touchHelper?.apply {
                setLimitRect(limit, exclusionRects)

                openRawDrawing()
                // A surface change while a popup is open must not bring the ink back under it.
                val allowed = !penInputHandler.inkBlocked
                setRawDrawingEnabled(allowed)
                setRawDrawingRenderEnabled(allowed)
                isRawDrawingActive = allowed

                // Mode 2 ignores enableFingerTouch(), so we don't need to call it.
                // Instead, we use setSingleRegionMode() which is crucial for fast stroke capture in SFTouchRender.
                setSingleRegionMode()

                EpdController.enterScribbleMode(this@OnyxCanvasView)
                EpdController.setScreenHandWritingPenState(
                    this@OnyxCanvasView,
                    if (allowed) EpdPenManager.PEN_DRAWING else EpdPenManager.PEN_PAUSE,
                )
            }
            if (isFirstInit) {
                performHardRefresh()
            } else {
                updateTouchHelperTool()
            }
        }

        private fun refreshAfterEdit(bounds: RectF? = null) {
            val visibleRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            matrix.invert(inverseMatrix)
            inverseMatrix.mapRect(visibleRect)

            // Only refresh tiles that are actually visible
            val refreshBounds = bounds ?: visibleRect
            if (RectF.intersects(refreshBounds, visibleRect)) {
                canvasRenderer.refreshTiles(viewportInteractor.getCurrentScale(), refreshBounds)
            }

            minimapDrawer?.setDirty()
            drawContent()

            // Use DU for fast visual update after undo/redo instead of full GC refresh
            EpdController.invalidate(this, UpdateMode.DU)
        }

        // --- Ruler / protractor ---

        private val guideFillPaint =
            Paint().apply {
                color = Color.argb(70, 160, 160, 160)
                style = Paint.Style.FILL
            }
        private val guideLinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
        private val guideTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
            }

        fun setRulerVisible(visible: Boolean) {
            if (visible == isRulerVisible) return
            if (visible) ruler.place(width, height, resources.displayMetrics.density)
            isRulerVisible = visible
            onGuidesChanged()
        }

        fun setProtractorVisible(visible: Boolean) {
            if (visible == isProtractorVisible) return
            if (visible) protractor.place(width, height, resources.displayMetrics.density)
            isProtractorVisible = visible
            onGuidesChanged()
        }

        private var isGuideInteracting = false

        /**
         * Like panning the canvas: while a guide is dragged, pause the pen's raw-drawing layer (which
         * otherwise holds back every surface update) and switch the screen to its fast animation mode.
         */
        private fun beginGuideInteraction() {
            if (isGuideInteracting) return
            isGuideInteracting = true
            touchHelper?.setRawDrawingEnabled(false)
            EpdController.setScreenHandWritingPenState(this, EpdPenManager.PEN_PAUSE)
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .enterAnimationUpdate(true)
            canvasRenderer.setInteracting(true)
        }

        private fun endGuideInteraction() {
            if (!isGuideInteracting) return
            isGuideInteracting = false
            canvasRenderer.setInteracting(false)
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .exitAnimationUpdate(true)
            drawContent()
            // A clean refresh once it stops, then hand the screen back to the pen.
            EpdController.invalidate(this, UpdateMode.GU)
            resumeRawDrawing()
        }

        private fun onGuidesChanged() {
            if (guideGesture != null) endGuideInteraction()
            guideGesture = null
            drawContent()
            performHardRefresh()
        }

        /** Called from the raw-input thread: the guide a stroke starting at (x, y) should follow. */
        private fun guideSnapAt(
            x: Float,
            y: Float,
        ): com.alexdremov.notate.ui.guide.GuideSnap? {
            if (zoomWindow.isInBox(x, y)) return null
            val tolerance = guideSnapDp * resources.displayMetrics.density
            if (isProtractorVisible) protractor.snapAt(x, y, tolerance)?.let { return it }
            if (isRulerVisible) ruler.snapAt(x, y, tolerance)?.let { return it }
            return null
        }

        private fun handleGuideTouch(event: MotionEvent): Boolean {
            if (!isRulerVisible && !isProtractorVisible) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x
                    val y = event.y
                    guideGesture =
                        when {
                            isProtractorVisible && protractor.contains(x, y) -> GuideGesture(false, x, y)
                            isRulerVisible && ruler.contains(x, y) -> GuideGesture(true, x, y)
                            else -> null
                        }
                    if (guideGesture != null) beginGuideInteraction()
                    return guideGesture != null
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    val g = guideGesture ?: return false
                    if (event.pointerCount >= 2) startTwoFingerGuide(g, event)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val g = guideGesture ?: return false
                    if (g.twoFinger && event.pointerCount >= 2) {
                        val angle = pointerAngle(event)
                        val midX = (event.getX(0) + event.getX(1)) / 2f
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        val rotation = g.startGuideAngle + (angle - g.startPointerAngle)
                        if (g.isRuler) {
                            ruler.setRotation(rotation)
                            ruler.moveBy(midX - g.lastMidX, midY - g.lastMidY)
                        } else {
                            protractor.setRotation(rotation)
                            protractor.setRadius(g.startRadius * pointerDist(event) / g.startPointerDist)
                            protractor.moveBy(midX - g.lastMidX, midY - g.lastMidY)
                        }
                        g.lastMidX = midX
                        g.lastMidY = midY
                    } else if (!g.twoFinger) {
                        val dx = event.x - g.lastX
                        val dy = event.y - g.lastY
                        if (g.isRuler) ruler.moveBy(dx, dy) else protractor.moveBy(dx, dy)
                        g.lastX = event.x
                        g.lastY = event.y
                    }
                    invalidateCanvas()
                    return true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val g = guideGesture ?: return false
                    // Keep moving with the finger that stays down, without a jump.
                    g.twoFinger = false
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    g.lastX = event.getX(remaining)
                    g.lastY = event.getY(remaining)
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (guideGesture == null) return false
                    guideGesture = null
                    endGuideInteraction()
                    return true
                }
            }
            return guideGesture != null
        }

        private fun startTwoFingerGuide(
            g: GuideGesture,
            event: MotionEvent,
        ) {
            g.twoFinger = true
            g.startPointerAngle = pointerAngle(event)
            g.startGuideAngle = if (g.isRuler) ruler.angle else protractor.angle
            g.startPointerDist = pointerDist(event).coerceAtLeast(1f)
            g.startRadius = protractor.radius
            g.lastMidX = (event.getX(0) + event.getX(1)) / 2f
            g.lastMidY = (event.getY(0) + event.getY(1)) / 2f
        }

        private fun pointerAngle(event: MotionEvent) =
            kotlin.math.atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))

        private fun pointerDist(event: MotionEvent) =
            kotlin.math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

        // Guide artwork is drawn once into a bitmap (hundreds of ticks and labels) and then only
        // rotated/moved per frame, so dragging a guide stays cheap.
        private var rulerArt: android.graphics.Bitmap? = null
        private var protractorArt: android.graphics.Bitmap? = null
        private var protractorArtRadius = 0f
        private val guideBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private fun rulerArt(): android.graphics.Bitmap {
            rulerArt?.let { if (it.width == kotlin.math.ceil(ruler.length).toInt()) return it }
            val r = ruler
            val w = kotlin.math.ceil(r.length).toInt().coerceAtLeast(1)
            val h = kotlin.math.ceil(r.width).toInt().coerceAtLeast(1)
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val mm = resources.displayMetrics.xdpi / 25.4f
            val half = w / 2f
            val hw = h / 2f
            c.translate(half, hw)
            c.drawRect(-half, -hw, half, hw, guideFillPaint)
            c.drawLine(-half, -hw + 1f, half, -hw + 1f, guideLinePaint)
            c.drawLine(-half, hw - 1f, half, hw - 1f, guideLinePaint)
            guideTextPaint.textSize = r.width * 0.2f
            val count = (half / mm).toInt()
            for (i in -count..count) {
                val x = i * mm
                val len =
                    when {
                        i % 10 == 0 -> r.width * 0.28f
                        i % 5 == 0 -> r.width * 0.18f
                        else -> r.width * 0.1f
                    }
                c.drawLine(x, -hw, x, -hw + len, guideLinePaint)
                c.drawLine(x, hw, x, hw - len, guideLinePaint)
                if (i % 10 == 0 && i != 0) {
                    c.drawText(kotlin.math.abs(i / 10).toString(), x, -hw + len + guideTextPaint.textSize, guideTextPaint)
                }
            }
            rulerArt?.recycle()
            rulerArt = bmp
            return bmp
        }

        /** Ruler body with millimetre ticks along both edges and its angle in the middle. */
        private fun drawRuler(cv: Canvas) {
            val r = ruler
            val art = rulerArt()
            cv.save()
            cv.translate(r.cx, r.cy)
            cv.rotate(Math.toDegrees(r.angle.toDouble()).toFloat())
            cv.drawBitmap(art, -art.width / 2f, -art.height / 2f, guideBitmapPaint)
            guideTextPaint.textSize = r.width * 0.3f
            cv.drawText("${r.degrees}°", 0f, guideTextPaint.textSize * 0.35f + r.width * 0.12f, guideTextPaint)
            cv.restore()
        }

        private fun protractorArt(radius: Float): android.graphics.Bitmap {
            protractorArt?.let { if (protractorArtRadius == radius) return it }
            val rad = radius
            val w = kotlin.math.ceil(rad * 2 + 4).toInt()
            val h = kotlin.math.ceil(rad + 4).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.translate(w / 2f, rad + 2f) // base centre
            val oval = RectF(-rad, -rad, rad, rad)
            c.drawArc(oval, 180f, 180f, true, guideFillPaint)
            c.drawArc(oval, 180f, 180f, false, guideLinePaint)
            c.drawLine(-rad, 0f, rad, 0f, guideLinePaint)
            guideTextPaint.textSize = (rad * 0.06f).coerceIn(14f, 36f)
            for (deg in 0..180) {
                val len =
                    when {
                        deg % 10 == 0 -> rad * 0.1f
                        deg % 5 == 0 -> rad * 0.065f
                        else -> rad * 0.035f
                    }
                val a = Math.toRadians(deg.toDouble())
                val cs = kotlin.math.cos(a).toFloat()
                val sn = -kotlin.math.sin(a).toFloat()
                c.drawLine(cs * rad, sn * rad, cs * (rad - len), sn * (rad - len), guideLinePaint)
                if (deg % 10 == 0) {
                    val lr = rad - len - guideTextPaint.textSize
                    c.drawText(deg.toString(), cs * lr, sn * lr + guideTextPaint.textSize * 0.35f, guideTextPaint)
                }
            }
            val mark = rad * 0.04f
            c.drawCircle(0f, 0f, mark, guideLinePaint)
            c.drawLine(0f, -mark * 2, 0f, 0f, guideLinePaint)
            protractorArt?.recycle()
            protractorArt = bmp
            protractorArtRadius = radius
            return bmp
        }

        /** Half disc with 1/5/10 degree ticks, labels every 10 degrees and a centre mark. */
        private fun drawProtractor(cv: Canvas) {
            val p = protractor
            // While pinching, stretch the cached art; it is redrawn sharp at the new size afterwards.
            val art = if (isGuideInteracting && protractorArt != null) protractorArt!! else protractorArt(p.radius)
            val scale = p.radius / protractorArtRadius
            cv.save()
            cv.translate(p.cx, p.cy)
            cv.rotate(Math.toDegrees(p.angle.toDouble()).toFloat())
            cv.scale(scale, scale)
            cv.drawBitmap(art, -art.width / 2f, -(protractorArtRadius + 2f), guideBitmapPaint)
            cv.restore()
        }

        // --- Zoom writing window ---

        val isZoomWindowOpen: Boolean get() = zoomWindow.isOpen

        private var pendingZoomControl: com.alexdremov.notate.ui.zoom.ZoomWindow.Control? = null

        private val zoomFillPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val zoomStripPaint = Paint().apply { color = Color.rgb(235, 235, 235); style = Paint.Style.FILL }
        private val zoomBorderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
        private val zoomIconPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 4f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        private val zoomTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
            }
        private val zoomTargetPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
        private val zoomGuidePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(160, 160, 160)
                style = Paint.Style.STROKE
                strokeWidth = 2f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 10f), 0f)
            }

        fun toggleZoomWindow() {
            if (zoomWindow.isOpen) closeZoomWindow() else openZoomWindow()
        }

        fun openZoomWindow() {
            if (width == 0 || height == 0 || zoomWindow.isOpen) return
            zoomWindow.open(matrix, width, height, resources.displayMetrics.density)
            applyExclusionRects()
            onZoomWindowChanged?.invoke(true)
            drawContent()
            performHardRefresh()
        }

        fun closeZoomWindow() {
            if (!zoomWindow.isOpen) return
            zoomWindow.close()
            zoomDrag = null
            pendingZoomControl = null
            applyExclusionRects()
            onZoomWindowChanged?.invoke(false)
            drawContent()
            performHardRefresh()
        }

        /**
         * A stylus tap on an excluded area, from pen-down ([downX], [downY]) to pen-up. Runs the zoom
         * window button under it, if any. Returns true if the tap was on the zoom window's buttons.
         */
        fun handleStylusTapOnZoomControls(
            downX: Float,
            downY: Float,
            upX: Float,
            upY: Float,
        ): Boolean {
            val control = zoomWindow.controlAt(downX, downY) ?: return false
            if (zoomWindow.controlAt(upX, upY) == control) onZoomControl(control)
            return true
        }

        private fun onZoomControl(control: com.alexdremov.notate.ui.zoom.ZoomWindow.Control) {
            val zw = zoomWindow
            when (control) {
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.CLOSE -> {
                    closeZoomWindow()
                    return
                }
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.ZOOM_OUT -> zw.zoomBy(1f / com.alexdremov.notate.ui.zoom.ZoomWindow.ZOOM_STEP)
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.ZOOM_IN -> zw.zoomBy(com.alexdremov.notate.ui.zoom.ZoomWindow.ZOOM_STEP)
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.PREV -> zw.step(-com.alexdremov.notate.ui.zoom.ZoomWindow.STEP_FRACTION)
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.NEXT -> zw.step(com.alexdremov.notate.ui.zoom.ZoomWindow.STEP_FRACTION)
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.NEW_LINE -> zw.newLine()
            }
            onZoomTargetMoved()
        }

        /** Keeps the zoom target visible above the docked box, then redraws and refreshes the screen. */
        private fun onZoomTargetMoved() {
            if (!zoomWindow.isOpen) return
            val screen = RectF(zoomWindow.target)
            matrix.mapRect(screen)
            val pad = 24f * resources.displayMetrics.density
            val bottomLimit = zoomWindow.box.top - pad
            var dx = 0f
            var dy = 0f
            if (screen.bottom > bottomLimit) dy = bottomLimit - screen.bottom
            if (screen.top + dy < pad) dy = pad - screen.top
            if (screen.right > width - pad) dx = width - pad - screen.right
            if (screen.left + dx < pad) dx = pad - screen.left
            if (dx != 0f || dy != 0f) scrollByOffset(dx, dy)
            drawContent()
            EpdController.invalidate(this, UpdateMode.DU)
        }

        /** Finger input on the zoom window: buttons, and dragging to move the target. */
        private fun handleZoomWindowTouch(event: MotionEvent): Boolean {
            if (!zoomWindow.isOpen) return false
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    zoomDrag = null
                    pendingZoomControl = zoomWindow.controlAt(x, y)
                    if (pendingZoomControl != null || zoomWindow.controls.contains(x, y)) return true
                    if (zoomWindow.isInBox(x, y)) {
                        // Dragging the magnified content moves the target the other way, like panning.
                        zoomDrag = ZoomDrag(x, y, -1f / zoomWindow.scale)
                        return true
                    }
                    val targetScreen = RectF(zoomWindow.target)
                    matrix.mapRect(targetScreen)
                    val slop = 16f * resources.displayMetrics.density
                    targetScreen.inset(-slop, -slop)
                    if (targetScreen.contains(x, y)) {
                        zoomDrag = ZoomDrag(x, y, 1f / viewportInteractor.getCurrentScale())
                        return true
                    }
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val drag = zoomDrag ?: return pendingZoomControl != null
                    zoomWindow.moveBy((x - drag.lastX) * drag.worldPerPx, (y - drag.lastY) * drag.worldPerPx)
                    drag.lastX = x
                    drag.lastY = y
                    invalidateCanvas()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val control = pendingZoomControl
                    val drag = zoomDrag
                    pendingZoomControl = null
                    zoomDrag = null
                    if (control != null) {
                        if (zoomWindow.controlAt(x, y) == control) onZoomControl(control)
                        return true
                    }
                    if (drag != null) {
                        zoomWindow.commitLineStart()
                        onZoomTargetMoved()
                        return true
                    }
                    return false
                }

                MotionEvent.ACTION_CANCEL -> {
                    val consumed = zoomDrag != null || pendingZoomControl != null
                    pendingZoomControl = null
                    zoomDrag = null
                    return consumed
                }

                else -> return zoomDrag != null || pendingZoomControl != null
            }
        }

        private fun drawZoomTargetOutline(
            cv: Canvas,
            viewScale: Float,
        ) {
            zoomWindow.onViewScaleChanged(viewScale)
            val r = RectF(zoomWindow.target)
            matrix.mapRect(r)
            cv.drawRect(r, zoomTargetPaint)
        }

        private fun drawZoomWindow(cv: Canvas) {
            val zw = zoomWindow
            cv.drawRect(zw.box, zoomFillPaint)

            val zm = zw.worldToWriting(zoomMatrix)
            cv.save()
            cv.clipRect(zw.writing)
            drawZoomBackground(cv, zm)
            canvasRenderer.renderDirectVectorsSync(cv, zm, RectF(zw.target), RenderQuality.HIGH)
            cv.restore()

            // Writing past this line slides the window along.
            val guideX = zw.writing.left + zw.writing.width() * com.alexdremov.notate.ui.zoom.ZoomWindow.ADVANCE_TRIGGER
            cv.drawLine(guideX, zw.writing.top + 8f, guideX, zw.writing.bottom - 8f, zoomGuidePaint)

            cv.drawRect(zw.controls, zoomStripPaint)
            val rects = zw.controlRects()
            for ((control, rect) in rects) drawZoomControlIcon(cv, control, rect)
            rects.firstOrNull { it.first == com.alexdremov.notate.ui.zoom.ZoomWindow.Control.ZOOM_IN }?.second?.let { r ->
                zoomTextPaint.textSize = zw.controls.height() * 0.4f
                val label = "×" + String.format(java.util.Locale.US, "%.1f", zw.magnification)
                cv.drawText(label, r.right + r.width() * 0.6f, r.centerY() + zoomTextPaint.textSize * 0.35f, zoomTextPaint)
            }
            cv.drawLine(zw.controls.left, zw.controls.bottom, zw.controls.right, zw.controls.bottom, zoomBorderPaint)
            cv.drawRect(zw.box, zoomBorderPaint)
        }

        private fun drawZoomBackground(
            cv: Canvas,
            zm: Matrix,
        ) {
            val target = zoomWindow.target
            val style = canvasModel.backgroundStyle
            cv.save()
            cv.concat(zm)
            if (canvasModel.canvasType == CanvasType.FIXED_PAGES) {
                cv.drawColor(CanvasConfig.FIXED_PAGE_CANVAS_BG_COLOR)
                val pageFullHeight = canvasModel.pageHeight + CanvasConfig.PAGE_SPACING
                val first = kotlin.math.floor(target.top / pageFullHeight).toInt().coerceAtLeast(0)
                val last = kotlin.math.floor(target.bottom / pageFullHeight).toInt()
                for (i in first..last) {
                    val top = i * pageFullHeight
                    val pageRect = RectF(0f, top, canvasModel.pageWidth, top + canvasModel.pageHeight)
                    if (!RectF.intersects(pageRect, target)) continue
                    cv.drawRect(pageRect, zoomFillPaint)
                    cv.save()
                    cv.clipRect(pageRect)
                    val patternArea =
                        com.alexdremov.notate.ui.render.background.PatternLayoutHelper
                            .calculatePatternArea(pageRect, style)
                    val visible = RectF(patternArea)
                    if (visible.intersect(target)) {
                        val (offsetX, offsetY) =
                            com.alexdremov.notate.ui.render.background.PatternLayoutHelper
                                .calculateOffsets(patternArea, style)
                        com.alexdremov.notate.ui.render.BackgroundDrawer
                            .draw(cv, style, visible, zoomLevel = zoomWindow.scale, offsetX = offsetX, offsetY = offsetY)
                    }
                    cv.restore()
                }
            } else {
                com.alexdremov.notate.ui.render.BackgroundDrawer
                    .draw(cv, style, RectF(target), zoomLevel = zoomWindow.scale)
            }
            cv.restore()
        }

        private fun drawZoomControlIcon(
            cv: Canvas,
            control: com.alexdremov.notate.ui.zoom.ZoomWindow.Control,
            r: RectF,
        ) {
            val cx = r.centerX()
            val cy = r.centerY()
            val u = r.height() * 0.22f
            val p = Path()
            when (control) {
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.ZOOM_OUT -> {
                    p.moveTo(cx - u, cy)
                    p.lineTo(cx + u, cy)
                }
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.ZOOM_IN -> {
                    p.moveTo(cx - u, cy)
                    p.lineTo(cx + u, cy)
                    p.moveTo(cx, cy - u)
                    p.lineTo(cx, cy + u)
                }
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.PREV -> {
                    p.moveTo(cx + u * 0.6f, cy - u)
                    p.lineTo(cx - u * 0.6f, cy)
                    p.lineTo(cx + u * 0.6f, cy + u)
                }
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.NEXT -> {
                    p.moveTo(cx - u * 0.6f, cy - u)
                    p.lineTo(cx + u * 0.6f, cy)
                    p.lineTo(cx - u * 0.6f, cy + u)
                }
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.NEW_LINE -> {
                    p.moveTo(cx + u, cy - u)
                    p.lineTo(cx + u, cy + u * 0.4f)
                    p.lineTo(cx - u, cy + u * 0.4f)
                    p.moveTo(cx - u * 0.4f, cy - u * 0.2f)
                    p.lineTo(cx - u, cy + u * 0.4f)
                    p.lineTo(cx - u * 0.4f, cy + u)
                }
                com.alexdremov.notate.ui.zoom.ZoomWindow.Control.CLOSE -> {
                    p.moveTo(cx - u, cy - u)
                    p.lineTo(cx + u, cy + u)
                    p.moveTo(cx + u, cy - u)
                    p.lineTo(cx - u, cy + u)
                }
            }
            cv.drawPath(p, zoomIconPaint)
        }

        fun notifyStrokeFinished() {
            lastStrokeEndTime = System.currentTimeMillis()
            palmRejector.onPenUp(SystemClock.uptimeMillis())
        }
    }
