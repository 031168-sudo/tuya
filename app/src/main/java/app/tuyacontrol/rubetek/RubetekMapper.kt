package app.tuyacontrol.rubetek

import app.tuyacontrol.DeviceUi
import app.tuyacontrol.cloud.DpSpec
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Устройства Rubetek показываем в общем списке как DeviceUi:
 *   relay:on[i]  -> switch_{i+1}
 *   rgb:level[1] -> bright_value (0..100 %)
 *   pwr:Pact     -> cur_power (Вт), pwr:Vrms -> cur_voltage (В), pwr:Irms -> cur_current (А)
 * id — «rubetek:<дом>:<устройство>».
 */
object RubetekMapper {

    const val PREFIX = "rubetek:"
    const val CATEGORY = "rubetek"

    fun isRubetek(id: String) = id.startsWith(PREFIX)

    /** (дом, устройство) из id; null — не Rubetek. */
    fun parseId(id: String): Pair<String, String>? {
        if (!isRubetek(id)) return null
        val parts = id.removePrefix(PREFIX).split(":", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    /** null — удалённое или скрытое устройство. */
    fun toDevice(houseId: String, houseName: String, multiHouse: Boolean, o: JSONObject): DeviceUi? {
        if (!o.isNull("deleted_at") && o.optString("deleted_at").isNotEmpty()) return null
        if (o.optBoolean("deleted", false) || o.optBoolean("hidden", false) || o.optBoolean("disabled", false)) return null
        val id = o.optString("id").ifEmpty { return null }
        val state = o.optJSONObject("state") ?: JSONObject()
        val custom = o.optJSONObject("custom_data") ?: JSONObject()
        // Старые записи-дубликаты («Конвектор» из прежней сети): ни состояния, ни модуля — не показываем
        if (state.length() == 0 && !custom.has("moduleName")) return null

        val status = LinkedHashMap<String, Any?>()
        val spec = LinkedHashMap<String, DpSpec>()
        for (i in 0..3) {
            val key = "relay:on[$i]"
            if (state.has(key)) {
                val code = "switch_${i + 1}"
                status[code] = state.optBoolean(key)
                spec[code] = DpSpec(code, "Boolean", writable = true)
            }
        }
        if (state.has("rgb:level[1]")) {
            status["bright_value"] = state.optLong("rgb:level[1]")
            spec["bright_value"] = DpSpec("bright_value", "Integer", unit = "%", min = 0, max = 100, step = 10, writable = true)
        }
        fun num(key: String, code: String, unit: String, digits: Int) {
            if (!state.has(key)) return
            val v = state.optDouble(key)
            if (v.isNaN()) return
            status[code] = round(v, digits)
            spec[code] = DpSpec(code, "Integer", unit = unit)
        }
        num("pwr:Pact", "cur_power", "Вт", 1)
        num("pwr:Vrms", "cur_voltage", "В", 0)
        num("pwr:Irms", "cur_current", "А", 2)

        // Конвекторы (Rusklimat: Electrolux, Ballu) через Wi-Fi-модуль Rubetek. Модуль умеет «термостат»
        // (dev:features: hkThermostat): thermostat:temp — в комнате, thermostat:setTemp — уставка,
        // thermostat:setMode — включение (0 — выключен, как в HomeKit)
        if (state.has("thermostat:temp")) {
            status["temp_current"] = state.optInt("thermostat:temp")
            spec["temp_current"] = DpSpec("temp_current", "Integer", unit = "°C")
        }
        if (state.has("thermostat:setTemp")) {
            status["temp_set"] = state.optLong("thermostat:setTemp")
            spec["temp_set"] = DpSpec("temp_set", "Integer", unit = "°C", min = 5, max = 35, step = 1, writable = true)
        }
        // Включение — у всех конвекторов с модулем-термостатом. Модуль сообщает thermostat:setMode только после
        // первого переключения; пока его нет, считаем конвектор включённым (он греет по своей уставке)
        if (state.has("thermostat:setMode") || state.has("thermostat:setTemp")) {
            status["switch"] = if (state.has("thermostat:setMode")) state.optInt("thermostat:setMode") != 0 else true
            spec["switch"] = DpSpec("switch", "Boolean", writable = true)
        }
        if (state.has("rusKlimat:SetPower")) {
            status["power_level"] = state.optInt("rusKlimat:SetPower")
            spec["power_level"] = DpSpec("power_level", "Integer")
        }
        if (state.has("rusKlimat:Mode")) {
            status["rk_mode"] = state.optInt("rusKlimat:Mode")
            spec["rk_mode"] = DpSpec("rk_mode", "Integer")
        }

        // Остальные поля состояния показываем как есть (только чтение): у конвекторов, обогревателей и
        // других «донглов» Rubetek свой набор ключей, его сопоставим по мере знакомства с устройствами
        val keys = state.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k in KNOWN || SERVICE_PREFIXES.any { k.startsWith(it) }) continue
            val v = state.opt(k)
            when (v) {
                is Boolean -> { status[k] = v; spec[k] = DpSpec(k, "Boolean") }
                is Number -> { status[k] = v; spec[k] = DpSpec(k, "Integer") }
                is String -> if (v.length <= 40) { status[k] = v; spec[k] = DpSpec(k, "String") }
                else -> {}
            }
        }

        val online = o.optBoolean("online", true) && (if (state.has("cloud:online")) state.optBoolean("cloud:online") else true)
        val type = state.optString("dev:type").ifEmpty { o.optString("type") }
        val room = o.optString("room")
        val name = o.optString("name").ifEmpty { type.ifEmpty { "Rubetek" } }
        return DeviceUi(
            id = "$PREFIX$houseId:$id",
            name = name,
            online = online,
            productName = buildString {
                append("Rubetek")
                if (type.isNotEmpty()) append(" $type")
                if (room.isNotEmpty()) append(" · $room")
                if (multiHouse) append(" · $houseName")
            },
            category = CATEGORY,
            status = status,
            spec = spec,
            lastDataTime = runCatching { OffsetDateTime.parse(o.optString("updated_at")).toInstant().toEpochMilli() }.getOrDefault(0L),
        )
    }

    /** Команда приложения -> состояние Rubetek; null — такой команды у Rubetek нет. */
    fun toState(code: String, value: Any): Map<String, Any>? {
        Regex("switch_(\\d)").matchEntire(code)?.let { m ->
            val i = m.groupValues[1].toInt() - 1
            val on = value as? Boolean ?: return null
            return mapOf("relay:on[$i]" to on)
        }
        if (code == "temp_set") {
            val v = (value as? Number)?.toInt() ?: return null
            return mapOf("thermostat:setTemp" to v.coerceIn(5, 35))
        }
        if (code == "switch") {
            val on = value as? Boolean ?: return null
            return mapOf("thermostat:setMode" to if (on) 1 else 0)
        }
        if (code == "bright_value") {
            val v = (value as? Number)?.toInt() ?: return null
            return mapOf("rgb:level[1]" to v.coerceIn(0, 100))
        }
        return null
    }

    private val KNOWN = setOf(
        "relay:on[0]", "relay:on[1]", "relay:on[2]", "relay:on[3]", "rgb:level[1]", "pwr:Pact", "pwr:Vrms", "pwr:Irms",
        "thermostat:temp", "thermostat:setTemp", "thermostat:setMode", "rusKlimat:SetPower", "rusKlimat:Mode",
        "rusKlimat:RoomTemp", "rusKlimat:SetTemp", "rusKlimat:SetTempComfortable", "stick:type", "ws:online",
    )
    private val SERVICE_PREFIXES = listOf(
        "dev:", "wifi:", "cloud:", "homekit:", "rf868:", "rtc:", "hub:", "health:", "child:", "protect:",
        "relay:change_src", "relay:off_delay", "relay:on_delay", "relay:state", "tmr:",
    )

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }
}
