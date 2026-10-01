package app.tuyacontrol.heating

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Настройки отопления и сведения о последней записи плана в облако. */
class HeatingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("heating", Context.MODE_PRIVATE)

    fun load(): HeatingSettings {
        val text = prefs.getString(KEY_SETTINGS, null) ?: return HeatingSettings.defaults()
        return try {
            parse(JSONObject(text))
        } catch (e: Exception) {
            HeatingSettings.defaults()
        }
    }

    fun save(s: HeatingSettings) {
        prefs.edit().putString(KEY_SETTINGS, toJson(s).toString()).apply()
    }

    /** Когда план последний раз записан в расписание Tuya (мс) и итог записи. */
    var deployedAt: Long
        get() = prefs.getLong("deployed_at", 0)
        set(v) = prefs.edit().putLong("deployed_at", v).apply()

    var deployResult: String?
        get() = prefs.getString("deploy_result", null)
        set(v) = prefs.edit().putString("deploy_result", v).apply()

    /** Устройства, у которых switch — реле нагрева (его нельзя трогать при записи плана). */
    var relayDevices: Set<String>
        get() = prefs.getStringSet("relay_devices", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("relay_devices", v).apply()

    /** Почасовые уставки устройств, которые переключает телефон (Rubetek): id -> 24 значения. */
    fun saveSchedules(m: Map<String, DoubleArray>) {
        val o = JSONObject()
        m.forEach { (id, sp) -> o.put(id, JSONArray(sp.toList())) }
        prefs.edit().putString("phone_schedules", o.toString()).apply()
    }

    fun loadSchedules(): Map<String, DoubleArray> = try {
        val o = JSONObject(prefs.getString("phone_schedules", "{}")!!)
        o.keys().asSequence().associateWith { id ->
            val a = o.getJSONArray(id)
            DoubleArray(24) { a.optDouble(it, 18.0) }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun toJson(s: HeatingSettings) = JSONObject()
        .put("lat", s.latitude)
        .put("lon", s.longitude)
        .put("autopilot", s.autopilot)
        .put("outdoor_id", s.outdoorSensorId ?: JSONObject.NULL)
        .put("outdoor_code", s.outdoorCode ?: JSONObject.NULL)
        .put("zones", JSONArray().apply {
            s.zones.forEach { z ->
                put(JSONObject()
                    .put("id", z.id)
                    .put("name", z.name)
                    .put("device", z.deviceId ?: JSONObject.NULL)
                    .put("base", z.baseTemp)
                    .put("drop", z.peakDrop)
                    .put("max", z.maxTemp)
                    .put("power", z.powerKw)
                    .put("heat", z.heatRate)
                    .put("loss", z.lossRate)
                    .put("in_total", z.inTotal)
                    .put("control", z.control)
                    .put("peak_heat", z.peakHeat)
                    .put("windows", JSONArray().apply {
                        z.windows.forEach { w ->
                            put(JSONObject().put("from", w.from).put("to", w.to).put("temp", w.temp))
                        }
                    }))
            }
        })

    private fun parse(o: JSONObject): HeatingSettings {
        val zones = mutableListOf<HeatZone>()
        val arr = o.optJSONArray("zones") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val z = arr.optJSONObject(i) ?: continue
            val wa = z.optJSONArray("windows") ?: JSONArray()
            val windows = (0 until wa.length()).mapNotNull { j ->
                wa.optJSONObject(j)?.let { ComfortWindow(it.optInt("from"), it.optInt("to"), it.optDouble("temp", 20.0)) }
            }
            zones += HeatZone(
                id = z.optString("id"),
                name = z.optString("name"),
                deviceId = if (z.isNull("device")) null else z.optString("device").ifEmpty { null },
                windows = windows,
                baseTemp = z.optDouble("base", 16.0),
                peakDrop = z.optDouble("drop", 1.0),
                maxTemp = z.optDouble("max", 25.0),
                powerKw = z.optDouble("power", 1.5),
                heatRate = z.optDouble("heat", 2.0),
                lossRate = z.optDouble("loss", 0.03),
                inTotal = z.optBoolean("in_total", true),
                control = z.optBoolean("control", false),
                peakHeat = z.optBoolean("peak_heat", false),
            )
        }
        return HeatingSettings(
            zones = zones,
            latitude = o.optDouble("lat", 55.75),
            longitude = o.optDouble("lon", 37.62),
            autopilot = o.optBoolean("autopilot", false),
            outdoorSensorId = if (o.isNull("outdoor_id")) null else o.optString("outdoor_id").ifEmpty { null },
            outdoorCode = if (o.isNull("outdoor_code")) null else o.optString("outdoor_code").ifEmpty { null },
        )
    }

    private companion object {
        const val KEY_SETTINGS = "settings"
    }
}
