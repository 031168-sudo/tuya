package app.tuyacontrol.heating

import android.content.Context
import app.tuyacontrol.cloud.DpSpec
import app.tuyacontrol.cloud.TuyaApiException
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.energy.EnergyDb
import app.tuyacontrol.rubetek.RubetekClient
import app.tuyacontrol.rubetek.RubetekMapper
import app.tuyacontrol.rubetek.RubetekStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.pow
import kotlin.math.roundToLong

/** Расчёт планов и запись их в облачное расписание термостатов. */
class HeatingEngine(context: Context) {

    private val app = context.applicationContext
    val store = HeatingStore(app)
    private val weather = Weather(app)

    /** Цены по часам на сегодня из тарифов, введённых в приложении. */
    suspend fun prices(): HourPrices = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val tariffs = runCatching { EnergyDb(app).tariffs() }.getOrDefault(emptyList())
            .filter { it.covers(today) && it.zones.isNotEmpty() }
        val t = tariffs.firstOrNull { it.deviceId == null } ?: tariffs.firstOrNull()
            ?: return@withContext HourPrices.default()
        val max = t.zones.maxOf { it.price }
        val price = DoubleArray(24)
        val zone = IntArray(24)
        for (h in 0 until 24) {
            val z = t.zoneAt(h)
            zone[h] = z ?: -1
            price[h] = if (z != null) t.zones[z].price else max
        }
        HourPrices.of(price, zone)
    }

    suspend fun forecast(s: HeatingSettings) = weather.forecast(s.latitude, s.longitude)

    /**
     * Записать план каждой зоны в расписание её термостата и сразу выставить уставку текущего часа.
     * Возвращает построчный отчёт.
     */
    suspend fun deploy(client: TuyaCloudClient, s: HeatingSettings): String {
        val prices = prices()
        val forecast = forecast(s)
        val lines = mutableListOf<String>()
        var ok = 0
        val schedules = mutableMapOf<String, DoubleArray>()
        for (zone in s.zones) {
            val id = zone.deviceId ?: continue
            // Конвекторы Rubetek: расписания уставок у них нет — переключает телефон по будильнику
            if (RubetekMapper.isRubetek(id)) {
                try {
                    val plan = HeatingPlanner.plan(zone, prices, forecast.temps, 1.0)
                    schedules[id] = plan.setpoints
                    applyRubetek(id, plan.setpoints[LocalTime.now().hour], turnOn = true)
                    lines += "${zone.name}: план в телефоне (Rubetek), переключений ${changes(plan.setpoints)}"
                    ok++
                } catch (e: Exception) {
                    AppLog.e("Отопление: ${zone.name} — Rubetek не ответил", e)
                    lines += "${zone.name}: ошибка Rubetek — ${e.message ?: e.javaClass.simpleName}"
                }
                continue
            }
            try {
                val spec = specOf(client, id)
                val temp = spec["temp_set"] ?: throw IllegalStateException("нет уставки temp_set")
                val plan = HeatingPlanner.plan(zone, prices, forecast.temps, stepOf(temp))
                val instructs = timerInstructs(plan.setpoints, temp)
                client.replaceDailyTimers(id, TIMER_CATEGORY, instructs, "Мой дом: ${zone.name}")

                // Сразу: включить, ручной режим (чтобы встроенная программа не перебивала), уставка сейчас
                val now = mutableListOf<Pair<String, Any?>>()
                spec["switch"]?.takeIf { it.writable }?.let { now += "switch" to true }
                spec["mode"]?.takeIf { it.writable && "manual" in it.range }?.let { now += "mode" to "manual" }
                now += "temp_set" to encode(plan.setpoints[LocalTime.now().hour], temp)
                runCatching { client.sendCommands(id, now) }
                    .onFailure { AppLog.e("Отопление: ${zone.name} — уставка сейчас не отправлена", it) }

                lines += "${zone.name}: записано ${instructs.size} переключений"
                ok++
            } catch (e: Exception) {
                AppLog.e("Отопление: ${zone.name} — план не записан", e)
                lines += "${zone.name}: ошибка — ${describe(e)}"
            }
        }
        if (lines.isEmpty()) lines += "Ни одной зоне не назначен термостат"
        store.saveSchedules(schedules)
        HeatingAlarm.scheduleNext(app)
        val report = lines.joinToString("\n")
        store.deployedAt = System.currentTimeMillis()
        store.deployResult = report
        AppLog.i("Отопление: план записан ($ok зон)\n$report")
        return report
    }

    /** Убрать наше расписание со всех термостатов. */
    suspend fun disable(client: TuyaCloudClient, s: HeatingSettings): String {
        val lines = mutableListOf<String>()
        store.saveSchedules(emptyMap())
        HeatingAlarm.cancel(app)
        for (zone in s.zones) {
            val id = zone.deviceId ?: continue
            if (RubetekMapper.isRubetek(id)) {
                lines += "${zone.name}: телефон больше не переключает"
                continue
            }
            lines += try {
                client.deleteTimers(id, TIMER_CATEGORY)
                "${zone.name}: расписание снято"
            } catch (e: Exception) {
                "${zone.name}: ошибка — ${describe(e)}"
            }
        }
        store.deployResult = "Автопилот выключен"
        return lines.joinToString("\n")
    }

    /** Выставить уставку конвектору Rubetek (и включить его, если нужно). */
    suspend fun applyRubetek(id: String, temp: Double, turnOn: Boolean = false) {
        val (house, device) = RubetekMapper.parseId(id) ?: return
        val state = mutableMapOf<String, Any>("thermostat:setTemp" to Math.round(temp).toInt().coerceIn(5, 35))
        if (turnOn) state["thermostat:setMode"] = 1
        RubetekClient(RubetekStore(app)).setState(house, device, state)
        AppLog.i("Отопление: Rubetek ${id.takeLast(6)} уставка ${state["thermostat:setTemp"]}")
    }

    /** Будильник: выставить конвекторам Rubetek уставку текущего часа и завести следующий. */
    suspend fun applyRubetekNow() {
        if (!store.load().autopilot) return
        val hour = LocalTime.now().hour
        for ((id, sp) in store.loadSchedules()) {
            runCatching { applyRubetek(id, sp[hour]) }
                .onFailure { AppLog.e("Отопление: уставка Rubetek не отправлена", it) }
        }
        HeatingAlarm.scheduleNext(app)
    }

    private fun changes(sp: DoubleArray) = (0 until 24).count { sp[it] != sp[(it + 23) % 24] }

    private suspend fun specOf(client: TuyaCloudClient, id: String): Map<String, DpSpec> {
        val spec = runCatching { client.getSpecification(id) }.getOrDefault(emptyMap())
        return if ("temp_set" in spec) spec else spec + runCatching { client.getThingModel(id) }.getOrDefault(emptyMap())
    }

    /** Таймеры только на часы, где уставка меняется. */
    private fun timerInstructs(setpoints: DoubleArray, temp: DpSpec): List<Pair<String, List<Pair<String, Any?>>>> {
        val out = mutableListOf<Pair<String, List<Pair<String, Any?>>>>()
        for (h in 0 until 24) {
            val prev = setpoints[(h + 23) % 24]
            if (h == 0 || setpoints[h] != prev) {
                out += "%02d:00".format(h) to listOf("temp_set" to encode(setpoints[h], temp))
            }
        }
        return out
    }

    companion object {
        /** Своя категория таймеров: расписания, заведённые вручную в Tuya Smart, не трогаем. */
        const val TIMER_CATEGORY = "moydomheat"

        /** Шаг уставки в градусах: step · 10^−scale (например, 5 при scale 1 — это 0,5°). */
        fun stepOf(spec: DpSpec?): Double {
            if (spec == null) return 0.5
            val s = spec.step.coerceAtLeast(1) / 10.0.pow(spec.scale)
            // Термостат может принимать десятые, но для плана хватает полуградуса
            return if (s in 0.05..5.0) maxOf(s, 0.5) else 0.5
        }

        /** Градусы -> значение DP с учётом множителя и допустимого диапазона. */
        fun encode(t: Double, spec: DpSpec): Long {
            var v = (t * 10.0.pow(spec.scale)).roundToLong()
            spec.min?.let { v = maxOf(v, it) }
            spec.max?.let { v = minOf(v, it) }
            return v
        }

        fun describe(e: Throwable): String = when (e) {
            is TuyaApiException -> when (e.code) {
                1106, 28841101 -> "API расписаний не подключено в облачном проекте Tuya (${e.code}). " +
                    "Добавьте сервис «Device Timing Management» (или IoT Core) в Cloud → Project → Service API"
                else -> "Tuya ${e.code}: ${e.message}"
            }
            is java.net.UnknownHostException -> "нет интернета"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
