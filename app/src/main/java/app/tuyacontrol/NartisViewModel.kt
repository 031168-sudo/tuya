package app.tuyacontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tuyacontrol.nartis.MeterReading
import app.tuyacontrol.nartis.NartisBle
import app.tuyacontrol.nartis.NartisDevice
import app.tuyacontrol.nartis.NartisMeter
import app.tuyacontrol.nartis.NusBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NartisUiState(
    val devices: List<NartisDevice> = emptyList(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val log: List<String> = emptyList(),
    val reading: MeterReading? = null,
    val pin: String = "123456",
    val password: String = "",
)

/** Чтение счётчика НАРТИС по Bluetooth: поиск, подключение, показания. */
class NartisViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(NartisUiState())
    val state: StateFlow<NartisUiState> = _state.asStateFlow()

    private var ble: NartisBle? = null

    fun setPin(value: String) = _state.update { it.copy(pin = value.filter { c -> c.isDigit() }.take(6)) }

    fun setPassword(value: String) = _state.update { it.copy(password = value) }

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

    /** Подключается к выбранному счётчику и читает показания (в фоне). */
    fun read(address: String) {
        if (_state.value.busy) return
        val s = _state.value
        val link = ble ?: NartisBle(getApplication(), ::log).also { ble = it }
        link.stopScan()
        _state.update { it.copy(scanning = false, busy = true, reading = null, status = "Подключаюсь…", log = emptyList()) }
        viewModelScope.launch {
            var bridge: NusBridge? = null
            try {
                withContext(Dispatchers.IO) {
                    link.connect(address, s.pin)
                    // Счётчику нужно немного времени после включения уведомлений
                    kotlinx.coroutines.delay(500)
                    bridge = NusBridge(link, ::log).also { it.start() }
                    val reading = NartisMeter.read(bridge!!.port, s.password, ::log)
                    _state.update { it.copy(reading = reading, status = "Готово") }
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

    private fun log(line: String) {
        _state.update { s ->
            s.copy(log = (s.log + line).takeLast(300))
        }
    }

    override fun onCleared() {
        runCatching { ble?.close() }
        super.onCleared()
    }
}
