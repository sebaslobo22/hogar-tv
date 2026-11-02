package live.hogar.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable

@Immutable
data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
)

/** Lista las apps lanzables (primero las de TV, luego las normales) sin incluir a Hogar. */
class AppRepository(private val context: Context) {
    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val own = setOf(context.packageName, "live.hogar")
        val found = LinkedHashMap<String, AppEntry>()
        fun collect(category: String, leanback: Boolean) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg in own || pkg in found) continue
                found[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), ComponentName(pkg, ai.name), leanback)
            }
        }
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)
        return found.values.sortedBy { it.label.lowercase() }
    }
}
