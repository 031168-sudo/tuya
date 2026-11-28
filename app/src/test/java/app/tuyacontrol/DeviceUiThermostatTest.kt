package app.tuyacontrol

import app.tuyacontrol.cloud.DpSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** TC02W и подобные: real_temp/set_temp вместо стандартных temp_current/temp_set. */
class DeviceUiThermostatTest {

    private fun thermostat(status: Map<String, Any?> = mapOf(
        "switch_1" to false,
        "real_temp" to 186,
        "set_temp" to 135,
    )) = DeviceUi(
        id = "bf7e30beac6961e376ciku",
        name = "Федин обогреватель",
        online = true,
        productName = "TC02W-Thermostat",
        category = "wk",
        status = status,
        spec = mapOf(
            "switch_1" to DpSpec("switch_1", "Boolean", writable = true),
            "real_temp" to DpSpec("real_temp", "Integer", unit = "°C", scale = 1),
            "set_temp" to DpSpec("set_temp", "Integer", unit = "°C", scale = 1, min = 50, max = 350, step = 5, writable = true),
        ),
    )

    @Test
    fun temperatureIsShownEvenWhenHeatingIsOff() {
        val d = thermostat()
        assertFalse("real_temp не зависит от реле", d.defaultTempOnlyWhenOn)
        assertFalse("прибор не считается выключенным для показаний", d.switchedOff)
        assertEquals("real_temp", d.sensorDevice?.temperature?.code)
        assertEquals(1, d.sensorDevice?.temperature?.scale)
    }

    @Test
    fun targetTemperatureAndHeatingSwitchAreRecognized() {
        val d = thermostat()
        assertEquals("set_temp", d.setpointCode)
        assertEquals("switch_1", d.heatingSwitchCode)
        assertEquals(false, d.heatingNow)
        assertEquals("device_thermostat", d.defaultIcon)
    }

    @Test
    fun demandWhenFactBelowSetpoint() {
        val d = thermostat(mapOf("switch_1" to false, "real_temp" to 186, "set_temp" to 200))
        assertEquals(true, d.heatingNow)
    }

    @Test
    fun heatingModeIsSeparateFromRelay() {
        val modes = mapOf(
            "switch_1" to false, "real_temp" to 186, "set_temp" to 135,
            "heating" to true, "manual_off" to false, "cooling" to false,
        )
        assertEquals(true, thermostat(modes).heatingModeOn)
        assertEquals(false, thermostat(modes + ("manual_off" to true)).heatingModeOn)
        val cooling = thermostat(modes + mapOf("heating" to false, "cooling" to true))
        assertEquals(false, cooling.heatingModeOn)
        assertEquals(true, cooling.coolingModeOn)
        // факт выше уставки — не греет, даже если режим heating
        assertEquals(false, thermostat(modes).heatingNow)
        // факт ниже уставки — огонёк горит по режиму и температуре, switch_1 не нужен
        assertEquals(true, thermostat(modes + mapOf("real_temp" to 200, "set_temp" to 215)).heatingNow)
        // keep off — не горит, даже если факт ниже уставки
        assertEquals(
            false,
            thermostat(modes + mapOf("real_temp" to 200, "set_temp" to 215, "manual_off" to true)).heatingNow,
        )
    }

    @Test
    fun hysteresisFromPositiveTempRange() {
        val base = mapOf(
            "switch_1" to false, "heating" to true, "manual_off" to false, "cooling" to false,
            "set_temp" to 215, "positive_temp_range_C" to 5,
        )
        // 21,1° при уставке 21,5° и гистерезисе 0,5° — ещё не включаем
        assertEquals(false, thermostat(base + ("real_temp" to 211)).heatingNow)
        // 20,9° — уже ниже порога 21,0° — включаем
        assertEquals(true, thermostat(base + ("real_temp" to 209)).heatingNow)
    }

    @Test
    fun latchedStateHoldsBetweenThresholds() {
        val base = mapOf(
            "switch_1" to false, "heating" to true, "manual_off" to false, "cooling" to false,
            "set_temp" to 215, "positive_temp_range_C" to 5, "real_temp" to 211,
        )
        // 21,1° внутри полосы 21,0…22,0 — держим предыдущее состояние
        assertEquals(true, thermostat(base).copy(heatingLatched = true).heatingNow)
        assertEquals(false, thermostat(base).copy(heatingLatched = false).heatingNow)
    }
}
