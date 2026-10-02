package app.tuyacontrol.data

import app.tuyacontrol.cloud.DpSpec
import java.math.BigDecimal

/** Понятные названия распространённых кодов Tuya. */
object DpLabels {
    private val labels = mapOf(
        "switch" to "Питание",
        "switch_1" to "Канал 1",
        "switch_2" to "Канал 2",
        "switch_3" to "Канал 3",
        "switch_4" to "Канал 4",
        "switch_led" to "Подсветка",
        "cur_power" to "Мощность",
        "cur_voltage" to "Напряжение",
        "cur_current" to "Ток",
        "add_ele" to "Энергия",
        "total_forward_energy" to "Энергия всего",
        "phase_a" to "Фаза A",
        "va_temperature" to "Температура",
        "va_humidity" to "Влажность",
        "temp_current" to "Температура",
        "temp_value" to "Температура",
        "humidity_value" to "Влажность",
        "temp_set" to "Уставка",
        "upper_temp" to "Макс. уставка",
        "lower_temp" to "Мин. уставка",
        "temp_correction" to "Коррекция темп.",
        "floor_temp" to "Темп. пола",
        "mode" to "Режим",
        "work_state" to "Состояние",
        "child_lock" to "Блокировка",
        "battery_percentage" to "Батарея",
        "battery_state" to "Батарея",
        "alarm_switch" to "Сирена",
        "alarm_volume" to "Громкость",
        "countdown_1" to "Таймер 1",
        "relay_status" to "После подачи питания",
        "fault" to "Ошибка",
        "power_level" to "Ступень мощности",
        "rk_mode" to "Режим конвектора",
        "thermostat:mode" to "Нагрев сейчас",
    )

    /** Коды, которые показываем сразу; остальное — под «Подробнее». */
    private val primary = setOf(
        "cur_power", "cur_voltage", "cur_current", "add_ele", "total_forward_energy",
        "va_temperature", "va_humidity", "temp_current", "temp_value", "humidity_value",
        "temp_set", "floor_temp", "mode", "work_state", "battery_percentage", "battery_state",
    )

    /** Подписи, которые устройства сообщают сами (например, свойства Xiaomi MIoT). */
    private val extra = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun register(code: String, label: String) {
        if (label.isNotEmpty() && code !in labels) extra[code] = label
    }

    fun label(code: String): String = labels[code] ?: extra[code] ?: code

    fun isPrimary(code: String, spec: DpSpec?): Boolean =
        code in primary || (spec?.writable == true && spec.type == "Boolean" && code.startsWith("switch"))
}

object DpFormat {

    fun format(value: Any?, spec: DpSpec?): String {
        if (value == null) return "—"
        return when (value) {
            is Boolean -> if (value) "вкл" else "выкл"
            is Number -> formatNumber(value, spec)
            is String -> if (value.length > 40) value.take(40) + "…" else value
            else -> value.toString().take(40)
        }
    }

    fun formatNumber(value: Number, spec: DpSpec?): String {
        val scale = spec?.scale ?: 0
        val number = if (scale > 0 && (value is Int || value is Long)) {
            BigDecimal.valueOf(value.toLong()).movePointLeft(scale).toPlainString()
        } else {
            value.toString()
        }
        val unit = spec?.unit.orEmpty()
        return if (unit.isEmpty()) number else "$number $unit"
    }

    fun asLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
