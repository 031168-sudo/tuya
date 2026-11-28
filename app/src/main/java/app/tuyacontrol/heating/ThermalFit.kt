package app.tuyacontrol.heating

import kotlin.math.abs

/**
 * Подбор параметров зоны по истории.
 *
 * Модель: dT/dt = heatRate·u − lossRate·(T − Tулица).
 * Оба параметра подбираются одновременно по всем участкам, где известно состояние нагрева.
 * Не требуем «12 часов без нагрева»: короткие остывания тоже дают данные.
 *
 * u — доля времени, когда прибор реально грел:
 *  1 — греет, 0 — не греет, 0..1 — частично (например, реле щёлкает).
 * Для Rubetek/Xiaomi фактический нагрев внутри прибора неизвестен; такие точки помечаются
 * [Sample.inferred] и результат считается приблизительным.
 */
object ThermalFit {

    /**
     * Один час истории: скорость изменения температуры (°/ч), доля нагрева 0..1,
     * разница с улицей (°) и признак «нагрев определён косвенно».
     */
    data class Sample(
        val dTdt: Double,
        val u: Double,
        val diff: Double,
        val inferred: Boolean = false,
    )

    data class Result(
        val heatRate: Double?,
        val lossRate: Double?,
        val coolHours: Int,
        val heatHours: Int,
        /** Почему не подобрано (null — подобрано). */
        val problem: String?,
        /** Оговорка: например, нагрев определён косвенно. */
        val note: String? = null,
        /** Параметры приблизительные: часть данных выведена, а не измерена. */
        val approximate: Boolean = false,
        /** Средняя ошибка модели по истории, °/ч. */
        val mae: Double = 0.0,
    )

    // Считаем по 15-минутным точкам: 4 точки = час
    const val MIN_SAMPLES = 24 * 4
    const val MIN_HEAT_HOURS = 6 * 4
    const val MIN_COOL_HOURS = 4 * 4
    const val MIN_HEAT_RATE = 0.1
    const val MAX_HEAT_RATE = 15.0
    const val MIN_LOSS = 0.002
    const val MAX_LOSS = 0.3

    /** Средняя ошибка модели выше этого — истории нельзя доверять. */
    private const val MAX_MAE = 1.5

    /**
     * Совместный робастный подбор heatRate и lossRate.
     * @param all часы истории; [Sample.inferred] — нагрев не измерен, а выведен.
     */
    fun fit(all: List<Sample>): Result {
        // Скачки (открыли окно, сбой датчика) и слишком маленькая разница с улицей не помогают
        val samples = all.filter { abs(it.dTdt) <= 6.0 && it.diff > 1.0 && it.u in 0.0..1.0 }
        val heatHours = samples.count { it.u >= 0.5 }
        val coolHours = samples.count { it.u <= 0.1 }
        if (samples.size < MIN_SAMPLES) {
            return Result(null, null, coolHours, heatHours, "мало истории температуры (${samples.size / 4} ч, нужно ${MIN_SAMPLES / 4})")
        }
        if (heatHours < MIN_HEAT_HOURS) {
            return Result(null, null, coolHours, heatHours, "мало часов с нагревом (${heatHours / 4} из ${MIN_HEAT_HOURS / 4})")
        }
        if (coolHours < MIN_COOL_HOURS) {
            return Result(null, null, coolHours, heatHours, "мало часов без нагрева (${coolHours / 4} из ${MIN_COOL_HOURS / 4})")
        }

        var h = 2.0
        var a = 0.03
        repeat(10) {
            var s11 = 0.0
            var s12 = 0.0
            var s22 = 0.0
            var b1 = 0.0
            var b2 = 0.0
            for (s in samples) {
                // Ошибка модели -> вес: большие выбросы почти не влияют
                val r = s.dTdt - (h * s.u - a * s.diff)
                val w = 1.0 / maxOf(1.0, abs(r) / 0.3)
                val x1 = s.u
                val x2 = -s.diff
                val y = s.dTdt
                s11 += w * x1 * x1
                s12 += w * x1 * x2
                s22 += w * x2 * x2
                b1 += w * x1 * y
                b2 += w * x2 * y
            }
            val det = s11 * s22 - s12 * s12
            if (det < 1e-9) {
                return Result(null, null, coolHours, heatHours, "недостаточно разнообразия в истории нагрева")
            }
            h = (b1 * s22 - b2 * s12) / det
            a = (s11 * b2 - b1 * s12) / det
        }

        if (h !in MIN_HEAT_RATE..MAX_HEAT_RATE) {
            return Result(null, null, coolHours, heatHours, "нагрев вне разумных пределов (${"%.1f".format(h)}°/ч)")
        }
        if (a !in MIN_LOSS..MAX_LOSS) {
            return Result(null, null, coolHours, heatHours, "остывание вне разумных пределов (${"%.1f".format(a * 100)}%/ч)")
        }

        val mae = samples.map { abs(it.dTdt - (h * it.u - a * it.diff)) }.average()
        if (mae > MAX_MAE) {
            return Result(
                null, null, coolHours, heatHours,
                "история противоречива: ошибка модели ${"%.1f".format(mae)}°/ч",
                mae = mae,
            )
        }
        val inferred = samples.count { it.inferred }
        val approximate = inferred > 0
        val note = buildString {
            if (inferred > 0) append("нагрев определён косвенно: $inferred ч")
        }.ifEmpty { null }
        return Result(h, a, coolHours, heatHours, null, note, approximate, mae)
    }
}
