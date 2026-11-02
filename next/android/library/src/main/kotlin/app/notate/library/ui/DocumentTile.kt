package app.notate.library.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notate.core.model.DocKind
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.Folder
import app.notate.library.R

private val Gray = Color(0xFF5F5F5F)
private val Border = Color(0xFF9E9E9E)

/** Bookshelf cell: 3:4 cover with a one-line title and date below (04 §2). The long-press [menu] is anchored to the cover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentTile(
    entry: DocumentEntry,
    now: Long,
    selected: Boolean,
    covers: CoverLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
) {
    Column(modifier.padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Color.Black else Border))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            AsyncCover(entry, covers)
            if (entry.pinned) {
                Icon(Icons.Outlined.PushPin, stringResource(R.string.cd_pinned), Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp), tint = Color.Black)
            }
            val badge = when (entry.kind) {
                DocKind.PDF -> R.string.badge_pdf
                DocKind.WEB -> R.string.badge_book
                DocKind.NOTEBOOK -> null
            }
            if (badge != null) {
                Text(
                    stringResource(badge), color = Color.White, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Check, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black),
                )
            }
            menu()
        }
        Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Text(
            DateUtils.getRelativeTimeSpanString(entry.modified, now, DateUtils.MINUTE_IN_MILLIS).toString(),
            maxLines = 1, color = Gray, fontSize = 13.sp,
        )
    }
}

/** Folder cell shown before the documents; same footprint as [DocumentTile]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderTile(
    folder: Folder,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit = {},
) {
    Column(modifier.padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).border(BorderStroke(1.dp, Border))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(72.dp), tint = Color.Black)
            menu()
        }
        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
