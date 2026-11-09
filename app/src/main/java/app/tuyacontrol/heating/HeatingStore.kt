package app.tuyacontrol.heating

import android.content.Context
import app.tuyacontrol.rubetek.RubetekMapper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки отопления и сведения о последней записи плана в облако.
 *
 * Два профиля с одинаковым устройством: «Отопление» (дома) и «Дежурство» (уехал — держать комнаты от замерзания).
 * Зоны, окна и параметры у каждого свои; всё про устройства (что записано, расписания телефона) — общее.
 * В устройства пишется действующий профиль ([loadActive]).
 */
/** Уставка, на которой считается конвектор Rubetek на дежурстве. */
const val DUTY_RUBETEK_SETPOINT = 12.0

class HeatingStore(context: Context, private val duty: Boolean = false) {

    private val prefs = context.applicationContext.getSharedPreferences("heating", Context.MODE_PRIVATE)

    fun load(): HeatingSettings = if (duty) loadDuty() else loadHome()

    fun save(s: HeatingSettings) {
        if (!duty) {
            prefs.edit().putString(KEY_SETTINGS, toJson(s).toString()).apply()
            return
        }
        prefs.edit().putString(KEY_DUTY, toJson(s).toString()).apply()
        // Место дома и уличный датчик — общие для обоих профилей
        val home = loadHome()
        if (home.latitude != s.latitude || home.longitude != s.longitude || home.outdoorSensorId != s.outdoorSensorId || home.outdoorCode != s.outdoorCode) {
            val h = home.copy(latitude = s.latitude, longitude = s.longitude, outdoorSensorId = s.outdoorSensorId, outdoorCode = s.outdoorCode)
            prefs.edit().putString(KEY_SETTINGS, toJson(h).toString()).apply()
        }
    }

    private fun loadHome(): HeatingSettings {
        val text = prefs.getString(KEY_SETTINGS, null) ?: return HeatingSettings.defaults()
        return try {
            parse(JSONObject(text))
        } catch (e: Exception) {
            HeatingSettings.defaults()
        }
    }

    /** Профиль «Дежурство»: свои зоны (вначале пусто), место дома и уличный датчик — из «Отопления». */
    private fun loadDuty(): HeatingSettings {
        val home = loadHome()
        val own = prefs.getString(KEY_DUTY, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            ?: HeatingSettings(zones = emptyList())
        return own.copy(
            // Конвекторы Rubetek на дежурстве считаются стоящими на 12°, пока в зоне не задано другое
            zones = own.zones.map { z ->
                if (z.assumedSetpoint == null && z.deviceId?.let { RubetekMapper.isRubetek(it) } == true) z.copy(assumedSetpoint = DUTY_RUBETEK_SETPOINT) else z
            },
            latitude = home.latitude, longitude = home.longitude,
            outdoorSensorId = home.outdoorSensorId, outdoorCode = home.outdoorCode, autopilot = false,
        )
    }

    /**
     * Что сейчас пишется в устройства: зоны обеих вкладок. Прибор управляется только с одной вкладки —
     * включение управления на одной выключает его на другой ([HeatingEngine.switchControl]).
     */
    fun loadActive(): HeatingSettings {
        val home = loadHome()
        val d = loadDuty()
        val taken = d.zones.filter { it.control }.mapNotNull { it.deviceId }.toSet()
        // На всякий случай (старые настройки): если прибор под управлением на обеих вкладках — действует «Дежурство»
        return home.copy(zones = home.zones.map { if (it.control && it.deviceId in taken) it.copy(control = false) else it } + d.zones)
    }

    /** Зоны обоих профилей (для истории и подбора параметров). */
    fun allZones(): List<HeatZone> = loadHome().zones + loadDuty().zones

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

    /**
     * Что приложение записало в модули Rubetek и проверило (id -> «минуты:1/0,…»).
     * Нет записи — приложение таймеров в этот модуль не писало (или сняло).
     */
    fun deployedTimers(): Map<String, List<Pair<Int, Boolean>>> = try {
        val o = JSONObject(prefs.getString("rubetek_deployed", "{}")!!)
        o.keys().asSequence().associateWith { id ->
            o.getString(id).split(",").filter { it.contains(":") }.map {
                val (m, on) = it.split(":")
                m.toInt() to (on == "1")
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun setDeployedTimers(id: String, events: List<Pair<Int, Boolean>>?) {
        val o = runCatching { JSONObject(prefs.getString("rubetek_deployed", "{}")!!) }.getOrDefault(JSONObject())
        if (events == null) o.remove(id) else o.put(id, events.joinToString(",") { "${it.first}:${if (it.second) 1 else 0}" })
        prefs.edit().putString("rubetek_deployed", o.toString()).apply()
    }

    /** Программа, которую приложение записало в термостат и проверило: id -> base64 значения DP. */
    fun deployedPrograms(): Map<String, String> = try {
        val o = JSONObject(prefs.getString("programs_deployed", "{}")!!)
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    fun setDeployedProgram(id: String, value: String?) {
        val o = runCatching { JSONObject(prefs.getString("programs_deployed", "{}")!!) }.getOrDefault(JSONObject())
        if (value == null) o.remove(id) else o.put(id, value)
        prefs.edit().putString("programs_deployed", o.toString()).apply()
    }

    /** Итоги подбора параметров по истории: id зоны -> итог. */
    fun learned(): Map<String, LearnInfo> = try {
        val o = JSONObject(prefs.getString("learned", "{}")!!)
        o.keys().asSequence().associateWith { k ->
            val j = o.getJSONObject(k)
            LearnInfo(
                at = j.optLong("at"),
                heatRate = if (j.isNull("heat")) null else j.optDouble("heat"),
                lossRate = if (j.isNull("loss")) null else j.optDouble("loss"),
                coolHours = j.optInt("cool"),
                heatHours = j.optInt("hot"),
                days = j.optInt("days"),
                problem = if (j.isNull("problem")) null else j.optString("problem"),
                applied = j.optBoolean("applied"),
                note = if (j.isNull("note")) null else j.optString("note").ifEmpty { null },
            )
        }
    } catch (e: Exception) {
        emptyMap()
    }

    fun saveLearned(m: Map<String, LearnInfo>) {
        val o = JSONObject()
        m.forEach { (k, v) ->
            o.put(k, JSONObject().put("at", v.at).put("heat", v.heatRate ?: JSONObject.NULL).put("loss", v.lossRate ?: JSONObject.NULL)
                .put("cool", v.coolHours).put("hot", v.heatHours).put("days", v.days)
                .put("problem", v.problem ?: JSONObject.NULL).put("applied", v.applied).put("note", v.note ?: JSONObject.NULL))
        }
        prefs.edit().putString("learned", o.toString()).apply()
    }

    /** Незавершённый тест таймера Rubetek: «id|слот» — при следующем запуске слот очищается. */
    var rubetekTest: String?
        get() = prefs.getString("rubetek_test", null)
        set(v) = prefs.edit().putString("rubetek_test", v).apply()

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
                    .put("hyst", z.hysteresis ?: JSONObject.NULL)
                    .put("store_heat", z.storeHeat ?: JSONObject.NULL)
                    .put("auto_tune", z.autoTune)
                    .put("temp_device", z.tempDeviceId ?: JSONObject.NULL)
                    .put("assumed_set", z.assumedSetpoint ?: JSONObject.NULL)
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
                hysteresis = if (z.isNull("hyst") || !z.has("hyst")) null else z.optDouble("hyst"),
                storeHeat = if (z.isNull("store_heat") || !z.has("store_heat")) null else z.optBoolean("store_heat"),
                autoTune = z.optBoolean("auto_tune", true),
                tempDeviceId = if (z.isNull("temp_device") || !z.has("temp_device")) null else z.optString("temp_device").ifEmpty { null },
                assumedSetpoint = if (!z.has("assumed_set") || z.isNull("assumed_set")) null else z.optDouble("assumed_set"),
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
        const val KEY_DUTY = "settings_duty"
    }
}
