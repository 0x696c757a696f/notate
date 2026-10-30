package com.alexdremov.notate.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.webdoc.WebDocActivity
import com.alexdremov.notate.webdoc.WebDocKind
import com.alexdremov.notate.webdoc.WebDocMeta
import com.alexdremov.notate.webdoc.WebDocResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library of imported interactive EPUB / HTML documents. Notes on them are kept with the
 * imported copy, so this is where they are reopened.
 */
@Composable
fun WebDocLibraryDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { WebDocActivity.library(context) }
    var docs by remember { mutableStateOf<List<WebDocMeta>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<WebDocMeta?>(null) }

    LaunchedEffect(refresh) {
        docs = withContext(Dispatchers.IO) { library.list() }
    }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                context.startActivity(WebDocActivity.importIntent(context, uri, isFolder = false))
                onDismiss()
            }
        }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.startActivity(WebDocActivity.importIntent(context, uri, isFolder = true))
                onDismiss()
            }
        }

    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("交互式 EPUB / HTML") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        filePicker.launch(
                            arrayOf(
                                "application/epub+zip",
                                "text/html",
                                "application/xhtml+xml",
                                "application/zip",
                                "application/octet-stream",
                            ),
                        )
                    }) { Text("导入文件", color = Color.Black) }
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("导入 HTML 文件夹", color = Color.Black) }
                }
                                Text(
                    "支持 .epub、.html、含 index.html 的 .zip，或整个网页文件夹（连同 JS / CSS / 字体 / 图片）。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                HorizontalDivider(color = Color.Black)
                if (docs.isEmpty()) {
                    Text("还没有导入的文档", modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(docs, key = { it.id }) { doc ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            context.startActivity(WebDocActivity.openIntent(context, doc.id))
                                            onDismiss()
                                        }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    val kind =
                                        when {
                                            doc.kind == WebDocKind.HTML -> "HTML"
                                            doc.fixedLayout -> "EPUB · 固定版式 · ${doc.pages.size} 页"
                                            else -> "EPUB · ${doc.pages.size} 节"
                                        }
                                    Text(kind, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                                }
                                IconButton(onClick = { toDelete = doc }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            }
                            HorizontalDivider(color = Color.LightGray)
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("关闭", color = Color.Black) }
        },
    )

    toDelete?.let { doc ->
        AlertDialog(
            modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
            onDismissRequest = { toDelete = null },
            title = { Text("删除“${doc.title}”？") },
            text = { Text("导入的副本、手写笔记以及网页保存的练习数据都会被删除，原文件不受影响。") },
            confirmButton = {
                OutlinedButton(onClick = {
                    toDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { library.delete(doc.id) }
                        android.webkit.WebStorage
                            .getInstance()
                            .deleteOrigin(WebDocResources.originFor(doc.id))
                        refresh++
                    }
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                OutlinedButton(onClick = { toDelete = null }) { Text("取消", color = Color.Black) }
            },
        )
    }
}
