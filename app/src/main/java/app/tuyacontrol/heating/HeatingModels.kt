package app.tuyacontrol.heating

/**
 * Окно комфорта: часы [from, to), может переходить через полночь (23–11).
 * from == to — круглые сутки.
 */
data class ComfortWindow(val from: Int, val to: Int, val temp: Double) {
    fun covers(hour: Int): Boolean = when {
        from == to -> true
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }

    fun text(): String = if (from == to) "круглосуточно" else "${hh(from)}–${hh(to)}"

    private fun hh(h: Int) = "${h.toString().padStart(2, '0')}:00"
}

/**
 * Зона отопления — комната с одним термостатом.
 *
 * Тепловая модель (первого порядка), температура по датчику термостата:
 *   dT/dt = heatRate·u − lossRate·(T − Tулица),  u ∈ [0, 1] — доля времени, когда нагрев включён.
 */
data class HeatZone(
    val id: String,
    val name: String,
    /** Термостат, которым управляем; null — ещё не выбран. */
    val deviceId: String? = null,
    val windows: List<ComfortWindow> = emptyList(),
    /** Дежурная температура вне окон комфорта. */
    val baseTemp: Double = 16.0,
    /** На сколько градусов можно просесть в пиковую зону внутри окна комфорта. */
    val peakDrop: Double = 1.0,
    /** Выше этого не греем даже про запас. */
    val maxTemp: Double = 25.0,
    /** Мощность нагревателя, кВт. */
    val powerKw: Double = 1.5,
    /** Скорость нагрева при постоянно включённом нагреве, °C/ч (без учёта потерь). */
    val heatRate: Double = 2.0,
    /** Доля разницы с улицей, теряемая за час (1/ч). 0,03 — дом остывает на 3% разницы в час. */
    val lossRate: Double = 0.03,
    /** Учитывать зону в общей сводке «Сегодня по плану» (на управление не влияет). */
    val inTotal: Boolean = true,
    /** Управление включено: приложение выставляет устройству уставки по плану. Выключено — не трогает. */
    val control: Boolean = false,
    /** Конвектор (вкл/выкл): разрешено включать в пиковые часы, чтобы держать комфорт с просадкой.
     *  Выключено — в пик не включаем, только если комната остыла ниже дежурной. */
    val peakHeat: Boolean = false,
    /**
     * Гистерезис термостата, °C; null — по типу устройства.
     *  > 0: при уставке S греет до S + h и включается при падении до S (тёплые полы: +0,5, спальня +1);
     *  < 0: греет до S, включается при S + h (конвекторы Rubetek −1, батарея в ванной −0,5).
     */
    val hysteresis: Double? = null,
    /**
     * Копить тепло заранее: греть по дешёвому тарифу выше нужного, чтобы потом не греть по дорогому.
     * Выключено — вне окон комфорта держим только дежурную и начинаем прогрев «впритык» к окну.
     * null — по умолчанию (включено).
     */
    val storeHeat: Boolean? = null,
    /** Подбирать скорость нагрева и остывания по истории (каждую ночь). */
    val autoTune: Boolean = true,
) {
    /** Тёплый пол (гостиная, спальня); остальное — обогреватели (ИК под потолком, конвекторы, батарея). */
    val isFloor: Boolean
        get() = id in setOf("bedroom", "living") || listOf("спальн", "гостин").any { name.contains(it, ignoreCase = true) }

    /** Гистерезис с учётом значения по умолчанию. */
    val hyst: Double
        get() = hysteresis ?: if (deviceId?.startsWith("rubetek:") == true) -1.0 else 0.5

    /** Температура, которую нужно держать в этот час (без поправки на пик). */
    fun targetAt(hour: Int): Double = windows.filter { it.covers(hour) }.maxOfOrNull { it.temp } ?: baseTemp

    /** Нижняя граница: в пиковый час внутри окна комфорта можно просесть на peakDrop. */
    fun minAt(hour: Int, peak: Boolean): Double {
        val inWindow = windows.any { it.covers(hour) }
        val t = targetAt(hour)
        return if (inWindow && peak) maxOf(t - peakDrop, baseTemp) else t
    }
}

data class HeatingSettings(
    val zones: List<HeatZone>,
    /** Координаты дома для прогноза погоды. */
    val latitude: Double = 55.75,
    val longitude: Double = 37.62,
    /** Автопилот: план записан в облачное расписание термостатов. */
    val autopilot: Boolean = false,
    /** Уличный датчик температуры (id устройства и код DP); null — не выбран. */
    val outdoorSensorId: String? = null,
    val outdoorCode: String? = null,
) {
    companion object {
        /** Начальные настройки по словам хозяина дома. */
        fun defaults() = HeatingSettings(
            zones = listOf(
                HeatZone(
                    id = "bedroom", name = "Спальня",
                    windows = listOf(ComfortWindow(23, 11, 23.0), ComfortWindow(16, 18, 23.0)),
                    baseTemp = 18.0, peakDrop = 1.0, maxTemp = 25.0,
                ),
                HeatZone(
                    id = "living", name = "Гостиная",
                    windows = listOf(ComfortWindow(0, 0, 22.0)),
                    baseTemp = 21.0, peakDrop = 1.0, maxTemp = 24.0,
                ),
                HeatZone(
                    id = "hall", name = "Коридор",
                    windows = listOf(ComfortWindow(12, 19, 18.0)),
                    baseTemp = 12.0, peakDrop = 1.0, maxTemp = 20.0,
                ),
            ),
        )

        /** Подсказки для автоподбора термостата по названию устройства. */
        val NAME_HINTS = mapOf(
            "bedroom" to listOf("спальн"),
            "living" to listOf("гостин"),
            "hall" to listOf("коридор"),
        )
    }
}

/** Цены по часам суток и признак пиковой зоны. */
data class HourPrices(val price: DoubleArray, val peak: BooleanArray, val zone: IntArray) {
    companion object {
        /** Тарифы по умолчанию (Т1 пик, Т2 ночь, Т3 полупик), если в приложении тарифы не введены. */
        fun default(): HourPrices {
            val price = DoubleArray(24)
            val zone = IntArray(24)
            for (h in 0 until 24) {
                val z = when (h) {
                    in 7 until 10, in 17 until 21 -> 0
                    in 23..23, in 0 until 7 -> 1
                    else -> 2
                }
                zone[h] = z
                price[h] = when (z) { 0 -> 9.83; 1 -> 3.57; else -> 6.88 }
            }
            return of(price, zone)
        }

        fun of(price: DoubleArray, zone: IntArray): HourPrices {
            val max = price.maxOrNull() ?: 0.0
            val min = price.minOrNull() ?: 0.0
            // Пик — самые дорогие часы (если тариф одноставочный, пика нет)
            val peak = BooleanArray(24) { max > min && price[it] >= max - 1e-9 }
            return HourPrices(price, peak, zone)
        }
    }
}
