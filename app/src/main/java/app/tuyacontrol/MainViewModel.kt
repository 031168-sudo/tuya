package app.tuyacontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tuyacontrol.cloud.CloudDevice
import app.tuyacontrol.cloud.DpSpec
import app.tuyacontrol.cloud.TuyaApiException
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.data.Credentials
import app.tuyacontrol.data.CredentialsStore
import app.tuyacontrol.local.LocalAnnounce
import app.tuyacontrol.local.LocalDiscovery
import app.tuyacontrol.local.LocalManager
import app.tuyacontrol.local.LocalState
import app.tuyacontrol.local.LocalTarget
import app.tuyacontrol.data.DeviceCache
import app.tuyacontrol.data.Category
import app.tuyacontrol.data.CategoryStore
import app.tuyacontrol.data.DevicePref
import app.tuyacontrol.sensor.SensorChannel
import app.tuyacontrol.sensor.SensorDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class Screen { Setup, Devices, Log, Energy, Tariffs, Sensor, Categories, CategoryDevices, Local, Heating }

data class DeviceUi(
    val id: String,
    val name: String,
    val online: Boolean,
    val productName: String,
    val category: String,
    val status: Map<String, Any?>,
    val spec: Map<String, DpSpec>,
    /** Коды, по которым команда отправлена и ещё не подтверждена. */
    val pending: Set<String> = emptySet(),
    /** true — устройство работает через Things Data Model (v2.0 shadow). */
    val thingModel: Boolean = false,
    /** Время активации, секунды Unix. */
    val activeTime: Long = 0,
    /** Локальный ключ устройства (для управления по Wi-Fi); в интерфейсе не показывается. */
    val localKey: String = "",
    /** Когда устройство последний раз присылало данные, мс (0 — неизвестно). */
    val lastDataTime: Long = 0,
    /** Код DP -> номер DP для локального протокола. */
    val dpIds: Map<String, Int> = emptyMap(),
    /** Данные пришли по Wi-Fi напрямую от устройства. */
    val viaLocal: Boolean = false,
    /** Таймеры вкл/выкл в модуле конвектора Rubetek (null — не Rubetek или модуль без таймеров). */
    val moduleTimers: List<app.tuyacontrol.rubetek.ModuleTimer>? = null,
) {
    /**
     * Устройство в сети, но выключено главным переключателем — его показания могут не обновляться.
     * Термостаты «温控仪» (S1TW, батарея в ванной) включены всегда: их switch — реле нагрева.
     */
    val switchedOff: Boolean
        get() {
            if (!online || isSensor || productName.contains(ALWAYS_ON_PRODUCT)) return false
            val main = MAIN_SWITCHES.firstNotNullOfOrNull { status[it] as? Boolean }
            return main == false
        }

    /**
     * Датчик температуры/влажности: питается от блока питания, выключателя нет.
     * В сети — всегда включён; переключатели на его карточке не показываем.
     */
    val isSensor: Boolean
        get() = category == SENSOR_CATEGORY ||
            SENSOR_HINTS.any { productName.contains(it, ignoreCase = true) || name.contains(it, ignoreCase = true) }

    /** Температура/влажность для экрана истории датчика (null — у устройства таких DP нет). */
    val sensorDevice: SensorDevice?
        get() {
            fun channel(codes: List<String>) = codes.firstOrNull { it in status }?.let { code ->
                SensorChannel(code, spec[code]?.scale ?: 0, spec[code]?.unit.orEmpty())
            }
            // Датчик Siren Temperature and Humidity не пишет журнал в облако — истории у него нет
            if (NO_HISTORY_PRODUCTS.any { productName.contains(it, ignoreCase = true) }) return null
            val temperature = channel(SensorDevice.TEMPERATURE_CODES)
            val humidity = channel(SensorDevice.HUMIDITY_CODES)
            if (temperature == null && humidity == null) return null
            return SensorDevice(id, name, thingModel, temperature, humidity)
        }

    /** Иконка по умолчанию, если пользователь не выбрал свою. */
    val defaultIcon: String
        get() = when {
            isSensor -> "sensors"
            hasEnergy -> "electric_meter"
            "temp_set" in status || productName.contains("温控") -> "device_thermostat"
            "switch_led" in status || category == "dj" -> "lightbulb"
            category == "rubetek" || category == "cz" || productName.contains("socket", true) || productName.contains("plug", true) -> "outlet"
            sensorDevice != null -> "thermostat"
            else -> "devices_other"
        }

    /**
     * Греет ли обогреватель прямо сейчас (null — не обогреватель или неизвестно):
     * work_state / valve_state термостата; у «温控仪» и розеток-терморегуляторов — их реле (switch).
     */
    val heatingNow: Boolean?
        get() {
            if (!online || isSensor) return null
            val main = MAIN_SWITCHES.firstNotNullOfOrNull { status[it] as? Boolean }
            status["work_state"]?.let { v ->
                if (main == false) return false
                return when (v.toString().lowercase()) {
                    "1", "heating", "heat", "hot", "warming", "true", "open", "on" -> true
                    "0", "idle", "stop", "standby", "cold", "false", "close", "off", "manual" -> false
                    else -> null
                }
            }
            status["valve_state"]?.let { v ->
                if (main == false) return false
                return v == true || v.toString().lowercase() in setOf("open", "1", "true", "on")
            }
            if (productName.contains(ALWAYS_ON_PRODUCT) || "heating_temp_start" in status) return main
            return null
        }

    /** Переключатель этого устройства — не «питание», а реле нагрева: показывается как «греет / не греет». */
    val switchIsRelay: Boolean
        get() = productName.contains(ALWAYS_ON_PRODUCT)

    /** Код уставки температуры: temp_set или похожий записываемый DP («set_temp» и т.п.). */
    val setpointCode: String?
        get() {
            if ("temp_set" in status || "temp_set" in spec) return "temp_set"
            // Терморегулятор S1TW (батарея в ванной): уставкой служит порог выключения нагрева
            if ("heating_temp_stop" in status && "heating_temp_start" in status) return "heating_temp_stop"
            return (status.keys + spec.keys).firstOrNull { c ->
                val s = spec[c]
                s != null && s.writable && s.type == "Integer" && "temp" in c && "set" in c
            }
        }

    /** Счётчик для расчёта расходов: только выключатели-электросчётчики «智美WiFi开关电表». */
    val hasEnergy: Boolean
        get() = productName.contains(ENERGY_PRODUCT, ignoreCase = true)

    companion object {
        const val ENERGY_PRODUCT = "WiFi开关电表"
        const val ALWAYS_ON_PRODUCT = "温控仪"
        /** Устройства без истории показаний в облаке: у них нет экрана графиков. */
        val NO_HISTORY_PRODUCTS = listOf("Siren Temperature")
        /** Категория Tuya «датчик температуры и влажности». */
        const val SENSOR_CATEGORY = "wsdcg"
        val SENSOR_HINTS = listOf("Temperature and Humidity", "Датчик температуры")
        val MAIN_SWITCHES = listOf("switch", "switch_1", "power")
    }
}

/** Как управлять устройствами. */
enum class ControlMode(val title: String) {
    /** По Wi-Fi, если устройство доступно в сети, иначе через облако. */
    AUTO("Авто"),
    /** Только напрямую по Wi-Fi, без интернета. */
    LOCAL("Wi-Fi"),
    /** Только через облако Tuya. */
    CLOUD("Облако"),
}

data class UiState(
    val screen: Screen = Screen.Setup,
    val credentials: Credentials? = null,
    val devices: List<DeviceUi> = emptyList(),
    val loading: Boolean = false,
    val setupInProgress: Boolean = false,
    val setupError: String? = null,
    val message: String? = null,
    val lastUpdated: Long? = null,
    val categories: List<Category> = emptyList(),
    val devicePrefs: Map<String, DevicePref> = emptyMap(),
    /** Результат поиска в локальной сети: id устройства -> объявление. */
    val localFound: Map<String, LocalAnnounce> = emptyMap(),
    val localScanning: Boolean = false,
    val localScannedAt: Long? = null,
    /** Куда вернуться из графиков/энергии: список устройств или категория, откуда открыли. */
    val returnTo: Screen? = null,
    /** Открытая категория (экран CategoryDevices). */
    val categoryId: String? = null,
    val mode: ControlMode = ControlMode.AUTO,
    /** Состояние локальных подключений по устройствам. */
    val local: Map<String, LocalState> = emptyMap(),
    /** Rubetek: логин подключённого аккаунта (null — не подключён). */
    val rubetekLogin: String? = null,
    /** Код отправлен на этот телефон/почту, ждём ввода. */
    val rubetekCodeSentTo: String? = null,
    /** Как придёт код: звонок, SMS или письмо. */
    val rubetekCodeHint: String? = null,
    val rubetekBusy: Boolean = false,
    val rubetekError: String? = null,
    val rubetekCount: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialsStore(application)
    private val categoryStore = CategoryStore(application)
    private var client: TuyaCloudClient? = null
    private val specCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, DpSpec>>()
    private val thingModelDevices: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var autoRefreshJob: Job? = null
    private val deviceCache = DeviceCache(application)
    private val settings = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    private val localManager = LocalManager(viewModelScope)
    private val dpIdCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()
    /** Устройства по данным облака (или кэша); на экран идут с наложением локальных данных. */
    private var baseDevices: List<DeviceUi> = emptyList()
    private var foreground = false
    private var localStartJob: Job? = null
    private var codesLogged = false
    private val rubetekStore = app.tuyacontrol.rubetek.RubetekStore(application)
    private val rubetek = app.tuyacontrol.rubetek.RubetekClient(rubetekStore)
    /** Устройства Rubetek (только облако), показываются вместе с Tuya. */
    private var rubetekDevices: List<DeviceUi> = emptyList()
    private var rubetekJob: Job? = null
    private var rubetekStateLogged = false

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val mode = runCatching { ControlMode.valueOf(settings.getString("mode", "AUTO")!!) }.getOrDefault(ControlMode.AUTO)
        _state.update {
            it.copy(
                categories = categoryStore.categories(),
                devicePrefs = categoryStore.devicePrefs(),
                mode = mode,
                rubetekLogin = if (rubetek.connected) rubetekStore.login ?: "" else null,
            )
        }
        // Локальные данные сразу накладываем на список устройств
        viewModelScope.launch {
            localManager.states.collect { local ->
                _state.update { it.copy(local = local) }
                publish()
            }
        }
        val saved = store.load()
        if (saved != null) {
            client = TuyaCloudClient(saved)
            // Последний известный список — сразу на экран (и для работы без интернета)
            baseDevices = deviceCache.load().sortedBy { it.name.lowercase() }
            baseDevices.forEach { d -> if (d.dpIds.isNotEmpty()) dpIdCache[d.id] = d.dpIds }
            _state.update { it.copy(screen = Screen.Categories, credentials = saved) }
            publish()
            refresh()
        }
    }

    // ---------- Режим управления: Авто / Wi-Fi / Облако ----------

    fun setMode(mode: ControlMode) {
        settings.edit().putString("mode", mode.name).apply()
        _state.update { it.copy(mode = mode) }
        AppLog.i("Режим управления: ${mode.title}")
        if (mode == ControlMode.CLOUD) localManager.stopAll() else ensureLocal()
        publish()
        if (mode != ControlMode.LOCAL && baseDevices.isEmpty()) refresh()
    }

    /** Список для экрана: данные облака + свежие данные по Wi-Fi. */
    private fun publish() {
        val s = _state.value
        val shown = baseDevices.map { d ->
            val ls = s.local[d.id]
            when {
                s.mode != ControlMode.CLOUD && ls != null && ls.connected -> {
                    val byNumber = d.dpIds.entries.associate { (code, n) -> n.toString() to code }
                    val mapped = ls.dps.mapNotNull { (n, v) -> byNumber[n]?.let { it to v } }.toMap()
                    d.copy(
                        status = d.status + mapped,
                        online = true,
                        viaLocal = true,
                        lastDataTime = maxOf(d.lastDataTime, ls.updatedAt),
                    )
                }
                // Только Wi-Fi: что не подключено по сети — недоступно
                s.mode == ControlMode.LOCAL -> d.copy(online = false, viaLocal = false)
                else -> d
            }
        }
        // Rubetek работает только через своё облако
        val rubetekShown = if (s.mode == ControlMode.LOCAL) rubetekDevices.map { it.copy(online = false) } else rubetekDevices
        val all = if (rubetekShown.isEmpty()) shown else (shown + rubetekShown).sortedBy { it.name.lowercase() }
        _state.update { it.copy(devices = all) }
    }

    /** Поиск в сети (если давно не искали) и подключение ко всем найденным устройствам с ключом. */
    private fun ensureLocal(forceScan: Boolean = false) {
        if (!foreground || _state.value.mode == ControlMode.CLOUD) {
            localManager.stopAll()
            return
        }
        if (localStartJob?.isActive == true) return
        localStartJob = viewModelScope.launch {
            val s = _state.value
            val stale = s.localScannedAt == null || System.currentTimeMillis() - s.localScannedAt > 10 * 60_000L
            val found = if (forceScan || stale || s.localFound.isEmpty()) doScan() else s.localFound
            val targets = baseDevices.mapNotNull { d ->
                val a = found[d.id] ?: return@mapNotNull null
                if (d.localKey.length != 16) return@mapNotNull null
                LocalTarget(d.id, d.name, a.ip, a.version, d.localKey, d.dpIds.values.sorted())
            }
            AppLog.i("Wi-Fi: подключаюсь к ${targets.size} устройствам")
            localManager.start(targets)
        }
    }

    // ---------- Навигация ----------

    fun open(screen: Screen) = _state.update {
        // Графики и энергию открываем «поверх» списка — запоминаем, куда вернуться
        val from = if (screen == Screen.Sensor || screen == Screen.Energy) {
            it.screen.takeIf { s -> s == Screen.Devices || s == Screen.CategoryDevices } ?: it.returnTo
        } else {
            it.returnTo
        }
        it.copy(screen = screen, setupError = null, returnTo = from)
    }

    fun back(): Boolean {
        val s = _state.value
        return if (s.screen == Screen.Tariffs) {
            open(Screen.Energy); true
        } else if ((s.screen == Screen.Sensor || s.screen == Screen.Energy) && s.returnTo != null) {
            // Обратно туда, откуда открыли: в список устройств или в ту же категорию
            _state.update { it.copy(screen = s.returnTo, returnTo = null) }; true
        } else if (s.screen == Screen.CategoryDevices) {
            open(Screen.Categories); true
        } else if (s.screen != Screen.Categories && s.credentials != null) {
            // Главный экран — категории
            open(Screen.Categories); true
        } else {
            false
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    // ---------- Локальная сеть ----------

    fun scanLocal() {
        if (_state.value.localScanning) return
        viewModelScope.launch {
            doScan()
            ensureLocal()
        }
    }

    private suspend fun doScan(): Map<String, LocalAnnounce> {
        _state.update { it.copy(localScanning = true) }
        val found = runCatching { LocalDiscovery.scan(getApplication<android.app.Application>()) }
            .onFailure { AppLog.e("Поиск в локальной сети", it) }
            .getOrDefault(emptyMap())
        _state.update {
            it.copy(localFound = found, localScanning = false, localScannedAt = System.currentTimeMillis())
        }
        return found
    }

    // ---------- Категории и иконки ----------

    fun openCategory(id: String) = _state.update { it.copy(screen = Screen.CategoryDevices, categoryId = id) }

    fun saveCategory(category: Category) {
        val list = _state.value.categories
        val updated = if (list.any { it.id == category.id }) {
            list.map { if (it.id == category.id) category else it }
        } else {
            list + category
        }
        categoryStore.saveCategories(updated)
        _state.update { it.copy(categories = updated) }
    }

    /** Удаление категории: её устройства остаются без категории. */
    fun deleteCategory(id: String) {
        val updated = _state.value.categories.filter { it.id != id }
        val prefs = _state.value.devicePrefs.mapValues { (_, p) ->
            if (p.categoryId == id) p.copy(categoryId = null) else p
        }
        categoryStore.saveCategories(updated)
        categoryStore.saveDevicePrefs(prefs)
        _state.update {
            it.copy(
                categories = updated,
                devicePrefs = prefs,
                screen = if (it.categoryId == id && it.screen == Screen.CategoryDevices) Screen.Categories else it.screen,
            )
        }
    }

    fun setDevicePref(deviceId: String, pref: DevicePref) {
        val prefs = _state.value.devicePrefs + (deviceId to pref)
        categoryStore.saveDevicePrefs(prefs)
        _state.update { it.copy(devicePrefs = prefs) }
    }

    // ---------- Настройка ключей ----------

    fun saveCredentials(accessId: String, accessSecret: String, endpoint: String) {
        val creds = Credentials(accessId.trim(), accessSecret.trim(), endpoint)
        if (creds.accessId.isEmpty() || creds.accessSecret.isEmpty()) {
            _state.update { it.copy(setupError = "Заполните Access ID и Access Secret") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(setupInProgress = true, setupError = null) }
            val newClient = TuyaCloudClient(creds)
            try {
                newClient.checkCredentials()
                store.save(creds)
                client = newClient
                specCache.clear()
        thingModelDevices.clear()
                _state.update {
                    it.copy(
                        setupInProgress = false,
                        credentials = creds,
                        screen = Screen.Categories,
                        devices = emptyList(),
                    )
                }
                refresh()
            } catch (e: Exception) {
                AppLog.e("Проверка ключей не прошла", e)
                _state.update { it.copy(setupInProgress = false, setupError = describe(e)) }
            }
        }
    }

    fun clearCredentials() {
        store.clear()
        localManager.stopAll()
        deviceCache.clear()
        baseDevices = emptyList()
        client = null
        specCache.clear()
        thingModelDevices.clear()
        _state.value = UiState(
            screen = Screen.Setup,
            categories = _state.value.categories,
            devicePrefs = _state.value.devicePrefs,
        )
    }

    // ---------- Устройства ----------

    fun refresh(silent: Boolean = false) {
        // Режим «только Wi-Fi»: облако не трогаем, обновляем локальные данные
        if (_state.value.mode == ControlMode.LOCAL) {
            if (!silent) viewModelScope.launch { localManager.queryAll() }
            ensureLocal(forceScan = !silent && _state.value.local.isEmpty())
            return
        }
        refreshRubetek(silent)
        val c = client ?: return
        if (_state.value.loading) return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            try {
                val cloudDevices = c.listDevices()
                AppLog.i("Устройств: ${cloudDevices.size}")
                val devices = loadDetails(c, cloudDevices)
                baseDevices = devices.sortedBy { d -> d.name.lowercase() }
                if (!codesLogged) {
                    // Один раз за запуск: какие DP есть у каждого устройства (для разбора новых моделей)
                    codesLogged = true
                    devices.forEach { d ->
                        AppLog.i("DP «${d.name}» (${d.productName}): " + d.status.entries.joinToString { "${it.key}=${it.value}" } +
                            " | уставка=${d.setpointCode} греет=${d.heatingNow}")
                    }
                }
                deviceCache.save(baseDevices)
                _state.update { it.copy(loading = false, lastUpdated = System.currentTimeMillis()) }
                publish()
                ensureLocal()
            } catch (e: Exception) {
                AppLog.e("Не удалось обновить список", e)
                _state.update { it.copy(loading = false, message = describe(e)) }
            }
        }
    }

    private suspend fun loadDetails(c: TuyaCloudClient, list: List<CloudDevice>): List<DeviceUi> =
        coroutineScope {
            val limit = Semaphore(4)
            list.map { d ->
                async {
                    limit.withPermit {
                        var status = d.status?.takeIf { it.isNotEmpty() }
                            ?: runCatching { c.getStatus(d.id) }
                                .onFailure { AppLog.e("Статус ${d.name}", it) }
                                .getOrDefault(emptyMap())
                        var useThingModel = d.id in thingModelDevices

                        // Нет стандартного набора команд -> берём DP через Things Data Model
                        if (status.isEmpty() || useThingModel) {
                            val shadow = runCatching { c.getShadowProperties(d.id) }
                                .onFailure { AppLog.e("Shadow ${d.name}", it) }
                                .getOrDefault(emptyMap())
                            if (shadow.isNotEmpty()) {
                                if (!useThingModel) AppLog.i("${d.name}: использую Things Data Model (${shadow.size} DP)")
                                status = shadow
                                useThingModel = true
                                thingModelDevices += d.id
                            }
                        }

                        // Термостаты «Temp»: в стандартном статусе нет work_state (греет / не греет),
                        // он есть только в shadow — дополняем недостающими DP
                        if (!useThingModel && d.category == "wk" && "work_state" !in status) {
                            val shadow = runCatching { c.getShadowProperties(d.id) }.getOrDefault(emptyMap())
                            val extra = shadow.filterKeys { it !in status }
                            if (extra.isNotEmpty()) status = status + extra
                        }

                        val spec = specCache[d.id] ?: runCatching {
                            if (useThingModel) c.getThingModel(d.id) else c.getSpecification(d.id)
                        }
                            .onFailure { AppLog.e("Спецификация ${d.name}", it) }
                            .getOrNull()
                            ?.also { specCache[d.id] = it }
                            .orEmpty()
                        DeviceUi(
                            id = d.id,
                            name = d.name,
                            online = d.online,
                            productName = d.productName,
                            category = d.category,
                            status = status,
                            spec = spec,
                            thingModel = useThingModel,
                            activeTime = d.activeTime,
                            lastDataTime = lastDataTime(c, d),
                            localKey = d.localKey,
                            dpIds = dpIds(c, d, spec),
                        )
                    }
                }
            }.awaitAll()
        }

    /** Номера DP для локального протокола: из модели устройства (один раз, потом из кэша). */
    private suspend fun dpIds(c: TuyaCloudClient, d: CloudDevice, spec: Map<String, DpSpec>): Map<String, Int> {
        dpIdCache[d.id]?.let { return it }
        if (d.localKey.isEmpty()) return emptyMap()
        val fromSpec = spec.mapNotNull { (code, s) -> s.dpId?.let { code to it } }.toMap()
        val ids = fromSpec.ifEmpty {
            runCatching { c.getThingModel(d.id) }
                .onFailure { AppLog.e("Номера DP ${d.name}", it) }
                .getOrNull()
                ?.mapNotNull { (code, s) -> s.dpId?.let { code to it } }?.toMap()
                .orEmpty()
        }
        if (ids.isNotEmpty()) dpIdCache[d.id] = ids
        return ids
    }

    /** Когда устройство последний раз присылало данные: shadow, иначе время обновления в облаке. */
    private suspend fun lastDataTime(c: TuyaCloudClient, d: CloudDevice): Long =
        runCatching { c.getLastReportTime(d.id) }.getOrDefault(0L).takeIf { it > 0 } ?: d.updateTime

    /** Отправка одной команды, например switch_1 = true или temp_set = 220. */
    fun sendCommand(deviceId: String, code: String, value: Any) {
        val before = _state.value.devices.find { it.id == deviceId } ?: return
        if (app.tuyacontrol.rubetek.RubetekMapper.isRubetek(deviceId)) {
            sendRubetek(before, code, value)
            return
        }
        val oldValue = before.status[code]
        val mode = _state.value.mode
        val dpId = before.dpIds[code]
        val local = mode != ControlMode.CLOUD && dpId != null && localManager.isConnected(deviceId)

        if (!local && mode == ControlMode.LOCAL) {
            _state.update { it.copy(message = "${before.name}: нет связи по Wi-Fi") }
            return
        }

        // Оптимистично показываем новое значение
        updateDevice(deviceId) { it.copy(status = it.status + (code to value), pending = it.pending + code) }

        if (local) {
            viewModelScope.launch {
                if (localManager.send(deviceId, mapOf(dpId.toString() to value))) {
                    AppLog.i("Wi-Fi: ${before.name} $code=$value")
                    // Устройство само пришлёт новое состояние; снимаем «ожидание»
                    delay(1200)
                    updateDevice(deviceId) { it.copy(pending = it.pending - code) }
                } else if (mode == ControlMode.LOCAL) {
                    updateDevice(deviceId) { it.copy(status = it.status + (code to oldValue), pending = it.pending - code) }
                    _state.update { it.copy(message = "${before.name}: команда по Wi-Fi не прошла") }
                } else {
                    AppLog.i("Wi-Fi не ответил, отправляю ${before.name} через облако")
                    sendViaCloud(before, code, value, oldValue)
                }
            }
            return
        }
        viewModelScope.launch { sendViaCloud(before, code, value, oldValue) }
    }

    private suspend fun sendViaCloud(before: DeviceUi, code: String, value: Any, oldValue: Any?) {
        val deviceId = before.id
        val c = client ?: run {
            updateDevice(deviceId) { it.copy(status = it.status + (code to oldValue), pending = it.pending - code) }
            return
        }
        run {
            try {
                if (before.thingModel) {
                    c.sendProperties(deviceId, listOf(code to value))
                } else {
                    c.sendCommands(deviceId, listOf(code to value))
                }
                delay(1500)
                val fresh = runCatching {
                    if (before.thingModel) c.getShadowProperties(deviceId) else c.getStatus(deviceId)
                }.getOrNull()?.takeIf { it.isNotEmpty() }
                updateDevice(deviceId) {
                    it.copy(status = fresh ?: it.status, pending = it.pending - code)
                }
            } catch (e: Exception) {
                AppLog.e("Команда $code=$value для ${before.name}", e)
                updateDevice(deviceId) {
                    it.copy(status = it.status + (code to oldValue), pending = it.pending - code)
                }
                _state.update { it.copy(message = "${before.name}: ${describe(e)}") }
            }
        }
    }

    private fun updateDevice(id: String, transform: (DeviceUi) -> DeviceUi) {
        if (app.tuyacontrol.rubetek.RubetekMapper.isRubetek(id)) {
            rubetekDevices = rubetekDevices.map { if (it.id == id) transform(it) else it }
        } else {
            baseDevices = baseDevices.map { if (it.id == id) transform(it) else it }
        }
        publish()
    }

    // ---------- Rubetek ----------

    private fun refreshRubetek(silent: Boolean) {
        if (!rubetek.connected || rubetekJob?.isActive == true) return
        rubetekJob = viewModelScope.launch {
            try {
                val houses = rubetek.houses()
                val list = mutableListOf<DeviceUi>()
                for (h in houses) {
                    val hid = h.optString("id")
                    if (hid.isEmpty()) continue
                    if (!h.isNull("deleted_at") && h.optString("deleted_at").isNotEmpty()) continue
                    val raw = rubetek.devices(hid)
                    if (!rubetekStateLogged) {
                        // Один раз за запуск — полное состояние каждого устройства, чтобы разобрать новые типы
                        rubetekStateLogged = true
                        raw.forEach { d ->
                            AppLog.i(
                                "Rubetek: «${d.optString("name")}» type=${d.optString("type")} " +
                                    "custom=${d.optJSONObject("custom_data")} state=${d.optJSONObject("state")}"
                            )
                        }
                    }
                    raw.mapNotNullTo(list) {
                        app.tuyacontrol.rubetek.RubetekMapper.toDevice(hid, h.optString("name"), houses.size > 1, it)
                    }
                }
                // Не затираем значения, по которым ещё ждём подтверждения команды
                val pending = rubetekDevices.associateBy { it.id }
                rubetekDevices = list.map { d ->
                    val old = pending[d.id]
                    if (old != null && old.pending.isNotEmpty()) d.copy(status = d.status + old.status.filterKeys { it in old.pending }, pending = old.pending) else d
                }
                AppLog.i("Rubetek: устройств ${list.size}")
                app.tuyacontrol.rubetek.RubetekHistory.record(getApplication<android.app.Application>(), list)
                _state.update { it.copy(rubetekCount = list.size, rubetekError = null) }
                publish()
            } catch (e: Exception) {
                AppLog.e("Rubetek: список не обновлён", e)
                val msg = describeRubetek(e)
                _state.update { it.copy(rubetekError = msg, message = if (silent) it.message else "Rubetek: $msg") }
            }
        }
    }

    private fun sendRubetek(before: DeviceUi, code: String, value: Any) {
        val (house, device) = app.tuyacontrol.rubetek.RubetekMapper.parseId(before.id) ?: return
        val state = app.tuyacontrol.rubetek.RubetekMapper.toState(code, value) ?: run {
            _state.update { it.copy(message = "${before.name}: эта команда для Rubetek не поддерживается") }
            return
        }
        if (_state.value.mode == ControlMode.LOCAL) {
            _state.update { it.copy(message = "${before.name}: Rubetek управляется только через облако") }
            return
        }
        val oldValue = before.status[code]
        updateDevice(before.id) { it.copy(status = it.status + (code to value), pending = it.pending + code) }
        viewModelScope.launch {
            try {
                rubetek.setState(house, device, state)
                AppLog.i("Rubetek: ${before.name} $code=$value")
                delay(1200)
                updateDevice(before.id) { it.copy(pending = it.pending - code) }
                delay(1500)
                refreshRubetek(silent = true)
            } catch (e: Exception) {
                AppLog.e("Rubetek: команда $code для ${before.name}", e)
                updateDevice(before.id) { it.copy(status = it.status + (code to oldValue), pending = it.pending - code) }
                _state.update { it.copy(message = "${before.name}: ${describeRubetek(e)}") }
            }
        }
    }

    fun rubetekSendCode(login: String) {
        val l = login.trim()
        if (l.isEmpty()) {
            _state.update { it.copy(rubetekError = "Введите телефон или почту") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(rubetekBusy = true, rubetekError = null) }
            try {
                val method = rubetek.sendCode(l)
                val how = when (method) {
                    "email" -> "Код отправлен на $l — проверьте почту"
                    "sms" -> "Ждите звонка или SMS на $l. При звонке код — последние 4 цифры номера"
                    else -> "Сейчас на $l позвонят: код — последние 4 цифры номера, с которого звонят"
                }
                _state.update { it.copy(rubetekBusy = false, rubetekCodeSentTo = l, rubetekCodeHint = how) }
            } catch (e: Exception) {
                AppLog.e("Rubetek: код не запрошен", e)
                _state.update { it.copy(rubetekBusy = false, rubetekError = describeRubetek(e)) }
            }
        }
    }

    fun rubetekSignIn(code: String) {
        val login = _state.value.rubetekCodeSentTo ?: return
        if (code.trim().length < 4) {
            _state.update { it.copy(rubetekError = "Введите код из сообщения") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(rubetekBusy = true, rubetekError = null) }
            try {
                rubetek.signIn(login, code)
                _state.update { it.copy(rubetekBusy = false, rubetekCodeSentTo = null, rubetekLogin = login) }
                app.tuyacontrol.rubetek.RubetekHistory.schedule(getApplication<android.app.Application>())
                refreshRubetek(silent = false)
            } catch (e: Exception) {
                AppLog.e("Rubetek: вход не удался", e)
                _state.update { it.copy(rubetekBusy = false, rubetekError = describeRubetek(e)) }
            }
        }
    }

    fun rubetekCancelCode() = _state.update { it.copy(rubetekCodeSentTo = null, rubetekError = null) }

    fun rubetekSignOut() {
        rubetekJob?.cancel()
        rubetek.signOut()
        app.tuyacontrol.rubetek.RubetekHistory.cancel(getApplication<android.app.Application>())
        rubetekDevices = emptyList()
        _state.update { it.copy(rubetekLogin = null, rubetekCount = 0, rubetekError = null) }
        publish()
    }

    private fun describeRubetek(e: Throwable): String = when (e) {
        is app.tuyacontrol.rubetek.RubetekException -> when (e.status) {
            401 -> "вход устарел — подключите Rubetek заново (${e.message})"
            400, 422 -> "неверный код или логин (${e.message})"
            429 -> "слишком часто — подождите и попробуйте позже"
            else -> "ошибка ${e.status}: ${e.message}"
        }
        is java.net.UnknownHostException -> "нет подключения к интернету"
        is java.net.SocketTimeoutException -> "Rubetek не отвечает (таймаут)"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---------- Автообновление, пока приложение на экране ----------

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        // Соединения по Wi-Fi держим, только пока приложение на экране
        if (foreground) ensureLocal() else localManager.stopAll()
        if (!foreground || client == null) return
        autoRefreshJob = viewModelScope.launch {
            // Сразу обновляем при возврате в приложение, если данные старше 30 секунд
            val last = _state.value.lastUpdated ?: 0L
            if (System.currentTimeMillis() - last > 30_000) refresh(silent = true)
            while (isActive) {
                delay(AUTO_REFRESH_MS)
                if (_state.value.screen in setOf(Screen.Devices, Screen.Categories, Screen.CategoryDevices)) refresh(silent = true)
            }
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is TuyaApiException -> when (e.code) {
            1004 -> "Неверная подпись: проверьте Access Secret (1004)"
            1005, 2009 -> "Неверный Access ID (${e.code})"
            1106, 2017 -> "Нет прав: проверьте привязку аккаунта и дата-центр (${e.code})"
            1100, 1101 -> "Неверные параметры запроса (${e.code}): ${e.message}"
            28841002 -> "Истёк пробный период IoT Core — продлите в консоли Tuya (${e.code})"
            else -> "Ошибка Tuya ${e.code}: ${e.message}"
        }
        is java.net.UnknownHostException -> "Нет подключения к интернету"
        is java.net.SocketTimeoutException -> "Tuya не отвечает (таймаут)"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val AUTO_REFRESH_MS = 60_000L
    }
}
