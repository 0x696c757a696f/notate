package app.notate.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notate.core.model.PageSize
import app.notate.core.model.PageTemplate
import app.notate.core.model.TemplateCatalog
import app.notate.core.model.TemplateOption
import app.notate.library.R

private val SIZES = listOf(
    PageSize.A4 to R.string.paper_a4, PageSize.A5 to R.string.paper_a5, PageSize.LETTER to R.string.paper_letter,
    PageSize.B5 to R.string.paper_b5, PageSize.SCREEN to R.string.paper_screen, PageSize.WHITEBOARD to R.string.paper_whiteboard,
)

private fun nameRes(o: TemplateOption): Int = when (o) {
    TemplateCatalog.BLANK -> R.string.template_blank
    TemplateCatalog.LINED -> R.string.template_lined
    TemplateCatalog.WIDE_LINED -> R.string.template_wide_lined
    TemplateCatalog.GRID_5 -> R.string.template_grid
    TemplateCatalog.DOTS_5 -> R.string.template_dots
    TemplateCatalog.ENGINEERING -> R.string.template_engineering
    TemplateCatalog.CORNELL -> R.string.template_cornell
    else -> R.string.template_isometric
}

/** 04 §2.1: title, paper size, orientation and a template grid. [initial] seeds the choices (the "Default template" setting). */
@Composable
fun NewNotebookDialog(
    suggestedTitle: String,
    initial: PageTemplate,
    onCreate: (title: String, template: PageTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(suggestedTitle) }
    var size by remember { mutableStateOf(initial.size) }
    var landscape by remember { mutableStateOf(initial.landscape) }
    var option by remember { mutableStateOf(TemplateCatalog.find(initial.background) ?: TemplateCatalog.LINED) }
    val create = { onCreate(title, TemplateCatalog.template(size, landscape, option)) }
    NotateDialog(
        title = stringResource(R.string.new_notebook_title), onDismiss = onDismiss,
        buttons = {
            DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss)
            DialogButton(stringResource(R.string.dialog_create), filled = true, onClick = create)
        },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NameField(suggestedTitle, stringResource(R.string.new_notebook_field_title), { title = it }, onDone = create)
            Labeled(stringResource(R.string.new_notebook_paper)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SIZES.forEach { (s, res) -> Chip(stringResource(res), s == size) { size = s } }
                }
            }
            if (size != PageSize.WHITEBOARD) {
                Labeled(stringResource(R.string.new_notebook_orientation)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(stringResource(R.string.orientation_portrait), !landscape) { landscape = false }
                        Chip(stringResource(R.string.orientation_landscape), landscape) { landscape = true }
                    }
                }
            }
            Labeled(stringResource(R.string.new_notebook_template)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TemplateCatalog.ALL.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { o -> TemplateCell(o, o == option, Modifier.weight(1f)) { option = o } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = Color(0xFF5F5F5F))
        content()
    }
}

@Composable
private fun TemplateCell(option: TemplateOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        TemplatePreview(option, Modifier.fillMaxWidth().border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Color.Black else Color(0xFF9E9E9E))))
        Text(stringResource(nameRes(option)), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp), color = if (selected) Color.Black else Color(0xFF5F5F5F))
    }
}
