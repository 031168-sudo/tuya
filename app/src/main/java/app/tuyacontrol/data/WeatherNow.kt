package app.tuyacontrol.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Погода «сейчас» для экрана «Карта»: код погоды (для пиктограммы) и мин/макс на сегодня.
 * Источник — wttr.in (Open-Meteo с мобильной сети не открывается); ответ хранится 30 минут.
 */
class WeatherNow(context: Context) {

    /** code — код погоды WMO; max/min — на сегодня, °C. */
    data class Now(val code: Int, val isDay: Boolean, val max: Double?, val min: Double?, val time: Long)

    private val prefs = context.applicationContext.getSharedPreferences("weather_now", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun get(lat: Double, lon: Double): Now? = withContext(Dispatchers.IO) {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        val saved = cached(key)
        if (saved != null && System.currentTimeMillis() - saved.time < MAX_AGE) return@withContext saved
        try {
            val now = wttrNow(lat, lon)
            save(key, now)
            now
        } catch (e: Exception) {
            AppLog.e("Погода сейчас не получена (wttr.in)", e)
            saved
        }
    }

    /** wttr.in: текущая погода, макс/мин на сегодня. */
    private fun wttrNow(lat: Double, lon: Double): Now {
        val url = String.format(Locale.US, "https://wttr.in/%.4f,%.4f?format=j1&lang=ru", lat, lon)
        val root = JSONObject(get(url))
        val cur = root.getJSONArray("current_condition").getJSONObject(0)
        val day = root.getJSONArray("weather").getJSONObject(0)
        val hour = LocalTime.now().hour
        return Now(
            code = wwoToWmo(cur.optInt("weatherCode", -1)),
            isDay = hour in 6..18,
            max = day.optString("maxtempC").toDoubleOrNull(),
            min = day.optString("mintempC").toDoubleOrNull(),
            time = System.currentTimeMillis(),
        )
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} — $url")
            return r.body?.string().orEmpty()
        }
    }

    private fun save(key: String, now: Now) {
        prefs.edit()
            .putString("key", key)
            .putInt("code", now.code)
            .putBoolean("day", now.isDay)
            .putString("max", now.max?.toString())
            .putString("min", now.min?.toString())
            .putLong("time", now.time)
            .apply()
    }

    private fun cached(key: String): Now? {
        if (prefs.getString("key", null) != key) return null
        val time = prefs.getLong("time", 0)
        if (time == 0L) return null
        return Now(
            code = prefs.getInt("code", -1),
            isDay = prefs.getBoolean("day", true),
            max = prefs.getString("max", null)?.toDoubleOrNull(),
            min = prefs.getString("min", null)?.toDoubleOrNull(),
            time = time,
        )
    }

    /** Вид погоды по коду WMO. */
    enum class Kind(val title: String) {
        CLEAR("Ясно"), PARTLY("Переменная облачность"), CLOUDY("Пасмурно"), FOG("Туман"),
        DRIZZLE("Морось"), RAIN("Дождь"), SNOW("Снег"), THUNDER("Гроза"), UNKNOWN("Погода неизвестна"),
    }

    companion object {
        private const val MAX_AGE = 30 * 60_000L

        fun kind(code: Int): Kind = when (code) {
            0 -> Kind.CLEAR
            1, 2 -> Kind.PARTLY
            3 -> Kind.CLOUDY
            45, 48 -> Kind.FOG
            in 51..57 -> Kind.DRIZZLE
            in 61..67, in 80..82 -> Kind.RAIN
            in 71..77, 85, 86 -> Kind.SNOW
            in 95..99 -> Kind.THUNDER
            else -> Kind.UNKNOWN
        }

        /** Коды wttr.in (WWO) → коды WMO, по которым рисуется значок. */
        private fun wwoToWmo(code: Int): Int = when (code) {
            113 -> 0
            116 -> 1
            119, 122 -> 3
            143, 248, 260 -> 45
            176, 263, 266, 293, 296, 299, 302, 305, 308, 353, 356, 359 -> 61
            179, 182, 185, 227, 230, 317, 320, 323, 326, 329, 332, 335, 338, 350, 368, 371, 374, 377, 392, 395 -> 71
            200, 386, 389 -> 95
            else -> -1
        }
    }
}
