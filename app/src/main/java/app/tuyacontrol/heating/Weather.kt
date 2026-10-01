package app.tuyacontrol.heating

import android.content.Context
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Прогноз уличной температуры по часам на сегодня и завтра (Open-Meteo, без ключа). */
class Weather(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 48 значений с 00:00 сегодняшнего дня; при ошибке — последний сохранённый прогноз. */
    suspend fun forecast(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        try {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&hourly=temperature_2m&past_days=1&forecast_days=2&timezone=auto",
                lat, lon,
            )
            val text = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
            // past_days=1: первые 24 часа — вчера (для сверки с уличным датчиком), дальше сегодня и завтра
            val arr = JSONObject(text).getJSONObject("hourly").getJSONArray("temperature_2m")
            val yesterday = DoubleArray(24) { i -> arr.optDouble(i, Double.NaN) }
            val temps = DoubleArray(48) { i -> arr.optDouble((24 + i).coerceAtMost(arr.length() - 1), 0.0) }
            prefs.edit().putString("yesterday", JSONArray(yesterday.map { if (it.isNaN()) JSONObject.NULL else it }).toString()).apply()
            prefs.edit()
                .putString("temps", JSONArray(temps.toList()).toString())
                .putString("day", LocalDate.now().toString())
                .apply()
            Forecast(temps, fresh = true, yesterday = yesterday)
        } catch (e: Exception) {
            AppLog.e("Прогноз погоды не получен", e)
            cached() ?: Forecast(DoubleArray(48) { FALLBACK_TEMP }, fresh = false)
        }
    }

    private fun cached(): Forecast? {
        val text = prefs.getString("temps", null) ?: return null
        val day = prefs.getString("day", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val arr = JSONArray(text)
        val all = DoubleArray(arr.length()) { arr.optDouble(it, FALLBACK_TEMP) }
        // Вчерашний прогноз: берём его «завтра» как сегодня
        val shift = if (day != null && day.plusDays(1) == LocalDate.now()) 24 else 0
        return Forecast(DoubleArray(48) { all.getOrElse(it + shift) { all.lastOrNull() ?: FALLBACK_TEMP } }, fresh = false)
    }

    /**
     * @param temps 48 часов с 00:00 сегодня
     * @param yesterday прогноз на вчера по часам (NaN — нет), для сверки с уличным датчиком
     * @param bias поправка по уличному датчику, уже прибавленная к temps (0 — без поправки)
     */
    class Forecast(
        val temps: DoubleArray,
        val fresh: Boolean,
        val yesterday: DoubleArray = DoubleArray(24) { Double.NaN },
        val bias: Double = 0.0,
    )

    private companion object {
        const val FALLBACK_TEMP = -5.0
    }
}
