package app.tuyacontrol.nartis

import android.content.Context
import app.tuyacontrol.data.AppLog
import org.json.JSONArray
import org.json.JSONObject

/** Одно прочитанное показание счётчика с датой и временем. */
data class NartisSnapshot(
    val time: Long,
    val total: Double?,
    val t1: Double?,
    val t2: Double?,
    val t3: Double?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time)
        .put("total", total ?: JSONObject.NULL)
        .put("t1", t1 ?: JSONObject.NULL)
        .put("t2", t2 ?: JSONObject.NULL)
        .put("t3", t3 ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): NartisSnapshot = NartisSnapshot(
            time = o.optLong("time"),
            total = o.optDouble("total").takeUnless { it.isNaN() },
            t1 = o.optDouble("t1").takeUnless { it.isNaN() },
            t2 = o.optDouble("t2").takeUnless { it.isNaN() },
            t3 = o.optDouble("t3").takeUnless { it.isNaN() },
        )
    }
}

/** Настройки и история счётчика НАРТИС (хранятся только на телефоне). */
class NartisStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("nartis", Context.MODE_PRIVATE)

    var serial: String
        get() = prefs.getString(KEY_SERIAL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERIAL, value.trim()).apply()

    fun snapshots(): List<NartisSnapshot> = try {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { NartisSnapshot.fromJson(it) }
        }
    } catch (e: Exception) {
        AppLog.e("История НАРТИС повреждена", e)
        emptyList()
    }

    /** Добавляет показание, если оно изменилось или прошло больше 10 минут. */
    fun add(snapshot: NartisSnapshot): List<NartisSnapshot> {
        val list = snapshots().toMutableList()
        val last = list.lastOrNull()
        val changed = last == null || last.total != snapshot.total || last.t1 != snapshot.t1 ||
            last.t2 != snapshot.t2 || last.t3 != snapshot.t3
        if (!changed && snapshot.time - last!!.time < 10 * 60_000L) return list
        list += snapshot
        val trimmed = list.takeLast(5000)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        return trimmed
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private companion object {
        const val KEY_SERIAL = "serial"
        const val KEY_HISTORY = "history"
    }
}
