package app.tuyacontrol.local

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Локальный протокол Tuya (TCP 6668), версии 3.3 и 3.4.
 * Сверено с эталонной реализацией tinytuya: см. TuyaProtocolTest (контрольные пакеты).
 *
 * Кадр: 000055AA | seq(4) | cmd(4) | len(4) | [retcode(4) — только от устройства] | payload |
 *       crc32(4) для 3.3 или HMAC-SHA256(32) для 3.4 | 0000AA55
 *
 * 3.3: payload = AES-ECB(local_key, json) с префиксом "3.3"+12 нулей для команд кроме запросов/heartbeat.
 * 3.4: payload = AES-ECB(ключ сессии, ["3.4"+12 нулей +] json), подпись кадра HMAC-SHA256 ключом сессии;
 *      ключ сессии согласуется командами 3/4/5.
 */
object TuyaProtocol {

    const val PREFIX = 0x000055AA
    const val SUFFIX = 0x0000AA55

    const val SESS_KEY_NEG_START = 3
    const val SESS_KEY_NEG_RESP = 4
    const val SESS_KEY_NEG_FINISH = 5
    const val CONTROL = 7
    const val STATUS = 8
    const val HEART_BEAT = 9
    const val DP_QUERY = 10
    const val CONTROL_NEW = 13
    const val DP_QUERY_NEW = 16
    const val UPDATEDPS = 18

    /** Команды без заголовка версии ("3.x" + 12 нулей). */
    private val NO_HEADER = setOf(DP_QUERY, DP_QUERY_NEW, UPDATEDPS, HEART_BEAT, SESS_KEY_NEG_START, SESS_KEY_NEG_RESP, SESS_KEY_NEG_FINISH, 0x40)

    fun isV34(version: String) = version.startsWith("3.4") || version.startsWith("3.5")

    private fun versionHeader(version: String): ByteArray =
        (if (isV34(version)) "3.4" else "3.3").toByteArray() + ByteArray(12)

    // ---------- Сборка исходящих кадров ----------

    /**
     * Готовый кадр для отправки.
     * @param key для 3.3 — local_key; для 3.4 — ключ сессии (или local_key при согласовании)
     */
    fun encode(version: String, key: ByteArray, seq: Int, cmd: Int, body: ByteArray): ByteArray {
        return if (isV34(version)) {
            val plain = if (cmd in NO_HEADER) body else versionHeader(version) + body
            pack(seq, cmd, aesEncrypt(key, plain), hmacKey = key)
        } else {
            val enc = aesEncrypt(key, body)
            val payload = if (cmd in NO_HEADER) enc else versionHeader(version) + enc
            pack(seq, cmd, payload, hmacKey = null)
        }
    }

    fun pack(seq: Int, cmd: Int, payload: ByteArray, hmacKey: ByteArray?): ByteArray {
        val endLen = (if (hmacKey != null) 32 else 4) + 4
        val head = ByteBuffer.allocate(16).putInt(PREFIX).putInt(seq).putInt(cmd).putInt(payload.size + endLen).array()
        val data = head + payload
        val check = if (hmacKey != null) {
            hmacSha256(hmacKey, data)
        } else {
            val crc = CRC32().apply { update(data) }.value.toInt()
            ByteBuffer.allocate(4).putInt(crc).array()
        }
        return data + check + ByteBuffer.allocate(4).putInt(SUFFIX).array()
    }

    // ---------- Разбор входящих кадров ----------

    class Frame(
        val seq: Int,
        val cmd: Int,
        val retcode: Int,
        /** Данные после кода возврата и до контрольной суммы. */
        val payload: ByteArray,
        /** Данные вместе с 4 байтами «кода возврата» — на случай кадра без него. */
        val payloadWithRetcode: ByteArray,
        val checkOk: Boolean,
    )

    /** Длина всего кадра по его 16-байтному заголовку; -1 — это не кадр Tuya. */
    fun frameLength(header: ByteArray): Int {
        if (header.size < 16 || readInt(header, 0) != PREFIX) return -1
        val len = readInt(header, 12)
        if (len < 8 || len > 64 * 1024) return -1
        return 16 + len
    }

    fun unpack(frame: ByteArray, hmacKey: ByteArray?): Frame {
        val len = readInt(frame, 12)
        val total = 16 + len
        val endLen = (if (hmacKey != null) 32 else 4) + 4
        val bodyEnd = total - endLen
        val withRet = frame.copyOfRange(16, bodyEnd)
        val retcode = if (withRet.size >= 4) readInt(withRet, 0) else 0
        val payload = if (withRet.size >= 4) withRet.copyOfRange(4, withRet.size) else ByteArray(0)
        val signed = frame.copyOfRange(0, bodyEnd)
        val checkOk = if (hmacKey != null) {
            hmacSha256(hmacKey, signed).contentEquals(frame.copyOfRange(bodyEnd, bodyEnd + 32))
        } else {
            CRC32().apply { update(signed) }.value.toInt() == readInt(frame, bodyEnd)
        }
        return Frame(readInt(frame, 4), readInt(frame, 8), retcode, payload, withRet, checkOk)
    }

    /**
     * Расшифровка данных кадра в текст JSON. null — не удалось (например, пустой ответ на команду).
     * Пробует вариант с кодом возврата и без: часть устройств присылает рассылки без него.
     */
    fun decodePayload(version: String, key: ByteArray, frame: Frame): String? =
        decodeBytes(version, key, frame.payload) ?: decodeBytes(version, key, frame.payloadWithRetcode)

    private fun decodeBytes(version: String, key: ByteArray, payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        return try {
            var p = payload
            if (isV34(version)) {
                p = aesDecrypt(key, p) ?: return null
                if (startsWithVersion(p)) p = p.copyOfRange(15, p.size)
            } else {
                if (startsWithVersion(p) || (p.size % 16 != 0 && p.size > 15)) p = p.copyOfRange(15, p.size)
                p = aesDecrypt(key, p) ?: return null
            }
            val text = String(p, Charsets.UTF_8).trim()
            if (text.startsWith("{") || text.contains("data unvalid")) text else null
        } catch (e: Exception) {
            null
        }
    }

    private fun startsWithVersion(p: ByteArray) =
        p.size >= 15 && p[0] == '3'.code.toByte() && p[1] == '.'.code.toByte() && p[2] in '0'.code.toByte()..'9'.code.toByte()

    // ---------- JSON команд ----------

    /** Запрос состояния. device22 — особые устройства с 22-символьным id: запрос через CONTROL_NEW со списком DP. */
    fun queryCommand(version: String, devId: String, t: Long, device22: Boolean, dpIds: List<Int>): Pair<Int, ByteArray> =
        when {
            isV34(version) -> DP_QUERY_NEW to "{}".toByteArray()
            device22 -> CONTROL_NEW to json(
                "devId" to devId, "uid" to devId, "t" to t.toString(),
                "dps" to RawJson(dpsJson(dpIds.ifEmpty { listOf(1) }.associate { it.toString() to null })),
            )
            else -> DP_QUERY to json("gwId" to devId, "devId" to devId, "uid" to devId, "t" to t.toString())
        }

    /** Установка значений DP: {"1": true, "2": 220}. */
    fun controlCommand(version: String, devId: String, t: Long, dps: Map<String, Any?>): Pair<Int, ByteArray> =
        if (isV34(version)) {
            CONTROL_NEW to json("protocol" to 5, "t" to t, "data" to RawJson("{\"dps\":" + dpsJson(dps) + "}"))
        } else {
            CONTROL to json("devId" to devId, "uid" to devId, "t" to t.toString(), "dps" to RawJson(dpsJson(dps)))
        }

    fun heartbeatCommand(devId: String): Pair<Int, ByteArray> =
        HEART_BEAT to json("gwId" to devId, "devId" to devId)

    // ---------- Согласование ключа сессии (3.4) ----------

    fun negotiateStart(seq: Int, realKey: ByteArray, localNonce: ByteArray): ByteArray =
        encode("3.4", realKey, seq, SESS_KEY_NEG_START, localNonce)

    /** Разбор ответа устройства: remote nonce или null, если подпись не сошлась. */
    fun negotiateRemoteNonce(frame: Frame, realKey: ByteArray, localNonce: ByteArray): ByteArray? {
        if (frame.cmd != SESS_KEY_NEG_RESP) return null
        val p = aesDecrypt(realKey, frame.payload) ?: return null
        if (p.size < 48) return null
        val remote = p.copyOfRange(0, 16)
        val expected = hmacSha256(realKey, localNonce)
        return if (expected.contentEquals(p.copyOfRange(16, 48))) remote else null
    }

    fun negotiateFinish(seq: Int, realKey: ByteArray, remoteNonce: ByteArray): ByteArray =
        encode("3.4", realKey, seq, SESS_KEY_NEG_FINISH, hmacSha256(realKey, remoteNonce))

    fun sessionKey(realKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray {
        val x = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
        return aesEncrypt(realKey, x, pad = false)
    }

    // ---------- Крипто ----------

    fun aesEncrypt(key: ByteArray, data: ByteArray, pad: Boolean = true): ByteArray {
        val cipher = Cipher.getInstance(if (pad) "AES/ECB/PKCS5Padding" else "AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray? {
        if (data.isEmpty() || data.size % 16 != 0) return null
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            val raw = cipher.doFinal(data)
            // Снимаем PKCS7-добивку, если она корректна (как tinytuya: без строгой проверки)
            val pad = raw.last().toInt() and 0xFF
            if (pad in 1..16 && pad <= raw.size) raw.copyOfRange(0, raw.size - pad) else raw
        } catch (e: Exception) {
            null
        }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    // ---------- Компактный JSON без пробелов, в заданном порядке ключей ----------

    /** Уже готовый фрагмент JSON. */
    class RawJson(val text: String)

    fun json(vararg pairs: Pair<String, Any?>): ByteArray =
        pairs.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + value(v) }.toByteArray(Charsets.UTF_8)

    fun dpsJson(dps: Map<String, Any?>): String =
        dps.entries.joinToString(",", "{", "}") { (k, v) -> quote(k) + ":" + value(v) }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is RawJson -> v.text
        is Boolean -> v.toString()
        is Int, is Long, is Short, is Byte -> v.toString()
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Float -> value(v.toDouble())
        is Number -> v.toString()
        else -> quote(v.toString())
    }

    private fun quote(s: String): String {
        val out = ByteArrayOutputStream()
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        out.close()
        return sb.append('"').toString()
    }
}
