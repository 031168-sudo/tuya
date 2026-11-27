package app.tuyacontrol.heating

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** План зоны на сутки с шагом 15 минут. */
class ZonePlan(
    val zone: HeatZone,
    /** Температура в начале каждого шага, 97 точек (00:00 … 24:00). */
    val temps: DoubleArray,
    /** Доля включения нагрева на каждом шаге, 96 значений. */
    val heat: DoubleArray,
    /** Нижняя граница комфорта на каждом шаге. */
    val minTemps: DoubleArray,
    /** Уставка термостата на каждый час. */
    val setpoints: DoubleArray,
    val kwh: Double,
    val cost: Double,
    /** Если просто держать температуру по расписанию комфорта, без оптимизации. */
    val baselineKwh: Double,
    val baselineCost: Double,
    /** кВт·ч по зонам тарифа (индекс зоны). */
    val kwhByZone: DoubleArray,
    /** Сколько шагов не удаётся удержать нижнюю границу (мощности не хватает). */
    val shortSteps: Int,
    /** Час суток, с которого начинается план (0..23). */
    val startHour: Int = 0,
    /** Фактическая температура в начале плана, °C; null — не было свежих данных. */
    val startTemp: Double? = null,
) {
    /** Тот же план с обновлёнными настройками зоны (например, флагом «в общем расчёте»). */
    fun withZone(z: HeatZone) = ZonePlan(
        z, temps, heat, minTemps, setpoints, kwh, cost, baselineKwh, baselineCost, kwhByZone, shortSteps,
        startHour, startTemp,
    )
}

/**
 * Оптимальный по деньгам график нагрева: динамическое программирование по температуре.
 * Горизонт — двое суток (чтобы к концу первых суток не «проедать» запас тепла), в план идут первые.
 */
object HeatingPlanner {

    const val STEPS_PER_HOUR = 4
    private const val DT = 1.0 / STEPS_PER_HOUR
    private const val HOURS = 48
    private const val N = HOURS * STEPS_PER_HOUR
    private const val GRID = 0.05
    private val LEVELS = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
    /** Штраф за каждый градус ниже границы комфорта на шаге, ₽ — практически запрет. */
    private const val PENALTY = 1000.0

    /** Больше этого числа шагов (2 часа) без комфорта — план считается небезопасным для записи. */
    const val MAX_SHORT_STEPS = 2 * STEPS_PER_HOUR

    /** Нагрев медленнее 0,5°/ч для настоящего обогревателя — почти наверняка ошибка подбора. */
    const val MIN_HEAT_RATE = 0.5

    /**
     * @param prices цены по часам суток (повторяются на вторые сутки)
     * @param outdoor уличная температура по часам, 48 значений (если меньше — последний повторяется)
     * @param step шаг уставки термостата, °C (например, 0.5 или 1)
     * @param peakBan в пиковые часы не греть совсем (только если комната остыла ниже дежурной) —
     *   для конвекторов, которыми управляем таймерами вкл/выкл
     * @param onOff прибор умеет только «вкл/выкл» (Rubetek, Xiaomi): решение на целый час; включённый прибор
     *   греет на 100% до собственной уставки и держит её (энергия — только на потери)
     */
    fun plan(
        zone: HeatZone,
        prices: HourPrices,
        outdoor: DoubleArray,
        step: Double = 0.5,
        peakBan: Boolean = false,
        onOff: Boolean = false,
        startTemp: Double? = null,
        startHour: Int = 0,
    ): ZonePlan {
        val start = Math.floorMod(startHour, 24)
        fun clock(k: Int) = (start + k / STEPS_PER_HOUR) % 24
        val out = if (outdoor.isEmpty()) doubleArrayOf(0.0) else outdoor
        val price = DoubleArray(N) { prices.price[clock(it)] }
        val tout = DoubleArray(N) {
            val idx = (start * STEPS_PER_HOUR + it) % (out.size * STEPS_PER_HOUR)
            out[idx / STEPS_PER_HOUR]
        }
        val peakStep = BooleanArray(N) { prices.peak[clock(it)] }
        // Граница комфорта в момент k (начало шага k): к началу часа комната уже должна быть нужной температуры
        val minT = DoubleArray(N + 1) { k ->
            var h = clock(k)
            // Без накопления тепла прогрев идёт «впритык»: в первые 15 минут окна комфорта ещё догреваемся
            if (zone.storeHeat == false && k % STEPS_PER_HOUR == 0 &&
                zone.windows.any { it.covers(h) } && zone.windows.none { it.covers((h + 23) % 24) }
            ) h = (h + 23) % 24
            if (peakBan && prices.peak[h]) minOf(zone.minAt(h, true), zone.baseTemp) else zone.minAt(h, prices.peak[h])
        }
        // Без накопления тепла: вне окон комфорта не выше дежурной, прогрев только «впритык» перед окном
        val cap: DoubleArray? = if (zone.storeHeat == false) DoubleArray(N + 1) { k ->
            val h = start + k / STEPS_PER_HOUR
            if (zone.windows.any { it.covers(h % 24) }) return@DoubleArray zone.maxTemp
            // Ближайший час окна комфорта впереди
            val next = (h + 1..h + 48).firstOrNull { hh -> zone.windows.any { it.covers(hh % 24) } }
            // Запас над дежурной на градус — иначе шагам нагрева «некуда» и дежурную не удержать
            val base = maxOf(zone.baseTemp, minT[k]) + 1.0
            if (next == null) return@DoubleArray base
            val hoursTo = next - k.toDouble() / STEPS_PER_HOUR
            // Чистая скорость прогрева: мощность минус потери к улице (с небольшим запасом)
            val target = zone.targetAt(next % 24)
            // Потолок чуть выше кривой «греем на полной мощности» — чтобы успеть к началу окна
            val rate = (zone.heatRate - zone.lossRate * (target - tout[minOf(k, N - 1)])).coerceAtLeast(0.2) * 1.5
            val ramp = target + 1.0 - rate * hoursTo
            maxOf(base, ramp)
        } else null
        val (temps, heat) = if (onOff) solveOnOff(zone, price, tout, minT, if (peakBan) peakStep else null, startTemp)
        else solve(zone, price, tout, minT, if (peakBan) peakStep else null, cap, startTemp)

        // Уставки с учётом гистерезиса термостата: при h > 0 он держит [S, S+h], при h < 0 — [S+h, S].
        // В часы нагрева верх полосы — температура, до которой греем; иначе низ полосы — граница комфорта.
        val up = maxOf(zone.hyst, 0.0)
        val down = minOf(zone.hyst, 0.0)
        val setpoints = DoubleArray(24) { hour ->
            // hour — час суток; в плане он может стоять не на своём относительном месте
            val rel = Math.floorMod(hour - start, 24)
            val ks = rel * STEPS_PER_HOUR until (rel + 1) * STEPS_PER_HOUR
            val heating = ks.any { heat[it] > 0 }
            if (heating) {
                // До ближайшего шага термостата, но так, чтобы низ полосы был не ниже границы комфорта
                maxOf(roundNearest(ks.maxOf { temps[it + 1] } - up, step), roundUp(ks.maxOf { minT[it + 1] } - down, step))
            } else {
                roundDown(ks.minOf { minT[it + 1] } - down, step)
            }
        }

        val steps = 24 * STEPS_PER_HOUR
        var kwh = 0.0
        var cost = 0.0
        val byZone = DoubleArray(3)
        var short = 0
        // После запрещённого пика конвектор догревает комнату — пока догревает, нехваткой мощности не считаем.
        // Если план стартует с фактической температуры ниже границы, первые шаги — тоже догрев, а не нехватка.
        var recovering = temps[0] < minT[0] - 0.15
        for (k in 0 until steps) {
            val e = zone.powerKw * heat[k] * DT
            kwh += e
            cost += e * price[k]
            val z = prices.zone[k / STEPS_PER_HOUR]
            if (z in byZone.indices) byZone[z] += e
            val h = k / STEPS_PER_HOUR
            val below = temps[k + 1] < minT[k + 1] - 0.15
            if (peakBan && prices.peak[h]) recovering = true
            else if (!below) recovering = false
            if (below && !recovering) short++
        }

        // Для сравнения: тот же комфорт без просадки в пик и без оглядки на тарифы —
        // минимум киловатт-часов (цена везде одинаковая), а платим по реальным тарифам
        val flat = DoubleArray(N) { 1.0 }
        val plainMin = DoubleArray(N + 1) { k -> zone.targetAt((k / STEPS_PER_HOUR) % 24) }
        val (_, bHeat) = if (onOff) solveOnOff(zone, flat, tout, plainMin, null) else solve(zone, flat, tout, plainMin)
        var bKwh = 0.0
        var bCost = 0.0
        for (k in 0 until steps) {
            val e = zone.powerKw * bHeat[k] * DT
            bKwh += e
            bCost += e * price[k]
        }

        return ZonePlan(
            zone = zone,
            temps = temps,
            heat = heat,
            minTemps = DoubleArray(steps + 1) { minT[it] },
            setpoints = setpoints,
            kwh = kwh,
            cost = cost,
            baselineKwh = bKwh,
            baselineCost = bCost,
            kwhByZone = byZone,
            shortSteps = short,
            startHour = start,
            startTemp = startTemp,
        )
    }

    /**
     * План не держит комфорт дольше двух часов в сутки: писать его в приборы нельзя — оставшиеся
     * окна комфорта окажутся без нагрева. Такой план заменяется аварийным «греть в окнах».
     */
    fun isUnsafe(plan: ZonePlan) = plan.shortSteps > MAX_SHORT_STEPS

    /** Текущие параметры зоны выглядят ошибочными: мощный прибор не может греть так медленно. */
    fun isSuspiciousHeat(zone: HeatZone) = zone.powerKw >= 1.0 && zone.heatRate < MIN_HEAT_RATE

    /** Что писать в устройство: обычный план, а если он не держит комфорт — аварийный график. */
    fun deployPlan(plan: ZonePlan): ZonePlan = if (isUnsafe(plan)) comfortFallback(plan) else plan

    /**
     * Аварийный график: держать уставку по окнам комфорта и дежурную вне них.
     * Конвектор включается на часы окон; если окон нет (дежурство) — работает постоянно.
     */
    fun comfortFallback(plan: ZonePlan): ZonePlan {
        val zone = plan.zone
        val setpoints = DoubleArray(24) { zone.targetAt(it) }
        val hasWindow = zone.windows.any { it.temp > zone.baseTemp + 1e-9 }
        val heat = DoubleArray(24 * STEPS_PER_HOUR) { k ->
            val h = (plan.startHour + k / STEPS_PER_HOUR) % 24
            if (!hasWindow || zone.windows.any { it.covers(h) && it.temp > zone.baseTemp + 1e-9 }) 1.0 else 0.0
        }
        return ZonePlan(
            zone = zone, temps = plan.temps, heat = heat, minTemps = plan.minTemps,
            setpoints = setpoints, kwh = plan.kwh, cost = plan.cost,
            baselineKwh = plan.baselineKwh, baselineCost = plan.baselineCost,
            kwhByZone = plan.kwhByZone, shortSteps = plan.shortSteps,
            startHour = plan.startHour, startTemp = plan.startTemp,
        )
    }

    /** Часы, в которые план греет (относительно часа старта). */
    private fun heatHours(plan: ZonePlan): BooleanArray = BooleanArray(24) { h ->
        (0 until STEPS_PER_HOUR).any { plan.heat[h * STEPS_PER_HOUR + it] > 0 }
    }

    private fun transitions(plan: ZonePlan): List<Pair<Int, Boolean>> {
        val on = heatHours(plan)
        return (0 until 24)
            .filter { h -> on[h] != on[(h + 23) % 24] }
            .map { h -> h * 60 to on[h] }
            .sortedBy { it.first }
    }

    /** Час включения/выключения по плану: в часы, где план греет, прибор включён. (минуты, вкл?) */
    fun onOffEvents(plan: ZonePlan): List<Pair<Int, Boolean>> {
        var rel = transitions(plan)
        if (rel.isEmpty()) rel = listOf(0 to heatHours(plan)[0])
        return rel
            .map { (m, state) -> ((plan.startHour * 60 + m) % 1440) to state }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    /**
     * Нужно ли греть прямо сейчас. Это разовая команда прибору, а не ежедневный таймер:
     * иначе «поставить текущее состояние» останется в расписании навсегда.
     */
    fun desiredOnNow(plan: ZonePlan, nowMinute: Int): Boolean {
        val rel = transitions(plan)
        if (rel.isEmpty()) return heatHours(plan)[0]
        val elapsed = Math.floorMod(nowMinute - plan.startHour * 60, 1440)
        return rel.lastOrNull { it.first <= elapsed }?.second
            ?: rel.firstOrNull()?.let { !it.second } ?: false
    }

    /** Динамическое программирование: температуры (первые сутки, 97 точек) и доли нагрева (96). */
    private fun solve(
        zone: HeatZone,
        price: DoubleArray,
        tout: DoubleArray,
        minT: DoubleArray,
        ban: BooleanArray? = null,
        cap: DoubleArray? = null,
        startTemp: Double? = null,
    ): Pair<DoubleArray, DoubleArray> {
        val lo = minOf(minT.min(), zone.baseTemp, startTemp ?: 0.0) - 3.0
        val hi = maxOf(zone.maxTemp, minT.max(), startTemp ?: 0.0)
        val states = ((hi - lo) / GRID).roundToInt() + 1
        val h = zone.heatRate
        val a = zone.lossRate
        val p = zone.powerKw

        // Обратный проход: next[i] — минимальная стоимость от шага k+1 до конца при температуре i
        var next = DoubleArray(states)
        val policy = Array(N) { ByteArray(states) }
        for (k in N - 1 downTo 0) {
            val cur = DoubleArray(states)
            for (i in 0 until states) {
                val t = lo + i * GRID
                var best = Double.MAX_VALUE
                var bestU = 0
                // Запрет на нагрев в пик, пока комната не остыла ниже дежурной
                val levels = if (ban != null && ban[k] && t >= zone.baseTemp) 1 else LEVELS.size
                for (u in 0 until levels) {
                    val t2 = (t + DT * (h * LEVELS[u] - a * (t - tout[k]))).coerceAtMost(hi)
                    // Выше потолка греть нельзя (без накопления тепла)
                    if (u > 0 && cap != null && t2 > cap[k + 1] + 1e-9) continue
                    val short = (minT[k + 1] - t2).coerceAtLeast(0.0)
                    val v = price[k] * p * LEVELS[u] * DT + short * PENALTY + interp(next, t2, lo, states)
                    if (v < best - 1e-9) {
                        best = v
                        bestU = u
                    }
                }
                cur[i] = best
                policy[k][i] = bestU.toByte()
            }
            next = cur
        }

        // Прямой проход от нижней границы в полночь
        val steps = 24 * STEPS_PER_HOUR
        val temps = DoubleArray(steps + 1)
        val heat = DoubleArray(steps)
        temps[0] = startTemp?.coerceIn(lo, hi) ?: minT[0]
        var t = temps[0]
        for (k in 0 until steps) {
            val i = ((t - lo) / GRID).roundToInt().coerceIn(0, states - 1)
            val u = LEVELS[policy[k][i].toInt()]
            heat[k] = u
            t = (t + DT * (h * u - a * (t - tout[k]))).coerceAtMost(hi)
            temps[k + 1] = t
        }
        return temps to heat
    }

    /**
     * Режим «вкл/выкл» (таймеры Rubetek/Xiaomi): на каждый час — либо выкл, либо вкл. Включённый прибор греет
     * на полную мощность до своей уставки (потолок — maxTemp зоны), дальше держит её: доля мощности = потери.
     */
    private fun solveOnOff(
        zone: HeatZone,
        price: DoubleArray,
        tout: DoubleArray,
        minT: DoubleArray,
        ban: BooleanArray?,
        startTemp: Double? = null,
    ): Pair<DoubleArray, DoubleArray> {
        val lo = minOf(minT.min(), zone.baseTemp, startTemp ?: 0.0) - 3.0
        val hi = maxOf(zone.maxTemp, minT.max(), startTemp ?: 0.0)
        val states = ((hi - lo) / GRID).roundToInt() + 1
        val h = zone.heatRate
        val a = zone.lossRate
        val p = zone.powerKw
        val ceiling = minOf(hi, zone.maxTemp)
        val blocks = N / STEPS_PER_HOUR

        fun stepOf(k: Int, t: Double, on: Boolean): Pair<Double, Double> {
            val loss = a * (t - tout[k])
            if (!on) return (t - DT * loss).coerceAtMost(hi) to 0.0
            val raw = t + DT * (h - loss)
            if (raw <= ceiling + 1e-9) return raw.coerceAtMost(hi) to 1.0
            if (t >= ceiling) return (t - DT * loss).coerceAtMost(hi) to 0.0
            return ceiling to (((ceiling - t) / DT + loss) / h).coerceIn(0.0, 1.0)
        }

        var next = DoubleArray(states)
        val policy = Array(blocks) { ByteArray(states) }
        for (b in blocks - 1 downTo 0) {
            val cur = DoubleArray(states)
            for (i in 0 until states) {
                val t0 = lo + i * GRID
                var best = Double.MAX_VALUE
                var bestU = 0
                val levels = if (ban != null && ban[b * STEPS_PER_HOUR] && t0 >= zone.baseTemp) 1 else 2
                for (u in 0 until levels) {
                    var t = t0
                    var v = 0.0
                    for (j in 0 until STEPS_PER_HOUR) {
                        val k = b * STEPS_PER_HOUR + j
                        val (t2, power) = stepOf(k, t, u == 1)
                        v += price[k] * p * power * DT + (minT[k + 1] - t2).coerceAtLeast(0.0) * PENALTY
                        t = t2
                    }
                    v += interp(next, t, lo, states)
                    if (v < best - 1e-9) { best = v; bestU = u }
                }
                cur[i] = best
                policy[b][i] = bestU.toByte()
            }
            next = cur
        }

        val steps = 24 * STEPS_PER_HOUR
        val temps = DoubleArray(steps + 1)
        val heat = DoubleArray(steps)
        temps[0] = startTemp?.coerceIn(lo, hi) ?: minT[0]
        var t = temps[0]
        for (b in 0 until steps / STEPS_PER_HOUR) {
            val i = ((t - lo) / GRID).roundToInt().coerceIn(0, states - 1)
            val on = policy[b][i].toInt() == 1
            for (j in 0 until STEPS_PER_HOUR) {
                val k = b * STEPS_PER_HOUR + j
                val (t2, power) = stepOf(k, t, on)
                heat[k] = power
                t = t2
                temps[k + 1] = t
            }
        }
        return temps to heat
    }

    private fun interp(v: DoubleArray, t: Double, lo: Double, states: Int): Double {
        val x = ((t - lo) / GRID).coerceIn(0.0, (states - 1).toDouble())
        val i = floor(x).toInt().coerceAtMost(states - 2).coerceAtLeast(0)
        val f = x - i
        return v[i] * (1 - f) + v[i + 1] * f
    }

    fun roundNearest(t: Double, step: Double) = Math.round(t / step) * step
    fun roundUp(t: Double, step: Double) = ceil(t / step - 1e-6) * step
    fun roundDown(t: Double, step: Double) = floor(t / step + 1e-6) * step
}
