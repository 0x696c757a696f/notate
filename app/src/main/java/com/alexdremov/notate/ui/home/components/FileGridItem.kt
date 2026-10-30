package com.alexdremov.notate.ui.home.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alexdremov.notate.data.CanvasItem
import com.alexdremov.notate.data.FileSystemItem
import com.alexdremov.notate.data.ProjectItem
import com.alexdremov.notate.data.SyncStatus
import com.alexdremov.notate.ui.theme.BorderWidth
import com.alexdremov.notate.ui.theme.CardShape
import com.alexdremov.notate.ui.theme.Ink
import com.alexdremov.notate.ui.theme.InkMuted
import com.alexdremov.notate.ui.theme.Paper
import com.alexdremov.notate.ui.theme.PaperDim
import com.alexdremov.notate.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/** Thumbnail decode result: still loading, decoded, or undecodable (show the fallback icon). */
private sealed interface ThumbnailState {
    data object Loading : ThumbnailState

    data class Ready(
        val bitmap: ImageBitmap,
    ) : ThumbnailState

    data object Failed : ThumbnailState
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    item: FileSystemItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean = true,
    isFavorite: Boolean = false,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .padding(Spacing.sm)
                .clip(CardShape)
                .alpha(if (enabled) 1f else 0.5f)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        // Flat card with a full hairline border: shadows dither into a gray smear on e-ink.
        shape = CardShape,
        border = BorderStroke(BorderWidth, Ink),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Paper),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Thumbnail / Icon Area
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(PaperDim),
                contentAlignment = Alignment.Center,
            ) {
                if (item is CanvasItem && !item.thumbnail.isNullOrEmpty()) {
                    // Decode off the main thread: a folder full of notes otherwise decodes every
                    // Base64 thumbnail during composition and stutters the first scroll.
                    val thumbnail by produceState<ThumbnailState>(ThumbnailState.Loading, item.thumbnail) {
                        value =
                            withContext(Dispatchers.Default) {
                                try {
                                    val decoded = Base64.decode(item.thumbnail, Base64.DEFAULT)
                                    BitmapFactory
                                        .decodeByteArray(decoded, 0, decoded.size)
                                        ?.asImageBitmap()
                                        ?.let { ThumbnailState.Ready(it) }
                                        ?: ThumbnailState.Failed
                                } catch (e: Exception) {
                                    ThumbnailState.Failed
                                }
                            }
                    }

                    val state = thumbnail
                    if (state is ThumbnailState.Ready) {
                        Image(
                            bitmap = state.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit, // Contain within box
                            modifier = Modifier.fillMaxSize().padding(Spacing.sm),
                        )
                    } else if (state is ThumbnailState.Failed) {
                        // Fallback icon
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = InkMuted,
                            modifier = Modifier.size(Spacing.xxxl),
                        )
                    }
                } else {
                    // Folder or No Thumbnail
                    Icon(
                        imageVector = if (item is ProjectItem) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        tint = if (item is ProjectItem) Ink else InkMuted,
                        modifier = Modifier.size(Spacing.xxxl),
                    )
                }

                if (item.syncStatus != SyncStatus.NONE) {
                    // Static badge: a spinning icon forces continuous e-ink refreshes (flicker,
                    // ghosting and battery drain) for as long as a sync runs.
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(Spacing.sm)
                                .size(Spacing.xl)
                                .background(Paper, CircleShape)
                                .border(BorderWidth, Ink, CircleShape)
                                .padding(Spacing.xs),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Syncing",
                            tint = if (item.syncStatus == SyncStatus.SYNCING) Ink else InkMuted,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (isFavorite) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(Spacing.sm)
                                .size(Spacing.xl)
                                .background(Paper, CircleShape)
                                .border(BorderWidth, Ink, CircleShape)
                                .padding(Spacing.xs),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = Ink,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Text Area
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Ink,
                )

                val date =
                    remember(item.lastModified) {
                        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(item.lastModified))
                    }

                Text(
                    text = date,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
