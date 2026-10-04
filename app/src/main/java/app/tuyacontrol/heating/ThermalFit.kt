package app.tuyacontrol.heating

/**
 * Подбор параметров зоны по истории. Модель та же, что в плане:
 *   dT/dt = heatRate·u − lossRate·(T − Tулица)
 * Сначала по часам без нагрева (u = 0) — остывание, затем по часам с нагревом — скорость нагрева.
 */
object ThermalFit {

    /** Один час истории: скорость изменения температуры (°/ч), доля нагрева 0..1, разница с улицей (°). */
    data class Sample(val dTdt: Double, val u: Double, val diff: Double)

    data class Result(
        val heatRate: Double?,
        val lossRate: Double?,
        val coolHours: Int,
        val heatHours: Int,
        /** Почему что-то не подобрано (null — всё подобрано). */
        val problem: String?,
    )

    const val MIN_COOL_HOURS = 12
    const val MIN_HEAT_HOURS = 4

    fun fit(all: List<Sample>): Result {
        // Скачки (открыли окно, сбой датчика) не учитываем
        val samples = all.filter { kotlin.math.abs(it.dTdt) <= 6.0 }
        val cool = samples.filter { it.u == 0.0 && it.diff > 1.0 }
        val heat = samples.filter { it.u >= 0.5 }

        var loss: Double? = null
        var problem: String? = null
        if (cool.size < MIN_COOL_HOURS) {
            problem = "мало часов без нагрева (${cool.size} из $MIN_COOL_HOURS)"
        } else {
            // dT/dt = −a·diff  →  a = −Σ(y·d) / Σ(d²)
            val a = -cool.sumOf { it.dTdt * it.diff } / cool.sumOf { it.diff * it.diff }
            if (a in 0.003..0.5) loss = a else problem = "остывание вне разумных пределов (${"%.1f".format(a * 100)}%/ч)"
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
        return Result(heatRate, loss, cool.size, heat.size, problem)
    }
}
