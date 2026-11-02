package live.hogar.player.bridge

import android.content.Context
import live.hogar.core.Speaker
import org.json.JSONArray
import org.json.JSONObject

/** Opciones del puente "Desde iPhone/iPad/Mac". */
data class BridgeSettings(
    /** Reenviar lo que llega por AirPlay a los altavoces elegidos. */
    val enabled: Boolean = false,
    val speakerIds: Set<String> = emptySet(),
    /** false = solo suenan los altavoces AirPlay; el TV calla. */
    val localEnabled: Boolean = true,
    /** Ajuste fino del retraso del TV (ms, +/-): se suma a la latencia de los altavoces. */
    val trimMs: Int = 0,
) {
    val active: Boolean get() = enabled && speakerIds.isNotEmpty()
}

/** Guarda las opciones y una copia de los altavoces elegidos (para poder conectar sin buscar de nuevo). */
class BridgeSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hogar_bridge", Context.MODE_PRIVATE)

    fun load(): BridgeSettings = BridgeSettings(
        enabled = prefs.getBoolean("enabled", false),
        speakerIds = prefs.getStringSet("speakers", emptySet())?.toSet() ?: emptySet(),
        localEnabled = prefs.getBoolean("local", true),
        trimMs = prefs.getInt("trimMs", 0),
    )

    fun save(s: BridgeSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putStringSet("speakers", s.speakerIds)
            .putBoolean("local", s.localEnabled)
            .putInt("trimMs", s.trimMs)
            .apply()
    }

    fun loadSnapshots(): Map<String, Speaker> {
        val raw = prefs.getString("snapshots", null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }.associate { o ->
                val txt = o.optJSONObject("txt")
                val map = HashMap<String, String>()
                txt?.keys()?.forEach { k -> map[k] = txt.getString(k) }
                val s = Speaker(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), o.optString("model").ifEmpty { null }, map)
                s.id to s
            }
        }.getOrDefault(emptyMap())
    }

    fun saveSnapshots(list: Collection<Speaker>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("host", s.host).put("port", s.port)
                .put("model", s.model ?: "").put("txt", JSONObject(s.txt as Map<*, *>)))
        }
        prefs.edit().putString("snapshots", arr.toString()).apply()
    }
}
