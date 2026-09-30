package app.tuyacontrol.local

import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Что нужно для локального подключения к устройству. */
data class LocalTarget(
    val id: String,
    val name: String,
    val ip: String,
    val version: String,
    val localKey: String,
    /** Номера DP устройства — нужны для запроса у «device22». */
    val dpIds: List<Int>,
)

/**
 * Постоянное TCP-соединение с одним устройством (порт 6668).
 * Сразу запрашивает состояние, дальше устройство само присылает изменения; heartbeat раз в 10 с.
 */
class LocalConnection(
    private val target: LocalTarget,
    private val scope: CoroutineScope,
    /** Новые значения DP (номер -> значение) — полностью или частично. */
    private val onDps: (Map<String, Any?>) -> Unit,
    /** Соединение закрылось (ошибка или устройство отключилось). */
    private val onClosed: (Throwable?) -> Unit,
) {
    private val realKey = target.localKey.toByteArray(Charsets.UTF_8)
    private var key = realKey
    private var seq = 1
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Mutex()
    // Как в tinytuya: обычный запрос, а при ответе «data unvalid» — переход на запрос по списку DP
    private var device22 = target.version.startsWith("3.2")
    private var jobs = mutableListOf<Job>()
    @Volatile var connected = false
        private set

    suspend fun connect() = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(target.ip, PORT), CONNECT_TIMEOUT_MS)
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        s.tcpNoDelay = true
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()

        if (TuyaProtocol.isV34(target.version)) negotiate()

        s.soTimeout = 0
        connected = true
        AppLog.i("Wi-Fi: ${target.name} подключено (${target.ip}, ${target.version})")
        jobs += scope.launch(Dispatchers.IO) { readLoop() }
        jobs += scope.launch(Dispatchers.IO) { heartbeatLoop() }
        query()
    }

    /** Согласование ключа сессии для 3.4. */
    private fun negotiate() {
        val localNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        writeRaw(TuyaProtocol.negotiateStart(nextSeq(), realKey, localNonce))
        val resp = readFrame(realKey) ?: throw IllegalStateException("нет ответа на согласование ключа")
        val remote = TuyaProtocol.negotiateRemoteNonce(resp, realKey, localNonce)
            ?: throw IllegalStateException("ключ устройства не подошёл (обновите список устройств)")
        writeRaw(TuyaProtocol.negotiateFinish(nextSeq(), realKey, remote))
        key = TuyaProtocol.sessionKey(realKey, localNonce, remote)
    }

    suspend fun query() {
        val (cmd, body) = TuyaProtocol.queryCommand(target.version, target.id, now(), device22, target.dpIds)
        send(cmd, body)
    }

    /** Установить значения DP: номер -> значение. */
    suspend fun set(dps: Map<String, Any?>) {
        val (cmd, body) = TuyaProtocol.controlCommand(target.version, target.id, now(), dps)
        send(cmd, body)
    }

    private suspend fun send(cmd: Int, body: ByteArray) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            writeRaw(TuyaProtocol.encode(target.version, key, nextSeq(), cmd, body))
        }
    }

    private fun writeRaw(bytes: ByteArray) {
        val out = output ?: throw IllegalStateException("нет соединения")
        out.write(bytes)
        out.flush()
    }

    private fun readFrame(hmacKey: ByteArray?): TuyaProtocol.Frame? {
        val inp = input ?: return null
        val header = ByteArray(16)
        inp.readFully(header)
        val total = TuyaProtocol.frameLength(header)
        if (total < 0) throw IllegalStateException("неверный кадр от устройства")
        val frame = header + ByteArray(total - 16).also { inp.readFully(it) }
        return TuyaProtocol.unpack(frame, hmacKey)
    }

    private suspend fun readLoop() {
        var error: Throwable? = null
        try {
            while (scope.isActive && connected) {
                val frame = readFrame(if (TuyaProtocol.isV34(target.version)) key else null) ?: break
                if (frame.cmd == TuyaProtocol.HEART_BEAT) continue
                val text = TuyaProtocol.decodePayload(target.version, key, frame) ?: continue
                if (text.contains("data unvalid")) {
                    if (!device22) {
                        AppLog.i("Wi-Fi: ${target.name} — особый тип устройства, запрашиваю по списку DP")
                        device22 = true
                        query()
                    }
                    continue
                }
                val dps = extractDps(text) ?: continue
                if (dps.isNotEmpty()) onDps(dps)
            }
        } catch (e: Throwable) {
            error = e
        }
        if (connected) {
            close()
            onClosed(error)
        }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive && connected) {
            delay(HEARTBEAT_MS)
            try {
                val (cmd, body) = TuyaProtocol.heartbeatCommand(target.id)
                send(cmd, body)
            } catch (e: Exception) {
                break
            }
        }
    }

    fun close() {
        connected = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { socket?.close() }
        socket = null
    }

    private fun nextSeq() = seq++

    private fun now() = System.currentTimeMillis() / 1000

    companion object {
        const val PORT = 6668
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val HEARTBEAT_MS = 10_000L

        /** dps из ответа: {"dps":{...}} или (3.4) {"data":{"dps":{...}}}. */
        fun extractDps(text: String): Map<String, Any?>? = try {
            val json = JSONObject(text)
            val dps = json.optJSONObject("dps") ?: json.optJSONObject("data")?.optJSONObject("dps")
            dps?.keys()?.asSequence()?.associateWith { k ->
                val v = dps.opt(k)
                if (v == JSONObject.NULL) null else v
            }
        } catch (e: Exception) {
            null
        }
    }
}
