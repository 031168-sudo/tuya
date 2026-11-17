package app.tuyacontrol.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Погода от Gismeteo (российский сервис, доступен с мобильной сети).
 * Город определяется по координатам, прогноз — на сегодня и завтра раз в 3 часа.
 */
class GismeteoClient(context: Context) {

    data class Now(
        val temp: Double?,
        val code: Int,
        val isDay: Boolean,
        val max: Double?,
        val min: Double?,
    )

    private val prefs = context.applicationContext.getSharedPreferences("gismeteo", Context.MODE_PRIVATE)
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

    /** 48 часов температуры с 00:00 сегодня. */
    suspend fun forecast(lat: Double, lon: Double): DoubleArray = withContext(Dispatchers.IO) {
        parseForecast(fetchForecast(lat, lon))
    }

    /** Погода сейчас и мин/макс на сегодня. */
    suspend fun now(lat: Double, lon: Double): Now = withContext(Dispatchers.IO) {
        val xml = fetchForecast(lat, lon)
        val fact = Regex("""<fact\b([^>]*)>(.*?)</fact>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues ?: throw IllegalStateException("Gismeteo: нет fact")
        val factAttrs = attrs(fact[1])
        val factValues = Regex("""<values\b([^>]*)>""").find(fact[2])?.groupValues
            ?: throw IllegalStateException("Gismeteo: нет значений fact")
        val v = attrs(factValues[1])
        val today = LocalDate.now().toString()
        val dayAttrs = Regex("""<day\b([^>]*)>""").findAll(xml)
            .map { attrs(it.groupValues[1]) }
            .firstOrNull { it["date"] == today }
        val tod = factAttrs["tod"]?.toIntOrNull()
        Now(
            temp = v["t"]?.toDoubleOrNull(),
            code = code(v["icon"].orEmpty(), v["descr"].orEmpty()),
            isDay = when (tod) {
                2, 3 -> true
                1, 4 -> false
                else -> LocalTime.now().hour in 6..18
            },
            max = dayAttrs?.get("tmax")?.toDoubleOrNull(),
            min = dayAttrs?.get("tmin")?.toDoubleOrNull(),
        )
    }

    private fun fetchForecast(lat: Double, lon: Double): String {
        val id = cityId(lat, lon)
        val url = "https://services.gismeteo.ru/inform-service/inf_chrome/forecast/?city=$id&lang=ru"
        return get(url)
    }

    /** Ближайший город Gismeteo по координатам (кэшируется). */
    private fun cityId(lat: Double, lon: Double): String {
        val key = String.format(Locale.US, "%.2f,%.2f", lat, lon)
        if (prefs.getString("city_key", null) == key) {
            prefs.getString("city_id", null)?.let { return it }
        }
        val url = String.format(Locale.US, "https://services.gismeteo.ru/inform-service/inf_chrome/cities/?lat=%.4f&lng=%.4f", lat, lon)
        val xml = get(url)
        val best = Regex("""<item\b([^>]*)>""").findAll(xml)
            .map { attrs(it.groupValues[1]) }
            .filter { it["id"] != null }
            .minByOrNull { it["distance"]?.toDoubleOrNull() ?: Double.MAX_VALUE }
            ?: throw IllegalStateException("Gismeteo: город не найден")
        val id = best["id"] ?: throw IllegalStateException("Gismeteo: город не найден")
        prefs.edit().putString("city_key", key).putString("city_id", id).apply()
        return id
    }

    private fun parseForecast(xml: String): DoubleArray {
        val today = LocalDate.now()
        val out = DoubleArray(48) { Double.NaN }
        val dayRegex = Regex("""<day\b([^>]*)>(.*?)</day>""", RegexOption.DOT_MATCHES_ALL)
        val fcRegex = Regex("""<forecast\b([^>]*)>(.*?)</forecast>""", RegexOption.DOT_MATCHES_ALL)
        val valRegex = Regex("""<values\b([^>]*)>""")
        for (day in dayRegex.findAll(xml)) {
            val dayAttrs = attrs(day.groupValues[1])
            val date = dayAttrs["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val dayIndex = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()
            if (dayIndex !in 0..1) continue
            for (fc in fcRegex.findAll(day.groupValues[2])) {
                val valid = attrs(fc.groupValues[1])["valid"] ?: continue
                val time = runCatching { LocalDateTime.parse(valid) }.getOrNull() ?: continue
                val values = valRegex.find(fc.groupValues[2])?.groupValues ?: continue
                val t = attrs(values[1])["t"]?.toDoubleOrNull() ?: continue
                val index = dayIndex * 24 + time.hour
                if (index in out.indices) out[index] = t
            }
        }
        if (out.all { it.isNaN() }) throw IllegalStateException("Gismeteo: нет температуры")
        return interpolate(out)
    }

    private fun interpolate(out: DoubleArray): DoubleArray {
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
        val first = out.indexOfFirst { !it.isNaN() }
        if (first > 0) {
            val v = out[first]
            for (i in 0 until first) out[i] = v
        }
        val last = out.last { !it.isNaN() }
        for (i in out.indices.reversed()) {
            if (out[i].isNaN()) out[i] = last else break
        }
        return out
    }

    private fun attrs(text: String): Map<String, String> =
        Regex("""([\w:-]+)="([^"]*)"""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} — $url")
            return r.body?.string().orEmpty()
        }
    }

    /** Gismeteo icon/descr → код WMO для значка. */
    private fun code(icon: String, descr: String): Int {
        val d = descr.lowercase(Locale("ru"))
        return when {
            d.contains("гроза") -> 95
            d.contains("снег") && (d.contains("дожд") || d.contains("мокр")) -> 61
            d.contains("снег") -> 71
            d.contains("дожд") -> 61
            d.contains("туман") -> 45
            d.contains("ясно") || d.contains("солнечно") -> 0
            d.contains("малооблачно") -> 1
            d.contains("облачно") && !d.contains("пасмурно") -> 2
            d.contains("пасмурно") || d.contains("облачно") -> 3
            icon.contains(".r") -> 61
            icon.contains(".s") -> 71
            icon.contains(".t") -> 95
            icon.contains("c1") -> 0
            icon.contains("c2") -> 1
            icon.contains("c3") -> 2
            else -> 3
        }
    }
}
