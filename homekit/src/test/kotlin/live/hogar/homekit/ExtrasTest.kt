package live.hogar.homekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class AccessoryDbStabilityTest {
    private val db = AccessoryDb(TEST_INPUTS)

    // Foto del mapa de iid con el que Casa ya emparejó el TV: nada de esto puede cambiar.
    private val frozen: Map<Int, String> = buildMap {
        for ((i, t) in listOf(2 to "14", 3 to "20", 4 to "21", 5 to "23", 6 to "30", 7 to "52")) put(i, t)
        put(9, "37")
        for ((i, t) in listOf(11 to "E3", 12 to "B0", 13 to "E7", 14 to "E8", 15 to "E1", 16 to "DF", 17 to "136")) put(i, t)
        for (n in 1..4) for ((part, t) in listOf(1 to "23", 2 to "E3", 3 to "DB", 4 to "D6", 5 to "135", 6 to "E6", 7 to "134")) put(100 + 10 * n + part, t)
        for ((i, t) in listOf(21 to "11", 22 to "B0", 23 to "E9", 24 to "EA", 25 to "119")) put(i, t)
    }

    @Test fun existingServicesAndCharacteristicsKeepTheirIids() {
        val order = db.services.map { it.iid }
        assertEquals(listOf(1, 8, 10, 110, 120, 130, 140, 20), order.take(8))
        assertEquals(listOf("3E", "A2", "D8", "D9", "D9", "D9", "D9", "113"), db.services.take(8).map { it.type })
        for ((iid, type) in frozen) assertEquals("iid $iid", type, db.char(iid)?.type)
        // Las propiedades que Casa ya conoce.
        assertEquals(listOf("pr", "pw", "ev"), db.char(Iid.TV_ACTIVE)!!.perms)
        assertEquals(listOf(110, 120, 130, 140, 20), db.services[2].linked)
        assertTrue(db.services[2].primary)
        // Nada nuevo se mete entre los iid antiguos: lo añadido va por encima de 10000.
        val newIids = db.services.drop(8).flatMap { s -> listOf(s.iid) + s.chars.map { it.iid } }
        assertTrue(newIids.all { it >= Extra.FIRST_IID })
        // Y no se pisa ninguno.
        val all = db.services.flatMap { s -> listOf(s.iid) + s.chars.map { it.iid } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun inputsWithAnyIdentifierNeverCollideWithTheNewServices() {
        assertEquals(989, Iid.MAX_INPUT_IDENTIFIER)
        assertTrue(Iid.inputChar(Iid.MAX_INPUT_IDENTIFIER, Iid.INPUT_TARGET_VISIBILITY) < Extra.FIRST_IID)
        assertNull(Iid.inputOf(Extra.NIGHT.onIid))
        assertEquals(30, Iid.inputOf(Iid.inputChar(30, Iid.INPUT_NAME)))
    }

    @Test fun configNumberBumpsBecauseTheSignatureChanged() {
        assertTrue(db.signature().startsWith("v2|"))
        val s = HomeKitStore(MemoryKv())
        assertEquals(1, s.configNumber("v1|1:10:Hogar"))
        assertEquals(2, s.configNumber(db.signature()))
    }

    @Test fun lightbulbAndSwitchesJson() {
        val root = Json.parse(db.accessoriesJson { null }) as Map<String, Any?>
        val services = ((root["accessories"] as List<Map<String, Any?>>)[0]["services"]) as List<Map<String, Any?>>
        fun svc(iid: Int) = services.first { (it["iid"] as Number).toInt() == iid }
        fun chars(iid: Int) = svc(iid)["characteristics"] as List<Map<String, Any?>>
        fun ch(svc: Int, iid: Int) = chars(svc).first { (it["iid"] as Number).toInt() == iid }

        assertEquals("43", svc(Extra.NIGHT.serviceIid)["type"])
        assertEquals(listOf(Extra.NIGHT.nameIid, Extra.NIGHT.onIid, Extra.NIGHT.brightnessIid, Extra.NIGHT.configuredIid),
            chars(Extra.NIGHT.serviceIid).map { (it["iid"] as Number).toInt() })
        val br = ch(Extra.NIGHT.serviceIid, Extra.NIGHT.brightnessIid)
        assertEquals("8", br["type"]); assertEquals("int", br["format"]); assertEquals("percentage", br["unit"])
        assertEquals(1L, (br["minValue"] as Number).toLong()); assertEquals(100L, (br["maxValue"] as Number).toLong())
        assertEquals(listOf("pr", "pw", "ev"), ch(Extra.NIGHT.serviceIid, Extra.NIGHT.onIid)["perms"])
        assertEquals("25", ch(Extra.NIGHT.serviceIid, Extra.NIGHT.onIid)["type"])

        for (e in listOf(Extra.MUSIC, Extra.RHYTHM, Extra.NOW_PLAYING, Extra.SAVER)) {
            assertEquals("49", svc(e.serviceIid)["type"])
            assertEquals(listOf(e.nameIid, e.onIid, e.configuredIid), chars(e.serviceIid).map { (it["iid"] as Number).toInt() })
        }
        assertEquals(setOf(10000, 10100, 10200, 10300, 10400, 10500, 10510, 10520, 10530), services.map { (it["iid"] as Number).toInt() }.filter { it >= 10000 }.toSet())
    }

    @Test fun programmableSwitchesAndServiceLabel() {
        val root = Json.parse(db.accessoriesJson { null }) as Map<String, Any?>
        val services = ((root["accessories"] as List<Map<String, Any?>>)[0]["services"]) as List<Map<String, Any?>>
        fun svc(iid: Int) = services.first { (it["iid"] as Number).toInt() == iid }
        fun chars(iid: Int) = svc(iid)["characteristics"] as List<Map<String, Any?>>

        assertEquals("CC", svc(Iid.LABEL)["type"])
        val ns = chars(Iid.LABEL)[0]
        assertEquals("CD", ns["type"])
        val tv = TvController(db, HomeKitStore(MemoryKv()), FakeTv())
        assertEquals(1, tv.read(Iid.LABEL_NAMESPACE))     // numerales arábigos

        for (b in Button.entries) {
            assertEquals("89", svc(b.serviceIid)["type"])
            val (ev, name, index) = chars(b.serviceIid)
            assertEquals("73", ev["type"])
            assertEquals(listOf("pr", "ev"), ev["perms"])
            assertTrue(ev.containsKey("value")); assertNull(ev["value"])          // se lee como null
            assertEquals(b.valid.map { it.toLong() }, (ev["valid-values"] as List<Number>).map { it.toLong() })
            assertEquals("23", name["type"]); assertEquals(b.label, tv.read(b.nameIid))
            assertEquals("CB", index["type"]); assertEquals(b.index, tv.read(b.indexIid))
        }
        assertEquals(listOf(1, 2, 3), Button.entries.map { it.index })
        assertEquals(listOf(0, 1, 2), Button.BLUE.valid)
        assertEquals(listOf(1, 2), Button.PROFILE.valid)     // la simple abre el Centro de control
        assertEquals(listOf(2), Button.TV.valid)             // la simple abre Reproduciendo ahora
        // Los eventos no se leen ni se escriben.
        assertEquals(HapStatus.READ_ONLY, tv.write(Button.BLUE.eventIid, 0L))
        assertTrue(db.char(Button.BLUE.eventIid)!!.eventOnly)
    }

    @Test fun programmableSwitchEventEncoding() {
        assertEquals("""{"characteristics":[{"aid":1,"iid":10511,"value":0}]}""", Http.eventJson(listOf(Button.BLUE.eventIid to 0)))
        assertEquals("""{"characteristics":[{"aid":1,"iid":10521,"value":1},{"aid":1,"iid":10531,"value":2}]}""",
            Http.eventJson(listOf(Button.PROFILE.eventIid to 1, Button.TV.eventIid to 2)))
        val raw = String(Http.event(Http.eventJson(listOf(Button.TV.eventIid to 2))), Charsets.ISO_8859_1)
        assertTrue(raw.startsWith("EVENT/1.0 200 OK\r\n"))
        assertTrue(raw.endsWith("""{"characteristics":[{"aid":1,"iid":10531,"value":2}]}"""))
        assertEquals(Button.TV, Button.byEvent(10531)); assertNull(Button.byEvent(10532))
        assertEquals(Button.PROFILE, Button.byId("profile"))
    }

    @Test fun displayOrderRoundTrips() {
        val b64 = AccessoryDb.displayOrder(listOf(3, 1, 2))
        assertEquals(listOf(3, 1, 2), AccessoryDb.parseDisplayOrder(b64))
        assertNull(AccessoryDb.parseDisplayOrder("!!!"))
        assertEquals(listOf(3, 1, 2, 4), AccessoryDb.applyOrder(listOf(3, 9, 1, 2), listOf(1, 2, 3, 4)))
    }
}

class ExtrasControllerTest {
    private var now = 1_000L
    private val tv = FakeTv()
    private val store = HomeKitStore(MemoryKv())
    private val c = TvController(AccessoryDb(TEST_INPUTS), store, tv) { now }

    @Test fun nightLightMapsOnAndBrightnessAndSchedule() {
        assertEquals("always", Mappings.NIGHT_ALWAYS)
        assertFalse(Mappings.nightOn("off", true)); assertFalse(Mappings.nightOn(null, true))
        assertTrue(Mappings.nightOn("always", null))
        assertTrue(Mappings.nightOn("sun", true)); assertFalse(Mappings.nightOn("fixed", false)); assertFalse(Mappings.nightOn("sun", null))
        assertEquals(1, Mappings.brightness(0)); assertEquals(100, Mappings.brightness(250))

        assertEquals(HapStatus.OK, c.write(Extra.NIGHT.onIid, true))
        assertEquals(HapStatus.OK, c.write(Extra.NIGHT.brightnessIid, 70L))
        assertEquals(listOf("night:true", "nightI:70"), tv.calls)
        assertEquals(true, c.read(Extra.NIGHT.onIid)); assertEquals(70, c.read(Extra.NIGHT.brightnessIid))
        assertEquals(HapStatus.INVALID_VALUE, c.write(Extra.NIGHT.brightnessIid, 0L))
        assertEquals(HapStatus.INVALID_VALUE, c.write(Extra.NIGHT.brightnessIid, 101L))
        c.write(Extra.NIGHT.onIid, false)
        assertEquals(false, c.read(Extra.NIGHT.onIid))
    }

    @Test fun nightLightScheduleChangesBecomeEvents() {
        c.refresh()
        tv.nightOn = true                       // el horario encendió el filtro
        assertEquals(listOf(Extra.NIGHT.onIid), c.refresh())
        tv.nightIntensity = 30                  // se cambió desde Hogar
        assertEquals(listOf(Extra.NIGHT.brightnessIid), c.refresh())
    }

    @Test fun musicLightAndRhythmStayConsistent() {
        assertEquals(1, Mappings.afterMusic(0, true)); assertEquals(2, Mappings.afterMusic(2, true))
        assertEquals(0, Mappings.afterMusic(2, false)); assertEquals(0, Mappings.afterMusic(1, false))
        assertEquals(2, Mappings.afterRhythm(0, true)); assertEquals(2, Mappings.afterRhythm(1, true))
        assertEquals(1, Mappings.afterRhythm(2, false)); assertEquals(0, Mappings.afterRhythm(0, false))

        c.write(Extra.RHYTHM.onIid, true)                         // Ritmo encendido implica Luz musical
        assertEquals(true, c.read(Extra.MUSIC.onIid)); assertEquals(true, c.read(Extra.RHYTHM.onIid))
        c.write(Extra.MUSIC.onIid, false)                          // Luz musical apagada apaga el ritmo
        assertEquals(false, c.read(Extra.MUSIC.onIid)); assertEquals(false, c.read(Extra.RHYTHM.onIid))
        c.write(Extra.MUSIC.onIid, true)
        assertEquals(1, tv.light)
        c.write(Extra.RHYTHM.onIid, false)                         // solo el ritmo; la paleta sigue
        assertEquals(true, c.read(Extra.MUSIC.onIid)); assertEquals(false, c.read(Extra.RHYTHM.onIid))
    }

    @Test fun lightChangesFromTheAppAreReportedForBothSwitches() {
        c.refresh()
        tv.light = 2
        assertEquals(setOf(Extra.MUSIC.onIid, Extra.RHYTHM.onIid), c.refresh().toSet())
    }

    @Test fun nowPlayingAndScreensaverSwitches() {
        assertEquals(false, c.read(Extra.NOW_PLAYING.onIid))
        c.write(Extra.NOW_PLAYING.onIid, true)
        assertEquals(listOf("np:true"), tv.calls)
        assertEquals(true, c.read(Extra.NOW_PLAYING.onIid))        // valor optimista un instante
        now += TvController.SHORT_HOLD_MS + 1
        assertEquals(false, c.read(Extra.NOW_PLAYING.onIid))       // y vuelve a la realidad si no abrió
        assertEquals(0, Mappings.afterSaver(Mappings.SAVER_NEVER, true)); assertEquals(1, Mappings.afterSaver(1, true))
        assertEquals(2, Mappings.afterSaver(0, false)); assertTrue(Mappings.saverOn(1)); assertFalse(Mappings.saverOn(2))
        c.write(Extra.SAVER.onIid, false)
        assertEquals(false, c.read(Extra.SAVER.onIid))
    }

    @Test fun extraNamesAreRenameableAndPersistent() {
        assertEquals("Luz nocturna", c.read(Extra.NIGHT.configuredIid))
        c.write(Extra.NIGHT.configuredIid, "Lámpara cuarto")
        assertEquals("Lámpara cuarto", c.read(Extra.NIGHT.configuredIid))
        assertEquals("Luz nocturna", c.read(Extra.NIGHT.nameIid))
        c.write(Extra.RHYTHM.configuredIid, "  ")
        assertEquals("Ritmo", c.read(Extra.RHYTHM.configuredIid))
        assertEquals(HapStatus.READ_ONLY, c.write(Extra.MUSIC.nameIid, "x"))
    }

    @Test fun displayOrderAndVisibilityPersistAndEmitEvents() {
        c.refresh()
        assertEquals(HapStatus.OK, c.write(Iid.TV_DISPLAY_ORDER, AccessoryDb.displayOrder(listOf(4, 3, 2, 1))))
        assertEquals(AccessoryDb.displayOrder(listOf(4, 3, 2, 1)), c.read(Iid.TV_DISPLAY_ORDER))
        assertEquals(listOf(Iid.TV_DISPLAY_ORDER), c.refresh())
        // Un controlador nuevo (reinicio) ve el mismo orden.
        val again = TvController(AccessoryDb(TEST_INPUTS), store, tv) { now }
        assertEquals(AccessoryDb.displayOrder(listOf(4, 3, 2, 1)), again.read(Iid.TV_DISPLAY_ORDER))
        assertEquals(HapStatus.INVALID_VALUE, c.write(Iid.TV_DISPLAY_ORDER, "***"))
        c.write(Iid.inputChar(3, Iid.INPUT_TARGET_VISIBILITY), 1L)
        assertTrue(Iid.inputChar(3, Iid.INPUT_CURRENT_VISIBILITY) in c.refresh())
    }
}
