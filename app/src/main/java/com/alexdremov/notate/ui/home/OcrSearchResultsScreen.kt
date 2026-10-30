package com.alexdremov.notate.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.alexdremov.notate.data.ProjectConfig
import com.alexdremov.notate.ocr.OcrTextSource
import com.alexdremov.notate.ocr.index.OcrSearchResult

@Composable
fun OcrSearchResultsScreen(
    query: String,
    results: List<OcrSearchResult>,
    projects: List<ProjectConfig>,
    onResultClick: (OcrSearchResult) -> Unit,
) {
    if (results.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No indexed text matches “$query”", style = MaterialTheme.typography.titleMedium)
            Text(
                "Saved notes are indexed in the background while the device is charging and idle.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val projectNames = projects.associate { it.id to it.name }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { "${it.documentId}:${it.source}:${it.text.hashCode()}" }) { result ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onResultClick(result) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(result.documentName, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (result.source) {
                            OcrTextSource.FILENAME -> "Filename"
                            OcrTextSource.TYPED_TEXT -> "Typed text"
                            OcrTextSource.INK_OCR -> "Handwriting"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                result.projectId?.let { projectId ->
                    Text(
                        projectNames[projectId] ?: projectId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(highlightedSnippet(result.snippet, query), modifier = Modifier.padding(top = 6.dp))
            }
            HorizontalDivider()
        }
    }
}

private fun highlightedSnippet(
    snippet: String,
    query: String,
) = buildAnnotatedString {
    val index = snippet.indexOf(query, ignoreCase = true)
    if (index < 0) {
        append(snippet)
    } else {
        append(snippet.substring(0, index))
        withStyle(SpanStyle(background = Color.LightGray, fontWeight = FontWeight.Bold)) {
            append(snippet.substring(index, index + query.length))
        }
        append(snippet.substring(index + query.length))
    }
}
