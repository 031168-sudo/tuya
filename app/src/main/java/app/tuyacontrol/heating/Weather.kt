package app.tuyacontrol.heating

import android.content.Context
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.data.GismeteoClient
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
 * Основной источник — Gismeteo (работает из России), запасной — wttr.in.
 * Open-Meteo больше не используем: с мобильной сети он не открывается.
 */
class Weather(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val gismeteo = GismeteoClient(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
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
            return@withContext Forecast(saved.temps, fresh = true)
        }
        try {
            val temps = gismeteo.forecast(lat, lon)
            save(temps)
            Forecast(temps, fresh = true)
        } catch (e: Exception) {
            try {
                val temps = wttr(lat, lon)
                save(temps)
                Forecast(temps, fresh = true)
            } catch (e2: Exception) {
                AppLog.e("Прогноз погоды не получен (Gismeteo и wttr.in)", e2)
                cached() ?: Forecast(DoubleArray(48) { FALLBACK_TEMP }, fresh = false)
            }
        }
    }

    /** wttr.in отдаёт температуру раз в 3 часа; раскладываем на часы линейно. */
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
        if (out.all { it.isNaN() }) throw IllegalStateException("wttr.in: нет температуры")

        // Линейно заполняем пропуски между известными точками
        var prev = -1
        for (i in out.indices) {
            if (out[i].isNaN()) continue
            if (prev >= 0 && i - prev > 1) {
                val a = out[prev]
                val b = out[i]
                for (k in prev + 1 until i) {
                    out[k] = a + (b - a) * (k - prev) / (i - prev)
                }
            }
            prev = i
        }
        // Края — крайними значениями
        if (prev >= 0) {
            for (i in 0 until out.indexOfFirst { !it.isNaN() }) out[i] = out[prev]
            val last = out.last { !it.isNaN() }
            for (i in out.indices.reversed()) if (out[i].isNaN()) out[i] = last else break
        }
        return out
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} — $url")
            return r.body?.string().orEmpty()
        }
    }

    private fun save(temps: DoubleArray) {
        prefs.edit()
            .putString("temps", JSONArray(temps.toList()).toString())
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
        return Forecast(
            DoubleArray(48) { all.getOrElse(it + shift) { all.lastOrNull() ?: FALLBACK_TEMP } },
            fresh = false,
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
