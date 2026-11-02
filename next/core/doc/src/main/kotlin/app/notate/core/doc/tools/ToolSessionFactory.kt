package app.notate.core.doc.tools

import app.notate.core.model.EraserMode
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset

/** Maps a toolbar preset to the session that handles the next pen gesture. */
class ToolSessionFactory {
    fun create(tool: ToolPreset, ctx: ToolContext): ToolSession = when (tool.kind) {
        ToolKind.PEN, ToolKind.HIGHLIGHTER -> PenSession(tool, ctx)
        ToolKind.ERASER -> when (tool.eraserMode ?: EraserMode.PARTIAL) {
            EraserMode.PARTIAL -> PartialEraserSession(tool, ctx)
            EraserMode.STROKE, EraserMode.LASSO -> StrokeEraserSession(tool, ctx)   // LASSO arrives in R2-4
        }
        ToolKind.SHAPE -> ShapeDragSession(tool, ctx)
        else -> NoopSession
    }
}
