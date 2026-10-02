package app.tuyacontrol.xiaomi

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Криптография Xiaomi:
 *  - облако Mi Home (api.io.mi.com): nonce, signed nonce, подпись SHA1 и шифрование RC4 (как в Mi Home и
 *    github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor);
 *  - локальный протокол miIO (UDP 54321): AES-128-CBC с ключом из token устройства.
 */
object MiCrypto {

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()
    private val random = SecureRandom()

    fun md5(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    /** MD5 пароля заглавными hex — так его отправляет Mi Home. */
    fun passwordHash(password: String): String = md5(password.toByteArray()).toHex().uppercase()

    // ---------- Облако ----------

    /** 8 случайных байт + номер минуты (4 байта, big-endian), base64. */
    fun nonce(millis: Long = System.currentTimeMillis(), randomBytes: ByteArray? = null): String {
        val r = randomBytes ?: ByteArray(8).also { random.nextBytes(it) }
        val b = ByteBuffer.allocate(12).put(r, 0, 8).putInt((millis / 60_000L).toInt()).array()
        return b64e.encodeToString(b)
    }

    fun signedNonce(ssecurity: String, nonce: String): String =
        b64e.encodeToString(sha256(b64d.decode(ssecurity), b64d.decode(nonce)))

    /** Подпись запроса: METHOD&/путь&k=v&…&signedNonce, SHA1, base64. Путь — после «.com», без «/app». */
    fun encSignature(url: String, method: String, signedNonce: String, params: Map<String, String>): String {
        val parts = mutableListOf(method.uppercase(), apiPath(url))
        params.forEach { (k, v) -> parts += "$k=$v" }
        parts += signedNonce
        return b64e.encodeToString(sha1(parts.joinToString("&").toByteArray()))
    }

    /** «https://ru.api.io.mi.com/app/v2/home/x» -> «/v2/home/x». */
    fun apiPath(url: String): String = url.substringAfter("com").replace("/app/", "/")

    /** Параметры запроса облака: rc4_hash__, все значения шифруются RC4, затем signature, ssecurity, _nonce. */
    fun encParams(url: String, method: String, signedNonce: String, nonce: String, params: Map<String, String>, ssecurity: String): LinkedHashMap<String, String> {
        val p = LinkedHashMap(params)
        p["rc4_hash__"] = encSignature(url, method, signedNonce, p)
        for (k in p.keys.toList()) p[k] = rc4Encrypt(signedNonce, p.getValue(k))
        p["signature"] = encSignature(url, method, signedNonce, p)
        p["ssecurity"] = ssecurity
        p["_nonce"] = nonce
        return p
    }

    fun rc4Encrypt(key: String, payload: String): String = b64e.encodeToString(rc4(b64d.decode(key), payload.toByteArray()))

    fun rc4Decrypt(key: String, payload: String): String =
        String(rc4(b64d.decode(key), b64d.decode(payload.trim())), Charsets.UTF_8)

    /** RC4 с отброшенными первыми 1024 байтами потока (RC4-drop1024). */
    fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var i = 0
        j = 0
        fun next(): Int {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            return s[(s[i] + s[j]) and 0xFF]
        }
        repeat(1024) { next() }
        return ByteArray(data.size) { k -> (data[k].toInt() xor next()).toByte() }
    }

    // ---------- miIO (локально) ----------

    fun aesEncrypt(token: ByteArray, data: ByteArray): ByteArray = aes(Cipher.ENCRYPT_MODE, token, data)

    fun aesDecrypt(token: ByteArray, data: ByteArray): ByteArray = aes(Cipher.DECRYPT_MODE, token, data)

    private fun aes(mode: Int, token: ByteArray, data: ByteArray): ByteArray {
        val key = md5(token)
        val iv = md5(key, token)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val h = hex.trim()
        require(h.length % 2 == 0) { "нечётная длина hex" }
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
