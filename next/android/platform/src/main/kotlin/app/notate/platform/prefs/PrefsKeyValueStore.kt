package app.notate.platform.prefs

import android.content.Context
import android.content.SharedPreferences
import app.notate.core.doc.settings.KeyValueStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** [KeyValueStore] over SharedPreferences `notate_next_settings`. Any thread. */
class PrefsKeyValueStore(ctx: Context, name: String = NAME) : KeyValueStore {
    private val prefs: SharedPreferences = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(k: String): String? = prefs.getString(k, null)

    override fun putString(k: String, v: String?) {
        prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
    }

    override val changes: Flow<String> = callbackFlow {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) trySend(key) }
        prefs.registerOnSharedPreferenceChangeListener(l)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }

    companion object { const val NAME = "notate_next_settings" }
}
