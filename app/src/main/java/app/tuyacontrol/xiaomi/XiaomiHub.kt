package app.tuyacontrol.xiaomi

import android.content.Context
import app.tuyacontrol.ControlMode
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.data.AppLog
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Устройства Xiaomi: список и token — из облака Mi Home (один раз и потом изредка), показания и команды —
 * по Wi-Fi напрямую (miIO). Если по Wi-Fi устройство не отвечает, в режиме «Авто» — через облако.
 */
class XiaomiHub(context: Context) {
    val store = XiaomiStore(context)
    val cloud = XiaomiCloud(store)
    private val spec = MiotSpec(context)

    private val connections = ConcurrentHashMap<String, MiioDevice>()
    private val lastValues = ConcurrentHashMap<String, Pair<Long, Map<Pair<Int, Int>, Any?>>>()
    private val discovered = ConcurrentHashMap<String, String>()
    private val scanMutex = Mutex()
    private var scanAt = 0L
    private var listAt = 0L
    private var listLogged = false

    val connected: Boolean get() = cloud.connected

    /** Обновить показания всех устройств. reloadList — заново взять список из облака. */
    suspend fun poll(mode: ControlMode, reloadList: Boolean, allowListRefresh: Boolean = true): List<DeviceUi> {
        var inventory = store.devices
        val stale = allowListRefresh && System.currentTimeMillis() - listAt > LIST_TTL_MS
        if (cloud.connected && mode != ControlMode.LOCAL && (inventory.isEmpty() || reloadList || stale)) {
            val fresh = runCatching { cloud.devices() }
                .onFailure { AppLog.e("Xiaomi: список устройств не обновлён", it) }
                .getOrElse { if (inventory.isEmpty()) throw it else null }
            if (fresh != null) {
                val old = inventory.associateBy { it.did }
                inventory = fresh.map { n -> if (n.ip.isEmpty()) n.copy(ip = old[n.did]?.ip.orEmpty()) else n }
                store.devices = inventory
                listAt = System.currentTimeMillis()
                listLogged = false
            }
        }
        if (!listLogged) {
            listLogged = true
            inventory.forEach {
                AppLog.i("Xiaomi: «${it.name}» ${it.model} did=${it.did} ip=${it.ip.ifEmpty { "—" }} " +
                    if (it.token.isEmpty()) "без Wi-Fi token — не показываю" else "token есть")
            }
        }
        return coroutineScope {
            inventory.filter { it.token.isNotEmpty() }.map { d -> async { read(d, mode) } }.awaitAll()
        }
    }

    /** Показания одного устройства: Wi-Fi, потом облако; не вышло — последние известные, «не в сети». */
    private suspend fun read(d: MiDevice, mode: ControlMode): DeviceUi {
        val props = spec.props(d.model).filter { it.readable }
        val keys = props.map { it.siid to it.piid }
        if (keys.isEmpty()) {
            AppLog.i("Xiaomi: для ${d.model} нет описания свойств — показываю без показаний")
            return XiaomiMapper.toDevice(d, props, emptyMap(), d.online, false, 0)
        }
        if (mode != ControlMode.CLOUD) {
            local(d) { it.getProperties(d.did, keys) }?.takeIf { it.isNotEmpty() }?.let { v ->
                lastValues[d.did] = System.currentTimeMillis() to v
                return XiaomiMapper.toDevice(d, props, v, online = true, viaLocal = true, dataTime = System.currentTimeMillis())
            }
        }
        if (mode != ControlMode.LOCAL && cloud.connected) {
            runCatching { cloud.getProperties(d.did, keys) }
                .onFailure { AppLog.e("Xiaomi: «${d.name}» через облако", it) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { v ->
                    lastValues[d.did] = System.currentTimeMillis() to v
                    return XiaomiMapper.toDevice(d, props, v, online = true, viaLocal = false, dataTime = System.currentTimeMillis())
                }
        }
        val last = lastValues[d.did]
        return XiaomiMapper.toDevice(d, props, last?.second.orEmpty(), online = false, viaLocal = false, dataTime = last?.first ?: 0)
    }

    /** Изменить свойство. Возвращает, каким путём ушла команда. */
    suspend fun send(id: String, code: String, value: Any, mode: ControlMode): String {
        val did = XiaomiMapper.did(id)
        val d = store.devices.firstOrNull { it.did == did } ?: throw XiaomiException("устройство не найдено — обновите список")
        val p = spec.props(d.model).firstOrNull { it.code == code } ?: throw XiaomiException("неизвестное свойство $code")
        val v = XiaomiMapper.toMiot(p, value) ?: throw XiaomiException("«${p.label.ifEmpty { code }}» менять нельзя")
        if (mode != ControlMode.CLOUD) {
            if (local(d) { it.setProperty(d.did, p.siid, p.piid, v) } != null) return "Wi-Fi"
            if (mode == ControlMode.LOCAL) throw XiaomiException("нет связи по Wi-Fi")
        }
        if (!cloud.connected) throw XiaomiException("нет связи по Wi-Fi, а облако Xiaomi не подключено")
        cloud.setProperty(d.did, p.siid, p.piid, v)
        return "облако"
    }

    /** Свежие показания одного устройства (после команды). */
    suspend fun readOne(id: String, mode: ControlMode): DeviceUi? {
        val d = store.devices.firstOrNull { it.did == XiaomiMapper.did(id) } ?: return null
        return read(d, mode)
    }

    /**
     * Действие по Wi-Fi. Не ответило по известному адресу — ищем устройство в сети (адрес мог смениться)
     * и пробуем ещё раз. null — по Wi-Fi не получилось.
     */
    private suspend fun <T> local(d: MiDevice, action: suspend (MiioDevice) -> T): T? {
        if (d.ip.isNotEmpty()) {
            runCatching { action(conn(d, d.ip)) }
                .onSuccess { return it }
                .onFailure { AppLog.i("Xiaomi: «${d.name}» по Wi-Fi (${d.ip}) — ${it.message}") }
        }
        val ip = scanMutex.withLock {
            if (System.currentTimeMillis() - scanAt > SCAN_GAP_MS) {
                scanAt = System.currentTimeMillis()
                discovered.putAll(MiioDiscovery.scan())
            }
            discovered[d.did]
        }
        if (ip == null || ip == d.ip) return null
        AppLog.i("Xiaomi: «${d.name}» найден по новому адресу $ip")
        store.devices = store.devices.map { if (it.did == d.did) it.copy(ip = ip) else it }
        return runCatching { action(conn(d, ip)) }
            .onFailure { AppLog.i("Xiaomi: «${d.name}» по Wi-Fi ($ip) — ${it.message}") }
            .getOrNull()
    }

    /**
     * Отчёт для карточки Xiaomi: все параметры и разведка таймеров — по Wi-Fi (старые методы miIO) и в облаке Mi Home.
     * Только чтение: так мы выясняем, где у устройства хранится расписание.
     */
    suspend fun diagnostics(device: DeviceUi): String {
        val sb = StringBuilder()
        val d = store.devices.firstOrNull { it.did == XiaomiMapper.did(device.id) }
        sb.appendLine("${device.name} · ${device.id}")
        if (d != null) sb.appendLine("Модель: ${d.model}, адрес: ${d.ip.ifEmpty { "не известен" }}")
        sb.appendLine().appendLine("ВСЕ ПАРАМЕТРЫ")
        device.status.toSortedMap().forEach { (k, v) -> sb.appendLine("$k = $v") }
        if (d == null) return sb.toString().trimEnd()

        sb.appendLine().appendLine("ПО WI-FI (miIO)")
        for ((method, params) in listOf<Pair<String, Any>>(
            "miIO.info" to org.json.JSONArray(),
            "get_timer" to org.json.JSONArray(),
            "get_timer_list" to org.json.JSONArray(),
            "get_countdown" to org.json.JSONArray(),
        )) {
            val r = runCatching { local(d) { it.call(method, params) } }
            sb.appendLine(
                "$method → " + r.fold({ (it?.toString() ?: "нет ответа по Wi-Fi").take(500) }, { "ошибка: ${it.message}" })
                    // Токен устройства даёт управление им по сети — в отчёт (который копируют) не попадает
                    .replace(Regex("(\"token\"\\s*:\\s*\")[0-9a-fA-F]{32}"), "$1***"),
            )
        }

        sb.appendLine().appendLine("ОБЛАКО MI HOME (разведка адресов)")
        val did = d.did
        val probes = listOf(
            "/home/rpc/$did" to JSONObject().put("id", 1).put("method", "get_timer").put("params", org.json.JSONArray()),
            "/home/rpc/$did" to JSONObject().put("id", 1).put("method", "get_timer_list").put("params", org.json.JSONArray()),
            "/scene/list" to JSONObject().put("st_id", 30),
            "/v2/scene/list" to JSONObject().put("did", did),
            "/timer/list" to JSONObject().put("did", did),
            "/v2/timer/list" to JSONObject().put("did", did),
            "/device/timer/list" to JSONObject().put("did", did),
            "/v2/device/timers" to JSONObject().put("did", did),
        )
        for ((path, body) in probes) {
            val extra = body.optString("method").takeIf { it.isNotEmpty() }?.let { " [$it]" }.orEmpty()
            sb.appendLine("$path$extra → " + cloud.probe(path, body))
        }
        return sb.toString().trimEnd()
    }

    private fun conn(d: MiDevice, ip: String): MiioDevice {
        val c = connections[d.did]
        if (c != null && c.ip == ip) return c
        return MiioDevice(ip, d.token).also { connections[d.did] = it }
    }

    fun signOut() {
        cloud.signOut()
        connections.clear()
        lastValues.clear()
        listAt = 0
    }

    private companion object {
        const val LIST_TTL_MS = 6 * 3600_000L
        const val SCAN_GAP_MS = 5 * 60_000L
    }
}
