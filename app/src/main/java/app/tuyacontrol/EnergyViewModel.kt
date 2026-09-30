package app.tuyacontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.data.CredentialsStore
import app.tuyacontrol.energy.EnergyDb
import app.tuyacontrol.energy.EnergyDevice
import app.tuyacontrol.energy.EnergyMeta
import app.tuyacontrol.energy.EnergyReport
import app.tuyacontrol.energy.EnergyReports
import app.tuyacontrol.energy.EnergySync
import app.tuyacontrol.energy.PeriodType
import app.tuyacontrol.energy.Tariff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class EnergyUiState(
    val devices: List<EnergyDevice> = emptyList(),
    /** null — все устройства вместе. */
    val selected: String? = null,
    val period: PeriodType = PeriodType.MONTH,
    val anchor: LocalDate = LocalDate.now(),
    val report: EnergyReport? = null,
    val meta: Map<String, EnergyMeta> = emptyMap(),
    val tariffs: List<Tariff> = emptyList(),
    val syncing: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
)

class EnergyViewModel(application: Application) : AndroidViewModel(application) {

    private val db = EnergyDb(application)
    private val sync = EnergySync(db)
    private val credentials = CredentialsStore(application)

    private val _state = MutableStateFlow(EnergyUiState())
    val state: StateFlow<EnergyUiState> = _state.asStateFlow()

    /** Список устройств со счётчиком приходит с главного экрана. */
    fun setDevices(devices: List<EnergyDevice>) {
        val sorted = devices.sortedBy { it.name.lowercase() }
        if (sorted == _state.value.devices) return
        _state.update { s ->
            s.copy(devices = sorted, selected = s.selected?.takeIf { id -> sorted.any { it.id == id } })
        }
        reload()
        // Автоматически докачиваем историю, если давно не обновляли
        viewModelScope.launch {
            val stale = withContext(Dispatchers.IO) {
                sorted.any { d ->
                    val m = db.meta(d.id)
                    m == null || System.currentTimeMillis() - m.updatedAt > 3_600_000L
                }
            }
            if (stale) syncAll()
        }
    }

    fun select(deviceId: String?) {
        _state.update { it.copy(selected = deviceId) }
        reload()
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

    // ---------- Тарифы ----------

    fun saveTariff(t: Tariff) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.saveTariff(t) }
            reload()
        }
    }

    fun deleteTariff(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteTariff(id) }
            reload()
        }
    }

    // ---------- Загрузка истории ----------

    fun syncAll() {
        if (_state.value.syncing) return
        val creds = credentials.load() ?: return
        val devices = _state.value.devices
        if (devices.isEmpty()) return
        val client = TuyaCloudClient(creds)
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, progress = "Загрузка истории…") }
            val errors = mutableListOf<String>()
            for (d in devices) {
                try {
                    sync.sync(client, d) { p -> _state.update { it.copy(progress = p) } }
                } catch (e: Exception) {
                    errors += "${d.name}: ${e.message ?: e.javaClass.simpleName}"
                }
                reload()
            }
            _state.update {
                it.copy(
                    syncing = false,
                    progress = null,
                    message = if (errors.isEmpty()) "История обновлена" else errors.joinToString("\n"),
                )
            }
            AppLog.i("Синхронизация энергии завершена, ошибок: ${errors.size}")
        }
    }

    fun reload() {
        viewModelScope.launch {
            val s = _state.value
            val ids = s.selected?.let { listOf(it) } ?: s.devices.map { it.id }
            val (from, to) = EnergyReports.range(s.period, s.anchor)
            val loaded = withContext(Dispatchers.IO) {
                val days = db.getDays(ids, from, to)
                val tariffs = db.tariffs()
                val meta = s.devices.mapNotNull { d -> db.meta(d.id)?.let { d.id to it } }.toMap()
                Triple(days, tariffs, meta)
            }
            val (days, tariffs, meta) = loaded
            val names = s.devices.associate { it.id to it.name }
            val report = EnergyReports.build(s.period, s.anchor, days, tariffs, names, LocalDate.now())
            _state.update {
                // Пока грузили, пользователь мог сменить период — применяем только актуальный отчёт
                if (it.period == s.period && it.anchor == s.anchor && it.selected == s.selected) {
                    it.copy(report = report, tariffs = tariffs, meta = meta)
                } else {
                    it.copy(tariffs = tariffs, meta = meta)
                }
            }
        }
    }
}
