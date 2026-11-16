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
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Прогноз уличной температуры по часам на сегодня и завтра.
 * Сначала Open-Meteo, если он недоступен (например, у оператора нет маршрута) — wttr.in.
 */
class Weather(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        // Короткий таймаут: если Open-Meteo не отвечает, быстрее перейти к запасному источнику
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "MoyDom/1.0 (Android; +https://github.com/031168-sudo/tuya)")
                    .build(),
            )
        }
        .build()

    /** 48 значений с 00:00 сегодняшнего дня; при ошибке — последний сохранённый прогноз. */
    suspend fun forecast(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        // Свежий прогноз не чаще раза в полчаса
        val saved = cached()
        val age = System.currentTimeMillis() - prefs.getLong("fetched_at", 0L)
        if (saved != null && prefs.getString("day", null) == today.toString() && age in 0 until CACHE_MS) {
            return@withContext Forecast(saved.temps, fresh = true, yesterday = saved.yesterday)
        }
        try {
            val openMeteo = runCatching { openMeteo(lat, lon) }.getOrNull()
            val temps = openMeteo?.first ?: wttr(lat, lon)
            val yesterday = openMeteo?.second ?: DoubleArray(24) { Double.NaN }
            save(temps, yesterday)
            Forecast(temps, fresh = true, yesterday = yesterday)
        } catch (e: Exception) {
            AppLog.e("Прогноз погоды не получен (Open-Meteo и wttr.in)", e)
            cached() ?: Forecast(DoubleArray(48) { FALLBACK_TEMP }, fresh = false)
        }
    }

    private fun openMeteo(lat: Double, lon: Double): Pair<DoubleArray, DoubleArray> {
        val query = String.format(
            Locale.US,
            "?latitude=%.4f&longitude=%.4f" +
                "&hourly=temperature_2m&past_days=1&forecast_days=2&timezone=auto",
            lat, lon,
        )
        val text = get("https://api.open-meteo.com/v1/forecast$query")
        val arr = JSONObject(text).getJSONObject("hourly").getJSONArray("temperature_2m")
        // past_days=1: первые 24 часа — вчера, дальше сегодня и завтра
        val yesterday = DoubleArray(24) { i -> arr.optDouble(i, Double.NaN) }
        val temps = DoubleArray(48) { i -> arr.optDouble((24 + i).coerceAtMost(arr.length() - 1), 0.0) }
        return temps to yesterday
    }

    /** wttr.in отдаёт температуру раз в 3 часа; раскладываем на часы. */
    private fun wttr(lat: Double, lon: Double): DoubleArray {
        val url = String.format(Locale.US, "https://wttr.in/%.4f,%.4f?format=j1&lang=ru", lat, lon)
        val root = JSONObject(get(url))
        val days = root.getJSONArray("weather")
        val out = DoubleArray(48) { Double.NaN }
        val today = LocalDate.now()
        for (d in 0 until days.length()) {
            val day = days.getJSONObject(d)
            val date = runCatching { LocalDate.parse(day.getString("date")) }.getOrNull() ?: continue
            val dayIndex = ChronoUnit.DAYS.between(today, date).toInt()
            if (dayIndex !in 0..1) continue
            val hourly = day.optJSONArray("hourly") ?: continue
            for (i in 0 until hourly.length()) {
                val h = hourly.getJSONObject(i)
                val hour = (h.optInt("time", -1) / 100).coerceIn(0, 23)
                val t = h.optString("tempC").toDoubleOrNull() ?: continue
                val index = dayIndex * 24 + hour
                if (index in out.indices) out[index] = t
            }
        }
        // Заполняем пропуски: сначала вперёд, потом назад
        var last = out.firstOrNull { !it.isNaN() } ?: throw IllegalStateException("wttr.in: нет температуры")
        for (i in out.indices) {
            if (out[i].isNaN()) out[i] = last else last = out[i]
        }
        last = out.last()
        for (i in out.indices.reversed()) {
            if (out[i].isNaN()) out[i] = last else last = out[i]
        }
        return out
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} — $url")
            return r.body?.string().orEmpty()
        }
    }

    private fun save(temps: DoubleArray, yesterday: DoubleArray) {
        prefs.edit()
            .putString("temps", JSONArray(temps.toList()).toString())
            .putString("yesterday", JSONArray(yesterday.map { if (it.isNaN()) JSONObject.NULL else it }).toString())
            .putString("day", LocalDate.now().toString())
            .putLong("fetched_at", System.currentTimeMillis())
            .apply()
    }

    private fun cached(): Forecast? {
        val text = prefs.getString("temps", null) ?: return null
        val day = prefs.getString("day", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val arr = JSONArray(text)
        val all = DoubleArray(arr.length()) { arr.optDouble(it, FALLBACK_TEMP) }
        // Вчерашний прогноз: берём его «завтра» как сегодня
        val shift = if (day != null && day.plusDays(1) == LocalDate.now()) 24 else 0
        val yesterdayText = prefs.getString("yesterday", null)
        val yesterday = yesterdayText?.let { t ->
            val ya = JSONArray(t)
            DoubleArray(24) { i -> ya.optDouble(i, Double.NaN) }
        } ?: DoubleArray(24) { Double.NaN }
        return Forecast(
            DoubleArray(48) { all.getOrElse(it + shift) { all.lastOrNull() ?: FALLBACK_TEMP } },
            fresh = false,
            yesterday = yesterday,
        )
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
        const val CACHE_MS = 30 * 60 * 1000L
    }
}
