package app.tuyacontrol.commands

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Группа устройств, к которой относится действие. */
enum class Target(val title: String) {
    ZONES("Отопление: все зоны"),
    ZONE("Отопление: зона"),
    RUBETEK("Rubetek"),
    XIAOMI("Xiaomi"),
    HEATING("Отопление (кроме ванны)"),
    BATH("Ванна"),
    WATER("Водогрейки"),
}

/** Что можно сделать. [needsTemp] — действие с температурой. */
enum class ActionKind(val target: Target, val title: String, val needsTemp: Boolean = false) {
    ZONES_CONTROL_ON(Target.ZONES, "Управление по плану — включить"),
    ZONES_CONTROL_OFF(Target.ZONES, "Управление по плану — выключить"),
    /** Одна зона (id зоны — в Action.deviceId, название — в deviceName). */
    ZONE_CONTROL_ON(Target.ZONE, "Управление по плану — включить"),

    RUBETEK_ON(Target.RUBETEK, "Включить"),
    RUBETEK_OFF(Target.RUBETEK, "Выключить"),
    RUBETEK_CLEAR_TIMERS(Target.RUBETEK, "Удалить все расписания"),
    RUBETEK_SET_TEMP(Target.RUBETEK, "Установить температуру", needsTemp = true),

    XIAOMI_ON(Target.XIAOMI, "Включить"),
    XIAOMI_OFF(Target.XIAOMI, "Выключить"),
    XIAOMI_SET_TEMP(Target.XIAOMI, "Установить температуру", needsTemp = true),

    HEATING_ON(Target.HEATING, "Включить"),
    HEATING_OFF(Target.HEATING, "Выключить"),
    HEATING_MANUAL(Target.HEATING, "Режим ручной"),
    HEATING_AUTO(Target.HEATING, "Режим авто (по программе)"),
    HEATING_SET_TEMP(Target.HEATING, "Ручной режим и температура", needsTemp = true),

    BATH_ON(Target.BATH, "Включить (пороги 19° / 20°)"),
    BATH_OFF(Target.BATH, "Выключить (пороги 12° / 13°)"),

    WATER_ON(Target.WATER, "Включить"),
    WATER_OFF(Target.WATER, "Выключить"),
    WATER_TIMERS_ON(Target.WATER, "Включить все события расписания"),
    WATER_TIMERS_OFF(Target.WATER, "Выключить все события расписания"),
}

/**
 * Одно действие. [deviceId] — конкретное устройство (имя запоминаем для показа); null — вся группа
 * (так только «все зоны отопления» и старые команды).
 */
data class Action(
    val kind: ActionKind,
    val temp: Double? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
) {
    fun text(): String = "${deviceName ?: kind.target.title}: ${kind.title}" +
        (if (kind.needsTemp && temp != null) " ${fmt(temp)}°" else "")

    private fun fmt(t: Double) = if (t % 1.0 == 0.0) t.toInt().toString() else t.toString().replace('.', ',')
}

data class Command(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Индекс цвета из палитры [app.tuyacontrol.ui.Pastel]. */
    val color: Int,
    val actions: List<Action>,
)

/** Команды хранятся в настройках приложения. */
class CommandStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("commands", Context.MODE_PRIVATE)

    fun load(): List<Command> = try {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val acts = o.optJSONArray("actions") ?: JSONArray()
            Command(
                id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                name = o.optString("name"),
                color = o.optInt("color"),
                actions = (0 until acts.length()).mapNotNull { j ->
                    val a = acts.optJSONObject(j) ?: return@mapNotNull null
                    val kind = runCatching { ActionKind.valueOf(a.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                    Action(
                        kind,
                        if (a.has("temp") && !a.isNull("temp")) a.optDouble("temp") else null,
                        a.optString("device").ifEmpty { null },
                        a.optString("device_name").ifEmpty { null },
                    )
                },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<Command>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject().put("id", c.id).put("name", c.name).put("color", c.color).put(
                    "actions",
                    JSONArray().apply {
                        c.actions.forEach { a ->
                            put(
                                JSONObject().put("kind", a.kind.name).put("temp", a.temp ?: JSONObject.NULL)
                                    .put("device", a.deviceId ?: "").put("device_name", a.deviceName ?: ""),
                            )
                        }
                    },
                ),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}

/** Устройства группы: Rubetek — все конвекторы; «Отопление» и «Водогрейки» — по названию категории пользователя. */
fun groupDevices(
    target: Target,
    devices: List<app.tuyacontrol.DeviceUi>,
    categories: List<app.tuyacontrol.data.Category>,
    prefs: Map<String, app.tuyacontrol.data.DevicePref>,
): List<app.tuyacontrol.DeviceUi> {
    fun byCategory(hint: String): List<app.tuyacontrol.DeviceUi> {
        val ids = categories.filter { it.name.contains(hint, ignoreCase = true) }.map { it.id }.toSet()
        return devices.filter { prefs[it.id]?.categoryId in ids }
    }
    val rubetek = { d: app.tuyacontrol.DeviceUi -> app.tuyacontrol.rubetek.RubetekMapper.isRubetek(d.id) }
    return when (target) {
        Target.ZONES, Target.ZONE -> emptyList()
        Target.RUBETEK -> devices.filter { rubetek(it) && it.setpointCode != null }
        Target.XIAOMI -> devices.filter { app.tuyacontrol.xiaomi.XiaomiMapper.isXiaomi(it.id) && it.setpointCode != null }
        Target.HEATING -> byCategory("отоплен").filter {
            !rubetek(it) && !app.tuyacontrol.xiaomi.XiaomiMapper.isXiaomi(it.id) && it.heatingPresetOn == null
        }
        Target.BATH -> devices.filter { it.heatingPresetOn != null }
        Target.WATER -> byCategory("водогр")
    }.sortedBy { it.name.lowercase() }
}
