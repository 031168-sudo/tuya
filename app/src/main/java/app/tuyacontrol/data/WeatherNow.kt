package app.tuyacontrol.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Погода «сейчас» для экрана «Карта»: код погоды (для пиктограммы) и мин/макс на сегодня.
 * Тот же Open-Meteo, что и прогноз в «Отоплении»; ответ хранится 30 минут.
 */
class WeatherNow(context: Context) {

    /** code — код погоды WMO; max/min — на сегодня, °C. */
    data class Now(val code: Int, val isDay: Boolean, val max: Double?, val min: Double?, val time: Long)

    private val prefs = context.applicationContext.getSharedPreferences("weather_now", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun get(lat: Double, lon: Double): Now? = withContext(Dispatchers.IO) {
        val key = String.format(Locale.US, "%.3f,%.3f", lat, lon)
        val saved = cached(key)
        if (saved != null && System.currentTimeMillis() - saved.time < MAX_AGE) return@withContext saved
        try {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                    "&forecast_days=1&timezone=auto",
                lat, lon,
            )
            val text = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
            val o = JSONObject(text)
            val cur = o.getJSONObject("current")
            val daily = o.optJSONObject("daily")
            val now = Now(
                code = cur.optInt("weather_code", -1),
                isDay = cur.optInt("is_day", 1) == 1,
                max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeUnless { it.isNaN() },
                min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeUnless { it.isNaN() },
                time = System.currentTimeMillis(),
            )
            prefs.edit()
                .putString("key", key)
                .putInt("code", now.code)
                .putBoolean("day", now.isDay)
                .putString("max", now.max?.toString())
                .putString("min", now.min?.toString())
                .putLong("time", now.time)
                .apply()
            now
        } catch (e: Exception) {
            AppLog.e("Погода сейчас не получена", e)
            saved
        }
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
    }
}
