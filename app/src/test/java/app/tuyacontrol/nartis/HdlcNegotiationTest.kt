package app.tuyacontrol.nartis

import com.pivotaccess.dlmscosem.hdlc.HDLCAddress
import com.pivotaccess.dlmscosem.hdlc.HDLCFrame
import com.pivotaccess.dlmscosem.hdlc.HDLCNegotiationParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class HdlcNegotiationTest {
    /**
     * Кадр SNRM с адресом счётчика из двух байт (логический 1, физический 17)
     * должен совпадать с реальным кадром НАРТИС-И100/И300.
     */
    @Test
    fun snrmMatchesNartisFrame() {
        val frame = HDLCFrame.snrm(
            HDLCAddress.serverTwoBytes(1, 17),
            HDLCAddress.client(16),
            HDLCNegotiationParameters.defaults().encode(),
        ).encode()
        val expected = "7E A0 21 02 23 21 93 48 55 81 80 14 " +
            "05 02 07 EE 06 02 07 EE 07 04 00 00 00 01 08 04 00 00 00 01 B5 D4 7E"
        assertEquals(expected, frame.joinToString(" ") { "%02X".format(it) })
    }

    @Test
    fun oneByteAddressStillWorks() {
        val frame = HDLCFrame.snrm(
            HDLCAddress.serverOneByte(1),
            HDLCAddress.client(16),
            HDLCNegotiationParameters.defaults().encode(),
        ).encode()
        val expected = "7E A0 20 03 21 93 7D 5D D9 81 80 14 " +
            "05 02 07 EE 06 02 07 EE 07 04 00 00 00 01 08 04 00 00 00 01 B5 D4 7E"
        assertEquals(expected, frame.joinToString(" ") { "%02X".format(it) })
    }
}
