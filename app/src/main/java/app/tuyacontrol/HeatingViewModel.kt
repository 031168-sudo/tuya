package app.tuyacontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.CredentialsStore
import app.tuyacontrol.heating.HeatZone
import app.tuyacontrol.heating.HeatingEngine
import app.tuyacontrol.heating.HeatingPlanner
import app.tuyacontrol.heating.HeatingSettings
import app.tuyacontrol.heating.HourPrices
import app.tuyacontrol.heating.ZonePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Термостат, который можно назначить зоне. */
data class Thermostat(
    val id: String,
    val name: String,
    val online: Boolean,
    val current: Double?,
    val setpoint: Double?,
    val step: Double,
)

data class HeatingUiState(
    val settings: HeatingSettings = HeatingSettings.defaults(),
    val thermostats: List<Thermostat> = emptyList(),
    val prices: HourPrices = HourPrices.default(),
    val outdoor: DoubleArray = DoubleArray(48),
    val forecastFresh: Boolean = false,
    val plans: List<ZonePlan> = emptyList(),
    val computing: Boolean = false,
    val deploying: Boolean = false,
    val deployedAt: Long = 0,
    val deployResult: String? = null,
    val message: String? = null,
)

class HeatingViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = HeatingEngine(application)
    private val credentials = CredentialsStore(application)

    private val _state = MutableStateFlow(
        HeatingUiState(
            settings = engine.store.load(),
            deployedAt = engine.store.deployedAt,
            deployResult = engine.store.deployResult,
        )
    )
    val state: StateFlow<HeatingUiState> = _state.asStateFlow()

    private var computeJob: Job? = null
    private var loaded = false

    /** Термостаты приходят с главного экрана; пустым зонам подбираем устройство по названию. */
    fun setDevices(devices: List<DeviceUi>) {
        // Конвекторы Rubetek пока не в планировщике: расписание пишется в облако Tuya
        val list = devices.filter {
            ("temp_set" in it.status || "temp_set" in it.spec) && !app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it.id)
        }.map { d ->
            val cur = d.spec["temp_current"]
            val set = d.spec["temp_set"]
            Thermostat(
                id = d.id,
                name = d.name,
                online = d.online,
                current = (d.status["temp_current"] as? Number)?.toDouble()?.let { it / pow10(cur?.scale ?: 0) },
                setpoint = (d.status["temp_set"] as? Number)?.toDouble()?.let { it / pow10(set?.scale ?: 0) },
                step = HeatingEngine.stepOf(set),
            )
        }.sortedBy { it.name.lowercase() }
        var settings = _state.value.settings
        val zones = settings.zones.map { z ->
            if (z.deviceId != null) return@map z
            val hints = HeatingSettings.NAME_HINTS[z.id] ?: return@map z
            val match = list.firstOrNull { t -> hints.any { t.name.contains(it, ignoreCase = true) } }
            if (match != null) z.copy(deviceId = match.id) else z
        }
        if (zones != settings.zones) {
            settings = settings.copy(zones = zones)
            engine.store.save(settings)
        }
        val changed = list != _state.value.thermostats
        _state.update { it.copy(thermostats = list, settings = settings) }
        if (!loaded || changed) {
            loaded = true
            recompute()
        }
    }

    fun recompute() {
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            _state.update { it.copy(computing = true) }
            val s = _state.value.settings
            val prices = engine.prices()
            val forecast = engine.forecast(s)
            val steps = _state.value.thermostats.associate { it.id to it.step }
            val plans = withContext(Dispatchers.Default) {
                s.zones.map { z -> HeatingPlanner.plan(z, prices, forecast.temps, steps[z.deviceId] ?: 0.5) }
            }
            _state.update {
                it.copy(prices = prices, outdoor = forecast.temps, forecastFresh = forecast.fresh, plans = plans, computing = false)
            }
        }
    }

    fun saveZone(zone: HeatZone) {
        val s = _state.value.settings
        val zones = if (s.zones.any { it.id == zone.id }) s.zones.map { if (it.id == zone.id) zone else it } else s.zones + zone
        applySettings(s.copy(zones = zones))
    }

    fun deleteZone(id: String) {
        val s = _state.value.settings
        applySettings(s.copy(zones = s.zones.filterNot { it.id == id }))
    }

    fun setLocation(lat: Double, lon: Double) = applySettings(_state.value.settings.copy(latitude = lat, longitude = lon))

    private fun applySettings(s: HeatingSettings) {
        engine.store.save(s)
        _state.update { it.copy(settings = s) }
        recompute()
        // При включённом автопилоте изменения сразу уходят в расписание
        if (s.autopilot) deploy()
    }

    fun setAutopilot(on: Boolean) {
        val s = _state.value.settings.copy(autopilot = on)
        engine.store.save(s)
        _state.update { it.copy(settings = s) }
        if (on) deploy() else disable()
    }

    fun deploy() = runCloud { client -> engine.deploy(client, _state.value.settings) }

    private fun disable() = runCloud { client -> engine.disable(client, _state.value.settings) }

    private fun runCloud(block: suspend (TuyaCloudClient) -> String) {
        val creds = credentials.load() ?: run {
            _state.update { it.copy(message = "Сначала введите ключи Tuya в настройках") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(deploying = true) }
            val result = try {
                block(TuyaCloudClient(creds))
            } catch (e: Exception) {
                "Ошибка: ${HeatingEngine.describe(e)}"
            }
            _state.update {
                it.copy(
                    deploying = false,
                    deployedAt = engine.store.deployedAt,
                    deployResult = result,
                    message = result.lineSequence().firstOrNull { l -> "ошибка" in l.lowercase() } ?: "Готово",
                )
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun pow10(n: Int): Double = Math.pow(10.0, n.toDouble())
}
