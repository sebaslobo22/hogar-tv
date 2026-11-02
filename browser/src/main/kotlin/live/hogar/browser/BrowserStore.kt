package live.hogar.browser

import android.content.Context
import java.io.File

/** Archivo JSON pequeño con favoritos, historial, pestañas y ajustes. Escritura atómica (temporal + renombrar). */
class BrowserStore(ctx: Context) {
    private val file = File(ctx.filesDir, "navegador.json")

    fun read(): BrowserData = DataJson.decode(runCatching { file.readText() }.getOrNull())

    @Synchronized
    fun write(d: BrowserData) {
        runCatching {
            val json = DataJson.encode(d)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }
}
