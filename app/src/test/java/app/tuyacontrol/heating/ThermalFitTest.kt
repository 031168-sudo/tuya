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
}
