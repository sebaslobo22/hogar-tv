package live.hogar.homekit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvControllerTest {
    private var now = 1_000L
    private val tv = FakeTv()
    private val store = HomeKitStore(MemoryKv())
    private val c = TvController(AccessoryDb(TEST_INPUTS), store, tv) { now }

    @Test fun activeReflectsScreenState() {
        tv.awake = true
        assertEquals(1, c.read(Iid.TV_ACTIVE))
        tv.awake = false
        assertEquals(0, c.read(Iid.TV_ACTIVE))
        assertEquals(0, c.read(Iid.SPK_ACTIVE))
    }

    @Test fun activeZeroSleepsAndOneWakes() {
        assertEquals(HapStatus.OK, c.write(Iid.TV_ACTIVE, 0L))
        assertEquals(listOf("sleep"), tv.calls)
        // Casa sigue viendo "apagado" aunque la pantalla tarde en reaccionar...
        tv.awake = true
        assertEquals(0, c.read(Iid.TV_ACTIVE))
        // ...pero solo unos segundos: si la pantalla no se apagó, vuelve a la realidad.
        now += TvController.HOLD_MS + 1
        assertEquals(1, c.read(Iid.TV_ACTIVE))

        tv.awake = false
        tv.calls.clear()
        assertEquals(HapStatus.OK, c.write(Iid.TV_ACTIVE, 1L))
        assertEquals(listOf("wake"), tv.calls)
        assertEquals(1, c.read(Iid.TV_ACTIVE))
    }

    @Test fun activeBooleanAndNoOpWhenAlreadyInState() {
        assertEquals(HapStatus.OK, c.write(Iid.TV_ACTIVE, true))   // ya encendida
        assertTrue(tv.calls.isEmpty())
        assertEquals(HapStatus.INVALID_VALUE, c.write(Iid.TV_ACTIVE, 2L))
        assertEquals(HapStatus.INVALID_VALUE, c.write(Iid.TV_ACTIVE, "1"))
    }

    @Test fun activeIdentifierFollowsForegroundAppAndKeepsLastKnown() {
        assertEquals(1, c.read(Iid.TV_ACTIVE_ID))               // por defecto la primera (Hogar)
        tv.foreground = "com.netflix.ninja"
        assertEquals(2, c.read(Iid.TV_ACTIVE_ID))
        tv.foreground = "com.android.systemui"                   // desconocida: se queda la última
        assertEquals(2, c.read(Iid.TV_ACTIVE_ID))
        tv.foreground = "com.google.android.youtube.tv"
        assertEquals(3, c.read(Iid.TV_ACTIVE_ID))
    }

    @Test fun writingActiveIdentifierLaunchesThatApp() {
        assertEquals(HapStatus.OK, c.write(Iid.TV_ACTIVE_ID, 3L))
        assertEquals(listOf("launch:com.google.android.youtube.tv"), tv.calls)
        assertEquals(3, c.read(Iid.TV_ACTIVE_ID))               // valor optimista hasta que la app llegue
        assertEquals(HapStatus.INVALID_VALUE, c.write(Iid.TV_ACTIVE_ID, 99L))
        tv.launchResult = false
        assertEquals(HapStatus.COMMUNICATION_FAILURE, c.write(Iid.TV_ACTIVE_ID, 2L))
    }

    @Test fun launchingAnAppWhileAsleepWakesFirst() {
        tv.awake = false
        c.write(Iid.TV_ACTIVE_ID, 2L)
        assertEquals(listOf("wake", "launch:com.netflix.ninja"), tv.calls)
        assertEquals(1, c.read(Iid.TV_ACTIVE))
    }

    @Test fun hdmiInputLaunchesPassthroughKey() {
        c.write(Iid.TV_ACTIVE_ID, 4L)
        assertEquals(listOf("launch:hdmi:HW1"), tv.calls)
    }

    @Test fun volumeAndMuteMapToAudio() {
        assertEquals(40, c.read(Iid.SPK_VOLUME))
        assertEquals(false, c.read(Iid.SPK_MUTE))
        c.write(Iid.SPK_VOLUME, 80L); c.write(Iid.SPK_MUTE, true)
        c.write(Iid.SPK_VOLUME_SELECTOR, 0L); c.write(Iid.SPK_VOLUME_SELECTOR, 1L)
        assertEquals(listOf("vol:80", "mute:true", "up", "down"), tv.calls)
        assertEquals(HapStatus.INVALID_VALUE, c.write(Iid.SPK_VOLUME, 101L))
        assertEquals(HapStatus.INVALID_VALUE, c.write(Iid.SPK_VOLUME, -1L))
        assertEquals(3, c.read(Iid.SPK_VOLUME_TYPE))
    }

    @Test fun remoteKeysMapToTheRightActions() {
        assertEquals(RemoteKeys.Action.Media(85), RemoteKeys.actionFor(RemoteKeys.PLAY_PAUSE))
        assertEquals(RemoteKeys.Action.Media(87), RemoteKeys.actionFor(RemoteKeys.NEXT_TRACK))
        assertEquals(RemoteKeys.Action.Media(88), RemoteKeys.actionFor(RemoteKeys.PREVIOUS_TRACK))
        assertEquals(RemoteKeys.Action.Media(89), RemoteKeys.actionFor(RemoteKeys.REWIND))
        assertEquals(RemoteKeys.Action.Media(90), RemoteKeys.actionFor(RemoteKeys.FAST_FORWARD))
        assertEquals(RemoteKeys.Action.Pointer(19), RemoteKeys.actionFor(RemoteKeys.ARROW_UP))
        assertEquals(RemoteKeys.Action.Pointer(20), RemoteKeys.actionFor(RemoteKeys.ARROW_DOWN))
        assertEquals(RemoteKeys.Action.Pointer(21), RemoteKeys.actionFor(RemoteKeys.ARROW_LEFT))
        assertEquals(RemoteKeys.Action.Pointer(22), RemoteKeys.actionFor(RemoteKeys.ARROW_RIGHT))
        assertEquals(RemoteKeys.Action.Pointer(23), RemoteKeys.actionFor(RemoteKeys.SELECT))
        assertEquals(RemoteKeys.Action.Back, RemoteKeys.actionFor(RemoteKeys.BACK))
        assertEquals(RemoteKeys.Action.Home, RemoteKeys.actionFor(RemoteKeys.EXIT))
        assertEquals(RemoteKeys.Action.Info, RemoteKeys.actionFor(RemoteKeys.INFORMATION))
        assertEquals(RemoteKeys.Action.None, RemoteKeys.actionFor(12))
        c.write(Iid.TV_REMOTE_KEY, 11L)
        assertEquals(listOf("key:11"), tv.calls)
    }

    @Test fun refreshReportsOnlyWhatChanged() {
        assertTrue(c.refresh().isEmpty())                        // primera foto
        assertTrue(c.refresh().isEmpty())
        tv.vol = 55
        assertEquals(listOf(Iid.SPK_VOLUME), c.refresh())
        tv.awake = false
        tv.foreground = "com.netflix.ninja"
        assertEquals(setOf(Iid.TV_ACTIVE, Iid.SPK_ACTIVE, Iid.TV_ACTIVE_ID), c.refresh().toSet())
        assertTrue(c.refresh().isEmpty())
    }

    @Test fun writesRaiseEventsOnlyOnceAndSettleAfterTheHold() {
        c.refresh()
        c.write(Iid.TV_ACTIVE, 0L)
        assertEquals(setOf(Iid.TV_ACTIVE, Iid.SPK_ACTIVE), c.refresh().toSet())   // valor optimista
        tv.awake = false                                                           // la pantalla por fin se apaga
        now += 500
        assertTrue(c.refresh().isEmpty())                                          // sin evento repetido
    }

    @Test fun failedActionRevertsAfterTheHoldWithAnEvent() {
        c.refresh()
        c.write(Iid.TV_ACTIVE, 0L)
        tv.awake = true                                                            // la pantalla ignoró la orden
        c.refresh()
        now += TvController.HOLD_MS + 1
        assertEquals(setOf(Iid.TV_ACTIVE, Iid.SPK_ACTIVE), c.refresh().toSet())
        assertEquals(1, c.read(Iid.TV_ACTIVE))
    }

    @Test fun inputRenamingAndVisibilityPersist() {
        c.write(Iid.inputChar(2, Iid.INPUT_CONFIGURED_NAME), "Peliculas")
        c.write(Iid.inputChar(2, Iid.INPUT_TARGET_VISIBILITY), 1L)
        c.write(Iid.TV_CONFIGURED_NAME, "Sala")
        assertEquals("Peliculas", c.read(Iid.inputChar(2, Iid.INPUT_CONFIGURED_NAME)))
        assertEquals("Netflix", c.read(Iid.inputChar(2, Iid.INPUT_NAME)))
        assertEquals(1, c.read(Iid.inputChar(2, Iid.INPUT_CURRENT_VISIBILITY)))
        assertEquals("Sala", c.read(Iid.TV_CONFIGURED_NAME))
        assertEquals(HapStatus.READ_ONLY, c.write(Iid.inputChar(2, Iid.INPUT_NAME), "x"))
        assertEquals(HapStatus.READ_ONLY, c.write(Iid.inputChar(2, Iid.INPUT_IDENTIFIER), 7L))
    }

    @Test fun inputTypesAndDisplayOrder() {
        assertEquals(10, c.read(Iid.inputChar(1, Iid.INPUT_TYPE)))
        assertEquals(3, c.read(Iid.inputChar(4, Iid.INPUT_TYPE)))
        assertEquals(4, c.read(Iid.inputChar(4, Iid.INPUT_IDENTIFIER)))
        // 01 04 01000000 | 00 00 | 01 04 02000000 ...
        assertEquals("AQQBAAAAAAABBAIAAAAAAAEEAwAAAAAAAQQEAAAA", c.read(Iid.TV_DISPLAY_ORDER))
        assertEquals(1, c.read(Iid.TV_SLEEP_DISCOVERY))
    }
}

class StoreTest {
    @Test fun identityAndCodeArePersistentAndStable() {
        val kv = MemoryKv()
        val a = HomeKitStore(kv)
        val b = HomeKitStore(kv)
        assertEquals(a.deviceId, b.deviceId)
        assertEquals(a.setupCode, b.setupCode)
        assertEquals(a.setupId, b.setupId)
        assertEquals(a.ltpk.toList(), b.ltpk.toList())
        assertEquals(a.ltpk.toList(), Curve25519.ed25519PublicKey(a.ltskSeed).toList())
        assertTrue(Setup.isValidCode(a.setupCode))
    }

    @Test fun pairingsPersistAndResetRegeneratesTheCode() {
        val kv = MemoryKv()
        val s = HomeKitStore(kv)
        val oldCode = s.setupCode
        s.savePairing(Pairing("abc-1", ByteArray(32) { 1 }, true))
        s.savePairing(Pairing("abc-2", ByteArray(32) { 2 }, false))
        val again = HomeKitStore(kv)
        assertTrue(again.isPaired())
        assertEquals(listOf("abc-1", "abc-2"), again.pairings().map { it.id })
        assertFalse(again.pairing("abc-2")!!.admin)
        again.resetPairing()
        assertFalse(again.isPaired())
        assertTrue(again.setupCode != oldCode || again.setupId.isNotEmpty())
        assertEquals(s.deviceId, again.deviceId)                 // la identidad se conserva
    }

    @Test fun configNumberOnlyBumpsWhenTheDatabaseChanges() {
        val s = HomeKitStore(MemoryKv())
        assertEquals(1, s.configNumber("a"))
        assertEquals(1, s.configNumber("a"))
        assertEquals(2, s.configNumber("b"))
        assertEquals(2, s.configNumber("b"))
        assertEquals(3, s.configNumber("a"))
    }

    @Test fun identifiersAreStableAndNeverReused() {
        val kv = MemoryKv()
        val s = HomeKitStore(kv)
        val n = s.identifierFor("com.netflix.ninja")
        val y = s.identifierFor("com.google.android.youtube.tv")
        assertEquals(1, n); assertEquals(2, y)
        assertEquals(n, HomeKitStore(kv).identifierFor("com.netflix.ninja"))
        assertEquals(3, HomeKitStore(kv).identifierFor("com.spotify.tv.android"))
    }

    @Test fun databaseSignatureChangesWithInputs() {
        val a = AccessoryDb(TEST_INPUTS).signature()
        val b = AccessoryDb(TEST_INPUTS.dropLast(1)).signature()
        assertTrue(a != b)
        assertEquals(a, AccessoryDb(TEST_INPUTS).signature())
    }
}
