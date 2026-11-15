package app.tuyacontrol.nartis

import com.pivotaccess.dlmscosem.apdu.AarqApdu
import com.pivotaccess.dlmscosem.hdlc.HDLCAddress
import com.pivotaccess.dlmscosem.hdlc.HDLCFrame
import org.junit.Test

class IFrameDebugTest {
    @Test
    fun print() {
        val aarq = AarqApdu.noAuth(512).encode()
        val payload = byteArrayOf(0xE6.toByte(), 0xE6.toByte(), 0x00) + aarq
        val f = HDLCFrame.iFrame(
            HDLCAddress.serverTwoBytes(1, 17),
            HDLCAddress.client(32),
            0, 0, true, false, payload,
        )
        println("IFRAME: " + f.encode().joinToString(" ") { "%02X".format(it) })
    }
}
