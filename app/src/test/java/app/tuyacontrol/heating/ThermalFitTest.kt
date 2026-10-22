package app.tuyacontrol.heating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalFitTest {

    @Test
    fun recoversParametersFromHistory() {
        var t = 20.0
        val samples = (0 until 24 * 5).map { k ->
            val u = if ((k / 3) % 3 == 0) 1.0 else 0.0
            val d = 2.5 * u - 0.04 * t
            ThermalFit.Sample(d, u, t).also { t += d }
        }
        val r = ThermalFit.fit(samples)
        assertNull(r.problem)
        assertEquals(2.5, r.heatRate!!, 0.05)
        assertEquals(0.04, r.lossRate!!, 0.002)
    }

    @Test
    fun tooLittleDataIsReported() {
        val r = ThermalFit.fit(List(5) { ThermalFit.Sample(-0.5, 0.0, 10.0) })
        assertNull(r.lossRate)
        assertNotNull(r.problem)
    }

    @Test
    fun floorHeatAfterSwitchOffIsSkipped() {
        // Реальное остывание 1 %/ч при разнице 15°. В первые 3 часа после нагрева пол греет воздух (+0,3°/ч)
        val samples = mutableListOf<ThermalFit.Sample>()
        repeat(10) {
            samples += ThermalFit.Sample(0.3, 0.0, 15.0, quiet = 1.0)
            samples += ThermalFit.Sample(0.3, 0.0, 15.0, quiet = 2.0)
            repeat(3) { samples += ThermalFit.Sample(-0.15, 0.0, 15.0, quiet = 5.0 + it) }
            repeat(3) { samples += ThermalFit.Sample(1.0, 1.0, 15.0, quiet = 0.0) }
        }
        val withSettle = ThermalFit.fit(samples, settle = 3.0)
        assertEquals(0.01, withSettle.lossRate!!, 0.001)
    }

    @Test
    fun almostZeroLossIsClampedNotRejected() {
        val r = ThermalFit.fit(List(20) { ThermalFit.Sample(0.01, 0.0, 15.0) } + List(6) { ThermalFit.Sample(1.0, 1.0, 15.0) })
        assertEquals(ThermalFit.MIN_LOSS, r.lossRate!!, 1e-9)
        assertNotNull(r.note)
        assertNull(r.problem)
    }

    @Test
    fun fewWrongHoursDoNotBreakTheMedian() {
        // Истинное остывание 1 %/ч, но 3 часа ошибочно помечены «не греет», хотя грели (+3°/ч)
        val good = List(30) { ThermalFit.Sample(-0.15, 0.0, 15.0) }
        val bad = List(3) { ThermalFit.Sample(3.0, 0.0, 15.0) }
        val r = ThermalFit.fit(good + bad + List(6) { ThermalFit.Sample(1.0, 1.0, 15.0) })
        assertEquals(0.01, r.lossRate!!, 0.001)
    }
}
