package app.tuyacontrol

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tuyacontrol.nartis.MeterReading
import app.tuyacontrol.nartis.NartisBle
import app.tuyacontrol.nartis.NartisDevice
import app.tuyacontrol.nartis.NartisMeter
import app.tuyacontrol.nartis.NartisSnapshot
import app.tuyacontrol.nartis.NartisStore
import app.tuyacontrol.nartis.NusBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class NartisUiState(
    val devices: List<NartisDevice> = emptyList(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val log: List<String> = emptyList(),
    val reading: MeterReading? = null,
    /** Серийный номер своего счётчика; подключаемся только к прибору с этим номером. */
    val serial: String = "",
    val pin: String = "123456",
    val password: String = "00000001",
    val lastReadAt: Long = 0,
    val history: List<NartisSnapshot> = emptyList(),
)

/** Чтение счётчика НАРТИС по Bluetooth: поиск, подключение, показания. */
class NartisViewModel(application: Application) : AndroidViewModel(application) {

    private val store = NartisStore(application)
    private val initialHistory = store.snapshots()

    private val _state = MutableStateFlow(
        NartisUiState(
            serial = store.serial,
            // После перезапуска показываем последние сохранённые показания (без токов/напряжений)
            reading = readingFromSnapshot(initialHistory.lastOrNull()),
            lastReadAt = initialHistory.lastOrNull()?.time ?: 0L,
            history = initialHistory,
        ),
    )
    val state: StateFlow<NartisUiState> = _state.asStateFlow()

    private var ble: NartisBle? = null

    fun setPin(value: String) = _state.update { it.copy(pin = value.filter { c -> c.isDigit() }.take(6)) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

    fun setSerial(value: String) {
        val serial = value.filter { it.isDigit() }.take(12)
        store.serial = serial
        _state.update { it.copy(serial = serial) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    fun startScan() {
        val existing = ble ?: NartisBle(getApplication(), ::log).also { ble = it }
        _state.update { it.copy(scanning = true, devices = emptyList(), status = "Ищу счётчик…") }
        try {
            existing.startScan(
                onFound = { d ->
                    _state.update { s ->
                        val others = s.devices.filterNot { it.address == d.address }
                        s.copy(devices = (others + d).sortedByDescending { it.rssi })
                    }
                },
                onError = { msg ->
                    _state.update { it.copy(scanning = false, status = msg) }
                },
            )
        } catch (e: SecurityException) {
            _state.update { it.copy(scanning = false, status = "Нет разрешения на Bluetooth") }
        }
    }

    fun stopScan() {
        ble?.stopScan()
        _state.update { it.copy(scanning = false) }
    }

    /** Автоматическое чтение при открытии экрана: не чаще раза в 2 минуты. */
    fun autoReadIfNeeded() {
        val s = _state.value
        if (s.serial.isBlank() || s.busy) return
        if (!hasBluetoothPermission()) return
        if (System.currentTimeMillis() - s.lastReadAt < 2 * 60_000L) return
        read()
    }

    /** Ищет только свой счётчик по серийному номеру и читает его. */
    fun read() {
        if (_state.value.busy) return
        val serial = _state.value.serial.trim()
        if (serial.isBlank()) {
            _state.update { it.copy(status = "Укажите серийный номер счётчика") }
            return
        }
        val s = _state.value
        val link = ble ?: NartisBle(getApplication(), ::log).also { ble = it }
        _state.update {
            it.copy(busy = true, reading = null, status = "Ищу счётчик $serial…", log = emptyList())
        }
        viewModelScope.launch {
            var bridge: NusBridge? = null
            try {
                val device = findMeter(link, serial)
                    ?: throw IllegalStateException("счётчик с номером $serial не найден рядом")
                withContext(Dispatchers.IO) {
                    link.connect(device.address, s.pin)
                    // Счётчику нужно немного времени после включения уведомлений
                    kotlinx.coroutines.delay(500)
                    bridge = NusBridge(link, ::log).also { it.start() }
                    val reading = NartisMeter.read(bridge!!.port, s.password, ::log)
                    val now = System.currentTimeMillis()
                    val snap = NartisSnapshot(
                        time = now,
                        total = reading.totalKwh,
                        t1 = reading.t1Kwh,
                        t2 = reading.t2Kwh,
                        t3 = reading.t3Kwh,
                    )
                    val history = store.add(snap)
                    _state.update {
                        it.copy(
                            reading = reading,
                            status = "Готово",
                            lastReadAt = now,
                            history = history,
                        )
                    }
                }
            } catch (e: Exception) {
                log("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                _state.update { it.copy(status = "Не удалось: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                withContext(Dispatchers.IO) {
                    runCatching { bridge?.close() }
                    runCatching { link.close() }
                }
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        val context = getApplication<Application>()
        val permission = if (Build.VERSION.SDK_INT >= 31) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun findMeter(link: NartisBle, serial: String): NartisDevice? {
        val found = CompletableDeferred<NartisDevice>()
        link.startScan(
            onFound = { d ->
                if (d.name.contains(serial, ignoreCase = true)) {
                    found.complete(d)
                    link.stopScan()
                }
            },
            onError = { msg ->
                _state.update { it.copy(status = msg) }
            },
        )
        val result = withTimeoutOrNull(15_000) { found.await() }
        link.stopScan()
        return result
    }

    private fun readingFromSnapshot(s: NartisSnapshot?): MeterReading? = s?.let {
        MeterReading(
            totalKwh = it.total,
            t1Kwh = it.t1,
            t2Kwh = it.t2,
            t3Kwh = it.t3,
            powerW = null,
            voltageA = null,
            voltageB = null,
            voltageC = null,
            currentA = null,
            currentB = null,
            currentC = null,
            neutralCurrent = null,
            frequency = null,
            serial = null,
            withPassword = true,
        )
    }

    private fun log(line: String) {
        _state.update { s ->
            s.copy(log = (s.log + line).takeLast(1000))
        }
    }

    override fun onCleared() {
        runCatching { ble?.close() }
        super.onCleared()
    }
}
