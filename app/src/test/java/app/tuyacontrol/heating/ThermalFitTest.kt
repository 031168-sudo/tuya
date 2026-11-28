package app.tuyacontrol.heating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalFitTest {

    /** y = h·u − a·diff */
    private fun sample(u: Double, diff: Double, h: Double = 2.5, a: Double = 0.04, inferred: Boolean = false) =
        ThermalFit.Sample(h * u - a * diff, u, diff, inferred)

    @Test
    fun recoversParametersFromHistory() {
        val samples = (0 until 120).map { i ->
            val u = if (i % 4 == 0) 0.0 else 1.0
            sample(u, 10.0 + i % 20)
        }
        val r = ThermalFit.fit(samples)
        assertNull(r.problem)
        assertEquals(2.5, r.heatRate!!, 0.01)
        assertEquals(0.04, r.lossRate!!, 0.001)
        assertTrue(r.coolHours >= ThermalFit.MIN_COOL_HOURS)
        assertTrue(r.heatHours >= ThermalFit.MIN_HEAT_HOURS)
    }

    @Test
    fun worksWithOnlyFourCoolHours() {
        // Раньше требовалось 12 часов без нагрева; теперь хватает четырёх
        val samples = (0 until 120).map { i -> sample(if (i < 16) 0.0 else 1.0, 15.0 + i % 10) }
        val r = ThermalFit.fit(samples)
        assertNull(r.problem)
        assertEquals(2.5, r.heatRate!!, 0.01)
        assertEquals(0.04, r.lossRate!!, 0.001)
    }

    @Test
    fun tooLittleDataIsReported() {
        val r = ThermalFit.fit(List(5) { ThermalFit.Sample(-0.5, 0.0, 10.0) })
        assertNull(r.heatRate)
        assertNotNull(r.problem)
    }

    @Test
    fun noCoolHoursIsReported() {
        val r = ThermalFit.fit(List(30) { sample(1.0, 15.0) })
        assertNull(r.lossRate)
        assertNotNull(r.problem)
    }

    @Test
    fun outliersDoNotBreakTheFit() {
        val good = (0 until 120).map { i -> sample(if (i % 4 == 0) 0.0 else 1.0, 12.0 + i % 15) }
        val bad = List(3) { ThermalFit.Sample(5.0, 1.0, 15.0) } +
            List(3) { ThermalFit.Sample(-5.0, 0.0, 15.0) }
        val r = ThermalFit.fit(good + bad)
        assertNull(r.problem)
        assertEquals(2.5, r.heatRate!!, 0.15)
        assertEquals(0.04, r.lossRate!!, 0.005)
    }

    @Test
    fun inferredHeatIsMarkedApproximate() {
        val samples = (0 until 120).map { i ->
            sample(if (i % 4 == 0) 0.0 else 1.0, 12.0 + i % 15, inferred = i % 2 == 0)
        }
        val r = ThermalFit.fit(samples)
        assertNull(r.problem)
        assertTrue(r.approximate)
        assertTrue(!r.note.isNullOrBlank())
    }
}
