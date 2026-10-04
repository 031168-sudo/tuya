package app.tuyacontrol.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Категория устройств: название, иконка и цвет (индекс в пастельной палитре). */
data class Category(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String,
    val color: Int,
)

/** Настройки устройства, заданные пользователем: иконка и категория. */
/**
 * Настройки устройства в приложении. [tempWhenOn]: true — текущая температура верна только когда прибор включён
 * (выключенный отдаёт старое значение), false — всегда верна, null — по типу прибора.
 */
data class DevicePref(val icon: String? = null, val categoryId: String? = null, val tempWhenOn: Boolean? = null)

/** Категории и назначения хранятся только в телефоне (обычные SharedPreferences, JSON). */
class CategoryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("categories", Context.MODE_PRIVATE)

    fun categories(): List<Category> = try {
        val arr = JSONArray(prefs.getString(KEY_CATEGORIES, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Category(o.getString("id"), o.optString("name"), o.optString("icon"), o.optInt("color"))
        }
    } catch (e: Exception) {
        AppLog.e("Категории повреждены", e)
        emptyList()
    }

    fun saveCategories(list: List<Category>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon).put("color", it.color))
        }
        prefs.edit().putString(KEY_CATEGORIES, arr.toString()).apply()
    }

    fun devicePrefs(): Map<String, DevicePref> = try {
        val o = JSONObject(prefs.getString(KEY_DEVICES, "{}") ?: "{}")
        o.keys().asSequence().associateWith { id ->
            val p = o.getJSONObject(id)
            DevicePref(
                icon = p.optString("icon").ifEmpty { null },
                categoryId = p.optString("category").ifEmpty { null },
                tempWhenOn = if (p.has("temp_on")) p.optBoolean("temp_on") else null,
            )
        }
    } catch (e: Exception) {
        AppLog.e("Настройки устройств повреждены", e)
        emptyMap()
    }

    fun saveDevicePrefs(map: Map<String, DevicePref>) {
        val o = JSONObject()
        map.forEach { (id, p) ->
            if (p.icon == null && p.categoryId == null && p.tempWhenOn == null) return@forEach
            val j = JSONObject().put("icon", p.icon ?: "").put("category", p.categoryId ?: "")
            p.tempWhenOn?.let { j.put("temp_on", it) }
            o.put(id, j)
        }
        prefs.edit().putString(KEY_DEVICES, o.toString()).apply()
    }

    private companion object {
        const val KEY_CATEGORIES = "categories"
        const val KEY_DEVICES = "devices"
    }
}
