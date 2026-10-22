package app.tuyacontrol.heating

/**
 * Подбор параметров зоны по истории. Модель та же, что в плане:
 *   dT/dt = heatRate·u − lossRate·(T − Tулица)
 * Сначала по часам без нагрева (u = 0) — остывание, затем по часам с нагревом — скорость нагрева.
 */
object ThermalFit {

    /**
     * Один час истории: скорость изменения температуры (°/ч), доля нагрева 0..1, разница с улицей (°),
     * сколько часов прошло с последнего нагрева (пол и конвектор ещё отдают тепло после выключения).
     */
    data class Sample(val dTdt: Double, val u: Double, val diff: Double, val quiet: Double = Double.MAX_VALUE)

    data class Result(
        val heatRate: Double?,
        val lossRate: Double?,
        val coolHours: Int,
        val heatHours: Int,
        /** Почему что-то не подобрано (null — всё подобрано). */
        val problem: String?,
        /** Подобрано, но с оговоркой (например, потери очень малы). */
        val note: String? = null,
    )

    const val MIN_COOL_HOURS = 12
    const val MIN_HEAT_HOURS = 4
    /** Нижняя граница остывания: 0,3 %/ч. */
    const val MIN_LOSS = 0.003

    /** Медиана с весами. */
    private fun weightedMedian(v: List<Pair<Double, Double>>): Double {
        val sorted = v.sortedBy { it.first }
        val half = sorted.sumOf { it.second } / 2
        var acc = 0.0
        for ((x, w) in sorted) {
            acc += w
            if (acc >= half) return x
        }
        return sorted.last().first
    }

    /** [settle] — сколько часов после нагрева не считать «остыванием» (тёплый пол отдаёт тепло ещё несколько часов). */
    fun fit(all: List<Sample>, settle: Double = 0.0): Result {
        // Скачки (открыли окно, сбой датчика) не учитываем
        val samples = all.filter { kotlin.math.abs(it.dTdt) <= 6.0 }
        val cool = samples.filter { it.u == 0.0 && it.diff > 1.0 && it.quiet >= settle }
        val heat = samples.filter { it.u >= 0.5 }

        var loss: Double? = null
        var problem: String? = null
        var note: String? = null
        if (cool.size < MIN_COOL_HOURS) {
            problem = "мало часов без нагрева (${cool.size} из $MIN_COOL_HOURS)"
        } else {
            // dT/dt = −a·diff: каждый час даёт своё a = −dT/dt / diff; берём взвешенную медиану (вес diff²),
            // чтобы несколько часов с неверной отметкой «не греет» не ломали оценку
            val a = weightedMedian(cool.map { -it.dTdt / it.diff to it.diff * it.diff })
            when {
                a in MIN_LOSS..0.5 -> loss = a
                // Остывание около нуля: потери очень малы, а шум датчика (шаг 0,5°) больше сигнала
                a > -0.005 && a < MIN_LOSS -> {
                    loss = MIN_LOSS
                    note = "потери очень малы — взято минимальное ${"%.1f".format(MIN_LOSS * 100)} %/ч"
                }
                else -> problem = "остывание вне разумных пределов (${"%.1f".format(a * 100)}%/ч)"
            }
        }

        var heatRate: Double? = null
        if (loss != null) {
            if (heat.size < MIN_HEAT_HOURS) {
                problem = "мало часов с нагревом (${heat.size} из $MIN_HEAT_HOURS)"
            } else {
                // dT/dt + a·diff = h·u  →  h = Σ u·(y + a·d) / Σ u²
                val h = heat.sumOf { it.u * (it.dTdt + loss * it.diff) } / heat.sumOf { it.u * it.u }
                if (h in 0.2..15.0) heatRate = h else problem = "нагрев вне разумных пределов (${"%.1f".format(h)}°/ч)"
            }
        }
        return Result(heatRate, loss, cool.size, heat.size, problem, note)
    }
}
