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
        if (code == "bright_value") {
            val v = (value as? Number)?.toInt() ?: return null
            return mapOf("rgb:level[1]" to v.coerceIn(0, 100))
        }
        return null
    }

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }
}
