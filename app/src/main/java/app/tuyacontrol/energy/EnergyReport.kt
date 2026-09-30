package app.tuyacontrol.energy

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class PeriodType(val title: String) { DAY("День"), WEEK("Неделя"), MONTH("Месяц"), YEAR("Год") }

/** Строка отчёта: день, месяц или устройство. */
data class Bucket(
    val label: String,
    val kwh: Double,
    val cost: Double,
    /** Есть потребление без тарифа — стоимость неполная. */
    val missingTariff: Boolean,
    /** Куда перейти при нажатии (null — некуда). */
    val drillType: PeriodType? = null,
    val drillDate: LocalDate? = null,
)

data class EnergyReport(
    val title: String,
    val from: LocalDate,
    val to: LocalDate,
    val kwh: Double,
    val cost: Double,
    val missingTariff: Boolean,
    /** Средний расход в день по прошедшим дням периода. */
    val avgKwhPerDay: Double,
    val avgCostPerDay: Double,
    val buckets: List<Bucket>,
    val perDevice: List<Bucket>,
)

object EnergyReports {

    val RU: Locale = Locale("ru")
    private val DAY_TITLE = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
    private val SHORT = DateTimeFormatter.ofPattern("dd.MM", RU)
    private val DAY_ROW = DateTimeFormatter.ofPattern("dd.MM, EE", RU)

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

    fun build(
        type: PeriodType,
        anchor: LocalDate,
        days: List<DayEnergy>,
        tariffs: List<Tariff>,
        deviceNames: Map<String, String>,
        today: LocalDate,
    ): EnergyReport {
        val (from, to) = range(type, anchor)

        data class Priced(val d: DayEnergy, val cost: Double, val missing: Boolean)

        val priced = days.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }.map { d ->
            val t = tariffFor(tariffs, d.deviceId, d.day)
            Priced(d, (t?.price ?: 0.0) * d.kwh, t == null && d.kwh > 0.0)
        }

        fun bucket(label: String, items: List<Priced>, drillType: PeriodType? = null, drillDate: LocalDate? = null) =
            Bucket(
                label = label,
                kwh = items.sumOf { it.d.kwh },
                cost = items.sumOf { it.cost },
                missingTariff = items.any { it.missing },
                drillType = drillType,
                drillDate = drillDate,
            )

        val buckets: List<Bucket> = when (type) {
            PeriodType.DAY -> emptyList()
            PeriodType.WEEK, PeriodType.MONTH -> {
                val byDay = priced.groupBy { it.d.day }
                generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { day ->
                    bucket(day.format(DAY_ROW), byDay[day].orEmpty(), PeriodType.DAY, day)
                }.toList()
            }
            PeriodType.YEAR -> {
                val byMonth = priced.groupBy { it.d.day.monthValue }
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

        val perDevice = priced.groupBy { it.d.deviceId }
            .map { (id, items) -> bucket(deviceNames[id] ?: id, items) }
            .sortedByDescending { it.kwh }

        val total = bucket("", priced)
        val lastDay = if (to.isBefore(today)) to else today
        val elapsedDays = if (lastDay.isBefore(from)) 0L else lastDay.toEpochDay() - from.toEpochDay() + 1
        return EnergyReport(
            title = title(type, anchor),
            from = from,
            to = to,
            kwh = total.kwh,
            cost = total.cost,
            missingTariff = total.missingTariff,
            avgKwhPerDay = if (elapsedDays > 0) total.kwh / elapsedDays else 0.0,
            avgCostPerDay = if (elapsedDays > 0) total.cost / elapsedDays else 0.0,
            buckets = buckets,
            perDevice = perDevice,
        )
    }

    fun kwh(v: Double): String = String.format(RU, "%.2f кВт·ч", v)
    fun rub(v: Double): String = String.format(RU, "%.2f ₽", v)
}
