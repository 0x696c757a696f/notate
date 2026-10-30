package com.alexdremov.notate.ui.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.ui.theme.BorderWidth
import com.alexdremov.notate.ui.theme.Ink
import com.alexdremov.notate.ui.theme.InkMuted
import com.alexdremov.notate.ui.theme.Paper
import com.alexdremov.notate.ui.theme.Spacing

/** One button in an [EmptyState]. */
data class EmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * A "getting started" panel instead of a lone gray sentence: what this place is, why it is
 * empty, and the next step as a button. The first action is the primary (filled) one.
 */
@Composable
fun EmptyState(
    title: String,
    message: String? = null,
    icon: ImageVector? = null,
    actions: List<EmptyStateAction> = emptyList(),
) {
    Box(modifier = Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(Spacing.xxxl),
                )
                Spacer(Modifier.height(Spacing.lg))
            }
            Text(
                text = title,
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            if (message != null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = message,
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xl))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    actions.forEachIndexed { index, action ->
                        if (index == 0) {
                            Button(
                                onClick = action.onClick,
                                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
                            ) { Text(action.label) }
                        } else {
                            OutlinedButton(
                                onClick = action.onClick,
                                border = BorderStroke(BorderWidth, Ink),
                            ) { Text(action.label, color = Ink) }
                        }
                    }
                }
            }
        }
    }
}
