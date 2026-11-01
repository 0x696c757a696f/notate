package app.notate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.notate.library.EmptyLibrary
import app.notate.library.NotateTheme

/** Launcher activity; shows the bookshelf (currently empty). */
class LibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NotateTheme { EmptyLibrary() } }
    }
}
