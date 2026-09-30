package app.tuyacontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.data.CredentialsStore
import app.tuyacontrol.data.DpFormat
import app.tuyacontrol.energy.EnergyReports
import app.tuyacontrol.energy.PeriodType
import app.tuyacontrol.sensor.SensorChannel
import app.tuyacontrol.sensor.SensorDb
import app.tuyacontrol.sensor.SensorDevice
import app.tuyacontrol.sensor.SensorSync
import app.tuyacontrol.sensor.SeriesPoint
import app.tuyacontrol.sensor.SeriesStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Данные одного графика (температура или влажность) за выбранный период. */
data class SeriesUi(
    val channel: SensorChannel,
    val from: Long,
    val to: Long,
    val bucketMs: Long,
    val points: List<SeriesPoint>,
    val stats: SeriesStats?,
)

data class SensorUiState(
    val device: SensorDevice? = null,
    val period: PeriodType = PeriodType.DAY,
    val anchor: LocalDate = LocalDate.now(),
    val title: String = "",
    val series: List<SeriesUi> = emptyList(),
    /** Текущие значения: код -> значение с учётом множителя. */
    val current: Map<String, Double> = emptyMap(),
    val currentTime: Long? = null,
    val historySince: Long? = null,
    val syncing: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
)

class SensorViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SensorDb(application)
    private val sync = SensorSync(db)
    private val credentials = CredentialsStore(application)
    private val zone = ZoneId.systemDefault()

    private val _state = MutableStateFlow(SensorUiState())
    val state: StateFlow<SensorUiState> = _state.asStateFlow()

    private var reloadJob: Job? = null
    private var syncJob: Job? = null

    /** Открыть датчик: сразу запросить свежие показания и докачать журнал. */
    fun open(device: SensorDevice) {
        val same = _state.value.device?.id == device.id
        _state.update {
            if (same) {
                it.copy(device = device)
            } else {
                SensorUiState(device = device, anchor = LocalDate.now(), period = it.period)
            }
        }
        reload()
        refresh()
    }

    fun refresh() {
        val device = _state.value.device ?: return
        val creds = credentials.load() ?: return
        if (syncJob?.isActive == true) return
        val client = TuyaCloudClient(creds)
        syncJob = viewModelScope.launch {
            _state.update { it.copy(syncing = true, progress = "Текущие показания…") }
            try {
                val raw = if (device.thingModel) {
                    client.getShadowProperties(device.id)
                } else {
                    runCatching { client.getStatus(device.id) }.getOrNull()?.takeIf { it.isNotEmpty() }
                        ?: client.getShadowProperties(device.id)
                }
                val current = device.channels.mapNotNull { ch ->
                    val v = DpFormat.asLong(raw[ch.code])?.toDouble() ?: (raw[ch.code] as? Number)?.toDouble()
                    v?.let { ch.code to BigDecimal.valueOf(it).movePointLeft(ch.scale).toDouble() }
                }.toMap()
                _state.update { it.copy(current = current, currentTime = System.currentTimeMillis()) }

                sync.sync(client, device) { p -> _state.update { it.copy(progress = p) } }
                _state.update { it.copy(syncing = false, progress = null) }
            } catch (e: Exception) {
                AppLog.e("${device.name}: ошибка загрузки показаний", e)
                _state.update {
                    it.copy(syncing = false, progress = null, message = e.message ?: e.javaClass.simpleName)
                }
            }
            reload()
        }
    }

    fun setPeriod(type: PeriodType, anchor: LocalDate = _state.value.anchor) {
        _state.update { it.copy(period = type, anchor = anchor) }
        reload()
    }

    fun shift(delta: Long) {
        _state.update { it.copy(anchor = EnergyReports.shift(it.period, it.anchor, delta)) }
        reload()
    }

    fun today() {
        _state.update { it.copy(anchor = LocalDate.now()) }
        reload()
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun reload() {
        val s = _state.value
        val device = s.device ?: return
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val (fromDay, toDay) = EnergyReports.range(s.period, s.anchor)
            val from = fromDay.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = toDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val bucket = when (s.period) {
                PeriodType.DAY -> 10L * 60_000
                PeriodType.WEEK -> 3600_000L
                PeriodType.MONTH -> 6L * 3600_000
                PeriodType.YEAR -> 24L * 3600_000
            }
            val loaded = withContext(Dispatchers.IO) {
                val series = device.channels.map { ch ->
                    SeriesUi(
                        channel = ch,
                        from = from,
                        to = to,
                        bucketMs = bucket,
                        points = db.series(device.id, ch.code, from, to, bucket),
                        stats = db.stats(device.id, ch.code, from, to),
                    )
                }
                series to db.firstTime(device.id)
            }
            _state.update {
                if (it.device?.id == device.id && it.period == s.period && it.anchor == s.anchor) {
                    it.copy(
                        series = loaded.first,
                        historySince = loaded.second,
                        title = EnergyReports.title(s.period, s.anchor),
                    )
                } else {
                    it
                }
            }
        }
    }
}
