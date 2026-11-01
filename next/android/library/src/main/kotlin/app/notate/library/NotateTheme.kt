package app.notate.library

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)

/** Black-on-white colour scheme for e-ink (04 §10). */
val NotateColorScheme = lightColorScheme(
    primary = Black,
    onPrimary = White,
    secondary = Black,
    onSecondary = White,
    background = White,
    onBackground = Black,
    surface = White,
    onSurface = Black,
    surfaceVariant = White,
    onSurfaceVariant = Color(0xFF5F5F5F),
    outline = Color(0xFF9E9E9E),
)

/** Indication that draws nothing: no ripple or fade, which would cost e-ink refreshes. */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

private class NoIndicationNode : DelegatingNode(), DrawModifierNode {
    override fun ContentDrawScope.draw() = drawContent()
}

/** App-wide Compose theme: black/white, no ripple. Call from composition on the main thread. */
@Composable
fun NotateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NotateColorScheme) {
        CompositionLocalProvider(LocalIndication provides NoIndication, content = content)
    }
}
