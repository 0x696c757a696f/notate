package app.notate

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.notate.core.store.library.DocumentEntry
import app.notate.editor.EditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Temporary bookshelf (R2-1 replaces it): lists every document and creates new notebooks.
 * Long-pressing the title toggles the debug HUD.
 */
class LibraryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val graph get() = NotateApp.graph
    private var entries: List<DocumentEntry> = emptyList()
    private lateinit var testButton: Button

    private val listAdapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: row()
            val e = entries[position]
            (row.getChildAt(0) as TextView).text = e.title
            (row.getChildAt(1) as TextView).text =
                "${DateFormat.format("yyyy-MM-dd HH:mm", Date(e.modified))} · ${resources.getQuantityString(R.plurals.pages, e.pageCount, e.pageCount)}"
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnLongClickListener {
                val s = graph.settings.debugHud
                s.set(!s.value)
                true
            }
        }
        val newButton = Button(this).apply {
            text = getString(R.string.new_notebook)
            setOnClickListener { createNotebook(1) }
        }
        testButton = Button(this).apply {
            text = getString(R.string.create_test_notebook)
            visibility = View.GONE
            setOnClickListener { createNotebook(TEST_PAGES) }
        }
        val list = ListView(this).apply {
            adapter = listAdapter
            setOnItemClickListener { _, _, position, _ -> startActivity(EditorActivity.intent(this@LibraryActivity, entries[position].id)) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(title)
            addView(newButton)
            addView(testButton)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        scope.launch {
            graph.documents.listDocuments().collect {
                entries = it
                listAdapter.notifyDataSetChanged()
            }
        }
        scope.launch { graph.settings.debugHud.collect { testButton.visibility = if (it) View.VISIBLE else View.GONE } }
    }

    private fun row(): LinearLayout {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(Color.BLACK) })
            addView(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(0xFF555555.toInt()) })
        }
    }

    private fun createNotebook(pages: Int) {
        scope.launch {
            try {
                val n = entries.count { it.title.startsWith(UNTITLED) } + 1
                val title = if (pages == 1) "$UNTITLED $n" else "Test notebook ($pages pages)"
                val id = graph.documents.createNotebook(title, pageCount = pages)
                startActivity(EditorActivity.intent(this@LibraryActivity, id))
            } catch (e: Exception) {
                graph.logger.e("LibraryActivity", "Creating a notebook failed", e)
                Toast.makeText(this@LibraryActivity, R.string.create_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val UNTITLED = "Untitled Note"
        const val TEST_PAGES = 50
    }
}
