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
) {
    /** Тот же план с обновлёнными настройками зоны (например, флагом «в общем расчёте»). */
    fun withZone(z: HeatZone) = ZonePlan(
        z, temps, heat, minTemps, setpoints, kwh, cost, baselineKwh, baselineCost, kwhByZone, shortSteps,
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

    /**
     * @param prices цены по часам суток (повторяются на вторые сутки)
     * @param outdoor уличная температура по часам, 48 значений (если меньше — последний повторяется)
     * @param step шаг уставки термостата, °C (например, 0.5 или 1)
     */
    fun plan(zone: HeatZone, prices: HourPrices, outdoor: DoubleArray, step: Double = 0.5): ZonePlan {
        val price = DoubleArray(N) { prices.price[(it / STEPS_PER_HOUR) % 24] }
        val tout = DoubleArray(N) { outdoor.getOrElse(it / STEPS_PER_HOUR) { outdoor.lastOrNull() ?: 0.0 } }
        // Граница комфорта в момент k (начало шага k)
        val minT = DoubleArray(N + 1) { k ->
            val h = (k / STEPS_PER_HOUR) % 24
            zone.minAt(h, prices.peak[h])
        }
        val (temps, heat) = solve(zone, price, tout, minT)

        // Уставки: в часы нагрева — температура, до которой греем; иначе — нижняя граница
        val setpoints = DoubleArray(24) { hour ->
            val ks = hour * STEPS_PER_HOUR until (hour + 1) * STEPS_PER_HOUR
            val heating = ks.any { heat[it] > 0 }
            if (heating) {
                // До ближайшего шага термостата, но не ниже границы комфорта
                maxOf(roundNearest(ks.maxOf { temps[it + 1] }, step), roundUp(ks.maxOf { minT[it + 1] }, step))
            } else {
                roundDown(ks.minOf { minT[it + 1] }, step)
            }
        }

        val steps = 24 * STEPS_PER_HOUR
        var kwh = 0.0
        var cost = 0.0
        val byZone = DoubleArray(3)
        var short = 0
        for (k in 0 until steps) {
            val e = zone.powerKw * heat[k] * DT
            kwh += e
            cost += e * price[k]
            val z = prices.zone[k / STEPS_PER_HOUR]
            if (z in byZone.indices) byZone[z] += e
            if (temps[k + 1] < minT[k + 1] - 0.15) short++
        }

        // Для сравнения: тот же комфорт без просадки в пик и без оглядки на тарифы —
        // минимум киловатт-часов (цена везде одинаковая), а платим по реальным тарифам
        val flat = DoubleArray(N) { 1.0 }
        val plainMin = DoubleArray(N + 1) { k -> zone.targetAt((k / STEPS_PER_HOUR) % 24) }
        val (_, bHeat) = solve(zone, flat, tout, plainMin)
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
        )
    }

    /** Динамическое программирование: температуры (первые сутки, 97 точек) и доли нагрева (96). */
    private fun solve(zone: HeatZone, price: DoubleArray, tout: DoubleArray, minT: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val lo = minOf(minT.min(), zone.baseTemp) - 3.0
        val hi = maxOf(zone.maxTemp, minT.max())
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
                for (u in LEVELS.indices) {
                    val t2 = (t + DT * (h * LEVELS[u] - a * (t - tout[k]))).coerceIn(lo, hi)
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
        temps[0] = minT[0]
        var t = temps[0]
        for (k in 0 until steps) {
            val i = ((t - lo) / GRID).roundToInt().coerceIn(0, states - 1)
            val u = LEVELS[policy[k][i].toInt()]
            heat[k] = u
            t = (t + DT * (h * u - a * (t - tout[k]))).coerceIn(lo, hi)
            temps[k + 1] = t
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
