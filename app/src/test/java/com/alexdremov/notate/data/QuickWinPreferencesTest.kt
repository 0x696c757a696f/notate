package com.alexdremov.notate.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alexdremov.notate.model.StylusButtonAction
import com.alexdremov.notate.model.TwoFingerTapAction
import com.alexdremov.notate.ui.input.Handedness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QuickWinPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `recents are most-recent-first, deduplicated and capped`() {
        for (i in 1..20) PreferencesManager.addRecent(context, "note-$i")
        PreferencesManager.addRecent(context, "note-15")

        val recents = PreferencesManager.getRecents(context)
        assertEquals(12, recents.size)
        assertEquals("note-15", recents.first())
        assertEquals(1, recents.count { it == "note-15" })
        assertFalse(recents.contains("note-1"))
    }

    @Test
    fun `favorites toggle on and off`() {
        PreferencesManager.setFavorite(context, "uuid-a", true)
        PreferencesManager.setFavorite(context, "uuid-b", true)
        PreferencesManager.setFavorite(context, "uuid-a", false)

        assertFalse(PreferencesManager.isFavorite(context, "uuid-a"))
        assertTrue(PreferencesManager.isFavorite(context, "uuid-b"))
    }

    @Test
    fun `input actions default safely and persist`() {
        assertEquals(TwoFingerTapAction.UNDO, PreferencesManager.getTwoFingerTapAction(context))
        assertEquals(StylusButtonAction.TEMPORARY_ERASER, PreferencesManager.getStylusButtonAction(context))

        PreferencesManager.setTwoFingerTapAction(context, TwoFingerTapAction.REDO)
        PreferencesManager.setStylusButtonAction(context, StylusButtonAction.NONE)

        assertEquals(TwoFingerTapAction.REDO, PreferencesManager.getTwoFingerTapAction(context))
        assertEquals(StylusButtonAction.NONE, PreferencesManager.getStylusButtonAction(context))
    }

    private fun rawPrefs() = context.getSharedPreferences("notate_prefs", Context.MODE_PRIVATE)

    @Test
    fun `fresh install lets fingers scroll with no banner`() {
        assertEquals(FingerMode.SCROLL, PreferencesManager.getFingerMode(context))
        assertFalse(PreferencesManager.isFingerOff(context))
        assertFalse(PreferencesManager.consumeFingerMigrationBanner(context))
    }

    @Test
    fun `old palm rejection on migrates to scroll and shows the banner once`() {
        rawPrefs().edit().putBoolean("palm_rejection_enabled", true).commit()

        assertEquals(FingerMode.SCROLL, PreferencesManager.getFingerMode(context))
        assertFalse(rawPrefs().contains("palm_rejection_enabled"))
        assertTrue(PreferencesManager.consumeFingerMigrationBanner(context))
        assertFalse(PreferencesManager.consumeFingerMigrationBanner(context))
    }

    @Test
    fun `old palm rejection off migrates to scroll without a banner`() {
        rawPrefs().edit().putBoolean("palm_rejection_enabled", false).commit()

        assertEquals(FingerMode.SCROLL, PreferencesManager.getFingerMode(context))
        assertFalse(PreferencesManager.consumeFingerMigrationBanner(context))
    }

    @Test
    fun `finger mode and handedness persist`() {
        assertEquals(Handedness.RIGHT, PreferencesManager.getHandedness(context))
        PreferencesManager.setFingerMode(context, FingerMode.OFF)
        PreferencesManager.setHandedness(context, Handedness.LEFT)

        assertTrue(PreferencesManager.isFingerOff(context))
        assertEquals(Handedness.LEFT, PreferencesManager.getHandedness(context))
    }

    @Test
    fun `daily note name is stable and locale independent`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.CHINA)
            val date = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 30) }.time
            assertEquals("Oct 30 2026", DailyNotesManager.todayNoteName(date))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
