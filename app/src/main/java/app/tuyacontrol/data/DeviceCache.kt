package app.tuyacontrol.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.cloud.DpSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Последний список устройств — чтобы приложение сразу показывало устройства при запуске
 * и работало в режиме «только Wi-Fi» без интернета. Хранится зашифрованно: там локальные ключи.
 */
class DeviceCache(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { open() }

    private fun open(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        AppLog.e("Кэш устройств повреждён, создаю заново", e)
        appContext.deleteSharedPreferences(FILE)
        create()
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        FILE,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(devices: List<DeviceUi>) {
        val arr = JSONArray()
        devices.forEach { d ->
            val status = JSONObject()
            d.status.forEach { (k, v) -> status.put(k, v ?: JSONObject.NULL) }
            val spec = JSONObject()
            d.spec.forEach { (k, s) -> spec.put(k, specJson(s)) }
            val dpIds = JSONObject()
            d.dpIds.forEach { (k, v) -> dpIds.put(k, v) }
            arr.put(
                JSONObject()
                    .put("id", d.id).put("name", d.name).put("online", d.online)
                    .put("product", d.productName).put("category", d.category)
                    .put("status", status).put("spec", spec).put("thing", d.thingModel)
                    .put("active", d.activeTime).put("key", d.localKey)
                    .put("last", d.lastDataTime).put("dpIds", dpIds),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun load(): List<DeviceUi> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val status = o.optJSONObject("status") ?: JSONObject()
            val spec = o.optJSONObject("spec") ?: JSONObject()
            val dpIds = o.optJSONObject("dpIds") ?: JSONObject()
            DeviceUi(
                id = o.getString("id"),
                name = o.optString("name"),
                online = o.optBoolean("online"),
                productName = o.optString("product"),
                category = o.optString("category"),
                status = status.keys().asSequence().associateWith { k -> status.opt(k).takeIf { it != JSONObject.NULL } },
                spec = spec.keys().asSequence().associateWith { k -> parseSpec(spec.getJSONObject(k)) },
                thingModel = o.optBoolean("thing"),
                activeTime = o.optLong("active"),
                localKey = o.optString("key"),
                lastDataTime = o.optLong("last"),
                dpIds = dpIds.keys().asSequence().associateWith { k -> dpIds.getInt(k) },
            )
        }
    } catch (e: Exception) {
        AppLog.e("Не удалось прочитать кэш устройств", e)
        emptyList()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun specJson(s: DpSpec) = JSONObject()
        .put("code", s.code).put("type", s.type).put("unit", s.unit).put("scale", s.scale)
        .put("min", s.min ?: JSONObject.NULL).put("max", s.max ?: JSONObject.NULL).put("step", s.step)
        .put("range", JSONArray(s.range)).put("w", s.writable).put("dp", s.dpId ?: JSONObject.NULL)

    private fun parseSpec(o: JSONObject): DpSpec {
        val range = o.optJSONArray("range") ?: JSONArray()
        return DpSpec(
            code = o.optString("code"),
            type = o.optString("type"),
            unit = o.optString("unit"),
            scale = o.optInt("scale"),
            min = if (o.isNull("min")) null else o.optLong("min"),
            max = if (o.isNull("max")) null else o.optLong("max"),
            step = o.optLong("step", 1),
            range = (0 until range.length()).map { range.optString(it) },
            writable = o.optBoolean("w"),
            dpId = if (o.isNull("dp")) null else o.optInt("dp"),
        )
    }

    private companion object {
        const val FILE = "device_cache"
        const val KEY = "devices"
    }
}
