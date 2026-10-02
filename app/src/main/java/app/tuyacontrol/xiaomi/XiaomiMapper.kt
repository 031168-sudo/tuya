package app.tuyacontrol.xiaomi

import app.tuyacontrol.DeviceUi
import app.tuyacontrol.cloud.DpSpec
import app.tuyacontrol.data.DpLabels
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Устройства Xiaomi в общем списке как DeviceUi. Свойства MIoT (siid/piid) становятся кодами приложения:
 * «on» -> switch, «target-temperature» -> temp_set, «temperature» -> temp_current и т.д.; остальные —
 * mi_<siid>_<piid> с подписью из описания модели. Дробные значения хранятся целыми со scale, как у Tuya.
 * id — «xiaomi:<did>».
 */
object XiaomiMapper {
    const val PREFIX = "xiaomi:"
    const val CATEGORY = "xiaomi"

    fun isXiaomi(id: String) = id.startsWith(PREFIX)
    fun did(id: String) = id.removePrefix(PREFIX)

    fun toDevice(
        d: MiDevice,
        props: List<MiProp>,
        values: Map<Pair<Int, Int>, Any?>,
        online: Boolean,
        viaLocal: Boolean,
        dataTime: Long,
    ): DeviceUi {
        val status = LinkedHashMap<String, Any?>()
        val spec = LinkedHashMap<String, DpSpec>()
        for (p in props) {
            DpLabels.register(p.code, p.label)
            val raw = values[p.siid to p.piid] ?: continue
            val f = 10.0.pow(p.scale)
            when {
                p.format == "bool" -> {
                    status[p.code] = raw == true || raw.toString() == "true" || raw.toString() == "1"
                    spec[p.code] = DpSpec(p.code, "Boolean", writable = p.writable)
                }
                p.values.isNotEmpty() -> {
                    val n = (raw as? Number)?.toInt()
                    status[p.code] = n?.let { p.values[it] } ?: raw.toString()
                    spec[p.code] = DpSpec(p.code, "String")
                }
                raw is Number -> {
                    status[p.code] = (raw.toDouble() * f).roundToLong()
                    spec[p.code] = DpSpec(
                        p.code, "Integer", unit = p.unit, scale = p.scale,
                        min = p.min?.let { (it * f).roundToLong() },
                        max = p.max?.let { (it * f).roundToLong() },
                        step = p.step?.let { (it * f).roundToLong().coerceAtLeast(1) } ?: 1,
                        writable = p.writable && p.min != null && p.max != null,
                    )
                }
                else -> {
                    status[p.code] = raw.toString()
                    spec[p.code] = DpSpec(p.code, "String")
                }
            }
        }
        return DeviceUi(
            id = PREFIX + d.did,
            name = d.name,
            online = online,
            productName = "Xiaomi ${d.model}" + (if (viaLocal) "" else if (online) " · облако" else ""),
            category = CATEGORY,
            status = status,
            spec = spec,
            lastDataTime = dataTime,
            viaLocal = viaLocal,
        )
    }

    /** Значение из приложения -> значение MIoT; null — свойство нельзя менять. */
    fun toMiot(p: MiProp, value: Any): Any? {
        if (!p.writable) return null
        if (p.format == "bool") return value as? Boolean
        val n = (value as? Number)?.toDouble() ?: return null
        val v = n / 10.0.pow(p.scale)
        return if (p.format == "float") v else v.roundToLong()
    }
}
