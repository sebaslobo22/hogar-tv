package live.hogar.airplay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Receptor falso en loopback: comprueba el formato de las peticiones, el cambio a cifrado HAP y la lectura de plist. */
class RtspLoopbackTest {
    @Test fun plainThenEncryptedExchange() = runBlocking {
        val clientToServer = ByteArray(32) { 1 }
        val serverToClient = ByteArray(32) { 2 }
        val server = ServerSocket(0)
        val seen = ArrayList<String>()
        val t = thread(isDaemon = true) {
            val s = server.accept()
            val ch = MessageChannel(s.getInputStream(), s.getOutputStream())
            val m1 = ch.readMessage()
            seen += m1.startLine
            ch.send("RTSP/1.0 200 OK\r\nCSeq: 0\r\nContent-Length: 0\r\n\r\n".toByteArray())
            ch.enable(serverToClient, clientToServer) // el servidor escribe con la clave “de lectura” del cliente
            val m2 = ch.readMessage()
            seen += m2.startLine
            seen += m2.headers["content-type"].orEmpty()
            val body = Plist.encode(mapOf("eventPort" to 4242, "streams" to listOf(mapOf("dataPort" to 1, "controlPort" to 2))))
            // Respuesta partida en dos mensajes de salida para ejercitar el reensamblado
            ch.send(("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body)
            s.close()
        }
        val c = TcpRtspClient("127.0.0.1", server.localPort)
        c.connect()
        val r1 = c.request("GET", "/info")
        assertTrue(r1.ok)
        c.enableEncryption(clientToServer, serverToClient)
        val r2 = c.request("SETUP", "rtsp://x/1", contentType = "application/x-apple-binary-plist", body = Plist.encode(mapOf("a" to 1)))
        assertEquals(4242L, r2.plist()["eventPort"])
        t.join(2000)
        c.close(); server.close()
        assertEquals("GET /info RTSP/1.0", seen[0])
        assertEquals("SETUP rtsp://x/1 RTSP/1.0", seen[1])
        assertEquals("application/x-apple-binary-plist", seen[2])
    }
}
