package app.tuyacontrol.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Прямоугольник в миллиметрах (ось Y вниз). */
data class MmRect(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun contains(x: Float, y: Float) = x in x1..x2 && y in y1..y2
}

/** Отрезок в миллиметрах; для стены — толщина, для двери — сторона распахивания (+1/-1). */
data class MmSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val extra: Float = 0f) {
    val vertical: Boolean get() = x1 == x2
}

data class PlanRoom(val id: String, val name: String, val rect: MmRect, val area: Double)
data class PlanTerrace(val name: String, val rect: MmRect)

data class PlanFloor(
    val id: String,
    val title: String,
    /** Габарит по осям, мм. */
    val width: Float,
    val height: Float,
    val rooms: List<PlanRoom>,
    val terraces: List<PlanTerrace>,
    /** extra — толщина стены, мм. */
    val walls: List<MmSeg>,
    /** Проём двери: петли в первой точке, extra — в какую сторону распахивается (+1/-1). */
    val doors: List<MmSeg>,
    val windows: List<MmSeg>,
    /** Проёмы во второй стене под дверью (где две стены стоят вплотную), рисуются фоном поверх стен. */
    val holes: List<MmRect> = emptyList(),
) {
    /** Толщина стены на линии x=c (vertical) или y=c, перекрывающей отрезок [a, b]; 0 — стены нет (как wall_on в gen_plans.py). */
    fun wallOn(vertical: Boolean, c: Float, a: Float, b: Float): Float {
        var best = 0f
        for (w in walls) {
            if (vertical && w.vertical && w.x1 == c &&
                minOf(w.y1, w.y2) < maxOf(a, b) && maxOf(w.y1, w.y2) > minOf(a, b)
            ) best = maxOf(best, w.extra)
            if (!vertical && !w.vertical && w.y1 == c &&
                minOf(w.x1, w.x2) < maxOf(a, b) && maxOf(w.x1, w.x2) > minOf(a, b)
            ) best = maxOf(best, w.extra)
        }
        return best
    }

    /** Толщина стены, на которой лежит проём. */
    fun wallUnder(s: MmSeg): Float {
        val t = if (s.vertical) wallOn(true, s.x1, s.y1, s.y2) else wallOn(false, s.y1, s.x1, s.x2)
        return if (t > 0f) t else OUTER_WALL
    }

    /** Комната «в чистоте»: прямоугольник по осям минус половины стен с каждой стороны. */
    fun interior(r: MmRect): MmRect = MmRect(
        r.x1 + wallOn(true, r.x1, r.y1, r.y2) / 2,
        r.y1 + wallOn(false, r.y1, r.x1, r.x2) / 2,
        r.x2 - wallOn(true, r.x2, r.y1, r.y2) / 2,
        r.y2 - wallOn(false, r.y2, r.x1, r.x2) / 2,
    )

    companion object {
        const val OUTER_WALL = 350f
    }
}

/** План дома из assets/plans/house.json (его собирает gen_plans.py.txt). */
object HousePlan {

    @Volatile private var cached: List<PlanFloor>? = null

    fun load(context: Context): List<PlanFloor> = cached ?: try {
        val text = context.assets.open("plans/house.json").bufferedReader().use { it.readText() }
        parse(text).also { cached = it }
    } catch (e: Exception) {
        AppLog.e("План дома не прочитан", e)
        emptyList()
    }

    fun parse(text: String): List<PlanFloor> {
        val floors = JSONObject(text).getJSONArray("floors")
        return (0 until floors.length()).map { i ->
            val f = floors.getJSONObject(i)
            val b = f.getJSONArray("bounds")
            PlanFloor(
                id = f.getString("id"),
                title = f.optString("title"),
                width = b.getDouble(2).toFloat(),
                height = b.getDouble(3).toFloat(),
                rooms = f.optJSONArray("rooms").objects().map { r ->
                    PlanRoom(r.getString("id"), r.optString("name"), r.getJSONArray("rect").rect(), r.optDouble("area", 0.0))
                },
                terraces = f.optJSONArray("terraces").objects().map { t ->
                    PlanTerrace(t.optString("name"), t.getJSONArray("rect").rect())
                },
                walls = f.optJSONArray("walls").arrays().map { it.seg() },
                doors = f.optJSONArray("doors").arrays().map { it.seg() },
                windows = f.optJSONArray("windows").arrays().map { it.seg() },
                holes = f.optJSONArray("holes").arrays().map { it.rect() },
            )
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.arrays(): List<JSONArray> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONArray(it) }

    private fun JSONArray.f(i: Int) = optDouble(i, 0.0).toFloat()

    private fun JSONArray.rect() = MmRect(f(0), f(1), f(2), f(3))

    private fun JSONArray.seg() = MmSeg(f(0), f(1), f(2), f(3), if (length() > 4) f(4) else 0f)
}

/** Комнаты всех этажей с названиями, заданными пользователем: (id, название) в порядке плана. */
fun roomNames(context: Context): List<Pair<String, String>> {
    val settings = RoomStore(context).load()
    return HousePlan.load(context).flatMap { it.rooms }.map { it.id to (settings[it.id]?.name ?: it.name) }
}

/** Настройки комнаты, заданные пользователем на экране «Карта». */
data class RoomSetting(val name: String? = null, val deviceId: String? = null)

/** Названия комнат и привязанные датчики — только в телефоне (SharedPreferences, JSON по id комнаты). */
class RoomStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("house_rooms", Context.MODE_PRIVATE)

    fun load(): Map<String, RoomSetting> = try {
        val o = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
        o.keys().asSequence().associateWith { id ->
            val r = o.getJSONObject(id)
            RoomSetting(
                name = r.optString("name").ifBlank { null },
                deviceId = r.optString("device").ifBlank { null },
            )
        }
    } catch (e: Exception) {
        AppLog.e("Настройки комнат повреждены", e)
        emptyMap()
    }

    fun save(map: Map<String, RoomSetting>) {
        val o = JSONObject()
        map.forEach { (id, r) ->
            if (r.name == null && r.deviceId == null) return@forEach
            o.put(id, JSONObject().put("name", r.name ?: "").put("device", r.deviceId ?: ""))
        }
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private companion object {
        const val KEY = "rooms"
    }
}
