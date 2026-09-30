package app.tuyacontrol.background

import android.content.Context
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.energy.EnergyDevice
import app.tuyacontrol.sensor.SensorChannel
import app.tuyacontrol.sensor.SensorDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * Что качать в фоне: счётчики и датчики, как их определило приложение при последнем обновлении
 * списка устройств. Хранится, чтобы фоновой задаче не повторять всю логику распознавания.
 * Здесь же — состояние фоновой загрузки для экрана настроек.
 */
class SyncTargets(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("background_sync", Context.MODE_PRIVATE)

    fun save(energy: List<EnergyDevice>, sensors: List<SensorDevice>) {
        val e = JSONArray()
        energy.forEach { d ->
            e.put(
                JSONObject().put("id", d.id).put("name", d.name).put("active", d.activeTime)
                    .put("codes", JSONArray(d.codes.toList())).put("thing", d.thingModel),
            )
        }
        val s = JSONArray()
        sensors.forEach { d ->
            s.put(
                JSONObject().put("id", d.id).put("name", d.name).put("thing", d.thingModel)
                    .put("temp", d.temperature?.let(::channelJson)).put("hum", d.humidity?.let(::channelJson)),
            )
        }
        prefs.edit().putString("energy", e.toString()).putString("sensors", s.toString()).apply()
    }

    fun energy(): List<EnergyDevice> = try {
        val arr = JSONArray(prefs.getString("energy", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val codes = o.optJSONArray("codes") ?: JSONArray()
            EnergyDevice(
                id = o.getString("id"),
                name = o.optString("name"),
                activeTime = o.optLong("active"),
                codes = (0 until codes.length()).map { codes.getString(it) }.toSet(),
                thingModel = o.optBoolean("thing"),
            )
        }
    } catch (e: Exception) {
        AppLog.e("Фон: список счётчиков повреждён", e)
        emptyList()
    }

    fun sensors(): List<SensorDevice> = try {
        val arr = JSONArray(prefs.getString("sensors", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SensorDevice(
                id = o.getString("id"),
                name = o.optString("name"),
                thingModel = o.optBoolean("thing"),
                temperature = o.optJSONObject("temp")?.let(::channel),
                humidity = o.optJSONObject("hum")?.let(::channel),
            )
        }
    } catch (e: Exception) {
        AppLog.e("Фон: список датчиков повреждён", e)
        emptyList()
    }

    // ---------- Состояние фоновой загрузки ----------

    var lastRun: Long
        get() = prefs.getLong("last_run", 0)
        set(v) = prefs.edit().putLong("last_run", v).apply()

    var lastSuccess: Long
        get() = prefs.getLong("last_success", 0)
        set(v) = prefs.edit().putLong("last_success", v).apply()

    var lastResult: String
        get() = prefs.getString("last_result", "").orEmpty()
        set(v) = prefs.edit().putString("last_result", v).apply()

    private fun channelJson(c: SensorChannel) = JSONObject().put("code", c.code).put("scale", c.scale).put("unit", c.unit)

    private fun channel(o: JSONObject) = SensorChannel(o.getString("code"), o.optInt("scale"), o.optString("unit"))
}
