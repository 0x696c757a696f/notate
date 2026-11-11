package app.notate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.notate.core.doc.export.PageRangeParser
import app.notate.core.doc.registry.DocumentHandle
import app.notate.core.model.DocId
import app.notate.core.model.PageId
import app.notate.export.doc.ExportFiles
import app.notate.export.doc.ExportFormat
import app.notate.export.doc.ExportOptions
import app.notate.export.doc.ExportRangeException
import app.notate.export.doc.PageSelection
import app.notate.library.NotateTheme
import app.notate.library.ui.DialogButton
import app.notate.library.ui.NameField
import app.notate.library.ui.NotateDialog
import app.notate.platform.log.ShareLogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Export… sheet (R3-2) for a notebook or an annotated PDF: format (PDF, or PNG of one page), page range and whether page
 * templates are included; Share… hands the file to another app, Save to… writes it where the user picks. It is a translucent
 * activity so both the editor (More → Export…) and the bookshelf (long-press → Export as PDF or PNG…) can open it on top of
 * themselves; the document is opened through the registry, so an open editor is not disturbed. Main thread only.
 */
class ExportActivity : ComponentActivity() {
    private val graph get() = NotateApp.graph
    private var handle: DocumentHandle? = null
    private var job: Job? = null

    private var format by mutableStateOf(ExportFormat.PDF)
    private var range by mutableStateOf(Range.ALL)
    private var spec by mutableStateOf("")
    private var includeTemplate by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var running by mutableStateOf(false)
    private var done by mutableIntStateOf(0)
    private var total by mutableIntStateOf(0)
    private var pageCount by mutableIntStateOf(0)
    private var title by mutableStateOf("")
    private var currentPage: PageId? = null

    private enum class Range { ALL, THIS, PAGES }

    private val saveTo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) start(uri)
    }
    private val savePng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) start(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_DOC)?.let(::DocId) ?: return finish()
        currentPage = intent.getLongExtra(EXTRA_PAGE, -1L).takeIf { it >= 0 }?.let(::PageId)
        range = if (currentPage != null) Range.THIS else Range.ALL
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setContent { NotateTheme { Sheet() } }
        lifecycleScope.launch {
            try {
                val h = graph.registry.acquire(id)
                if (isDestroyed) { graph.registry.release(h); return@launch }
                handle = h
                title = h.editor.meta.value.title
                pageCount = h.editor.pages.value.size
                if (currentPage != null && h.editor.pages.value.none { it.id == currentPage }) currentPage = null
                if (currentPage == null && range == Range.THIS) range = Range.ALL
                withContext(graph.dispatchers.io) { ExportFiles.cleanOld(graph.paths.exportsDir) }
            } catch (e: Exception) {
                graph.logger.e(TAG, "Opening the document for export failed", e)
                error = getString(R.string.export_failed)
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        handle?.let { graph.registry.release(it) }
        handle = null
        super.onDestroy()
    }

    @Composable
    private fun Sheet() {
        NotateDialog(
            title = stringResource(R.string.export_title, title),
            onDismiss = { if (!running) finish() },
            buttons = {
                DialogButton(stringResource(R.string.export_cancel)) { if (running) job?.cancel() else finish() }
                DialogButton(stringResource(R.string.export_save_to), enabled = handle != null && !running) { askSave() }
                DialogButton(stringResource(R.string.export_share), enabled = handle != null && !running, filled = true) { start(null) }
            },
        ) {
            if (handle == null && error == null) Text(stringResource(R.string.export_loading), fontSize = 16.sp)
            Option(stringResource(R.string.export_format_pdf), format == ExportFormat.PDF) { format = ExportFormat.PDF; error = null }
            Option(stringResource(R.string.export_format_png), format == ExportFormat.PNG) { format = ExportFormat.PNG; error = null; if (range == Range.ALL) range = if (currentPage != null) Range.THIS else Range.PAGES }
            if (format == ExportFormat.PDF) Option(stringResource(R.string.export_range_all), range == Range.ALL) { range = Range.ALL; error = null }
            if (currentPage != null) Option(stringResource(R.string.export_range_this), range == Range.THIS) { range = Range.THIS; error = null }
            Option(stringResource(if (format == ExportFormat.PNG) R.string.export_page_label else R.string.export_range_pages), range == Range.PAGES) { range = Range.PAGES; error = null }
            if (range == Range.PAGES) {
                NameField(spec, stringResource(if (format == ExportFormat.PNG) R.string.export_page_label else R.string.export_pages_label), { spec = it; error = null })
            }
            if (format == ExportFormat.PDF) {
                Check(stringResource(R.string.export_include_template), includeTemplate) { includeTemplate = !includeTemplate }
            }
            if (running) Text(
                if (total > 0) stringResource(R.string.export_progress, done, total) else stringResource(R.string.export_preparing), fontSize = 16.sp,
            )
            error?.let { Text(it, fontSize = 15.sp, color = Color.Black) }
        }
    }

    @Composable
    private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.clickable(enabled = !running, onClick = onClick).padding(vertical = 6.dp)) {
            Text(if (selected) "●  " else "○  ", fontSize = 18.sp)
            Text(label, fontSize = 17.sp)
        }
    }

    @Composable
    private fun Check(label: String, checked: Boolean, onClick: () -> Unit) {
        Row(Modifier.clickable(enabled = !running, onClick = onClick).padding(vertical = 6.dp)) {
            Text(if (checked) "☑  " else "☐  ", fontSize = 18.sp)
            Text(label, fontSize = 17.sp)
        }
    }

    /** The chosen selection, or null after showing why it is not valid. */
    private fun selection(): PageSelection? {
        val h = handle ?: return null
        val count = h.editor.pages.value.size
        return when (range) {
            Range.ALL -> PageSelection.All
            Range.THIS -> currentPage?.let(PageSelection::Current) ?: PageSelection.All
            Range.PAGES -> when (val r = PageRangeParser.parse(spec, count)) {
                is PageRangeParser.Result.Error -> { error = r.message; null }
                is PageRangeParser.Result.Ok -> {
                    if (format == ExportFormat.PNG && r.indexes.size != 1) { error = getString(R.string.export_one_page); null }
                    else PageSelection.Pages(spec)
                }
            }
        }
    }

    private fun askSave() {
        val h = handle ?: return
        if (selection() == null) return
        val name = ExportFiles.fileName(h.editor.meta.value.title, if (format == ExportFormat.PDF) "pdf" else "png")
        if (format == ExportFormat.PDF) saveTo.launch(name) else savePng.launch(name)
    }

    /** Runs the export into the cache; then shares the file ([dest] null) or copies it to [dest]. */
    private fun start(dest: Uri?) {
        val h = handle ?: return
        val selected = selection() ?: return
        val fmt = format
        val options = ExportOptions(fmt, selected, includeTemplate = includeTemplate)
        error = null
        running = true
        done = 0
        total = 0
        job = lifecycleScope.launch {
            val ext = if (fmt == ExportFormat.PDF) "pdf" else "png"
            val file = File(graph.paths.exportsDir, ExportFiles.fileName(h.editor.meta.value.title, ext))
            try {
                if (fmt == ExportFormat.PDF) {
                    graph.pdfExporter.export(h, options, file) { d, t -> done = d; total = t }
                } else {
                    val pages = h.editor.pages.value
                    val page = when (selected) {
                        is PageSelection.Current -> pages.firstOrNull { it.id == selected.page }
                        is PageSelection.Pages -> (PageRangeParser.parse(selected.spec, pages.size) as? PageRangeParser.Result.Ok)?.indexes?.firstOrNull()?.let(pages::getOrNull)
                        PageSelection.All -> pages.firstOrNull()
                    } ?: throw ExportRangeException(getString(R.string.export_one_page))
                    graph.pngExporter.export(h, page, file)
                }
                if (dest != null) {
                    withContext(graph.dispatchers.io) {
                        val out = contentResolver.openOutputStream(dest, "wt") ?: throw IllegalStateException("Cannot write the chosen file")
                        out.use { o -> file.inputStream().use { it.copyTo(o) } }
                    }
                    Toast.makeText(applicationContext, R.string.export_saved, Toast.LENGTH_SHORT).show()
                } else {
                    val uri = FileProvider.getUriForFile(this@ExportActivity, ShareLogs.authority(this@ExportActivity), file)
                    val mime = if (fmt == ExportFormat.PDF) "application/pdf" else "image/png"
                    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, h.editor.meta.value.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, h.editor.meta.value.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportRangeException) {
                error = e.message
            } catch (e: Exception) {
                graph.logger.e(TAG, "Export failed", e)
                error = getString(R.string.export_failed)
            } finally {
                running = false
            }
        }
    }

    companion object {
        private const val TAG = "ExportActivity"
        private const val EXTRA_DOC = "doc"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PAGE = "page"

        /** The sheet for document [id]; [page] is the page being viewed (editor), null from the bookshelf. */
        fun intent(context: Context, id: DocId, title: String?, page: PageId? = null): Intent =
            Intent(context, ExportActivity::class.java).putExtra(EXTRA_DOC, id.value).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PAGE, page?.value ?: -1L)
    }
}
