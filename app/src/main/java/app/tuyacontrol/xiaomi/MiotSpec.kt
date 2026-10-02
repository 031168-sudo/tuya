package app.tuyacontrol.xiaomi

import android.content.Context
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Одно свойство MIoT, как его показывает приложение. */
data class MiProp(
    val siid: Int,
    val piid: Int,
    /** Код в приложении: switch, temp_set, temp_current… или mi_<siid>_<piid>. */
    val code: String,
    /** Название для экрана. */
    val label: String,
    /** bool, uint8, int32, float, string… */
    val format: String,
    val readable: Boolean,
    val writable: Boolean,
    val unit: String,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    /** Значение -> описание (для перечислений). */
    val values: Map<Int, String>,
    /** Число 0/1, показываем переключателем наоборот: 0 — вкл (индикатор обогревателей zhimi). */
    val invertedSwitch: Boolean = false,
) {
    /** Сколько знаков после запятой: 0 для шага 1, 1 для 0,1 и 0,5. */
    val scale: Int
        get() {
            val s = step ?: return if (format == "float") 1 else 0
            return if (s >= 1.0 && s == Math.floor(s)) 0 else 1
        }
}

/**
 * Описание устройства из открытого реестра MIoT (miot-spec.org): какие свойства у модели, их диапазоны,
 * можно ли их менять. Скачивается один раз на модель и хранится на телефоне.
 */
class MiotSpec(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("miot_spec", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val memory = java.util.concurrent.ConcurrentHashMap<String, List<MiProp>>()
    /** Реестр моделей большой — качаем по одной модели за раз, чтобы не тянуть его параллельно дважды. */
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()

    /** Свойства модели; пустой список — описания нет. */
    suspend fun props(model: String): List<MiProp> {
        memory[model]?.let { return it }
        val cached = prefs.getString("spec:$model", null) ?: BUILT_IN[model]
        val json = cached ?: downloadMutex.withLock { prefs.getString("spec:$model", null) ?: runCatching { download(model) }
            .onFailure { AppLog.e("Xiaomi: описание модели $model не скачано", it) }
            .getOrNull()
            ?.also { prefs.edit().putString("spec:$model", it).apply() } }
            ?: return emptyList()
        val list = runCatching { parse(JSONObject(json), model) }.onFailure { AppLog.e("Xiaomi: описание $model не разобрано", it) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) memory[model] = list
        return list
    }

    private suspend fun download(model: String): String = withContext(Dispatchers.IO) {
        val type = instanceType(model) ?: throw IllegalStateException("модели $model нет в реестре MIoT")
        get("$BASE/instance?type=$type")
    }

    /** Тип (urn) последней выпущенной версии модели. */
    private fun instanceType(model: String): String? {
        val all = prefs.getString("instances", null)?.takeIf {
            System.currentTimeMillis() - prefs.getLong("instances_at", 0) < 30L * 24 * 3600_000
        } ?: get("$BASE/instances?status=released").also {
            prefs.edit().putString("instances", it).putLong("instances_at", System.currentTimeMillis()).apply()
        }
        val a = JSONObject(all).optJSONArray("instances") ?: return null
        var best: JSONObject? = null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("model") != model) continue
            if (best == null || o.optInt("version") > best.optInt("version")) best = o
        }
        return best?.optString("type")
    }

    private fun get(url: String): String {
        AppLog.i("MIoT → GET ${url.substringBefore("?")}")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("miot-spec.org: HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    companion object {
        private const val BASE = "https://miot-spec.org/miot-spec-v2"

        /** Свойство «тип» -> код приложения (чтобы работали общие подписи, иконки, уставки). */
        private val KNOWN = mapOf(
            "target-temperature" to "temp_set",
            "temperature" to "temp_current",
            "relative-humidity" to "humidity_value",
            "physical-controls-locked" to "child_lock",
            "countdown-time" to "countdown",
            "alarm" to "buzzer",
            "brightness" to "indicator",
            "mode" to "mode",
            "fault" to "fault",
            "heat-level" to "power_level",
        )

        /** Подписи по-русски для частых свойств. */
        val LABELS = mapOf(
            "countdown" to "Выключить через",
            "buzzer" to "Звук",
            "indicator" to "Яркость индикатора",
            "child_lock" to "Блокировка кнопок",
        )

        /** Частные свойства обогревателей zhimi.heater: «siid.piid» -> подпись. */
        private val PRIVATE_LABELS = mapOf(
            "8.8" to "Постоянная температура",
            "8.9" to "Наработка",
        )

        private val UNITS = mapOf("celsius" to "°C", "percentage" to "%", "hours" to "ч", "minutes" to "мин", "seconds" to "с", "watt" to "Вт")

        /** Сервисы, которые не показываем: сведения об устройстве. */
        private val SKIP_SERVICES = setOf("device-information")

        fun parse(spec: JSONObject, model: String = ""): List<MiProp> {
            val out = mutableListOf<MiProp>()
            val used = HashSet<String>()
            val services = spec.optJSONArray("services") ?: JSONArray()
            for (i in 0 until services.length()) {
                val s = services.optJSONObject(i) ?: continue
                val siid = s.optInt("iid")
                val sType = name(s.optString("type"))
                if (sType in SKIP_SERVICES) continue
                val props = s.optJSONArray("properties") ?: continue
                for (k in 0 until props.length()) {
                    val p = props.optJSONObject(k) ?: continue
                    val piid = p.optInt("iid")
                    val access = p.optJSONArray("access")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    val readable = "read" in access
                    if (!readable) continue
                    val format = p.optString("format")
                    if (format == "string") continue
                    val pType = name(p.optString("type"))
                    // Главный выключатель — первый «on» после сведений об устройстве
                    val wanted = if (pType == "on" && "switch" !in used) "switch" else KNOWN[pType]
                    val code: String = if (wanted == null || wanted in used) "mi_${siid}_$piid" else wanted
                    used += code
                    val range = p.optJSONArray("value-range")
                    val values = HashMap<Int, String>()
                    p.optJSONArray("value-list")?.let { vl ->
                        for (v in 0 until vl.length()) vl.optJSONObject(v)?.let { e -> values[e.optInt("value")] = e.optString("description") }
                    }
                    val label = LABELS[code] ?: PRIVATE_LABELS["$siid.$piid"].takeIf { code.startsWith("mi_") && model.startsWith("zhimi.heater") }
                        ?: if (code.startsWith("mi_")) p.optString("description").ifEmpty { pType } else ""
                    // Яркость индикатора обогревателей zhimi: 0 — горит, 1 — погашен (как «Индикатор» в Mi Home)
                    val inverted = code == "indicator" && model.startsWith("zhimi.heater") &&
                        range != null && range.optDouble(0) == 0.0 && range.optDouble(1) == 1.0
                    out += MiProp(
                        invertedSwitch = inverted,
                        siid = siid, piid = piid, code = code, label = if (inverted) "Индикатор" else label, format = format,
                        readable = true, writable = "write" in access,
                        unit = UNITS[p.optString("unit")] ?: "",
                        min = range?.optDouble(0)?.takeIf { !it.isNaN() },
                        max = range?.optDouble(1)?.takeIf { !it.isNaN() },
                        step = range?.optDouble(2)?.takeIf { !it.isNaN() },
                        values = values,
                    )
                }
            }
            return out
        }

        /** «urn:miot-spec-v2:property:target-temperature:00000021:zhimi-mc2:1» -> «target-temperature». */
        private fun name(urn: String) = urn.split(":").getOrNull(3).orEmpty()

        /** Описание Mi Smart Space Heater S (zhimi.heater.mc2) — на случай, если реестр недоступен. */
        private val BUILT_IN = mapOf(
            "zhimi.heater.mc2" to """
            {"services":[
             {"iid":2,"type":"urn:miot-spec-v2:service:heater:0000782E:zhimi-mc2:1","properties":[
              {"iid":1,"type":"urn:miot-spec-v2:property:on:00000006:zhimi-mc2:1","description":"Switch Status","format":"bool","access":["read","write","notify"]},
              {"iid":2,"type":"urn:miot-spec-v2:property:fault:00000009:zhimi-mc2:1","description":"Device Fault","format":"uint8","access":["read","notify"],"value-range":[0,255,1]},
              {"iid":5,"type":"urn:miot-spec-v2:property:target-temperature:00000021:zhimi-mc2:1","description":"Target Temperature","format":"float","access":["read","write","notify"],"unit":"celsius","value-range":[18,28,1]}]},
             {"iid":3,"type":"urn:miot-spec-v2:service:countdown:0000782D:zhimi-mc2:1","properties":[
              {"iid":1,"type":"urn:miot-spec-v2:property:countdown-time:00000053:zhimi-mc2:1","description":"Countdown Time","format":"uint32","access":["read","write","notify"],"unit":"hours","value-range":[0,12,1]}]},
             {"iid":4,"type":"urn:miot-spec-v2:service:environment:0000780A:zhimi-mc2:1","properties":[
              {"iid":7,"type":"urn:miot-spec-v2:property:temperature:00000020:zhimi-mc2:1","description":"Temperature","format":"float","access":["read","notify"],"unit":"celsius","value-range":[-30,100,0.1]}]},
             {"iid":5,"type":"urn:miot-spec-v2:service:physical-controls-locked:00007807:zhimi-mc2:1","properties":[
              {"iid":1,"type":"urn:miot-spec-v2:property:physical-controls-locked:0000001D:zhimi-mc2:1","description":"Physical Control Locked","format":"bool","access":["read","write","notify"]}]},
             {"iid":6,"type":"urn:miot-spec-v2:service:alarm:00007804:zhimi-mc2:1","properties":[
              {"iid":1,"type":"urn:miot-spec-v2:property:alarm:00000012:zhimi-mc2:1","description":"Alarm","format":"bool","access":["read","write","notify"]}]},
             {"iid":7,"type":"urn:miot-spec-v2:service:indicator-light:00007803:zhimi-mc2:1","properties":[
              {"iid":3,"type":"urn:miot-spec-v2:property:brightness:0000000D:zhimi-mc2:1","description":"Brightness","format":"uint8","access":["read","write","notify"],"unit":"percentage","value-range":[0,1,1]}]},
             {"iid":8,"type":"urn:zhimi-spec:service:private-service:00007801:zhimi-mc2:1","properties":[
              {"iid":8,"type":"urn:zhimi-spec:property:constant-temperature:00000008:zhimi-mc2:1","description":"Constant temperature function enable","format":"bool","access":["read","notify","write"]},
              {"iid":9,"type":"urn:zhimi-spec:property:user-time:00000009:zhimi-mc2:1","description":"User operating duration","format":"uint32","access":["read","notify"],"value-range":[0,2147483647,1]}]}
            ]}
            """.trimIndent(),
        )
    }
}
