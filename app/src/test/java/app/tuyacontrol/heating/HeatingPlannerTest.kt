package app.tuyacontrol.heating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatingPlannerTest {

    private val prices = HourPrices.default()

    @Test
    fun comfortIsKeptAndPlanIsCheaper() {
        for (tout in listOf(-20.0, -5.0, 5.0)) {
            for (zone in HeatingSettings.defaults().zones) {
                val p = HeatingPlanner.plan(zone, prices, DoubleArray(48) { tout })
                assertEquals("${zone.name} $tout: комфорт", 0, p.shortSteps)
                assertTrue("${zone.name} $tout: дешевле", p.cost <= p.baselineCost + 0.01)
                assertTrue("${zone.name} $tout: не выше максимума", p.temps.all { it <= zone.maxTemp + 1e-6 })
            }
        }
    }

    @Test
    fun peakHoursAreAvoided() {
        val hall = HeatingSettings.defaults().zones.first { it.id == "hall" }
        val p = HeatingPlanner.plan(hall, prices, DoubleArray(48) { -5.0 })
        // Коридор: в пиковые часы не греем совсем, основная энергия — ночью
        assertEquals(0.0, p.kwhByZone[0], 0.01)
        assertTrue(p.kwhByZone[1] > p.kwhByZone[2])
    }

    @Test
    fun setpointsFollowThermostatStep() {
        val bedroom = HeatingSettings.defaults().zones.first()
        val p = HeatingPlanner.plan(bedroom, prices, DoubleArray(48) { -10.0 }, step = 1.0)
        assertTrue(p.setpoints.all { it == Math.round(it).toDouble() })
        // В пик 7–10 уставка не выше нижней границы (22°), если не догреваем
        assertTrue(p.setpoints[7] <= 22.0)
    }

    @Test
    fun convectorDoesNotHeatInPeakWhenBanned() {
        val conv = HeatZone("c", "Конвектор", windows = listOf(ComfortWindow(0, 0, 21.0)), baseTemp = 16.0,
            maxTemp = 21.0, powerKw = 2.0, heatRate = 3.0, lossRate = 0.04)
        val p = HeatingPlanner.plan(conv, prices, DoubleArray(48) { 0.0 }, step = 1.0, peakBan = true)
        for (h in 0 until 24) if (prices.peak[h]) {
            assertTrue("$h:00 в пик не греем", (0 until 4).all { p.heat[h * 4 + it] == 0.0 })
        }
        // Сразу после пика — включение, догрев нехваткой мощности не считается
        assertTrue(p.heat[21 * 4] > 0.0)
        assertEquals(0, p.shortSteps)
    }

    @Test
    fun setpointsAccountForHysteresis() {
        val bedroom = HeatingSettings.defaults().zones.first()
        val p0 = HeatingPlanner.plan(bedroom.copy(hysteresis = 0.0), prices, DoubleArray(48) { -10.0 }, step = 1.0)
        val p1 = HeatingPlanner.plan(bedroom.copy(hysteresis = 1.0), prices, DoubleArray(48) { -10.0 }, step = 1.0)
        for (h in 0 until 24) {
            // Термостат догревает на гистерезис выше уставки — уставка не выше, чем без гистерезиса,
            // и не ниже границы комфорта (низ полосы)
            assertTrue("$h", p1.setpoints[h] <= p0.setpoints[h])
            assertTrue("$h", p1.setpoints[h] >= 18.0)
        }
    }

    @Test
    fun comfortWindowAcrossMidnight() {
        val w = ComfortWindow(23, 11, 23.0)
        assertTrue(w.covers(23) && w.covers(0) && w.covers(10))
        assertTrue(!w.covers(11) && !w.covers(22))
        assertTrue(ComfortWindow(0, 0, 22.0).covers(15))
    }
}
