package live.hogar.receiver

import android.content.Context
import live.hogar.receiver.crypto.Curve25519
import java.security.SecureRandom
import java.util.UUID

/** Identidad estable del receptor (se guarda en SharedPreferences para que los iPhone no vuelvan a emparejar). */
internal class Identity(
    val deviceId: String, // "AA:BB:CC:DD:EE:FF"
    val edSeed: ByteArray,
    val edPublic: ByteArray,
    val pi: String,
) {
    val deviceIdPlain: String get() = deviceId.replace(":", "")
    val pkHex: String get() = edPublic.joinToString("") { "%02x".format(it) }

    companion object {
        fun load(context: Context): Identity {
            val sp = context.getSharedPreferences("hogar_receiver", Context.MODE_PRIVATE)
            val rnd = SecureRandom()
            var dev = sp.getString("deviceId", null)
            var seedHex = sp.getString("edSeed", null)
            var pi = sp.getString("pi", null)
            val ed = sp.edit()
            if (dev == null) {
                val b = ByteArray(6).also(rnd::nextBytes)
                b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte() // local, unicast
                dev = b.joinToString(":") { "%02X".format(it) }
                ed.putString("deviceId", dev)
            }
            if (seedHex == null) {
                seedHex = ByteArray(32).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
                ed.putString("edSeed", seedHex)
            }
            if (pi == null) {
                pi = UUID.randomUUID().toString()
                ed.putString("pi", pi)
            }
            ed.apply()
            val seed = ByteArray(32) { seedHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return Identity(dev, seed, Curve25519.ed25519PublicKey(seed), pi)
        }
    }
}

internal object Features {
    /** Bits de AirPlay: 0 video, 1 foto, 2 video FairPlay, 3 volumen de video, 4 HLS, 5 slideshow, 7 pantalla, 9 audio, ... (ver NOTICE). */
    const val VALUE = 0x5A7FFEFFL
    const val HEX = "0x5A7FFEFF"
    const val NAME = "TV Daewoo"
    const val MODEL = "AppleTV3,2"
    const val SRCVERS = "220.68"
    const val PORT_PREFERRED = 7100
}
