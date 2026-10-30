package com.alexdremov.notate.ui.input

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.alexdremov.notate.config.CanvasConfig
import com.alexdremov.notate.model.EraserType
import com.alexdremov.notate.model.PenTool
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.model.ToolType
import com.alexdremov.notate.ui.controller.CanvasController
import com.alexdremov.notate.util.ColorUtils
import com.alexdremov.notate.util.Logger
import com.alexdremov.notate.util.ShapeGeometry
import com.alexdremov.notate.util.ShapeRecognizer
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.schedule

class PenInputHandler(
    private val controller: CanvasController,
    private val view: android.view.View,
    private val scope: CoroutineScope,
    private val matrix: Matrix,
    private val inverseMatrix: Matrix,
    private val onStrokeStarted: () -> Unit = {},
    private val onStrokeFinished: () -> Unit,
) : RawInputCallback() {
    @Volatile
    private var currentTool: PenTool = PenTool.defaultPens()[0]

    @Volatile
    private var eraserTool: PenTool? = null

    @Volatile
    private var previousTool: PenTool? = null

    @Volatile
    private var isTemporaryEraserActive = false

    @Volatile
    private var isStrokeInProgress = false

    // --- Selection State ---
    private var isSelecting = false // True if drawing selection lasso/rect

    private var touchHelper: TouchHelper? = null
    private val strokeBuilder = StrokeBuilder()
    private val eraserHandler = EraserGestureHandler(controller, strokeBuilder, scope)
    private var currentScale: Float = 1.0f
    private var cursorView: com.alexdremov.notate.ui.CursorView? = null
    private val lassoPath = Path()

    // Track if the current active stroke is intended to be an eraser
    private var isCurrentStrokeEraser = false

    // Track if hardware rendering is disabled due to size
    private var isLargeStrokeMode = false

    // Track screen bounds for partial refresh
    private val currentStrokeScreenBounds = RectF()

    @Volatile
    private var pendingPerfectShape: ShapeRecognizer.RecognitionResult? = null

    private var isIgnoringCurrentStroke = false

    // World-space pen-down point of the current drag shape (shape tools only)
    @Volatile
    private var shapeStartWorld: android.graphics.PointF? = null

    // The current stroke started on the toolbar/sidebar: discard it and repaint that UI.
    private var isDiscardingExcludedStroke = false
    private var excludedDownX = 0f
    private var excludedDownY = 0f

    private lateinit var dwellDetector: DwellDetector

    /**
     * Zoom writing window hook: returns the world -> screen matrix of the magnified writing area if the
     * view point lies inside it, else null (the stroke uses the canvas matrix).
     */
    var zoomMatrixAt: ((Float, Float) -> Matrix?)? = null

    /** Called on the main thread with the world bounds of a stroke written in the zoom window. */
    var onZoomStrokeFinished: ((RectF) -> Unit)? = null

    // World <-> screen mapping of the current stroke: the canvas matrix, or the zoom window's.
    private val strokeMatrix = Matrix()
    private val strokeInverse = Matrix()

    @Volatile
    private var isZoomStroke = false

    /** Ruler/protractor hook: the guide a stroke starting at this view point should follow, if any. */
    var guideSnapAt: ((Float, Float) -> com.alexdremov.notate.ui.guide.GuideSnap?)? = null

    // Guide followed by the current stroke (screen space), and where it started.
    private var activeGuide: com.alexdremov.notate.ui.guide.GuideSnap? = null
    private var guideStartX = 0f
    private var guideStartY = 0f
    private var guideArcStart = 0.0
    private var guideArcLast = 0.0
    private var guideArcSweep = 0.0
    private var guideScreenPoints: List<android.graphics.PointF> = emptyList()

    // The pen hovers next to a guide: hardware ink is off so only the guided preview shows.
    private var isHoveringGuide = false

    // Scale used for hardware ink width: the zoom window's while the pen is over it.
    private var inkScale = 1.0f
    private var isHoveringZoom = false

    init {
        dwellDetector =
            DwellDetector(view.context, strokeBuilder) { pts ->
                // On Dwell Detected
                if (!isStrokeInProgress) return@DwellDetector
                // Do not trigger shape perfection for selection or eraser tools
                if (currentTool.type == ToolType.SELECT || currentTool.type == ToolType.ERASER) return@DwellDetector

                // Shape Perfection Logic (Stylus Dwell)
                val result = ShapeRecognizer.recognize(pts)
                if (result != null && result.shape != ShapeRecognizer.RecognizedShape.NONE) {
                    pendingPerfectShape = result
                    dwellDetector.markRecognized()

                    // Transform path to Screen Coordinates for display
                    val screenPath = Path(result.path)
                    screenPath.transform(strokeMatrix)

                    cursorView?.showShapePreview(screenPath)

                    // Force EPD Refresh (UpdateMode.DU for fast feedback)
                    val bounds = RectF()
                    screenPath.computeBounds(bounds, true)
                    // Add padding for stroke width
                    val padding = (currentTool.width * inkScale) + 20f
                    bounds.inset(-padding, -padding)

                    val dirtyRect =
                        android.graphics.Rect(
                            bounds.left.toInt(),
                            bounds.top.toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt(),
                        )

                    // Toggle Raw Drawing to allow software layer update to be visible
                    val drawingBefore = touchHelper?.isRawDrawingInputEnabled() ?: false
                    if (drawingBefore) {
                        touchHelper?.setRawDrawingEnabled(false)
                    }

                    EpdController.invalidate(
                        view,
                        dirtyRect.left,
                        dirtyRect.top,
                        dirtyRect.right,
                        dirtyRect.bottom,
                        UpdateMode.DU,
                    )

                    if (drawingBefore) {
                        touchHelper?.setRawDrawingEnabled(true)
                    }
                }
            }
    }

    // Delayed Refresh Logic
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val HOVER_EXIT_REFRESH_DELAY_MS = 2000L

    private fun performRefresh(ignoreStrokeState: Boolean = false) {
        if (!ignoreStrokeState && isStrokeInProgress) return
        if (!currentStrokeScreenBounds.isEmpty) {
            val drawingBefore = touchHelper?.isRawDrawingInputEnabled() ?: false
            touchHelper?.setRawDrawingEnabled(false)

            // Expand bounds slightly for anti-aliasing safety
            val refreshPadding = (currentTool.width * inkScale) + 10f
            currentStrokeScreenBounds.inset(-refreshPadding, -refreshPadding)

            val dirtyRect =
                android.graphics.Rect(
                    currentStrokeScreenBounds.left.toInt(),
                    currentStrokeScreenBounds.top.toInt(),
                    currentStrokeScreenBounds.right.toInt(),
                    currentStrokeScreenBounds.bottom.toInt(),
                )

            val l = dirtyRect.left
            val t = dirtyRect.top
            val r = dirtyRect.right
            val b = dirtyRect.bottom

            // Perform region-specific High Quality refresh
            EpdController.invalidate(
                view,
                l,
                t,
                r,
                b,
                UpdateMode.GC,
            )

            touchHelper?.setRawDrawingEnabled(drawingBefore)
            // Restore the correct rendering state (as toggling input might reset it)
            updateTouchHelperTool()

            // Reset bounds after refresh
            currentStrokeScreenBounds.setEmpty()
        }
    }

    private val refreshRunnable = Runnable { performRefresh(false) }

    fun onHoverEnter() {
        // User started hovering, postpone any pending refresh
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    fun onHoverMove(event: android.view.MotionEvent) {
        // Keep postponing as long as we move
        refreshHandler.removeCallbacks(refreshRunnable)

        // Size the hardware ink for the area the pen is about to write in.
        if (isStrokeInProgress) return
        val zoom = zoomMatrixAt?.invoke(event.x, event.y)
        val nearGuide = zoom == null && isGuideTool() && guideSnapAt?.invoke(event.x, event.y) != null
        if ((zoom != null) != isHoveringZoom || nearGuide != isHoveringGuide) {
            isHoveringZoom = zoom != null
            isHoveringGuide = nearGuide
            inkScale = if (zoom != null) zoom.mapRadius(1f) else currentScale
            updateTouchHelperTool()
        }
    }

    fun onHoverExit() {
        if (isStrokeInProgress) return
        // User stopped hovering. If we have pending changes, refresh soon.
        if (!currentStrokeScreenBounds.isEmpty) {
            refreshHandler.postDelayed(refreshRunnable, HOVER_EXIT_REFRESH_DELAY_MS)
        }
    }

    fun setTool(tool: PenTool) {
        if (isTemporaryEraserActive) {
            previousTool = tool
        } else {
            this.currentTool = tool
            updateTouchHelperTool()
        }
    }

    fun setEraserTool(tool: PenTool) {
        this.eraserTool = tool
    }

    fun setTouchHelper(helper: TouchHelper) {
        this.touchHelper = helper
        updateTouchHelperTool()
    }

    fun setScale(scale: Float) {
        this.currentScale = scale
        if (!isHoveringZoom && !isZoomStroke) inkScale = scale
        updateTouchHelperTool()
    }

    fun setCursorView(view: com.alexdremov.notate.ui.CursorView) {
        this.cursorView = view
    }

    /**
     * Activates the temporary eraser mode (e.g. side button press).
     * Ignored if a stroke is already in progress to prevent hardware glitches.
     */
    fun prepareEraser() {
        if (isStrokeInProgress) return // Ignore button press mid-stroke

        if (!isTemporaryEraserActive && eraserTool != null && currentTool.type != ToolType.ERASER) {
            previousTool = currentTool
            currentTool = eraserTool!!
            isTemporaryEraserActive = true
            updateTouchHelperTool()
        }
    }

    /**
     * Deactivates the temporary eraser mode.
     * Ignored if a stroke is already in progress.
     */
    fun finishEraser() {
        if (isStrokeInProgress) return // Ignore button release mid-stroke

        if (isTemporaryEraserActive && previousTool != null) {
            currentTool = previousTool!!
            previousTool = null
            isTemporaryEraserActive = false
            updateTouchHelperTool()
        }
    }

    private var selectionStartX: Float? = null
    private var selectionStartY: Float? = null

    /**
     * Configures the Onyx TouchHelper based on the current tool and scale.
     */
    private fun updateTouchHelperTool() {
        val helper = touchHelper ?: return
        isLargeStrokeMode =
            PenToolConfigurator.configure(
                helper,
                currentTool,
                inkScale,
                view.context,
            )
        // Next to a ruler/protractor the raw pen path would wobble off the guide: show only the preview.
        if (isHoveringGuide || activeGuide != null) helper.setRawDrawingRenderEnabled(false)
    }

    // Deprecated: Use setScale instead
    fun setStrokeWidth(width: Float) {
        touchHelper?.setStrokeWidth(width)
    }

    private fun mapPoint(
        x: Float,
        y: Float,
    ): FloatArray {
        val pts = floatArrayOf(x, y)
        strokeInverse.mapPoints(pts)
        return pts
    }

    @Volatile
    private var lastProcessedTimestamp: Long = -1L

    /**
     * Called when the stylus touches the screen.
     * Starts a new stroke or eraser session.
     */
    override fun onBeginRawDrawing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        // Cancel any pending refresh as the user has resumed writing
        refreshHandler.removeCallbacks(refreshRunnable)

        // A stylus tap on the toolbar is a click, never ink — even if the driver's
        // exclusion rects are stale. Leave the selection alone too, so tools can act on it.
        val canvasView = view as? com.alexdremov.notate.ui.OnyxCanvasView
        if (canvasView != null && canvasView.isInExclusionZone(touchPoint.x, touchPoint.y)) {
            isStrokeInProgress = true
            isIgnoringCurrentStroke = true
            isDiscardingExcludedStroke = true
            excludedDownX = touchPoint.x
            excludedDownY = touchPoint.y
            lastProcessedTimestamp = touchPoint.timestamp
            return
        }

        isStrokeInProgress = true
        isSelecting = false
        isIgnoringCurrentStroke = false
        lastProcessedTimestamp = touchPoint.timestamp

        // Pick the coordinate mapping for this stroke: zoom writing window or the canvas itself.
        val zoom = zoomMatrixAt?.invoke(touchPoint.x, touchPoint.y)
        isZoomStroke = zoom != null
        strokeMatrix.set(zoom ?: matrix)
        strokeMatrix.invert(strokeInverse)
        inkScale = if (zoom != null) zoom.mapRadius(1f) else currentScale
        isHoveringZoom = isZoomStroke
        activeGuide = if (!b && !isZoomStroke && isGuideTool()) guideSnapAt?.invoke(touchPoint.x, touchPoint.y) else null
        isHoveringGuide = activeGuide != null
        activeGuide?.let { startGuide(it, touchPoint.x, touchPoint.y) }
        if (isZoomStroke && currentTool.type == ToolType.SELECT) {
            // Selecting through the magnifier isn't supported: ignore the stroke.
            isIgnoringCurrentStroke = true
            return
        }

        // Always clear previous selection when starting a new stylus interaction
        scope.launch { controller.clearSelection() }
        view.post { (view as? com.alexdremov.notate.ui.OnyxCanvasView)?.dismissActionPopup() }

        if (currentTool.type == ToolType.SELECT) {
            isSelecting = true
            if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                lassoPath.reset()
                lassoPath.moveTo(touchPoint.x, touchPoint.y)
            } else {
                selectionStartX = touchPoint.x
                selectionStartY = touchPoint.y
            }
        }

        if (dwellDetector.consumeIgnoreNextStroke()) {
            isIgnoringCurrentStroke = true
            return
        }

        if (b || currentTool.type == ToolType.ERASER) {
            isCurrentStrokeEraser = true
        }

        if (b && currentTool.type != ToolType.ERASER && eraserTool != null) {
            if (!isTemporaryEraserActive) {
                previousTool = currentTool
                currentTool = eraserTool!!
                isTemporaryEraserActive = true
                isCurrentStrokeEraser = true
            }
        }

        // Force refresh configuration to ensure driver state matches tool state.
        // This fixes a race condition where switching tools (especially via toolbar) might not propagate
        // to the native driver in time for the next stroke, causing "Old Hardware Ink" to appear.
        updateTouchHelperTool()

        // Always enter A2 mode for writing to ensure low latency and visibility of fast strokes
        com.onyx.android.sdk.api.device.EpdDeviceManager
            .enterAnimationUpdate(true)

        if (currentTool.type == ToolType.ERASER) {
            scope.launch { controller.startBatchSession() }
            eraserHandler.reset()
        } else if (currentTool.type != ToolType.SELECT) {
            // Standard Pen Start: Ensure Eraser Channel is DEAD
            // We skip this for SELECT because LASSO select uses the eraser channel for hardware dashed line.
            Device.currentDevice().setEraserRawDrawingEnabled(false, TouchHelper.STROKE_STYLE_DASH)
        }

        val worldPts = mapPoint(touchPoint.x, touchPoint.y)
        onStrokeStarted()

        // Initialize screen bounds for refresh
        currentStrokeScreenBounds.set(touchPoint.x, touchPoint.y, touchPoint.x, touchPoint.y)

        val startPoint =
            TouchPoint(
                worldPts[0],
                worldPts[1],
                touchPoint.pressure,
                touchPoint.size,
                touchPoint.tiltX,
                touchPoint.tiltY,
                touchPoint.timestamp,
            )

        synchronized(strokeBuilder) {
            strokeBuilder.start(startPoint)
        }

        // Reset Dwell State
        cursorView?.hideShapePreview()
        pendingPerfectShape = null
        shapeStartWorld = if (currentTool.isDragShapeTool) android.graphics.PointF(startPoint.x, startPoint.y) else null

        if (currentTool.type != ToolType.SELECT && !currentTool.isDragShapeTool && activeGuide == null) {
            dwellDetector.onStart(touchPoint, currentTool)
        }

        if (currentTool.type == ToolType.ERASER && currentTool.eraserType == EraserType.LASSO) {
            lassoPath.reset()
            lassoPath.moveTo(touchPoint.x, touchPoint.y)
        }
        if (currentTool.type == ToolType.ERASER) {
            eraserHandler.start(startPoint)
        }
        updateCursor(touchPoint)
    }

    /**
     * Called when the stylus is lifted.
     * Finalizes the stroke/erasure and commits it to the controller.
     */
    override fun onEndRawDrawing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        if (isIgnoringCurrentStroke) {
            isIgnoringCurrentStroke = false
            isStrokeInProgress = false
            com.onyx.android.sdk.api.device.EpdDeviceManager
                .exitAnimationUpdate(true)
            if (isDiscardingExcludedStroke) {
                isDiscardingExcludedStroke = false
                val downX = excludedDownX
                val downY = excludedDownY
                view.post {
                    val canvasView = view as? com.alexdremov.notate.ui.OnyxCanvasView ?: return@post
                    // The zoom window's buttons live inside the canvas, so a stylus tap on them lands here.
                    canvasView.handleStylusTapOnZoomControls(downX, downY, touchPoint.x, touchPoint.y)
                    canvasView.onExcludedStrokeDiscarded?.invoke()
                }
            }
            return
        }

        dwellDetector.onStop()
        // Always exit A2 mode
        com.onyx.android.sdk.api.device.EpdDeviceManager
            .exitAnimationUpdate(true)

        cursorView?.hide()

        // Handle Select Tool Finalization
        if (currentTool.type == ToolType.SELECT && isSelecting) {
            val currentLasso = Path(lassoPath)
            val currentStartX = selectionStartX
            val currentStartY = selectionStartY
            val curX = touchPoint.x
            val curY = touchPoint.y

            scope.launch {
                try {
                    if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                        currentLasso.lineTo(curX, curY)
                        currentLasso.close()
                        val worldPath = Path()
                        val inv = Matrix()
                        matrix.invert(inv)
                        currentLasso.transform(inv, worldPath)
                        val items = controller.getItemsInPath(worldPath)
                        controller.selectItems(items)
                    } else {
                        val left = minOf(currentStartX ?: curX, curX)
                        val top = minOf(currentStartY ?: curY, curY)
                        val right = maxOf(currentStartX ?: curX, curX)
                        val bottom = maxOf(currentStartY ?: curY, curY)
                        val screenRect = RectF(left, top, right, bottom)
                        val worldRect = RectF()
                        val inv = Matrix()
                        matrix.invert(inv)
                        inv.mapRect(worldRect, screenRect)
                        val items = controller.getItemsInRect(worldRect)
                        controller.selectItems(items)
                    }

                    view.post {
                        if (view is com.alexdremov.notate.ui.OnyxCanvasView) {
                            view.showActionPopup()
                            view.requestEpdRefresh(UpdateMode.GC)
                        }
                    }
                } finally {
                    isStrokeInProgress = false
                    isSelecting = false
                    (view as? com.alexdremov.notate.ui.OnyxCanvasView)?.notifyStrokeFinished()
                    onStrokeFinished()
                }
            }

            selectionStartX = null
            selectionStartY = null
            cursorView?.hideSelectionRect()
            lassoPath.reset()
            return
        }

        val worldPts = mapPoint(touchPoint.x, touchPoint.y)
        currentStrokeScreenBounds.union(touchPoint.x, touchPoint.y)

        val endPoint =
            TouchPoint(
                worldPts[0],
                worldPts[1],
                touchPoint.pressure,
                touchPoint.size,
                touchPoint.tiltX,
                touchPoint.tiltY,
                touchPoint.timestamp,
            )

        val toolSnapshot = currentTool // Snapshot tool for coroutine
        val zoomStroke = isZoomStroke
        val guide = activeGuide
        if (guide != null) updateGuidePreview(touchPoint.x, touchPoint.y)
        val guideWorldPoints =
            if (guide != null) {
                guideScreenPoints.map { p ->
                    val pts = floatArrayOf(p.x, p.y)
                    strokeInverse.mapPoints(pts)
                    android.graphics.PointF(pts[0], pts[1])
                }
            } else {
                null
            }
        activeGuide = null
        if (guide != null) cursorView?.hideShapePreview()
        val eraserToolSnapshot = eraserTool
        val tempShape = pendingPerfectShape
        val shapeStart = shapeStartWorld
        shapeStartWorld = null
        val isEraser = isCurrentStrokeEraser || toolSnapshot.type == ToolType.ERASER || b

        var builtEraserStroke: com.alexdremov.notate.model.Stroke? = null
        var builtOriginalStroke: com.alexdremov.notate.model.Stroke? = null
        var hasPoints = false

        synchronized(strokeBuilder) {
            try {
                if (!dwellDetector.isShapeRecognized && touchPoint.timestamp > lastProcessedTimestamp) {
                    strokeBuilder.addPoint(endPoint)
                    lastProcessedTimestamp = touchPoint.timestamp
                }

                hasPoints = strokeBuilder.hasPoints()
                if (hasPoints) {
                    if (isEraser) {
                        val effectiveEraserType =
                            if (toolSnapshot.type == ToolType.ERASER) {
                                toolSnapshot.eraserType
                            } else {
                                eraserToolSnapshot?.eraserType ?: EraserType.STANDARD
                            }

                        // Standard pixel eraser should be DOCUMENT-CONSTANT (matching other tools).
                        val finalWidth = toolSnapshot.width

                        builtEraserStroke = strokeBuilder.build(android.graphics.Color.BLACK, finalWidth, StrokeType.FINELINER)
                    } else {
                        builtOriginalStroke = strokeBuilder.build(toolSnapshot.color, toolSnapshot.width, toolSnapshot.strokeType)
                    }
                }
            } finally {
                strokeBuilder.clear()
            }
        }

        scope.launch {
            try {
                if (hasPoints) {
                    if (isEraser) {
                        val effectiveEraserType =
                            if (toolSnapshot.type == ToolType.ERASER) {
                                toolSnapshot.eraserType
                            } else {
                                eraserToolSnapshot?.eraserType ?: EraserType.STANDARD
                            }

                        builtEraserStroke?.let { s ->
                            controller.commitEraser(s, effectiveEraserType)
                            refreshHandler.post { performRefresh(true) }
                        }
                    } else {
                        val originalStroke = builtOriginalStroke
                        val isScribble =
                            guideWorldPoints == null &&
                                originalStroke != null &&
                                !toolSnapshot.isShapeTool &&
                                toolSnapshot.strokeType != StrokeType.HIGHLIGHTER &&
                                com.alexdremov.notate.data.PreferencesManager
                                    .isScribbleToEraseEnabled(view.context) &&
                                com.alexdremov.notate.util.ScribbleDetector
                                    .isScribble(originalStroke.points)

                        if (guideWorldPoints != null) {
                            // Drawn along a ruler/protractor: commit the guided line, not the pen path.
                            if (originalStroke != null && guideWorldPoints.size >= 2 && pathLength(guideWorldPoints) > 0f) {
                                commitShapeSegments(listOf(ShapeGeometry.Segment(guideWorldPoints)), originalStroke, toolSnapshot)
                            }
                        } else if (isScribble) {
                            controller.commitEraser(originalStroke!!, EraserType.STROKE)
                        } else if (toolSnapshot.isDragShapeTool) {
                            // Drag shape: geometry comes from pen-down/pen-up only; a tap draws nothing.
                            if (originalStroke != null && shapeStart != null) {
                                val segments =
                                    ShapeGeometry.build(
                                        toolSnapshot.shapeType,
                                        shapeStart,
                                        android.graphics.PointF(endPoint.x, endPoint.y),
                                        toolSnapshot.polygonSides,
                                    )
                                if (segments.isNotEmpty()) commitShapeSegments(segments, originalStroke, toolSnapshot)
                            }
                        } else {
                            val shapeEnabled =
                                com.alexdremov.notate.data.PreferencesManager
                                    .isShapePerfectionEnabled(view.context)
                            // Auto shape pen: snap on pen-up without needing to hold the pen still.
                            val shape =
                                when {
                                    shapeEnabled && tempShape != null &&
                                        tempShape.shape != ShapeRecognizer.RecognizedShape.NONE -> tempShape
                                    toolSnapshot.shapeType == com.alexdremov.notate.model.ShapeType.AUTO && originalStroke != null ->
                                        ShapeRecognizer.recognize(originalStroke.points)
                                    else -> null
                                }
                            if (originalStroke != null && shape != null && shape.shape != ShapeRecognizer.RecognizedShape.NONE) {
                                commitShapeSegments(
                                    shape.segments.map { ShapeGeometry.Segment(it) },
                                    originalStroke,
                                    toolSnapshot,
                                )
                            } else {
                                originalStroke?.let { s -> controller.commitStroke(s) }
                            }
                        }
                    }
                }

                if (toolSnapshot.type == ToolType.ERASER) {
                    controller.endBatchSession()
                }
            } finally {
                eraserHandler.reset()
                isCurrentStrokeEraser = false
                isStrokeInProgress = false
                (view as? com.alexdremov.notate.ui.OnyxCanvasView)?.notifyStrokeFinished()
                onStrokeFinished()
                if (zoomStroke) {
                    val worldBounds = (builtOriginalStroke ?: builtEraserStroke)?.bounds
                    if (worldBounds != null) {
                        // The ink also lands on the canvas itself: include it in the pending refresh.
                        val onCanvas = RectF(worldBounds)
                        matrix.mapRect(onCanvas)
                        currentStrokeScreenBounds.union(onCanvas)
                        if (!isEraser) view.post { onZoomStrokeFinished?.invoke(RectF(worldBounds)) }
                    }
                }
            }
        }
    }

    /**
     * Commits [segments] as one stroke each, inheriting the average pressure/tilt of [source] so the
     * result looks like the pen that drew it. Batched so the whole shape is a single undo step.
     */
    private suspend fun commitShapeSegments(
        segments: List<ShapeGeometry.Segment>,
        source: com.alexdremov.notate.model.Stroke,
        tool: PenTool,
    ) {
        val avgPressure = source.points.map { it.pressure }.average().toFloat()
        val avgSize = source.points.map { it.size }.average().toFloat()
        val avgTiltX = source.points.map { it.tiltX }.average().toFloat()
        val avgTiltY = source.points.map { it.tiltY }.average().toFloat()

        controller.startBatchSession()
        try {
            for (segment in segments) {
                if (segment.points.isEmpty()) continue
                val newTouchPoints =
                    segment.points.map { p ->
                        TouchPoint(
                            p.x,
                            p.y,
                            avgPressure,
                            avgSize,
                            avgTiltX.toInt(),
                            avgTiltY.toInt(),
                            System.currentTimeMillis(),
                        )
                    }
                val segmentPath = Path()
                segmentPath.moveTo(newTouchPoints[0].x, newTouchPoints[0].y)
                for (i in 1 until newTouchPoints.size) {
                    segmentPath.lineTo(newTouchPoints[i].x, newTouchPoints[i].y)
                }
                val width = tool.width * segment.widthScale
                val perfectedStroke =
                    com.alexdremov.notate.model.Stroke(
                        path = segmentPath,
                        points = newTouchPoints,
                        color = tool.color,
                        width = width,
                        style = tool.strokeType,
                        bounds =
                            com.alexdremov.notate.util.StrokeGeometry.computeStrokeBounds(
                                segmentPath,
                                width,
                                tool.strokeType,
                            ),
                    )
                controller.commitStroke(perfectedStroke)
            }
        } finally {
            controller.endBatchSession()
        }
    }

    /**
     * Called when the stylus moves.
     * Updates the current stroke path and handles real-time eraser feedback.
     */
    override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {
        internalProcessMove(touchPoint)
    }

    private fun internalProcessMove(
        touchPoint: TouchPoint,
        preInvertedMatrix: Matrix? = null,
    ) {
        if (isIgnoringCurrentStroke) return

        // Timestamp-based deduplication
        if (touchPoint.timestamp <= lastProcessedTimestamp) return
        lastProcessedTimestamp = touchPoint.timestamp

        val worldPts = floatArrayOf(touchPoint.x, touchPoint.y)
        (preInvertedMatrix ?: strokeInverse).mapPoints(worldPts)

        val newPoint =
            TouchPoint(
                worldPts[0],
                worldPts[1],
                touchPoint.pressure,
                touchPoint.size,
                touchPoint.tiltX,
                touchPoint.tiltY,
                touchPoint.timestamp,
            )

        if (dwellDetector.isShapeRecognized) return

        synchronized(strokeBuilder) {
            strokeBuilder.addPoint(newPoint)
        }

        dwellDetector.onMove(touchPoint, currentTool)

        currentStrokeScreenBounds.union(touchPoint.x, touchPoint.y)

        if (currentTool.isDragShapeTool) {
            updateShapePreview(android.graphics.PointF(newPoint.x, newPoint.y))
        }
        if (activeGuide != null) updateGuidePreview(touchPoint.x, touchPoint.y)

        if (currentTool.type == ToolType.ERASER || currentTool.type == ToolType.SELECT) {
            if (currentTool.type == ToolType.SELECT) {
                if (currentTool.selectionType == com.alexdremov.notate.model.SelectionType.LASSO) {
                    lassoPath.lineTo(touchPoint.x, touchPoint.y)
                } else {
                    val startX = selectionStartX
                    val startY = selectionStartY
                    if (startX != null && startY != null) {
                        val left = minOf(startX, touchPoint.x)
                        val top = minOf(startY, touchPoint.y)
                        val right = maxOf(startX, touchPoint.x)
                        val bottom = maxOf(startY, touchPoint.y)
                        cursorView?.showSelectionRect(RectF(left, top, right, bottom))
                    }
                }
            } else if (currentTool.eraserType == EraserType.LASSO) {
                lassoPath.lineTo(touchPoint.x, touchPoint.y)
            } else {
                // Eraser Width should be DOCUMENT-CONSTANT (matching pens).
                val worldWidth = currentTool.width
                val eraserType = currentTool.eraserType
                scope.launch { eraserHandler.processMove(newPoint, worldWidth, eraserType, inkScale) }
            }
        }

        updateCursor(touchPoint)
    }

    /** Pen tools that follow a ruler/protractor (drag shapes have their own geometry). */
    private fun isGuideTool() = currentTool.type == ToolType.PEN && !currentTool.isShapeTool

    private fun startGuide(
        guide: com.alexdremov.notate.ui.guide.GuideSnap,
        x: Float,
        y: Float,
    ) {
        guideStartX = x
        guideStartY = y
        guideScreenPoints = emptyList()
        if (guide is com.alexdremov.notate.ui.guide.GuideSnap.Arc) {
            guideArcStart = com.alexdremov.notate.ui.guide.GuidePath.angleOf(guide, x, y)
            guideArcLast = guideArcStart
            guideArcSweep = 0.0
        }
    }

    /** Recomputes the guided stroke for the pen at (x, y) and shows it as the preview. */
    private fun updateGuidePreview(
        x: Float,
        y: Float,
    ) {
        val guide = activeGuide ?: return
        val gp = com.alexdremov.notate.ui.guide.GuidePath
        guideScreenPoints =
            when (guide) {
                is com.alexdremov.notate.ui.guide.GuideSnap.Line -> gp.line(guide, guideStartX, guideStartY, x, y)
                is com.alexdremov.notate.ui.guide.GuideSnap.Ray -> gp.ray(guide, x, y)
                is com.alexdremov.notate.ui.guide.GuideSnap.Arc -> {
                    val a = gp.angleOf(guide, x, y)
                    guideArcSweep += gp.angleDelta(guideArcLast, a)
                    guideArcLast = a
                    gp.arc(guide, guideArcStart, guideArcSweep)
                }
            }
        val path = Path()
        guideScreenPoints.firstOrNull()?.let { path.moveTo(it.x, it.y) }
        for (i in 1 until guideScreenPoints.size) path.lineTo(guideScreenPoints[i].x, guideScreenPoints[i].y)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        currentStrokeScreenBounds.union(bounds)
        cursorView?.showShapePreview(path)
    }

    private fun pathLength(points: List<android.graphics.PointF>): Float {
        var len = 0f
        for (i in 1 until points.size) len += kotlin.math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        return len
    }

    /** Draws the drag shape from the pen-down point to [currentWorld] as a dashed preview. */
    private fun updateShapePreview(currentWorld: android.graphics.PointF) {
        val start = shapeStartWorld ?: return
        val segments = ShapeGeometry.build(currentTool.shapeType, start, currentWorld, currentTool.polygonSides)
        if (segments.isEmpty()) {
            cursorView?.hideShapePreview()
            return
        }
        val screenPath = Path()
        for (segment in segments) {
            val pts = segment.points
            if (pts.isEmpty()) continue
            screenPath.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) screenPath.lineTo(pts[i].x, pts[i].y)
        }
        screenPath.transform(strokeMatrix)

        // Shapes can extend past the pen path (e.g. polygons drawn from their center): grow refresh bounds.
        val bounds = RectF()
        screenPath.computeBounds(bounds, true)
        currentStrokeScreenBounds.union(bounds)

        cursorView?.showShapePreview(screenPath)
    }

    private fun updateCursor(touchPoint: TouchPoint) {
        if (currentTool.type == ToolType.ERASER) {
            if (currentTool.eraserType == EraserType.LASSO) {
                cursorView?.hide()
            } else {
                val radius = (currentTool.width * inkScale) / 2f
                cursorView?.update(touchPoint.x, touchPoint.y, radius)
            }
        } else if (isLargeStrokeMode) {
            val radius = (currentTool.width * inkScale) / 2f
            cursorView?.update(touchPoint.x, touchPoint.y, radius)
        }
    }

    override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
        val points = touchPointList.points
        if (points.isEmpty()) return

        synchronized(strokeBuilder) {
            for (point in points) {
                internalProcessMove(point)
            }
        }
    }

    override fun onBeginRawErasing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        if (isStrokeInProgress) {
            // Already in a stroke, don't switch tools mid-way!
            onRawDrawingTouchPointMoveReceived(touchPoint)
            return
        }

        if (!isTemporaryEraserActive && eraserTool != null && currentTool.type != ToolType.ERASER) {
            previousTool = currentTool
            currentTool = eraserTool!!
            isTemporaryEraserActive = true
            updateTouchHelperTool()
        }
        onBeginRawDrawing(b, touchPoint)
    }

    override fun onEndRawErasing(
        b: Boolean,
        touchPoint: TouchPoint,
    ) {
        onEndRawDrawing(b, touchPoint)
        view.post { finishEraser() }
    }

    override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {
        onRawDrawingTouchPointMoveReceived(touchPoint)
    }

    override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {
        onRawDrawingTouchPointListReceived(touchPointList)
    }
}
