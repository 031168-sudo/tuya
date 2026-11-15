package app.tuyacontrol.nartis

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Мост «локальный TCP-порт ↔ Bluetooth». Библиотека DLMS умеет говорить по TCP,
 * а счётчик — по Bluetooth; здесь байты перекладываются из одного в другое.
 * Слушает только 127.0.0.1, снаружи недоступен.
 */
class NusBridge(private val ble: NartisBle, private val log: (String) -> Unit) : Closeable {

    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    @Volatile
    private var running = true
    private var socket: Socket? = null
    private var rxThread: Thread? = null

    fun start() {
        thread(name = "nartis-bridge") {
            while (running) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (running) log("Мост: ${e.message}")
                    return@thread
                }
                runSession(client)
            }
        }
    }

    private fun runSession(client: Socket) {
        socket = client
        try {
            client.tcpNoDelay = true
            val out = client.getOutputStream()
            val input = client.getInputStream()

            rxThread = thread(name = "nartis-ble-rx") {
                try {
                    while (running && !client.isClosed) {
                        val chunk = ble.read(200)
                        if (chunk.isNotEmpty()) {
                            synchronized(out) {
                                out.write(chunk)
                                out.flush()
                            }
                            log("← ${hex(chunk)}")
                        }
                    }
                } catch (_: Exception) {
                    // соединение закрывается — это нормально
                }
            }

            val buffer = ByteArray(2048)
            while (running) {
                val n = input.read(buffer)
                if (n < 0) break
                val data = buffer.copyOf(n)
                // Содержимое длинных запросов не пишем: в AARQ идёт пароль
                log(if (data.size <= 40) "→ ${hex(data)}" else "→ ${data.size} Б")
                ble.write(data)
            }
        } catch (e: Exception) {
            if (running) log("Мост: ${e.message}")
        } finally {
            runCatching { client.close() }
            socket = null
        }
    }

    override fun close() {
        running = false
        runCatching { socket?.close() }
        runCatching { server.close() }
        rxThread?.interrupt()
    }
}

/** Первые байты посылки в hex — для журнала. */
internal fun hex(data: ByteArray, limit: Int = 96): String {
    val n = minOf(data.size, limit)
    val text = (0 until n).joinToString(" ") { "%02X".format(data[it]) }
    return if (data.size > limit) "$text … (${data.size} Б)" else text
}
