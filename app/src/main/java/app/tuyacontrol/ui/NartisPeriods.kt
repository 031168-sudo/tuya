package app.tuyacontrol.ui

import app.tuyacontrol.energy.EnergyReports
import app.tuyacontrol.energy.Tariff
import app.tuyacontrol.nartis.NartisSnapshot
import java.time.LocalDate

/** Расход и стоимость за период по разнице показаний Т1–Т3 и тарифам. */
data class MeterPeriod(val kwh: Double, val cost: Double, val from: Long?, val to: Long?)

object NartisPeriods {

    /** Цена зоны Т1/Т2/Т3 из тарифа: зоны в порядке Т1, Т2, Т3. */
    fun price(tariff: Tariff?, index: Int): Double? =
        tariff?.zones?.getOrNull(index)?.price

    /** Тариф на день: общий (без конкретного счётчика), действующий в этот день. */
    fun tariffFor(tariffs: List<Tariff>, day: LocalDate): Tariff? =
        EnergyReports.tariffFor(tariffs, "", day)

    fun period(
        history: List<NartisSnapshot>,
        since: Long,
        tariff: Tariff?,
    ): MeterPeriod {
        val last = history.lastOrNull()
        val base = history.lastOrNull { it.time < since }
            ?: history.firstOrNull { it.time >= since }
        if (last == null || base == null || last.time <= base.time) {
            return MeterPeriod(0.0, 0.0, base?.time, last?.time)
        }
        fun delta(a: Double?, b: Double?): Double =
            ((b ?: 0.0) - (a ?: 0.0)).coerceAtLeast(0.0)

        val t1 = delta(base.t1, last.t1)
        val t2 = delta(base.t2, last.t2)
        val t3 = delta(base.t3, last.t3)
        val cost = t1 * (price(tariff, 0) ?: 0.0) +
            t2 * (price(tariff, 1) ?: 0.0) +
            t3 * (price(tariff, 2) ?: 0.0)
        return MeterPeriod(t1 + t2 + t3, cost, base.time, last.time)
    }
}
