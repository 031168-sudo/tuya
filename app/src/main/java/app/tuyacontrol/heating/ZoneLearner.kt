package app.tuyacontrol.heating

import android.content.Context
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.rubetek.RubetekMapper
import app.tuyacontrol.sensor.SensorDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Итог подбора параметров зоны по истории. */
data class LearnInfo(
    val at: Long,
    val heatRate: Double?,
    val lossRate: Double?,
    val coolHours: Int,
    val heatHours: Int,
    val days: Int,
    /** Почему не подобрано (null — подобрано). */
    val problem: String?,
    /** Параметры зоны обновлены по этому подбору. */
    val applied: Boolean = false,
    /** Подобрано с оговоркой. */
    val note: String? = null,
)

/**
 * Подбор скорости нагрева и остывания зоны по последней неделе: температура в зоне (журнал облака и свои
 * точки), когда прибор грел (журнал облака work_state / valve_state / реле и свои точки «heating»),
 * температура на улице с уличного датчика.
 */
class ZoneLearner(context: Context) {

    private val db = SensorDb(context.applicationContext)
    private val store = HeatingStore(context.applicationContext)
    /** Код температуры по устройству (у датчиков va_temperature и т.п.). */
    private val tempCodes: Map<String, String> = app.tuyacontrol.background.SyncTargets(context.applicationContext).sensors()
        .mapNotNull { s -> s.temperature?.let { s.id to it.code } }.toMap()

    suspend fun learn(zone: HeatZone, s: HeatingSettings, client: TuyaCloudClient?, relay: Boolean): LearnInfo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        fun fail(why: String) = LearnInfo(now, null, null, 0, 0, 0, why)
        val id = zone.deviceId ?: return@withContext fail("не выбрано устройство")
        val oid = s.outdoorSensorId?.takeIf { it != "-" } ?: return@withContext fail("не выбран уличный датчик")
        val ocode = s.outdoorCode ?: return@withContext fail("не выбран уличный датчик")
        val from = now - 14 * DAY
        // Температура — с прибора для измерения, если он выбран; «греет» — всегда с нагревательного
        val tid = zone.tempDeviceId ?: id
        val tcode = tempCodes[tid] ?: "temp_current"
        val temps = db.series(tid, tcode, from, now, HOUR).associate { it.time to it.avg }
        if (temps.size < 24) return@withContext fail("мало истории температуры (${temps.size} ч)")
        val outdoor = db.series(oid, ocode, from, now, HOUR).associate { it.time to it.avg }
        if (outdoor.size < 24) return@withContext fail("мало истории уличного датчика (${outdoor.size} ч)")

        // Когда прибор грел: свои точки + журнал облака
        val events = mutableListOf<Pair<Long, Boolean>>()
        db.series(id, "heating", from, now, 60_000L).forEach { events += it.time to (it.avg >= 0.5) }
        val rubetek = RubetekMapper.isRubetek(id)
        if (!rubetek && client != null) {
            val logs = runCatching { client.getDeviceLogs(id, "work_state,valve_state,switch", from, now, maxPages = 60) }
                .onFailure { AppLog.e("Подбор «${zone.name}»: журнал нагрева не прочитан", it) }
                .getOrDefault(emptyList())
            val hasState = logs.any { it.code == "work_state" || it.code == "valve_state" }
            for (e in logs) {
                val v = e.value.lowercase()
                val on = when {
                    e.code == "work_state" -> v in setOf("1", "heating", "heat", "hot", "warming", "true", "open", "on")
                    e.code == "valve_state" -> v in setOf("open", "1", "true", "on")
                    e.code == "switch" && relay && !hasState -> v == "true"
                    else -> continue
                }
                events += e.time to on
            }
        }
        events.sortBy { it.first }
        if (events.isEmpty()) return@withContext fail("нет истории «греет / не греет»")

        // Rubetek: «включён» не значит «греет» — конвектор сам выключается у уставки. Считаем, что он
        // точно греет только первый час после включения
        val onSince = LongArray(events.size)
        var since = -1L
        events.forEachIndexed { i, (t, on) ->
            if (on && (i == 0 || !events[i - 1].second)) since = t
            onSince[i] = if (on) since else -1L
        }
        fun stateAt(t: Long): Boolean? {
            var lo = 0
            var hi = events.size - 1
            var idx = -1
            while (lo <= hi) {
                val m = (lo + hi) / 2
                if (events[m].first <= t) { idx = m; lo = m + 1 } else hi = m - 1
            }
            if (idx < 0) return null
            // Свои точки пишутся раз в 5–30 минут: состояние старше 2 часов считаем неизвестным
            if (t - events[idx].first > 2 * HOUR) return null
            val on = events[idx].second
            if (on && rubetek && t - onSince[idx] > HOUR) return null
            return on
        }
        fun uOver(a: Long, b: Long): Double? {
            var on = 0
            var n = 0
            var t = a
            while (t < b) {
                val st = stateAt(t) ?: return null
                if (st) on++
                n++
                t += 5 * 60_000L
            }
            return if (n == 0) null else on.toDouble() / n
        }

        // Часы после выключения: тёплый пол и корпус конвектора ещё отдают тепло — для «остывания» они не годятся
        fun quietHours(a: Long): Double {
            var lastOn = Long.MIN_VALUE
            for (i in events.indices) {
                if (events[i].first > a) break
                if (events[i].second) lastOn = minOf(events.getOrNull(i + 1)?.first ?: a, a)
            }
            return if (lastOn == Long.MIN_VALUE) Double.MAX_VALUE else (a - lastOn).toDouble() / HOUR
        }
        // Rubetek включается и выключается таймерами в самом модуле, а мы узнаём об этом при опросе (до 30 мин
        // позже). Поэтому «без нагрева» верно только для часов вдали от переключений — и по расписанию таймеров
        // (оно точное), и по замеченным переключениям
        val schedule = if (rubetek) store.deployedTimers()[id].orEmpty().map { it.first } else emptyList()
        val zoneId = java.time.ZoneId.systemDefault()
        fun minuteOfDay(t: Long): Int = java.time.Instant.ofEpochMilli(t).atZone(zoneId).let { it.hour * 60 + it.minute }
        fun nearSwitch(a: Long, b: Long): Boolean {
            // окно проверки: от часа до начала и до конца окна (остаточное тепло) + запас на опрос после конца
            val fromT = a - HOUR
            val spanMin = ((b - fromT) / 60_000L).toInt()
            val start = minuteOfDay(fromT)
            if (schedule.any { Math.floorMod(it - start, 1440) <= spanMin }) return true
            for (i in 1 until events.size) {
                if (events[i].second != events[i - 1].second && events[i].first in fromT..(b + HOUR / 2)) return true
            }
            return false
        }

        val samples = mutableListOf<ThermalFit.Sample>()
        val hours = temps.keys.sorted()
        for (k in hours) {
            val t0 = temps[k] ?: continue
            val t1 = temps[k + HOUR] ?: continue
            val out = outdoor[k] ?: outdoor[k + HOUR] ?: outdoor[k - HOUR] ?: continue
            // Среднее за час k относится к середине часа: изменение — между серединами часов k и k+1
            val a = k + HOUR / 2
            val b = k + HOUR + HOUR / 2
            val u = uOver(a, b) ?: continue
            if (rubetek && u == 0.0 && nearSwitch(a, b)) continue
            samples += ThermalFit.Sample(t1 - t0, u, (t0 + t1) / 2 - out, quietHours(a))
        }
        val days = ((now - hours.first()) / DAY).toInt().coerceAtLeast(1)
        val r = ThermalFit.fit(samples, settle = if (zone.isFloor) 3.0 else 1.0)
        AppLog.i(
            "Подбор «${zone.name}»: часов ${samples.size} (без нагрева ${r.coolHours}, с нагревом ${r.heatHours}), " +
                "нагрев ${r.heatRate?.let { "%.2f".format(it) }}°/ч, остывание ${r.lossRate?.let { "%.1f".format(it * 100) }}%/ч" +
                (r.note?.let { " ($it)" } ?: "") + (r.problem?.let { " — $it" } ?: ""),
        )
        LearnInfo(now, r.heatRate, r.lossRate, r.coolHours, r.heatHours, days, r.problem, note = r.note)
    }

    private companion object {
        const val HOUR = 3600_000L
        const val DAY = 24 * HOUR
    }
}
