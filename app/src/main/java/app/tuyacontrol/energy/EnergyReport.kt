package app.tuyacontrol.energy

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PeriodType(val title: String) { DAY("День"), WEEK("Неделя"), MONTH("Месяц"), YEAR("Год") }

/** Строка отчёта: час, день, месяц или устройство. */
data class Bucket(
    val label: String,
    val kwh: Double,
    val cost: Double,
    /** Есть потребление без тарифа — стоимость неполная. */
    val missingTariff: Boolean,
    /** Часть потребления разнесена по часам приблизительно (нет почасовых данных). */
    val estimated: Boolean = false,
    /** Куда перейти при нажатии (null — некуда). */
    val drillType: PeriodType? = null,
    val drillDate: LocalDate? = null,
)

/** Итог по зоне тарифа (Т1, Т2, Т3). */
data class ZoneTotal(val index: Int, val kwh: Double, val cost: Double)

data class EnergyReport(
    val title: String,
    val from: LocalDate,
    val to: LocalDate,
    val kwh: Double,
    val cost: Double,
    val missingTariff: Boolean,
    val estimated: Boolean,
    /** Средний расход в день по прошедшим дням периода. */
    val avgKwhPerDay: Double,
    val avgCostPerDay: Double,
    val zones: List<ZoneTotal>,
    val buckets: List<Bucket>,
    val perDevice: List<Bucket>,
)

object EnergyReports {

    val RU: Locale = Locale("ru")
    private val DAY_TITLE = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
    private val SHORT = DateTimeFormatter.ofPattern("dd.MM", RU)
    private val DAY_ROW = DateTimeFormatter.ofPattern("dd.MM, EE", RU)
    private val UNIFORM = DoubleArray(24) { 1.0 / 24 }

    fun range(type: PeriodType, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (type) {
        PeriodType.DAY -> anchor to anchor
        PeriodType.WEEK -> {
            val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            start to start.plusDays(6)
        }
        PeriodType.MONTH -> anchor.withDayOfMonth(1) to anchor.with(TemporalAdjusters.lastDayOfMonth())
        PeriodType.YEAR -> anchor.withDayOfYear(1) to anchor.with(TemporalAdjusters.lastDayOfYear())
    }

    fun shift(type: PeriodType, anchor: LocalDate, delta: Long): LocalDate = when (type) {
        PeriodType.DAY -> anchor.plusDays(delta)
        PeriodType.WEEK -> anchor.plusWeeks(delta)
        PeriodType.MONTH -> anchor.plusMonths(delta)
        PeriodType.YEAR -> anchor.plusYears(delta)
    }

    fun title(type: PeriodType, anchor: LocalDate): String {
        val (from, to) = range(type, anchor)
        return when (type) {
            PeriodType.DAY -> anchor.format(DAY_TITLE)
            PeriodType.WEEK -> "${from.format(SHORT)} – ${to.format(SHORT)}.${to.year}"
            PeriodType.MONTH -> monthName(anchor).replaceFirstChar { it.uppercase(RU) } + " ${anchor.year}"
            PeriodType.YEAR -> "${anchor.year} год"
        }
    }

    private fun monthName(d: LocalDate): String = d.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)

    /** Тариф на день: тариф устройства важнее общего, при пересечении — с более поздним началом. */
    fun tariffFor(tariffs: List<Tariff>, deviceId: String, day: LocalDate): Tariff? =
        tariffs.filter { it.deviceId == deviceId && it.covers(day) }.maxByOrNull { it.start.toEpochDay() }
            ?: tariffs.filter { it.deviceId == null && it.covers(day) }.maxByOrNull { it.start.toEpochDay() }

    /** Доли суток по часам (сумма = 1). */
    private fun shares(arr: DoubleArray?): DoubleArray? {
        val sum = arr?.sum() ?: return null
        if (sum <= 0.0) return null
        return DoubleArray(24) { arr[it] / sum }
    }

    /** Один час одного устройства после разнесения суточного расхода и применения тарифа. */
    private data class HourCost(
        val deviceId: String,
        val day: LocalDate,
        val hour: Int,
        val kwh: Double,
        val cost: Double,
        val zone: Int?,
        val missing: Boolean,
        val estimated: Boolean,
    )

    /**
     * @param hourly почасовые данные (устройство, день) -> 24 значения
     * @param profiles средний профиль по часам для каждого устройства (для дней без почасовых данных)
     */
    fun build(
        type: PeriodType,
        anchor: LocalDate,
        days: List<DayEnergy>,
        hourly: Map<Pair<String, LocalDate>, DoubleArray>,
        profiles: Map<String, DoubleArray>,
        tariffs: List<Tariff>,
        deviceNames: Map<String, String>,
        today: LocalDate,
    ): EnergyReport {
        val (from, to) = range(type, anchor)

        // Суточный расход берём из статистики, а по часам раскладываем по почасовым долям
        val hours = mutableListOf<HourCost>()
        for (d in days) {
            if (d.day.isBefore(from) || d.day.isAfter(to) || d.kwh <= 0.0) continue
            val exact = shares(hourly[d.deviceId to d.day])
            val share = exact ?: shares(profiles[d.deviceId]) ?: UNIFORM
            val tariff = tariffFor(tariffs, d.deviceId, d.day)
            for (h in 0 until 24) {
                val kwh = d.kwh * share[h]
                if (kwh <= 0.0) continue
                val zone = tariff?.zoneAt(h)
                val price = if (tariff != null && zone != null) tariff.zones[zone].price else null
                hours += HourCost(
                    deviceId = d.deviceId,
                    day = d.day,
                    hour = h,
                    kwh = kwh,
                    cost = (price ?: 0.0) * kwh,
                    zone = zone,
                    missing = price == null,
                    // При одной зоне на весь день разбивка по часам на стоимость не влияет
                    estimated = exact == null && (tariff?.zones?.size ?: 0) > 1,
                )
            }
        }

        fun bucket(label: String, items: List<HourCost>, drillType: PeriodType? = null, drillDate: LocalDate? = null) =
            Bucket(
                label = label,
                kwh = items.sumOf { it.kwh },
                cost = items.sumOf { it.cost },
                missingTariff = items.any { it.missing },
                estimated = items.any { it.estimated },
                drillType = drillType,
                drillDate = drillDate,
            )

        val buckets: List<Bucket> = when (type) {
            PeriodType.DAY -> {
                val byHour = hours.groupBy { it.hour }
                (0 until 24).map { h -> bucket("${h.toString().padStart(2, '0')}:00", byHour[h].orEmpty()) }
            }
            PeriodType.WEEK, PeriodType.MONTH -> {
                val byDay = hours.groupBy { it.day }
                generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { day ->
                    bucket(day.format(DAY_ROW), byDay[day].orEmpty(), PeriodType.DAY, day)
                }.toList()
            }
            PeriodType.YEAR -> {
                val byMonth = hours.groupBy { it.day.monthValue }
                (1..12).map { m ->
                    val first = from.withMonth(m)
                    bucket(
                        monthName(first).replaceFirstChar { it.uppercase(RU) },
                        byMonth[m].orEmpty(),
                        PeriodType.MONTH,
                        first,
                    )
                }
            }
        }

        val perDevice = hours.groupBy { it.deviceId }
            .map { (id, items) -> bucket(deviceNames[id] ?: id, items) }
            .sortedByDescending { it.kwh }

        val zones = hours.filter { it.zone != null }.groupBy { it.zone!! }
            .map { (z, items) -> ZoneTotal(z, items.sumOf { it.kwh }, items.sumOf { it.cost }) }
            .sortedBy { it.index }

        val total = bucket("", hours)
        val lastDay = if (to.isBefore(today)) to else today
        val elapsedDays = if (lastDay.isBefore(from)) 0L else lastDay.toEpochDay() - from.toEpochDay() + 1
        return EnergyReport(
            title = title(type, anchor),
            from = from,
            to = to,
            kwh = total.kwh,
            cost = total.cost,
            missingTariff = total.missingTariff,
            estimated = total.estimated,
            avgKwhPerDay = if (elapsedDays > 0) total.kwh / elapsedDays else 0.0,
            avgCostPerDay = if (elapsedDays > 0) total.cost / elapsedDays else 0.0,
            zones = zones,
            buckets = buckets,
            perDevice = perDevice,
        )
    }

    fun kwh(v: Double): String = String.format(RU, "%.2f кВт·ч", v)
    fun rub(v: Double): String = String.format(RU, "%.2f ₽", v)
    fun zoneName(index: Int): String = "Т${index + 1}"
}
