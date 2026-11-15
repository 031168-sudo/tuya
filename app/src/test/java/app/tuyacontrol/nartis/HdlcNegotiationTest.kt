package app.tuyacontrol.nartis

import com.pivotaccess.dlmscosem.hdlc.HDLCAddress
import com.pivotaccess.dlmscosem.hdlc.HDLCFrame
import com.pivotaccess.dlmscosem.hdlc.HDLCNegotiationParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class HdlcNegotiationTest {
    /**
     * Кадр SNRM должен совпадать со стандартным для НАРТИС:
     * 05 02 07D0 (maxInfoTx 2000), 06 02 07D0 (maxInfoRx 2000),
     * 07 04 1 (windowTx), 08 04 1 (windowRx).
     */
    @Test
    fun snrmMatchesNartisStandard() {
        val frame = HDLCFrame.snrm(
            HDLCAddress.serverOneByte(1),
            HDLCAddress.client(16),
            HDLCNegotiationParameters.defaults().encode(),
        ).encode()
        val expected = "7E A0 20 03 21 93 7D 5D D9 81 80 14 " +
            "05 02 07 D0 06 02 07 D0 07 04 00 00 00 01 08 04 00 00 00 01 3A F2 7E"
        assertEquals(expected, frame.joinToString(" ") { "%02X".format(it) })
    }
}
