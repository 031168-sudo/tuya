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

/** Датчик температуры, который можно назначить уличным. */
data class OutdoorSensor(val id: String, val name: String, val code: String)

/** Термостат, который можно назначить зоне. */
data class Thermostat(
    val id: String,
    val name: String,
    val online: Boolean,
    val current: Double?,
    val setpoint: Double?,
    val step: Double,
    /** Греет сейчас (null — устройство не сообщает). */
    val heating: Boolean? = null,
)

data class HeatingUiState(
    val settings: HeatingSettings = HeatingSettings.defaults(),
    val thermostats: List<Thermostat> = emptyList(),
    val prices: HourPrices = HourPrices.default(),
    val outdoor: DoubleArray = DoubleArray(48),
    val forecastFresh: Boolean = false,
    /** Поправка прогноза по уличному датчику, °C (0 — без поправки). */
    val outdoorBias: Double = 0.0,
    /** Датчики температуры, которые можно назначить уличными. */
    val outdoorSensors: List<OutdoorSensor> = emptyList(),
    /** Уличный датчик сейчас: значение и время данных (мс). */
    val outdoorNow: Double? = null,
    val outdoorTime: Long = 0,
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

    init {
        // Раньше был общий автопилот. Теперь управление включается по зонам: если автопилот был включён,
        // один раз снимаем все наши расписания (уставки на устройствах остаются как есть)
        val s = engine.store.load()
        if (s.autopilot) {
            val cleared = s.copy(autopilot = false)
            engine.store.save(cleared)
            _state.update { it.copy(settings = cleared) }
            runCloud { client -> engine.disable(client, s) }
        }
    }

    private var computeJob: Job? = null
    private var timersLogged = false

    /** Один раз за запуск: записать в журнал все расписания устройств зон (как их хранит Tuya Smart). */
    private fun logDeviceTimers(s: HeatingSettings) {
        if (timersLogged) return
        val creds = credentials.load() ?: return
        timersLogged = true
        viewModelScope.launch {
            val client = TuyaCloudClient(creds)
            for (z in s.zones) {
                val id = z.deviceId ?: continue
                if (app.tuyacontrol.rubetek.RubetekMapper.isRubetek(id)) continue
                val raw = runCatching { client.timersRaw(id) }.getOrElse { "ошибка: ${it.message}" }
                app.tuyacontrol.data.AppLog.i("Расписания устройства зоны «${z.name}»: $raw")
            }
        }
    }
    private var loaded = false
    private var lastOutdoorSave = 0L

    /** Термостаты приходят с главного экрана; пустым зонам подбираем устройство по названию. */
    fun setDevices(devices: List<DeviceUi>) {
        engine.store.relayDevices = devices.filter { it.switchIsRelay }.map { it.id }.toSet()
        val list = devices.filter { it.setpointCode != null }.map { d ->
            val cur = d.spec["temp_current"]
            val code = d.setpointCode!!
            val set = d.spec[code]
            Thermostat(
                id = d.id,
                name = d.name,
                online = d.online,
                current = (d.status["temp_current"] as? Number)?.toDouble()?.let { it / pow10(cur?.scale ?: 0) },
                setpoint = (d.status[code] as? Number)?.toDouble()?.let { it / pow10(set?.scale ?: 0) },
                step = HeatingEngine.stepOf(set),
                heating = d.heatingNow,
            )
        }.sortedBy { it.name.lowercase() }
        var settings = _state.value.settings
        // Уличный датчик: все датчики температуры, кроме термостатов; по умолчанию — «T & H» / «улица»
        val sensors = devices.mapNotNull { d ->
            val ch = d.sensorDevice?.temperature ?: return@mapNotNull null
            if (d.setpointCode != null || app.tuyacontrol.rubetek.RubetekMapper.isRubetek(d.id)) return@mapNotNull null
            OutdoorSensor(d.id, d.name, ch.code)
        }.sortedBy { it.name.lowercase() }
        if (settings.outdoorSensorId == null) {
            sensors.firstOrNull { s -> listOf("T & H", "улиц", "outdoor").any { s.name.contains(it, ignoreCase = true) } }
                ?.let { settings = settings.copy(outdoorSensorId = it.id, outdoorCode = it.code) }
        }
        val outdoorDevice = devices.firstOrNull { it.id == settings.outdoorSensorId }
        val outdoorNow = outdoorDevice?.let { d ->
            val ch = d.sensorDevice?.temperature ?: return@let null
            (d.status[ch.code] as? Number)?.toDouble()?.let { it / pow10(ch.scale) }
        }
        // Текущее уличное значение — в архив (не чаще раза в 10 минут): по нему сверяется прогноз
        if (outdoorNow != null && outdoorDevice?.online == true && System.currentTimeMillis() - lastOutdoorSave > 600_000L) {
            lastOutdoorSave = System.currentTimeMillis()
            val code = outdoorDevice.sensorDevice!!.temperature!!.code
            val t = (outdoorDevice.lastDataTime.takeIf { it > 0 } ?: System.currentTimeMillis()) / 60_000 * 60_000
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { app.tuyacontrol.sensor.SensorDb(getApplication<Application>()).insert(outdoorDevice.id, listOf(app.tuyacontrol.sensor.Reading(code, t, outdoorNow))) }
            }
        }
        val zones = settings.zones.map { z ->
            if (z.deviceId != null) return@map z
            val hints = HeatingSettings.NAME_HINTS[z.id] ?: return@map z
            val match = list.firstOrNull { t -> hints.any { t.name.contains(it, ignoreCase = true) } }
            if (match != null) z.copy(deviceId = match.id) else z
        }
        if (zones != settings.zones || settings != _state.value.settings) {
            settings = settings.copy(zones = zones)
            engine.store.save(settings)
        }
        val changed = list != _state.value.thermostats
        _state.update {
            it.copy(
                thermostats = list,
                settings = settings,
                outdoorSensors = sensors,
                outdoorNow = outdoorNow?.takeIf { outdoorDevice?.online == true },
                outdoorTime = outdoorDevice?.lastDataTime ?: 0,
            )
        }
        if (!loaded || changed) {
            loaded = true
            recompute()
        }
    }

    fun recompute() {
        logDeviceTimers(_state.value.settings)
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
                it.copy(
                    prices = prices, outdoor = forecast.temps, forecastFresh = forecast.fresh,
                    outdoorBias = forecast.bias, plans = plans, computing = false,
                )
            }
        }
    }

    fun saveZone(zone: HeatZone) {
        val s = _state.value.settings
        val zones = if (s.zones.any { it.id == zone.id }) s.zones.map { if (it.id == zone.id) zone else it } else s.zones + zone
        applySettings(s.copy(zones = zones))
    }

    /** Зона для каждого конвектора Rubetek, которому ещё не назначена зона: комфорт 21° круглосуточно. */
    fun addRubetekZones() {
        val s = _state.value.settings
        val used = s.zones.mapNotNull { it.deviceId }.toSet()
        val fresh = _state.value.thermostats
            .filter { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it.id) && it.id !in used }
            .map { t ->
                HeatZone(
                    id = java.util.UUID.randomUUID().toString().take(8),
                    name = t.name.filter { it.isLetterOrDigit() || it == ' ' || it == ',' }.trim().ifEmpty { t.name },
                    deviceId = t.id,
                    windows = listOf(app.tuyacontrol.heating.ComfortWindow(0, 0, 21.0)),
                    baseTemp = 16.0,
                    peakDrop = 1.0,
                    maxTemp = 23.0,
                    // Конвектор греет воздух быстро, но и остывает комната быстрее, чем с тёплым полом
                    powerKw = 2.0,
                    heatRate = 3.0,
                    lossRate = 0.04,
                )
            }
        if (fresh.isEmpty()) {
            _state.update { it.copy(message = "Все конвекторы Rubetek уже в зонах") }
            return
        }
        applySettings(s.copy(zones = s.zones + fresh))
        _state.update { it.copy(message = "Добавлено зон: ${fresh.size}") }
    }

    /** Включить/исключить зону из общей сводки: только пересчёт итогов, без записи в устройства. */
    fun setInTotal(id: String, on: Boolean) {
        val s = _state.value.settings
        val updated = s.copy(zones = s.zones.map { if (it.id == id) it.copy(inTotal = on) else it })
        engine.store.save(updated)
        _state.update { st ->
            st.copy(
                settings = updated,
                plans = st.plans.map { p -> if (p.zone.id == id) p.withZone(p.zone.copy(inTotal = on)) else p },
            )
        }
    }

    fun deleteZone(id: String) {
        val s = _state.value.settings
        val zone = s.zones.firstOrNull { it.id == id }
        val rest = s.copy(zones = s.zones.filterNot { it.id == id })
        engine.store.save(rest)
        _state.update { it.copy(settings = rest) }
        recompute()
        // Удалённая зона с управлением: снимаем её расписание, остальные не трогаем
        if (zone?.control == true) runCloud { client -> engine.stopZone(client, zone) }
    }

    fun setLocation(lat: Double, lon: Double, outdoor: OutdoorSensor?) = applySettings(
        _state.value.settings.copy(
            latitude = lat,
            longitude = lon,
            // "-" — пользователь сознательно отказался от уличного датчика
            outdoorSensorId = outdoor?.id ?: "-",
            outdoorCode = outdoor?.code,
        )
    )

    private fun applySettings(s: HeatingSettings) {
        engine.store.save(s)
        _state.update { it.copy(settings = s) }
        recompute()
        // Зоны с включённым управлением сразу получают новый план
        if (s.zones.any { it.control }) deploy()
    }

    /** Управление зоной: включить — выставить уставки по плану и обновлять их; выключить — перестать трогать. */
    fun setZoneControl(id: String, on: Boolean) {
        val s = _state.value.settings
        val updated = s.copy(zones = s.zones.map { if (it.id == id) it.copy(control = on) else it })
        engine.store.save(updated)
        _state.update { st ->
            st.copy(
                settings = updated,
                plans = st.plans.map { p -> if (p.zone.id == id) p.withZone(p.zone.copy(control = on)) else p },
            )
        }
        val zone = updated.zones.first { it.id == id }
        if (on) {
            runCloud { client -> engine.deploy(client, updated, onlyZone = id) }
        } else {
            runCloud { client -> engine.stopZone(client, zone) }
        }
    }

    fun deploy() = runCloud { client -> engine.deploy(client, _state.value.settings) }

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
