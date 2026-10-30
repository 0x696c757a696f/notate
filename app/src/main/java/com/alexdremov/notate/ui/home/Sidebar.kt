@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alexdremov.notate.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.data.ProjectConfig
import com.alexdremov.notate.model.Tag
import com.alexdremov.notate.ui.theme.BorderWidth
import com.alexdremov.notate.ui.theme.Ink
import com.alexdremov.notate.ui.theme.Paper
import com.alexdremov.notate.ui.theme.Radius
import com.alexdremov.notate.ui.theme.Spacing

@Composable
fun Sidebar(
    projects: List<ProjectConfig>,
    tags: List<Tag>,
    selectedProject: ProjectConfig?,
    selectedTag: Tag?,
    onProjectSelected: (ProjectConfig) -> Unit,
    onProjectLongClick: (ProjectConfig) -> Unit,
    onTagSelected: (Tag) -> Unit,
    onAddProject: () -> Unit,
    onManageTags: () -> Unit,
    onSettingsClick: () -> Unit,
    onTodayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Flat white pane separated by a hairline: a translucent gray fill dithers on e-ink.
    Row(modifier = modifier.width(280.dp).fillMaxHeight()) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Paper)
                    .padding(vertical = Spacing.lg, horizontal = Spacing.md),
        ) {
            // Projects Section
            SidebarHeader(
                title = "Projects",
                actionIcon = Icons.Default.Add,
                actionDescription = "Add project",
                onActionClick = onAddProject,
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (projects.isEmpty()) {
                    item {
                        Text(
                            text = "No projects",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm),
                        )
                    }
                }
                items(projects) { project ->
                    SidebarItem(
                        text = project.name,
                        icon = Icons.Default.Folder,
                        isSelected = selectedProject?.id == project.id,
                        onClick = { onProjectSelected(project) },
                        onLongClick = { onProjectLongClick(project) },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(Spacing.xl))
                    SidebarHeader(
                        title = "Tags",
                        actionIcon = Icons.Default.Add,
                        actionDescription = "Manage tags",
                        onActionClick = onManageTags,
                    )
                }

                if (tags.isEmpty()) {
                    item {
                        Text(
                            text = "No tags",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm),
                        )
                    }
                }

                items(tags) { tag ->
                    SidebarItem(
                        text = tag.name,
                        icon = Icons.Default.Circle,
                        iconTint = Color(tag.color),
                        isSelected = selectedTag?.id == tag.id,
                        onClick = { onTagSelected(tag) },
                    )
                }
            }

            // Bottom Settings Area
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.sm), color = Ink)
            SidebarItem(
                text = "Today",
                icon = Icons.Default.Today,
                isSelected = false,
                onClick = onTodayClick,
            )
            SidebarItem(
                text = "Settings",
                icon = Icons.Default.Settings,
                isSelected = false,
                onClick = onSettingsClick,
            )
        }
        VerticalDivider(color = Ink, thickness = BorderWidth)
    }
}

@Composable
private fun SidebarHeader(
    title: String,
    actionIcon: ImageVector? = null,
    actionDescription: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
        if (actionIcon != null && onActionClick != null) {
            // Full 48dp touch target; the old 16dp icon was hard to hit with a finger or stylus.
            IconButton(onClick = onActionClick) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = actionDescription,
                    modifier = Modifier.size(Spacing.xl),
                    tint = Ink,
                )
            }
        }
    }
}

@Composable
private fun SidebarItem(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val backgroundColor =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }

    val contentColor =
        if (isSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(backgroundColor)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(Spacing.xl),
            tint = if (icon == Icons.Default.Circle) iconTint else contentColor,
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}
