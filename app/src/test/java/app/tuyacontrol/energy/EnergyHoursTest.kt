package app.tuyacontrol.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EnergyHoursTest {
    private val day = LocalDate.of(2026, 10, 12)

    // Одно устройство, 1 кВт·ч в каждый час (24 кВт·ч за день), цена 5 ₽ круглые сутки
    private fun report(from: Int, to: Int): EnergyReport {
        val hourly = mapOf(("d" to day) to DoubleArray(24) { 1.0 })
        val tariff = Tariff(deviceId = null, start = day.minusDays(10), end = null, zones = listOf(TariffZone(5.0, 0, 24)))
        return EnergyReports.build(
            PeriodType.DAY, day, listOf(DayEnergy("d", day, 24.0, "stats", DayEnergy.HOURLY_PRESENT)),
            hourly, emptyMap(), listOf(tariff), mapOf("d" to "Счётчик"), day, from, to,
        )
    }

    @Test
    fun wholeDayByDefault() {
        val r = report(0, 24)
        assertEquals(24.0, r.kwh, 1e-9)
        assertEquals(120.0, r.cost, 1e-9)
        assertTrue(r.buckets.all { it.selected })
    }

    @Test
    fun selectedHoursOnly() {
        val r = report(17, 20) // 17:00, 18:00, 19:00
        assertEquals(3.0, r.kwh, 1e-9)
        assertEquals(15.0, r.cost, 1e-9)
        assertEquals(24, r.buckets.size) // график остаётся на весь день
        assertEquals(listOf(17, 18, 19), r.buckets.withIndex().filter { it.value.selected }.map { it.index })
        assertEquals(3.0, r.perDevice.single().kwh, 1e-9)
    }

    @Test
    fun badRangeIsFixed() {
        val r = report(20, 10) // «по» не раньше «с»: берётся один час
        assertEquals(20, r.hourFrom)
        assertEquals(21, r.hourTo)
        assertEquals(1.0, r.kwh, 1e-9)
    }
}
