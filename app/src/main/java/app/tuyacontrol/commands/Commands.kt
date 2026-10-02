package app.tuyacontrol.commands

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Группа устройств, к которой относится действие. */
enum class Target(val title: String) {
    ZONES("Отопление: все зоны"),
    RUBETEK("Rubetek"),
    HEATING("Отопление (кроме ванны)"),
    BATH("Ванна"),
    WATER("Водогрейки"),
}

/** Что можно сделать. [needsTemp] — действие с температурой. */
enum class ActionKind(val target: Target, val title: String, val needsTemp: Boolean = false) {
    ZONES_CONTROL_ON(Target.ZONES, "Управление по плану — включить"),
    ZONES_CONTROL_OFF(Target.ZONES, "Управление по плану — выключить"),

    RUBETEK_ON(Target.RUBETEK, "Включить"),
    RUBETEK_OFF(Target.RUBETEK, "Выключить"),
    RUBETEK_CLEAR_TIMERS(Target.RUBETEK, "Удалить все расписания"),
    RUBETEK_SET_TEMP(Target.RUBETEK, "Установить температуру", needsTemp = true),

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

data class Action(val kind: ActionKind, val temp: Double? = null) {
    fun text(): String = "${kind.target.title}: ${kind.title}" +
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
                    Action(kind, if (a.has("temp") && !a.isNull("temp")) a.optDouble("temp") else null)
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
                        c.actions.forEach { a -> put(JSONObject().put("kind", a.kind.name).put("temp", a.temp ?: JSONObject.NULL)) }
                    },
                ),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
