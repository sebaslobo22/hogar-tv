package live.hogar.app

import android.app.Application
import live.hogar.airplay.AirPlayDiscovery
import live.hogar.airplay.AirPlayOutputFactory
import live.hogar.core.AudioOutputFactory
import live.hogar.core.SpeakerDiscovery
import live.hogar.homekit.HomeKit
import live.hogar.player.bridge.BridgeHub
import live.hogar.receiver.AirPlayReceiver

class HogarApp : Application() {
    val discovery: SpeakerDiscovery by lazy { AirPlayDiscovery(this) }
    val outputs: AudioOutputFactory by lazy { AirPlayOutputFactory(this) }

    override fun onCreate() {
        super.onCreate()
        BridgeHub.init(this, outputs, discovery)   // puente iPhone -> TV + altavoces AirPlay (apagado por defecto)
        AirPlayReceiver.start(this)
        HomeKit.start(this)   // accesorio Televisión para Casa de Apple (HAP); nunca lanza
    }
}
