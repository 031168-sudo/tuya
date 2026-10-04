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
    /** Включён ли прибор (выключатель; у батареи в ванной — пороги «вкл»). null — неизвестно. */
    val powerOn: Boolean? = null,
    /** Таймеры, которые сейчас стоят в модуле конвектора Rubetek (null — неизвестно / не Rubetek). */
    val moduleTimers: List<app.tuyacontrol.rubetek.ModuleTimer>? = null,
    /** Когда прочитаны таймеры модуля, мс. */
    val timersAt: Long = 0,
    /** Недельная программа в самом термостате: код DP, значение (base64), периоды рабочего дня, включена ли. */
    val programCode: String? = null,
    val programRaw: String? = null,
    val programOn: Boolean? = null,
    val programAt: Long = 0,
) {
    val program: List<app.tuyacontrol.heating.WeekProgram.Period>?
        get() = programCode?.let { c -> programRaw?.let { app.tuyacontrol.heating.WeekProgram.decode(c, it) } }

    companion object {
        /** Программа из состояния DP: (код, значение, по программе ли работает); null — программы нет. */
        fun programOf(st: Map<String, Any?>): Triple<String, String, Boolean>? {
            val code = app.tuyacontrol.heating.HeatingEngine.PROGRAM_CODES.firstOrNull { st[it] is String } ?: return null
            val on = app.tuyacontrol.heating.HeatingEngine.programModes(code).all { (k, v) -> st[k]?.toString() == v }
            return Triple(code, st[code] as String, on)
        }
    }
}

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
    /** Что приложение записало и проверило в модулях Rubetek: id -> (минуты, вкл?). */
    val deployedTimers: Map<String, List<Pair<Int, Boolean>>> = emptyMap(),
    /** Итоги подбора параметров зон по истории: id зоны -> итог. */
    val learned: Map<String, app.tuyacontrol.heating.LearnInfo> = emptyMap(),
    /** Идёт подбор параметров. */
    val learning: Boolean = false,
    /** Что приложение записало и проверило в программах термостатов: id -> base64. */
    val deployedPrograms: Map<String, String> = emptyMap(),
)

class HeatingViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = HeatingEngine(application)
    private val credentials = CredentialsStore(application)

    private val _state = MutableStateFlow(
        HeatingUiState(
            settings = engine.store.load(),
            deployedAt = engine.store.deployedAt,
            deployResult = engine.store.deployResult,
            deployedTimers = engine.store.deployedTimers(),
            deployedPrograms = engine.store.deployedPrograms(),
            learned = engine.store.learned(),
        )
    )
    val state: StateFlow<HeatingUiState> = _state.asStateFlow()

    init {
        // Тест таймера Rubetek прервался (приложение закрыли) — снимаем тестовый таймер, иначе он сработает завтра
        engine.store.rubetekTest?.let { pending ->
            viewModelScope.launch { clearRubetekTest(pending) }
        }
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
                // Модель с диапазонами (что означают режимы и рабочие дни) и программа, записанная в термостат
                val model = runCatching { client.getThingModel(id) }.getOrNull()
                if (model != null) {
                    app.tuyacontrol.data.AppLog.i(
                        "Модель устройства зоны «${z.name}»: " + model.values.joinToString { d ->
                            "${d.code}#${d.dpId}:${d.type}${if (d.writable) "(rw)" else ""}" +
                                (if (d.range.isNotEmpty()) d.range.toString() else "") +
                                (if (d.type == "Integer") "[${d.min}..${d.max} шаг ${d.step} ×10^-${d.scale}]" else "")
                        }
                    )
                }
                val status = runCatching { client.getStatus(id) }.getOrDefault(emptyMap()) +
                    runCatching { client.getShadowProperties(id) }.getOrDefault(emptyMap())
                status.forEach { (code, v) ->
                    val periods = app.tuyacontrol.heating.WeekProgram.decode(code, v?.toString() ?: return@forEach) ?: return@forEach
                    app.tuyacontrol.data.AppLog.i(
                        "Программа в термостате «${z.name}» ($code): " + periods.joinToString(", ") { it.text() } +
                            "; режим=${status["mode"]}, рабочие дни=${status["work_days"]}, program_mode=${status["program_mode"]}"
                    )
                }
                // Что устройство сообщало за последние 2 часа (запись расписания на борт видна как отчёт DP)
                val now = System.currentTimeMillis()
                val logs = runCatching { client.getDeviceLogs(id, "", now - 2 * 3600_000L, now, maxPages = 3) }.getOrDefault(emptyList())
                app.tuyacontrol.data.AppLog.i(
                    "Отчёты устройства зоны «${z.name}» за 2 ч: " +
                        logs.filter { it.code !in setOf("cur_current", "cur_power", "cur_voltage", "temp_current", "temp_current_f", "add_ele", "total_electricity", "cost") }
                            .joinToString { "${it.code}=${it.value.take(80)}" }
                )
            }
        }
    }
    private var loaded = false
    private var lastOutdoorSave = 0L

    /** Термостаты приходят с главного экрана; пустым зонам подбираем устройство по названию. */
    fun setDevices(all: List<DeviceUi>) {
        // Устройства Xiaomi в отопление автоматически не попадают — ими управляют вручную
        val devices = all.filterNot { app.tuyacontrol.xiaomi.XiaomiMapper.isXiaomi(it.id) }
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
                powerOn = if (!d.online) null else d.heatingPresetOn
                    ?: DeviceUi.MAIN_SWITCHES.firstNotNullOfOrNull { d.status[it] as? Boolean },
                moduleTimers = d.moduleTimers,
                timersAt = if (d.moduleTimers != null) d.lastDataTime else 0,
            ).let { t ->
                val p = Thermostat.programOf(d.status) ?: return@let t
                t.copy(programCode = p.first, programRaw = p.second, programOn = p.third, programAt = d.lastDataTime)
            }
        }.map { t ->
            // Таймеры, прочитанные отоплением напрямую, свежее списка устройств — не затираем их старыми
            val own = _state.value.thermostats.firstOrNull { it.id == t.id }
            val t1 = if (own != null && own.timersAt > t.timersAt) t.copy(moduleTimers = own.moduleTimers, timersAt = own.timersAt) else t
            if (own != null && own.programAt > t1.programAt) {
                t1.copy(programCode = own.programCode, programRaw = own.programRaw, programOn = own.programOn, programAt = own.programAt)
            } else t1
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
        }.map { z ->
            // Гистерезис не задан — берём по типу термостата
            val d = devices.firstOrNull { it.id == z.deviceId } ?: return@map z
            var zz = z
            if (zz.hysteresis == null) zz = zz.copy(hysteresis = HeatingEngine.defaultHysteresis(d))
            // Копить тепло — только тёплым полам (обогреватели быстро остывают, конвектор выше уставки не греет)
            if (zz.storeHeat == null) zz = zz.copy(storeHeat = zz.isFloor && !app.tuyacontrol.rubetek.RubetekMapper.isRubetek(d.id))
            zz
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

    /** Настройки могли поменяться не отсюда (команда включила/выключила управление зонами) — перечитать. */
    fun reloadSettings() {
        _state.update {
            it.copy(
                settings = engine.store.load(),
                deployedAt = engine.store.deployedAt,
                deployResult = engine.store.deployResult,
                deployedTimers = engine.store.deployedTimers(),
                deployedPrograms = engine.store.deployedPrograms(),
                learned = engine.store.learned(),
            )
        }
        reloadModuleTimers(force = true)
        reloadPrograms(force = true)
        recompute()
    }

    /** Подобрать параметры всех зон по истории сейчас (обычно это делается каждую ночь). */
    fun learnNow() {
        if (_state.value.learning) return
        val creds = credentials.load()
        viewModelScope.launch {
            _state.update { it.copy(learning = true) }
            val report = try {
                engine.learnAll(creds?.let { TuyaCloudClient(it) })
            } catch (e: Exception) {
                "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            }
            _state.update { it.copy(learning = false, message = "Подбор по истории:\n$report") }
            reloadSettings()
        }
    }

    private var timersReadAt = 0L

    /** Прочитать из облака Rubetek, какие таймеры сейчас стоят в модулях конвекторов зон. */
    fun reloadModuleTimers(force: Boolean = false) {
        val ids = _state.value.settings.zones.mapNotNull { it.deviceId }.filter { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it) }
        if (ids.isEmpty()) return
        if (!force && System.currentTimeMillis() - timersReadAt < 60_000) return
        timersReadAt = System.currentTimeMillis()
        viewModelScope.launch {
            val rubetek = app.tuyacontrol.rubetek.RubetekClient(app.tuyacontrol.rubetek.RubetekStore(getApplication()))
            val devices = try {
                rubetek.allDevices()
            } catch (e: Exception) {
                app.tuyacontrol.data.AppLog.e("Отопление: таймеры Rubetek не прочитаны", e)
                return@launch
            }
            val now = System.currentTimeMillis()
            val byId = devices.filter { it.id in ids }.associateBy { it.id }
            _state.update { st ->
                st.copy(
                    deployedTimers = engine.store.deployedTimers(),
                    thermostats = st.thermostats.map { t ->
                        byId[t.id]?.let { d -> t.copy(moduleTimers = d.moduleTimers, timersAt = now) } ?: t
                    },
                )
            }
        }
    }

    private var programsReadAt = 0L

    /** Прочитать недельные программы термостатов зон (они в теневых свойствах, список устройств их не обновляет). */
    fun reloadPrograms(force: Boolean = false) {
        val ids = _state.value.settings.zones.mapNotNull { it.deviceId }.filterNot { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it) }
        if (ids.isEmpty()) return
        if (!force && System.currentTimeMillis() - programsReadAt < 60_000) return
        programsReadAt = System.currentTimeMillis()
        val creds = credentials.load() ?: return
        viewModelScope.launch {
            val states = runCatching { engine.readPrograms(TuyaCloudClient(creds), ids) }.getOrDefault(emptyMap())
            val now = System.currentTimeMillis()
            _state.update { st ->
                st.copy(
                    deployedPrograms = engine.store.deployedPrograms(),
                    thermostats = st.thermostats.map { t ->
                        val p = states[t.id]?.let { Thermostat.programOf(it) } ?: return@map t
                        t.copy(programCode = p.first, programRaw = p.second, programOn = p.third, programAt = now)
                    },
                )
            }
        }
    }

    fun recompute() {
        logDeviceTimers(_state.value.settings)
        reloadModuleTimers()
        reloadPrograms()
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            _state.update { it.copy(computing = true) }
            val s = _state.value.settings
            val prices = engine.prices()
            val forecast = engine.forecast(s)
            val steps = _state.value.thermostats.associate { it.id to it.step }
            val plans = withContext(Dispatchers.Default) {
                s.zones.map { z ->
                    // Rubetek: температура — уставка самого конвектора, план только вкл/выкл
                    val own = _state.value.thermostats.firstOrNull { it.id == z.deviceId }?.setpoint
                    val onOff = z.deviceId != null && app.tuyacontrol.rubetek.RubetekMapper.isRubetek(z.deviceId)
                    val planZone = if (own != null && onOff) {
                        HeatingEngine.rubetekZone(z, own)
                    } else z
                    HeatingPlanner.plan(
                        planZone, prices, forecast.temps, steps[z.deviceId] ?: 0.5,
                        peakBan = onOff && !z.peakHeat,
                    ).withZone(z)
                }
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

    /**
     * Проверка: можно ли писать в «Heating mode schedule» (облачная группа studio). Добавляет ОДНУ запись
     * 03:30 с порогами 12°/13°, сразу выключает её и читает список обратно. Существующие записи не трогает.
     */
    fun testStudioTimer(zoneId: String) {
        val zone = _state.value.settings.zones.firstOrNull { it.id == zoneId } ?: return
        val id = zone.deviceId ?: return
        if (app.tuyacontrol.rubetek.RubetekMapper.isRubetek(id)) {
            testRubetekOff(zone.name, id)
            return
        }
        runCloud { client ->
            val log = app.tuyacontrol.data.AppLog
            val lines = mutableListOf<String>()
            try {
                val group = client.addDailyTimer(
                    id, "studio", "03:30",
                    listOf("heating_temp_start" to 120, "heating_temp_stop" to 130),
                    "Мой дом тест",
                )
                lines += "Запись добавлена (группа $group)"
                log.i("Тест studio: добавлено, group_id=$group")
                if (group.isNotEmpty()) {
                    runCatching { client.setTimerGroupStatus(id, "studio", group, false) }
                        .onSuccess { lines += "и выключена" }
                        .onFailure { lines += "выключить не удалось: ${it.message}"; log.e("Тест studio: выключение", it) }
                }
            } catch (e: Exception) {
                lines += "Запись не принята: ${HeatingEngine.describe(e)}"
                log.e("Тест studio: добавление", e)
            }
            val raw = runCatching { client.timersRaw(id) }.getOrElse { "ошибка: ${it.message}" }
            log.i("Тест studio: расписания после записи: $raw")
            "Тест записи в Heating mode schedule: " + lines.joinToString(", ") +
                ". Проверьте список в Tuya Smart: должна появиться выключенная запись 03:30 (12°/13°)"
        }
    }

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
            reloadModuleTimers(force = true)
            reloadPrograms(force = true)
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

    /**
     * Проверка, выполняет ли модуль Rubetek таймеры, записанные через облако: в свободный слот пишем
     * «выкл» через 3 минуты, ждём и смотрим, выключился ли конвектор. Затем слот очищаем.
     */
    private fun testRubetekOff(name: String, id: String) {
        val (house, device) = app.tuyacontrol.rubetek.RubetekMapper.parseId(id) ?: return
        val log = app.tuyacontrol.data.AppLog
        viewModelScope.launch {
            _state.update { it.copy(deploying = true) }
            val rubetek = app.tuyacontrol.rubetek.RubetekClient(app.tuyacontrol.rubetek.RubetekStore(getApplication()))
            val result = try {
                fun stateOf(all: List<org.json.JSONObject>) =
                    all.firstOrNull { it.optString("id") == device }?.optJSONObject("state") ?: org.json.JSONObject()
                val before = stateOf(rubetek.devices(house))
                val slot = (0 until 10).firstOrNull { before.optLong("tmr:off[$it]", -1) == -1L }
                    ?: throw IllegalStateException("нет свободного слота таймера")
                val at = java.time.LocalTime.now().plusMinutes(3).withSecond(0).withNano(0)
                val minutes = at.hour * 60 + at.minute
                val value = (127 shl 16) or minutes
                log.i("Тест Rubetek «$name»: питание до теста=${before.opt("rusKlimat:Power")}, слот tmr:off[$slot]=$value (%02d:%02d)".format(at.hour, at.minute))
                engine.store.rubetekTest = "$id|$slot"
                rubetek.setState(house, device, mapOf("tmr:off[$slot]" to value))
                _state.update { it.copy(message = "Тест: «$name» должен выключиться в %02d:%02d. Ждите ~4 минуты".format(at.hour, at.minute)) }

                // Ждём наступления минуты таймера и ещё минуту
                val waitMs = java.time.Duration.between(java.time.LocalTime.now(), at).toMillis().coerceAtLeast(0) + 60_000
                kotlinx.coroutines.delay(waitMs)
                var power: Any? = null
                for (attempt in 1..4) {
                    val st = stateOf(rubetek.devices(house))
                    power = st.opt("rusKlimat:Power")
                    log.i("Тест Rubetek «$name»: после таймера питание=$power (проверка $attempt)")
                    if (power != true) break
                    kotlinx.coroutines.delay(20_000)
                }
                clearRubetekTest("$id|$slot")
                if (power == true) {
                    "Тест «$name»: в %02d:%02d НЕ выключился — модуль наши таймеры не выполняет".format(at.hour, at.minute)
                } else {
                    "Тест «$name»: в %02d:%02d выключился — таймеры из приложения работают. Включите конвектор обратно".format(at.hour, at.minute)
                }
            } catch (e: Exception) {
                log.e("Тест Rubetek «$name» не выполнен", e)
                engine.store.rubetekTest?.let { clearRubetekTest(it) }
                "Тест «$name»: ошибка — ${e.message ?: e.javaClass.simpleName}"
            }
            log.i(result)
            reloadModuleTimers(force = true)
            _state.update { it.copy(deploying = false, message = result, deployResult = result) }
        }
    }

    /** Снять тестовый таймер выключения (слот «id|n» -> −1). */
    private suspend fun clearRubetekTest(pending: String) {
        val (id, slot) = pending.split("|").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: return) }
        val (house, device) = app.tuyacontrol.rubetek.RubetekMapper.parseId(id) ?: return
        try {
            app.tuyacontrol.rubetek.RubetekClient(app.tuyacontrol.rubetek.RubetekStore(getApplication()))
                .setState(house, device, mapOf("tmr:off[$slot]" to -1))
            engine.store.rubetekTest = null
            app.tuyacontrol.data.AppLog.i("Тест Rubetek: тестовый таймер tmr:off[$slot] снят")
        } catch (e: Exception) {
            app.tuyacontrol.data.AppLog.e("Тест Rubetek: тестовый таймер не снят", e)
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun pow10(n: Int): Double = Math.pow(10.0, n.toDouble())
}
