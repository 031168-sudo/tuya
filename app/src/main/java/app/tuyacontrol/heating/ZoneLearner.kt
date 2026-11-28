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
    /** Подобрано приблизительно: часть данных о нагреве выведена, а не измерена. */
    val approximate: Boolean = false,
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

        fun stateAt(events: List<Pair<Long, Boolean>>, t: Long): Boolean? {
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
            return events[idx].second
        }
        fun uOver(events: List<Pair<Long, Boolean>>, a: Long, b: Long): Double? {
            var on = 0
            var n = 0
            var t = a
            while (t < b) {
                val st = stateAt(events, t) ?: return null
                if (st) on++
                n++
                t += 5 * 60_000L
            }
            return if (n == 0) null else on.toDouble() / n
        }

        val hours = temps.keys.sorted()
        val samples = mutableListOf<ThermalFit.Sample>()
        val onOff = app.tuyacontrol.rubetek.RubetekMapper.isRubetek(id) ||
            app.tuyacontrol.xiaomi.XiaomiMapper.isXiaomi(id)

        if (onOff) {
            // Rubetek/Xiaomi: известно только вкл/выкл, внутри прибора свой термостат.
            // Греет точно, только когда включён и комната ниже уставки; у уставки — неизвестно.
            val power = mutableListOf<Pair<Long, Boolean>>()
            db.series(id, "power_s", from, now, 60_000L).forEach { power += it.time to (it.avg >= 0.5) }
            power.sortBy { it.first }
            if (power.isEmpty()) return@withContext fail("нет истории вкл/выкл")
            val setpoints = db.series(id, "setpoint", from, now, HOUR).associate { it.time to it.avg }
            val assumed = zone.assumedSetpoint
            fun setAt(t: Long): Double? {
                assumed?.let { return it }
                val key = setpoints.keys.minByOrNull { kotlin.math.abs(it - t) } ?: return null
                return if (kotlin.math.abs(key - t) <= 2 * HOUR) setpoints[key] else null
            }
            for (k in hours) {
                val t0 = temps[k] ?: continue
                val t1 = temps[k + HOUR] ?: continue
                val out = outdoor[k] ?: outdoor[k + HOUR] ?: outdoor[k - HOUR] ?: continue
                val a = k + HOUR / 2
                val b = k + HOUR + HOUR / 2
                val powerOn = stateAt(power, a) ?: continue
                val mid = (t0 + t1) / 2
                val diff = mid - out
                val rise = t1 - t0
                val sample = if (!powerOn) {
                    if (rise > 0.05) null else ThermalFit.Sample(rise, 0.0, diff, inferred = false)
                } else {
                    val set = setAt(a)
                    when {
                        // Ниже уставки — прибор должен греть, даже если реле внутри ещё не сработало
                        set != null && mid <= set - 0.8 -> ThermalFit.Sample(rise, 1.0, diff, inferred = true)
                        // У уставки — не знаем, греет или уже щёлкает; такие часы не берём
                        set != null && mid >= set - 0.2 -> null
                        // Уставки нет — только явный рост температуры
                        rise > 0.2 -> ThermalFit.Sample(rise, 1.0, diff, inferred = true)
                        else -> null
                    }
                }
                if (sample != null) samples += sample
            }
        } else {
            // Термостаты с фактическим состоянием: work_state / valve_state / реле
            val events = mutableListOf<Pair<Long, Boolean>>()
            fun addLocalHeating() {
                db.series(id, "heating", from, now, 60_000L).forEach { events += it.time to (it.avg >= 0.5) }
            }
            if (client != null) {
                val logs = runCatching { client.getDeviceLogs(id, "work_state,valve_state,switch,switch_1", from, now, maxPages = 60) }
                    .onFailure { AppLog.e("Подбор «${zone.name}»: журнал нагрева не прочитан", it) }
                    .getOrDefault(emptyList())
                val hasState = logs.any { it.code == "work_state" || it.code == "valve_state" }
                val hasSwitch = logs.any { it.code == "switch" || it.code == "switch_1" }
                // Для тёплого пола work_state часто означает режим, а не реле. Приоритет — switch,
                // и старые локальные точки «heating» (могли быть записаны по work_state) не подмешиваем.
                val useSwitch = zone.isFloor && hasSwitch
                if (!useSwitch) addLocalHeating()
                for (e in logs) {
                    val v = e.value.lowercase()
                    val on = when {
                        e.code == "work_state" && !useSwitch -> v in setOf("1", "heating", "heat", "hot", "warming", "true", "open", "on")
                        e.code == "valve_state" && !useSwitch -> v in setOf("open", "1", "true", "on")
                        (e.code == "switch" || e.code == "switch_1") && (useSwitch || !hasState) -> v == "true"
                        else -> continue
                    }
                    events += e.time to on
                }
            } else {
                addLocalHeating()
            }
            events.sortBy { it.first }
            if (events.isEmpty()) return@withContext fail("нет истории «греет / не греет»")
            for (k in hours) {
                val t0 = temps[k] ?: continue
                val t1 = temps[k + HOUR] ?: continue
                val out = outdoor[k] ?: outdoor[k + HOUR] ?: outdoor[k - HOUR] ?: continue
                val a = k + HOUR / 2
                val b = k + HOUR + HOUR / 2
                val u = uOver(events, a, b) ?: continue
                val rise = t1 - t0
                // «Включён, но температура стоит» — это цикл реле/уставка, а не полная мощность.
                // Такие часы не должны занижать heatRate.
                if (u >= 0.5 && rise < 0.05) continue
                if (u <= 0.1 && rise > 0.05) continue
                samples += ThermalFit.Sample(rise, u, (t0 + t1) / 2 - out, inferred = false)
            }
        }

        if (samples.isEmpty()) return@withContext fail("нет данных для подбора")
        val days = ((now - hours.first()) / DAY).toInt().coerceAtLeast(1)
        val r = ThermalFit.fit(samples)
        AppLog.i(
            "Подбор «${zone.name}»: часов ${samples.size} (без нагрева ${r.coolHours}, с нагревом ${r.heatHours}), " +
                "нагрев ${r.heatRate?.let { "%.2f".format(it) }}°/ч, остывание ${r.lossRate?.let { "%.1f".format(it * 100) }}%/ч" +
                (r.note?.let { " ($it)" } ?: "") + (r.problem?.let { " — $it" } ?: ""),
        )
        LearnInfo(
            now, r.heatRate, r.lossRate, r.coolHours, r.heatHours, days, r.problem,
            approximate = r.approximate, note = r.note,
        )
    }

    private companion object {
        const val HOUR = 3600_000L
        const val DAY = 24 * HOUR
    }
}
