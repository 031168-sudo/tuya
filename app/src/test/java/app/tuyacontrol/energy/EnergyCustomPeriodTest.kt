package app.tuyacontrol.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnergyCustomPeriodTest {
    private val from = LocalDate.of(2026, 11, 1)
    private val tariff = Tariff(
        deviceId = null,
        start = from.minusDays(30),
        end = null,
        zones = listOf(TariffZone(5.0, 0, 24)),
    )

    /** По 24 кВт·ч в сутки (1 кВт·ч в каждый час). */
    private fun build(
        days: Long,
        custom: CustomPeriod,
        hourFrom: Int = 0,
        hourTo: Int = 24,
        type: PeriodType = PeriodType.MONTH,
    ): EnergyReport {
        val list = (0 until days).map {
            DayEnergy("d", from.plusDays(it), 24.0, "stats", DayEnergy.HOURLY_PRESENT)
        }
        return EnergyReports.build(
            type, from, list, emptyMap(), emptyMap(), listOf(tariff),
            mapOf("d" to "Счётчик"), from.plusDays(days), hourFrom, hourTo, custom,
        )
    }

    @Test
    fun shortPeriodByDays() {
        val r = build(3, CustomPeriod(from, from.plusDays(2)))
        assertEquals(72.0, r.kwh, 1e-9)
        assertEquals(360.0, r.cost, 1e-9)
        assertEquals(3, r.buckets.size)
        assertFalse(r.monthly)
        assertTrue(r.buckets.all { it.drillType == PeriodType.DAY })
        assertEquals("01.11 – 03.11.2026", r.title)
        assertEquals(24.0, r.avgKwhPerDay, 1e-9)
    }

    @Test
    fun reversedDatesAreFixed() {
        val r = build(3, CustomPeriod(from.plusDays(2), from))
        assertEquals(from, r.from)
        assertEquals(from.plusDays(2), r.to)
        assertEquals(72.0, r.kwh, 1e-9)
    }

    @Test
    fun longPeriodByMonths() {
        val r = build(40, CustomPeriod(from, from.plusDays(39)))
        assertEquals(40 * 24.0, r.kwh, 1e-9)
        assertEquals(2, r.buckets.size) // ноябрь и декабрь
        assertTrue(r.monthly)
        assertTrue(r.buckets.all { it.drillType == PeriodType.MONTH })
        assertEquals("Ноябрь", r.buckets[0].label)
        assertEquals("Декабрь", r.buckets[1].label)
    }

    @Test
    fun shortCustomStaysDailyEvenIfLastTabWasYear() {
        val r = build(3, CustomPeriod(from, from.plusDays(2)), type = PeriodType.YEAR)
        assertEquals(3, r.buckets.size)
        assertFalse(r.monthly)
    }

    @Test
    fun hoursAreIgnored() {
        val r = build(3, CustomPeriod(from, from.plusDays(2)), hourFrom = 10, hourTo = 12)
        assertEquals(72.0, r.kwh, 1e-9)
        assertEquals(0, r.hourFrom)
        assertEquals(24, r.hourTo)
    }
}
