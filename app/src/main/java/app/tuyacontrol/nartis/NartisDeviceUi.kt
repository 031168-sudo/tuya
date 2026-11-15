package app.tuyacontrol.nartis

import app.tuyacontrol.DeviceUi
import app.tuyacontrol.cloud.DpSpec

/**
 * Счётчик НАРТИС как устройство в общем списке. Живёт только в приложении:
 * облачных команд у него нет, показания приходят по Bluetooth.
 */
fun nartisDeviceUi(state: app.tuyacontrol.NartisUiState): DeviceUi? {
    val serial = state.serial.trim()
    if (serial.isBlank()) return null
    val r = state.reading
    val status = linkedMapOf<String, Any?>()
    if (r != null) {
        r.totalKwh?.let { status["nartis_total"] = it }
        r.t1Kwh?.let { status["nartis_t1"] = it }
        r.t2Kwh?.let { status["nartis_t2"] = it }
        r.t3Kwh?.let { status["nartis_t3"] = it }
        r.powerW?.let { status["nartis_power"] = it }
        r.voltageA?.let { status["nartis_ua"] = it }
        r.voltageB?.let { status["nartis_ub"] = it }
        r.voltageC?.let { status["nartis_uc"] = it }
        r.currentA?.let { status["nartis_ia"] = it }
        r.currentB?.let { status["nartis_ib"] = it }
        r.currentC?.let { status["nartis_ic"] = it }
    }
    fun spec(code: String, unit: String): DpSpec = DpSpec(code = code, type = "Double", unit = unit)
    val specs = mapOf(
        "nartis_total" to spec("nartis_total", "кВт·ч"),
        "nartis_t1" to spec("nartis_t1", "кВт·ч"),
        "nartis_t2" to spec("nartis_t2", "кВт·ч"),
        "nartis_t3" to spec("nartis_t3", "кВт·ч"),
        "nartis_power" to spec("nartis_power", "Вт"),
        "nartis_ua" to spec("nartis_ua", "В"),
        "nartis_ub" to spec("nartis_ub", "В"),
        "nartis_uc" to spec("nartis_uc", "В"),
        "nartis_ia" to spec("nartis_ia", "А"),
        "nartis_ib" to spec("nartis_ib", "А"),
        "nartis_ic" to spec("nartis_ic", "А"),
    )
    return DeviceUi(
        id = "nartis:$serial",
        name = "Счётчик НАРТИС",
        online = state.lastReadAt > 0,
        productName = "NARTIS-I300",
        category = "",
        status = status,
        spec = specs,
        lastDataTime = state.lastReadAt,
    )
}
