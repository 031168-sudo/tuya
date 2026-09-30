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

enum class Screen { Setup, Devices, Log, Energy, Tariffs }

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
) {
    /** Счётчик для расчёта расходов: только выключатели-электросчётчики «智美WiFi开关电表». */
    val hasEnergy: Boolean
        get() = productName.contains(ENERGY_PRODUCT, ignoreCase = true)

    companion object {
        const val ENERGY_PRODUCT = "WiFi开关电表"
    }
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialsStore(application)
    private var client: TuyaCloudClient? = null
    private val specCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, DpSpec>>()
    private val thingModelDevices: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var autoRefreshJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val saved = store.load()
        if (saved != null) {
            client = TuyaCloudClient(saved)
            _state.update { it.copy(screen = Screen.Devices, credentials = saved) }
            refresh()
        }
    }

    // ---------- Навигация ----------

    fun open(screen: Screen) = _state.update { it.copy(screen = screen, setupError = null) }

    fun back(): Boolean {
        val s = _state.value
        return if (s.screen == Screen.Tariffs) {
            open(Screen.Energy); true
        } else if (s.screen != Screen.Devices && s.credentials != null) {
            open(Screen.Devices); true
        } else {
            false
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

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
                        screen = Screen.Devices,
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
        client = null
        specCache.clear()
        thingModelDevices.clear()
        _state.value = UiState(screen = Screen.Setup)
    }

    // ---------- Устройства ----------

    fun refresh(silent: Boolean = false) {
        val c = client ?: return
        if (_state.value.loading) return
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            try {
                val cloudDevices = c.listDevices()
                AppLog.i("Устройств: ${cloudDevices.size}")
                val devices = loadDetails(c, cloudDevices)
                _state.update {
                    it.copy(
                        devices = devices.sortedBy { d -> d.name.lowercase() },
                        loading = false,
                        lastUpdated = System.currentTimeMillis(),
                    )
                }
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
                        )
                    }
                }
            }.awaitAll()
        }

    /** Отправка одной команды, например switch_1 = true или temp_set = 220. */
    fun sendCommand(deviceId: String, code: String, value: Any) {
        val c = client ?: return
        val before = _state.value.devices.find { it.id == deviceId } ?: return
        val oldValue = before.status[code]

        // Оптимистично показываем новое значение
        updateDevice(deviceId) { it.copy(status = it.status + (code to value), pending = it.pending + code) }

        viewModelScope.launch {
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
        _state.update { s -> s.copy(devices = s.devices.map { if (it.id == id) transform(it) else it }) }
    }

    // ---------- Автообновление, пока приложение на экране ----------

    fun setForeground(foreground: Boolean) {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        if (!foreground || client == null) return
        autoRefreshJob = viewModelScope.launch {
            // Сразу обновляем при возврате в приложение, если данные старше 30 секунд
            val last = _state.value.lastUpdated ?: 0L
            if (System.currentTimeMillis() - last > 30_000) refresh(silent = true)
            while (isActive) {
                delay(AUTO_REFRESH_MS)
                if (_state.value.screen == Screen.Devices) refresh(silent = true)
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
