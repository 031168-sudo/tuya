package app.tuyacontrol.xiaomi

import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MiioException(message: String) : Exception(message)

/**
 * Протокол miIO по Wi-Fi (UDP 54321), как в python-miio.
 * Пакет: заголовок 32 байта (0x2131, длина, 0, id устройства, метка времени, MD5) + JSON, зашифрованный AES
 * ключом из token. Перед командами — «hello», устройство отвечает своим id и меткой времени.
 */
object MiioPacket {
    const val PORT = 54321
    const val MAGIC = 0x2131

    /** Пакет «hello»: 0x2131 0x0020 и 28 байт 0xFF. */
    fun hello(): ByteArray = ByteArray(32) { 0xFF.toByte() }.also {
        it[0] = 0x21; it[1] = 0x31; it[2] = 0x00; it[3] = 0x20
    }

    data class Header(val length: Int, val deviceId: Long, val stamp: Long)

    fun parseHeader(b: ByteArray): Header? {
        if (b.size < 32) return null
        val bb = ByteBuffer.wrap(b)
        if (bb.getShort(0).toInt() and 0xFFFF != MAGIC) return null
        return Header(
            length = bb.getShort(2).toInt() and 0xFFFF,
            deviceId = bb.getInt(8).toLong() and 0xFFFFFFFFL,
            stamp = bb.getInt(12).toLong() and 0xFFFFFFFFL,
        )
    }

    fun encode(token: ByteArray, deviceId: Long, stamp: Long, json: String): ByteArray {
        val enc = MiCrypto.aesEncrypt(token, json.toByteArray())
        val bb = ByteBuffer.allocate(32 + enc.size)
        bb.putShort(MAGIC.toShort())
        bb.putShort((32 + enc.size).toShort())
        bb.putInt(0)
        bb.putInt(deviceId.toInt())
        bb.putInt(stamp.toInt())
        bb.put(token) // на время подсчёта MD5 на месте контрольной суммы стоит token
        bb.put(enc)
        val packet = bb.array()
        val sum = MiCrypto.md5(packet)
        System.arraycopy(sum, 0, packet, 16, 16)
        return packet
    }

    /** JSON из ответа; null — пустой ответ (например, на hello). */
    fun decode(token: ByteArray, packet: ByteArray): String? {
        val h = parseHeader(packet) ?: throw MiioException("не пакет miIO")
        val len = minOf(h.length, packet.size)
        if (len <= 32) return null
        val check = packet.copyOf(len)
        System.arraycopy(token, 0, check, 16, 16)
        if (!MiCrypto.md5(check).contentEquals(packet.copyOfRange(16, 32))) throw MiioException("неверная контрольная сумма (token не тот?)")
        val plain = MiCrypto.aesDecrypt(token, packet.copyOfRange(32, len))
        return String(plain, Charsets.UTF_8).trimEnd('\u0000', ' ', '\n')
    }
}

/** Соединение с одним устройством по Wi-Fi. Потокобезопасно: запросы идут по очереди. */
class MiioDevice(val ip: String, tokenHex: String) {
    private val token = MiCrypto.hexToBytes(tokenHex)
    private val mutex = Mutex()
    private var deviceId = 0L
    private var stamp = 0L
    private var helloAt = 0L
    private val ids = AtomicInteger((System.currentTimeMillis() / 1000 % 10_000).toInt())

    /** Вызов метода miIO; возвращает поле result. */
    suspend fun call(method: String, params: Any): Any? = mutex.withLock {
        withContext(Dispatchers.IO) {
            DatagramSocket().use { s ->
                s.soTimeout = TIMEOUT_MS
                val addr = InetAddress.getByName(ip)
                if (System.currentTimeMillis() - helloAt > 60_000) handshake(s, addr)
                var lastError: Exception? = null
                repeat(2) { attempt ->
                    val id = ids.incrementAndGet()
                    val req = JSONObject().put("id", id).put("method", method).put("params", params).toString()
                    val st = stamp + (System.currentTimeMillis() - helloAt) / 1000
                    val out = MiioPacket.encode(token, deviceId, st, req)
                    try {
                        s.send(DatagramPacket(out, out.size, addr, MiioPacket.PORT))
                        while (true) {
                            val buf = ByteArray(4096)
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            val text = MiioPacket.decode(token, buf.copyOf(p.length)) ?: continue
                            val o = JSONObject(text)
                            if (o.optInt("id") != id) continue // старый ответ
                            o.optJSONObject("error")?.let { e ->
                                throw MiioException("ошибка устройства ${e.optInt("code")}: ${e.optString("message")}")
                            }
                            return@withContext o.opt("result")
                        }
                    } catch (e: SocketTimeoutException) {
                        lastError = e
                        // Устройство могло сменить метку времени — повторяем с новым hello
                        if (attempt == 0) handshake(s, addr)
                    }
                }
                throw MiioException("$ip не отвечает (${lastError?.javaClass?.simpleName})")
            }
        }
    }

    private fun handshake(s: DatagramSocket, addr: InetAddress) {
        val h = MiioPacket.hello()
        val buf = ByteArray(64)
        val p = DatagramPacket(buf, buf.size)
        // UDP по Wi-Fi теряется: hello до трёх раз
        var got = false
        val old = s.soTimeout
        s.soTimeout = 1500
        for (attempt in 1..3) {
            s.send(DatagramPacket(h, h.size, addr, MiioPacket.PORT))
            try {
                s.receive(p)
                got = true
                break
            } catch (_: SocketTimeoutException) {
            }
        }
        s.soTimeout = old
        if (!got) throw MiioException("$ip не отвечает на hello")
        val hdr = MiioPacket.parseHeader(buf.copyOf(p.length)) ?: throw MiioException("$ip: непонятный ответ на hello")
        deviceId = hdr.deviceId
        stamp = hdr.stamp
        helloAt = System.currentTimeMillis()
    }

    /** MIoT: прочитать свойства. На вход и на выход — [{did, siid, piid[, value, code]}]. */
    suspend fun getProperties(did: String, props: List<Pair<Int, Int>>): Map<Pair<Int, Int>, Any?> {
        val out = HashMap<Pair<Int, Int>, Any?>()
        // Устройства отвечают на ограниченное число свойств за раз — читаем порциями
        for (chunk in props.chunked(12)) {
            val arr = JSONArray()
            chunk.forEach { (si, pi) -> arr.put(JSONObject().put("did", did).put("siid", si).put("piid", pi)) }
            val r = call("get_properties", arr) as? JSONArray ?: continue
            for (k in 0 until r.length()) {
                val o = r.optJSONObject(k) ?: continue
                if (o.optInt("code", 0) != 0) continue
                out[o.optInt("siid") to o.optInt("piid")] = o.opt("value")
            }
        }
        return out
    }

    suspend fun setProperty(did: String, siid: Int, piid: Int, value: Any) {
        val arr = JSONArray().put(JSONObject().put("did", did).put("siid", siid).put("piid", piid).put("value", value))
        val r = call("set_properties", arr) as? JSONArray
        val code = r?.optJSONObject(0)?.optInt("code", 0) ?: 0
        if (code != 0) throw MiioException("устройство не приняло значение (код $code)")
    }

    companion object {
        private const val TIMEOUT_MS = 2500
    }
}

/** Поиск устройств Xiaomi в сети: «hello» на широковещательный адрес, в ответе — id устройства (= did). */
object MiioDiscovery {
    suspend fun scan(timeoutMs: Int = 2500): Map<String, String> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, String>()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 400
                val h = MiioPacket.hello()
                val end = System.currentTimeMillis() + timeoutMs
                var sent = 0
                while (System.currentTimeMillis() < end) {
                    if (sent < 3) {
                        s.send(DatagramPacket(h, h.size, InetAddress.getByName("255.255.255.255"), MiioPacket.PORT))
                        sent++
                    }
                    try {
                        val buf = ByteArray(64)
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val hdr = MiioPacket.parseHeader(buf.copyOf(p.length)) ?: continue
                        found[hdr.deviceId.toString()] = p.address.hostAddress ?: continue
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        }.onFailure { AppLog.e("Xiaomi: поиск в сети", it) }
        AppLog.i("Xiaomi: в сети ответили ${found.size}: ${found.entries.joinToString { "${it.key}@${it.value}" }}")
        found
    }
}
