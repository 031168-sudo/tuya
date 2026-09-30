package app.tuyacontrol.local

import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Снимок локальных данных устройства. */
data class LocalState(
    val connected: Boolean,
    /** Номер DP -> значение (всё, что устройство прислало за время подключения). */
    val dps: Map<String, Any?>,
    val updatedAt: Long,
    val error: String? = null,
)

/**
 * Держит соединения с устройствами в Wi-Fi сети, пока приложение на экране.
 * При обрыве переподключается с паузой (5 → 10 → 20 → 30 с).
 */
class LocalManager(private val scope: CoroutineScope) {

    private val _states = MutableStateFlow<Map<String, LocalState>>(emptyMap())
    val states: StateFlow<Map<String, LocalState>> = _states.asStateFlow()

    private val connections = ConcurrentHashMap<String, LocalConnection>()
    private val loops = ConcurrentHashMap<String, Job>()
    private val targets = ConcurrentHashMap<String, LocalTarget>()

    fun isConnected(id: String) = connections[id]?.connected == true

    /** Подключиться к указанным устройствам; лишние соединения закрыть. */
    fun start(list: List<LocalTarget>) {
        val ids = list.map { it.id }.toSet()
        (loops.keys - ids).forEach { stop(it) }
        for (t in list) {
            val old = targets[t.id]
            if (old == t && loops[t.id]?.isActive == true) continue
            stop(t.id)
            targets[t.id] = t
            loops[t.id] = scope.launch { keepConnected(t) }
        }
    }

    fun stopAll() {
        loops.keys.toList().forEach { stop(it) }
    }

    private fun stop(id: String) {
        loops.remove(id)?.cancel()
        connections.remove(id)?.close()
        targets.remove(id)
        _states.update { it - id }
    }

    private suspend fun keepConnected(t: LocalTarget) {
        var backoff = 5_000L
        while (scope.isActive) {
            val closed = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            val conn = LocalConnection(
                target = t,
                scope = scope,
                onDps = { dps -> merge(t.id, dps) },
                onClosed = { closed.complete(it) },
            )
            try {
                withTimeout(10_000) { conn.connect() }
                connections[t.id] = conn
                _states.update { it + (t.id to (it[t.id]?.copy(connected = true, error = null) ?: LocalState(true, emptyMap(), System.currentTimeMillis()))) }
                backoff = 5_000L
                val error = closed.await()
                AppLog.i("Wi-Fi: ${t.name} соединение закрыто" + (error?.let { ": ${it.message}" } ?: ""))
            } catch (e: Throwable) {
                if (!scope.isActive) return
                AppLog.e("Wi-Fi: ${t.name} не подключается", e)
                _states.update { it + (t.id to (it[t.id]?.copy(connected = false, error = e.message) ?: LocalState(false, emptyMap(), 0, e.message))) }
            } finally {
                conn.close()
                connections.remove(t.id)
                _states.update { m -> m[t.id]?.let { m + (t.id to it.copy(connected = false)) } ?: m }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun merge(id: String, dps: Map<String, Any?>) {
        _states.update { m ->
            val old = m[id] ?: LocalState(true, emptyMap(), 0)
            m + (id to old.copy(connected = true, dps = old.dps + dps, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Отправить команду локально. false — устройство не подключено или отправка не удалась. */
    suspend fun send(id: String, dps: Map<String, Any?>): Boolean {
        val conn = connections[id]?.takeIf { it.connected } ?: return false
        return try {
            conn.set(dps)
            true
        } catch (e: Exception) {
            AppLog.e("Wi-Fi: команда не отправлена", e)
            false
        }
    }

    /** Перезапросить состояние у всех подключённых. */
    suspend fun queryAll() {
        connections.values.filter { it.connected }.forEach { runCatching { it.query() } }
    }
}
