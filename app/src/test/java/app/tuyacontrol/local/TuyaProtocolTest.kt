package app.tuyacontrol.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контрольные пакеты сгенерированы эталонной реализацией tinytuya
 * (ключ 0123456789abcdef, t = 1790000000, фиксированные seq и nonce).
 * Протокол приложения должен совпадать с ними байт в байт.
 */
class TuyaProtocolTest {

    private val key = "0123456789abcdef".toByteArray()
    private val dev = "7238772198f4abc21ca2"
    private val t = 1790000000L

    @Test fun v33Query() {
        val (cmd, body) = TuyaProtocol.queryCommand("3.3", dev, t, device22 = false, dpIds = emptyList())
        assertEquals(V33_QUERY, hex(TuyaProtocol.encode("3.3", key, 1, cmd, body)))
    }

    @Test fun v33Control() {
        val (cmd, body) = TuyaProtocol.controlCommand("3.3", dev, t, mapOf("1" to true))
        assertEquals(V33_CONTROL, hex(TuyaProtocol.encode("3.3", key, 2, cmd, body)))
    }

    @Test fun v33Heartbeat() {
        val (cmd, body) = TuyaProtocol.heartbeatCommand(dev)
        assertEquals(V33_HEARTBEAT, hex(TuyaProtocol.encode("3.3", key, 3, cmd, body)))
    }

    @Test fun v33Device22Query() {
        val (cmd, body) = TuyaProtocol.queryCommand("3.3", "bfcdbfb9fe99fbaa21i49k", t, device22 = true, dpIds = listOf(1, 2))
        assertEquals(V33_D22_QUERY, hex(TuyaProtocol.encode("3.3", key, 4, cmd, body)))
    }

    @Test fun v33StatusIn() {
        val frame = TuyaProtocol.unpack(unhex(V33_STATUS_IN), null)
        assertTrue(frame.checkOk)
        assertEquals(TuyaProtocol.STATUS, frame.cmd)
        assertEquals("{\"devId\":\"$dev\",\"dps\":{\"1\":true,\"19\":1250},\"t\":1790000000}",
            TuyaProtocol.decodePayload("3.3", key, frame))
    }

    @Test fun v33QueryResponseIn() {
        val frame = TuyaProtocol.unpack(unhex(V33_QUERY_RESP_IN), null)
        assertTrue(frame.checkOk)
        assertEquals("{\"devId\":\"$dev\",\"dps\":{\"1\":false,\"20\":2301}}",
            TuyaProtocol.decodePayload("3.3", key, frame))
    }

    @Test fun v34FullSession() {
        val ln = "abcdefghijklmnop".toByteArray()
        val rn = "ponmlkjihgfedcba".toByteArray()
        assertEquals(V34_NEG_START, hex(TuyaProtocol.negotiateStart(1, key, ln)))

        val resp = TuyaProtocol.unpack(unhex(V34_NEG_RESP_IN), key)
        assertTrue(resp.checkOk)
        val remote = TuyaProtocol.negotiateRemoteNonce(resp, key, ln)
        assertNotNull(remote)
        assertArrayEquals(rn, remote)
        assertEquals(V34_NEG_FINISH, hex(TuyaProtocol.negotiateFinish(2, key, remote!!)))

        val session = TuyaProtocol.sessionKey(key, ln, remote)
        assertEquals(V34_SESSION_KEY, hex(session))

        val dev4 = "bfe74dac4ad9e53858ebtn"
        val (qc, qb) = TuyaProtocol.queryCommand("3.4", dev4, t, device22 = false, dpIds = emptyList())
        assertEquals(V34_QUERY, hex(TuyaProtocol.encode("3.4", session, 3, qc, qb)))
        val (cc, cb) = TuyaProtocol.controlCommand("3.4", dev4, t, mapOf("1" to true))
        assertEquals(V34_CONTROL, hex(TuyaProtocol.encode("3.4", session, 4, cc, cb)))
        val (hc, hb) = TuyaProtocol.heartbeatCommand(dev4)
        assertEquals(V34_HEARTBEAT, hex(TuyaProtocol.encode("3.4", session, 5, hc, hb)))

        val status = TuyaProtocol.unpack(unhex(V34_STATUS_IN), session)
        assertTrue(status.checkOk)
        assertEquals("{\"protocol\":4,\"t\":1790000000,\"data\":{\"dps\":{\"1\":true,\"3\":215}}}",
            TuyaProtocol.decodePayload("3.4", session, status))
    }

    @Test fun frameLength() {
        val frame = unhex(V33_STATUS_IN)
        assertEquals(frame.size, TuyaProtocol.frameLength(frame.copyOfRange(0, 16)))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val V33_QUERY = "000055aa000000010000000a0000007801d283fdb38a4bd1321e9302ba7f4080862b1a238188543959e0e029331c9fc3670f9d7dde146cdd2717c15d4a4cebe6886b7c82b4ff304a14dd428fc6662f025c98cd3df91c4ad4c30951db21c9e39244ee698ac8bbf44325e85657f5e368de0aedaaad200b05b3f0a1bfc6dc6c8b0a107e0b570000aa55"
        const val V33_CONTROL = "000055aa000000020000000700000077332e3300000000000000000000000093dd96d354d766ee497361c14d061b0b3446617da737127059a4a44a3a7a900a7c2ae5a8f51e737573a719c4a1984f0c86da6a2191e501832bfe31fe9b5c348a372419af66e6fccb0c223d0dc8e88a6f5c91b94d45d4e66e522e93cf8c4ab1fec007ab7e0000aa55"
        const val V33_HEARTBEAT = "000055aa00000003000000090000004801d283fdb38a4bd1321e9302ba7f4080862b1a238188543959e0e029331c9fc3670f9d7dde146cdd2717c15d4a4cebe61d7232103243350b805b77a7afe0473bbd4f1cf10000aa55"
        const val V33_STATUS_IN = "000055aa00000005000000080000006b00000000332e3300000000000000000000000093dd96d354d766ee497361c14d061b0b3446617da737127059a4a44a3a7a900a79644ab9afbda994a9f8a6c9cecdf2b28658efc6583ca0524d6f79963f4a81f0691aaf54e025be3e8693e349d03d391ca37dbc200000aa55"
        const val V33_QUERY_RESP_IN = "000055aa000000010000000a0000004c0000000093dd96d354d766ee497361c14d061b0b3446617da737127059a4a44a3a7a900a20732d3448c1530434b02aa5e450ca50bb49e20c75281ef1c9f5371b787eb96df5d5ed9f0000aa55"
        const val V33_D22_QUERY = "000055aa000000040000000d00000087332e330000000000000000000000003ee3c6db256144b20392e20f6ad90274c24ffb4a77e87cef520c4a3fc5e802f69ddcc32cf9d8c136ab66376654e5266a47e688d82be899fcd876c56d0e6e48efef28d0be9458c3d82839c955acb3dc32c514004b1250788cbadeb4061108a425d801e3f8da8a63e04d29b8197101ae97c112ef700000aa55"
        const val V34_NEG_START = "000055aa00000001000000030000004485627df0451e7740eb260b1df1f4fc64377222e061a924c591cd9c27ea163ed49ed8549dde300f86b824d4da84d5c282792626ecbc22ac3729d819beab9997540000aa55"
        const val V34_NEG_RESP_IN = "000055aa00000001000000040000006800000000a118ba3a27c06fe8e7460a59605b782b69ed70381a5fdc1f0208a319d1a63489d242a0b9a9af284bd9e93b2a15e0ec26377222e061a924c591cd9c27ea163ed461c7b372f5a0296dd5a0e84983e826ad2897c66fecf7181bdafdb851f0f5173f0000aa55"
        const val V34_NEG_FINISH = "000055aa000000020000000500000054058823e182038cbc26dea9a9e5a06696512c41ad81ec397b2c14673d90d40e65377222e061a924c591cd9c27ea163ed412fa1481be220f3138c848f2ccdcf26f00b1d8af7aabc66617c8aa6820a3e2d80000aa55"
        const val V34_SESSION_KEY = "9ffa085e8b5849b3ab12b3096d8b0e9a"
        const val V34_QUERY = "000055aa0000000300000010000000346e0b2ba10621d1b74094c7a46eb5d91537752d6c738881852ed3ccabec2a1be42cb9a82d37756a2bc656b8f3bca149640000aa55"
        const val V34_CONTROL = "000055aa000000040000000d000000746b98f640309aa91e299fc6984ee9c4ecebddf8079d400ae94f617a3e9c8258ac23bc815f6a4cbc112b103559eccc406f3f3a1c28ac142f6bd7a5458e3ce83257eb127c770a92ff7c6e39065af5d8a11617680f7f3c2aac7fff61921f0816fc285722653e872590c59b9689858b5edf770000aa55"
        const val V34_HEARTBEAT = "000055aa0000000500000009000000743b58f9715b80768e6a9793c62642876fea4535483b678a466c09afb47ecf28c94d9f362ddff8b032d5acd18dd0e3e84e8b52344c62166e001afa28125c9c21fcab899a31993ec33765693bedea201e648f44fea09424ba4d2b6e70c68809f8efebb8525287eed8ac23f67c370c00b4f80000aa55"
        const val V34_STATUS_IN = "000055aa000000070000000800000078000000006b98f640309aa91e299fc6984ee9c4ecb382f927a88a60874dabe5fa4611572523bc815f6a4cbc112b103559eccc406f3f3a1c28ac142f6bd7a5458e3ce832570d578dbe67d736481db5b122d1b7d0071b8666328687108f8ddbffb18b6417f39e5c784404f0a038048aefab7fb9b6a60000aa55"
    }
}
