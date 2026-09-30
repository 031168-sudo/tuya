package app.tuyacontrol.local

import android.content.Context
import android.net.wifi.WifiManager
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Устройство, объявившее себя в локальной сети. */
data class LocalAnnounce(
    val deviceId: String,
    val ip: String,
    /** Версия протокола: "3.1", "3.3", "3.4", "3.5". */
    val version: String,
    val productKey: String,
    val port: Int,
    val time: Long,
)

/**
 * Поиск устройств Tuya в Wi-Fi сети: устройства раз в несколько секунд рассылают UDP-объявления
 * на порты 6666 (без шифрования, старые), 6667 (AES-ECB общим ключом) и 7000 (протокол 3.5, AES-GCM).
 * Общий ключ объявлений — md5("yGAdlopoPVldABfn"), он одинаков для всех устройств.
 */
object LocalDiscovery {

    private val PORTS = listOf(6666, 6667, 7000)
    private val UDP_KEY: ByteArray = MessageDigest.getInstance("MD5").digest("yGAdlopoPVldABfn".toByteArray())

    /** Слушать объявления [durationMs] мс. Возвращает найденные устройства по id. */
    suspend fun scan(context: Context, durationMs: Long = 7_000): Map<String, LocalAnnounce> {
        val found = ConcurrentHashMap<String, LocalAnnounce>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Без MulticastLock Android отбрасывает широковещательные пакеты, чтобы экономить батарею
        val lock = wifi.createMulticastLock("tuya-discovery").apply { setReferenceCounted(false) }
        lock.acquire()
        try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(durationMs) {
                    coroutineScope {
                        for (port in PORTS) {
                            launch { listen(port, found) }
                        }
                    }
                }
            }
        } finally {
            lock.release()
        }
        AppLog.i("Локальная сеть: найдено устройств ${found.size}")
        return found
    }

    private suspend fun listen(port: Int, found: MutableMap<String, LocalAnnounce>) = coroutineScope {
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 500
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            AppLog.e("Локальная сеть: порт $port занят", e)
            return@coroutineScope
        }
        socket.use { s ->
            val buf = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val data = packet.data.copyOf(packet.length)
                val json = decode(data) ?: continue
                val id = json.optString("gwId").ifEmpty { json.optString("devId") }
                if (id.isEmpty()) continue
                val ip = json.optString("ip").ifEmpty { packet.address.hostAddress.orEmpty() }
                val announce = LocalAnnounce(
                    deviceId = id,
                    ip = ip,
                    version = json.optString("version", "3.3"),
                    productKey = json.optString("productKey"),
                    port = port,
                    time = System.currentTimeMillis(),
                )
                if (found.put(id, announce) == null) {
                    AppLog.i("Локальная сеть: $id ${announce.ip} v${announce.version} (порт $port)")
                }
            }
        }
    }

    /** Разбор объявления любого формата; null — не удалось. */
    internal fun decode(data: ByteArray): JSONObject? {
        if (data.size < 20) return null
        val prefix = readInt(data, 0)
        return when (prefix) {
            0x000055AA -> decode55aa(data)
            0x00006699 -> decode6699(data)
            else -> null
        }
    }

    /** 3.1–3.4: 55AA seq(4) cmd(4) len(4) [retcode(4)] payload crc(4) AA55. */
    private fun decode55aa(data: ByteArray): JSONObject? {
        val end = data.size - 8
        if (end <= 16) return null
        for (start in intArrayOf(20, 16)) {
            if (start >= end) continue
            val payload = data.copyOfRange(start, end)
            parseJson(payload)?.let { return it }
            aesEcbDecrypt(payload)?.let { parseJson(it)?.let { j -> return j } }
        }
        return null
    }

    /** 3.5: 6699 unknown(2) seq(4) cmd(4) len(4) iv(12) ciphertext tag(16) 9966; AAD — байты 4..18. */
    private fun decode6699(data: ByteArray): JSONObject? {
        if (data.size < 18 + 12 + 16 + 4) return null
        val len = readInt(data, 14)
        val bodyEnd = 18 + len
        if (bodyEnd > data.size || len < 28) return null
        val iv = data.copyOfRange(18, 30)
        val cipherAndTag = data.copyOfRange(30, bodyEnd)
        val aad = data.copyOfRange(4, 18)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(UDP_KEY, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(cipherAndTag)
            // В начале расшифровки бывает 4-байтный код возврата
            parseJson(plain) ?: if (plain.size > 4) parseJson(plain.copyOfRange(4, plain.size)) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun aesEcbDecrypt(payload: ByteArray): ByteArray? {
        if (payload.isEmpty() || payload.size % 16 != 0) return null
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(UDP_KEY, "AES"))
            cipher.doFinal(payload)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseJson(bytes: ByteArray): JSONObject? {
        val text = String(bytes, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        val startIdx = text.indexOf('{')
        val endIdx = text.lastIndexOf('}')
        if (startIdx < 0 || endIdx <= startIdx) return null
        return try {
            JSONObject(text.substring(startIdx, endIdx + 1))
        } catch (e: Exception) {
            null
        }
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
