package app.notate.editor.chrome

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.notate.core.model.DocId
import app.notate.core.store.library.DocQuery
import app.notate.core.store.library.DocumentEntry
import app.notate.core.store.library.LibraryStore
import app.notate.core.store.library.Scope
import app.notate.core.store.library.Sort
import app.notate.editor.R
import app.notate.editor.popup.Placement
import app.notate.editor.popup.PopupHandle
import app.notate.editor.popup.PopupHost
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The short list behind "+" in the tab row: recent documents, or the ones whose title contains what was typed. A tap picks
 * one. It is a popup of the window (a real dialog would take the window focus and switch the pen driver off). Main thread only.
 */
class DocumentPickerDialog(
    private val popups: PopupHost,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    private val onPick: (DocId) -> Unit,
) {
    private var entries: List<DocumentEntry> = emptyList()
    private var job: Job? = null
    private var handle: PopupHandle? = null
    private var search: EditText? = null

    fun show(context: Context) {
        val density = context.resources.displayMetrics.density
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = (convertView as? TextView) ?: TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(Color.BLACK)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(context.dp(12), 0, context.dp(12), 0)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(ROW_DP))
                }
                t.text = entries[position].title
                return t
            }
        }
        val list = ListView(context).apply {
            this.adapter = adapter
            divider = android.graphics.drawable.ColorDrawable(Ink.GRAY)
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                val id = entries[position].id
                dismiss()
                onPick(id)
            }
        }
        val empty = TextView(context).apply {
            text = context.getString(R.string.picker_empty)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Ink.SECONDARY)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            visibility = View.GONE
        }
        val search = EditText(context).apply {
            hint = context.getString(R.string.picker_search)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            setHintTextColor(Ink.GRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = query(s?.toString().orEmpty(), adapter, empty)
            })
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.picker_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.BLACK)
            setPadding(context.dp(12), context.dp(8), context.dp(12), 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(52)).apply { setMargins(context.dp(8), 0, context.dp(8), 0) })
            addView(empty)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(ROW_DP * VISIBLE_ROWS)))
            minimumWidth = (360 * density).toInt()
        }
        this.search = search
        handle = popups.show(null, box, Placement.CENTER)
        query("", adapter, empty)
    }

    fun dismiss() {
        search?.let { v ->
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(v.windowToken, 0)
        }
        search = null
        job?.cancel()
        handle?.dismiss()
        handle = null
    }

    private fun query(text: String, adapter: BaseAdapter, empty: View) {
        job?.cancel()
        job = scope.launch {
            library.observeDocuments(DocQuery(scope = Scope.ALL, sort = Sort.MODIFIED, text = text)).collectLatest {
                if (handle?.isShowing != true) { dismiss(); return@collectLatest }     // closed by a tap outside or another popup
                entries = it
                empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    private companion object {
        const val ROW_DP = 56
        const val VISIBLE_ROWS = 6
    }
}
