package live.hogar.launcher

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.launcherDataStore by preferencesDataStore(name = "hogar_launcher")

/** Persistencia de [LauncherPrefs] en DataStore (sobrevive a reinicios). */
class LauncherStore(context: Context) {
    private val ds = context.applicationContext.launcherDataStore

    val prefs: Flow<LauncherPrefs> = ds.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toPrefs() }
        .distinctUntilChanged()

    /** La transformación se aplica sobre lo guardado, dentro de la transacción. */
    suspend fun update(transform: (LauncherPrefs) -> LauncherPrefs) {
        ds.edit { p ->
            val n = transform(p.toPrefs())
            p[PINNED] = LauncherLogic.encode(n.pinned)
            p[HIDDEN] = LauncherLogic.encode(n.hidden)
            p[ORDER] = LauncherLogic.encode(n.order)
            p[SHOW_HIDDEN] = n.showHidden
            p[ORIGINAL_LOGO] = LauncherLogic.encode(n.originalLogo)
            p[HINT_SEEN] = n.hintSeen
        }
    }

    private fun Preferences.toPrefs() = LauncherPrefs(
        pinned = LauncherLogic.decode(this[PINNED]),
        // Primera vez: las apps de relleno del fabricante salen ocultas (siguen en "Mostrar ocultas").
        hidden = this[HIDDEN]?.let { LauncherLogic.decode(it).toSet() } ?: LauncherLogic.FactoryFiller,
        order = LauncherLogic.decode(this[ORDER]),
        showHidden = this[SHOW_HIDDEN] ?: false,
        originalLogo = LauncherLogic.decode(this[ORIGINAL_LOGO]).toSet(),
        hintSeen = this[HINT_SEEN] ?: false,
    )

    private companion object {
        val PINNED = stringPreferencesKey("pinned")
        val HIDDEN = stringPreferencesKey("hidden")
        val ORDER = stringPreferencesKey("order")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val ORIGINAL_LOGO = stringPreferencesKey("original_logo")
        val HINT_SEEN = booleanPreferencesKey("hint_seen")
    }
}
