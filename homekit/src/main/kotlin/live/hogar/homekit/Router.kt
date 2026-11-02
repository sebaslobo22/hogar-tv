package live.hogar.homekit

internal class HttpReq(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * Rutas HTTP de HAP. [tv] devuelve el controlador vigente (cambia cuando se reconstruye la lista
 * de apps). [afterWrite] avisa de que un controlador cambió algo, para releer el estado y mandar
 * eventos a los demás suscritos.
 */
internal class HapRouter(
    private val store: HomeKitStore,
    private val pairing: PairingManager,
    private val tv: () -> TvController,
    private val afterWrite: (Conn) -> Unit = {},
) {
    fun handle(conn: Conn, req: HttpReq): HttpResp = try {
        route(conn, req)
    } catch (e: Exception) {
        HttpResp.json(500, """{"status":${HapStatus.COMMUNICATION_FAILURE}}""")
    }

    private fun route(conn: Conn, req: HttpReq): HttpResp {
        val m = req.method
        return when {
            m == "POST" && req.path == "/pair-setup" -> pairing.setup(conn, req.body)
            m == "POST" && req.path == "/pair-verify" -> pairing.verify(conn, req.body)
            m == "POST" && req.path == "/identify" ->
                if (store.isPaired()) HttpResp.json(400, """{"status":${HapStatus.INSUFFICIENT_PRIVILEGES}}""") else HttpResp.empty(204)
            !conn.verified -> HttpResp.json(470, """{"status":${HapStatus.INSUFFICIENT_PRIVILEGES}}""")
            m == "GET" && req.path == "/accessories" -> HttpResp.json(200, tv().accessoriesJson())
            m == "GET" && req.path == "/characteristics" -> getCharacteristics(conn, req)
            m == "PUT" && req.path == "/characteristics" -> putCharacteristics(conn, req)
            m == "POST" && req.path == "/pairings" -> pairing.pairings(conn, req.body)
            else -> HttpResp.json(404, """{"status":${HapStatus.NOT_FOUND}}""")
        }
    }

    private fun ids(q: String?): List<Pair<Int, Int>>? {
        if (q.isNullOrEmpty()) return null
        return q.split(',').map {
            val p = it.split('.')
            if (p.size != 2) return null
            (p[0].toIntOrNull() ?: return null) to (p[1].toIntOrNull() ?: return null)
        }
    }

    private fun getCharacteristics(conn: Conn, req: HttpReq): HttpResp {
        val list = ids(req.query["id"]) ?: return HttpResp.json(400, """{"status":${HapStatus.INVALID_VALUE}}""")
        val controller = tv()
        val db = controller.db
        var anyError = false
        val results = list.map { (aid, iid) ->
            val m = LinkedHashMap<String, Any?>()
            m["aid"] = aid; m["iid"] = iid
            val def = if (aid == Iid.AID) db.char(iid) else null
            val status = when {
                def == null -> HapStatus.NOT_FOUND
                !def.readable -> HapStatus.WRITE_ONLY
                else -> HapStatus.OK
            }
            if (status == HapStatus.OK) {
                val v = controller.read(iid)
                if (v == null) { m["status"] = HapStatus.COMMUNICATION_FAILURE; anyError = true } else m["value"] = v
                if (req.query["ev"] == "1" && def!!.notifies) m["ev"] = iid in conn.subscriptions
            } else { m["status"] = status; anyError = true }
            m
        }
        // En un 207 cada entrada lleva su estado, también las correctas (HAP-NodeJS hace lo mismo).
        if (anyError) results.forEach { if (!it.containsKey("status")) it["status"] = HapStatus.OK }
        val body = Json.write(mapOf("characteristics" to results))
        return HttpResp.json(if (anyError) 207 else 200, body)
    }

    @Suppress("UNCHECKED_CAST")
    private fun putCharacteristics(conn: Conn, req: HttpReq): HttpResp {
        val root = try { Json.parse(String(req.body, Charsets.UTF_8)) as? Map<String, Any?> } catch (e: Exception) { null }
            ?: return HttpResp.json(400, """{"status":${HapStatus.INVALID_VALUE}}""")
        val items = root["characteristics"] as? List<Any?> ?: return HttpResp.json(400, """{"status":${HapStatus.INVALID_VALUE}}""")
        val controller = tv()
        val db = controller.db
        var anyError = false
        var wrote = false
        val results = items.map { raw ->
            val item = raw as? Map<String, Any?>
            val aid = (item?.get("aid") as? Number)?.toInt()
            val iid = (item?.get("iid") as? Number)?.toInt()
            val m = LinkedHashMap<String, Any?>()
            m["aid"] = aid; m["iid"] = iid
            val def = if (aid == Iid.AID && iid != null) db.char(iid) else null
            var status = if (def == null) HapStatus.NOT_FOUND else HapStatus.OK
            if (def != null && iid != null && item != null) {
                if (item.containsKey("ev")) {
                    val ev = item["ev"]
                    if (!def.notifies) status = HapStatus.NOTIFICATION_NOT_SUPPORTED
                    else if (ev == true || (ev as? Number)?.toInt() == 1) conn.subscriptions += iid
                    else conn.subscriptions -= iid
                }
                if (status == HapStatus.OK && item.containsKey("value")) {
                    status = controller.write(iid, item["value"])
                    if (status == HapStatus.OK) wrote = true
                }
            }
            m["status"] = status
            if (status != HapStatus.OK) anyError = true
            m
        }
        if (wrote) afterWrite(conn)
        return if (anyError) HttpResp.json(207, Json.write(mapOf("characteristics" to results))) else HttpResp.empty(204)
    }
}
