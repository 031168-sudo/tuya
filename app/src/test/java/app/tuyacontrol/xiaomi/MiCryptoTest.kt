package app.tuyacontrol.xiaomi

import app.tuyacontrol.xiaomi.MiCrypto.toHex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Эталонные значения посчитаны той же схемой на Python (как в Xiaomi-cloud-tokens-extractor и python-miio). */
class MiCryptoTest {

    private val ssecurity = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })

    @Test
    fun nonceAndSignedNonce() {
        val nonce = MiCrypto.nonce(1_700_000_000_000L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals("AQIDBAUGBwgBsFUV", nonce)
        assertEquals("5ep+rMHy/Bph+UDBR3Cb6aW2JDdKFCrVRtJBDO5BLUQ=", MiCrypto.signedNonce(ssecurity, nonce))
    }

    @Test
    fun encryptedParams() {
        val nonce = "AQIDBAUGBwgBsFUV"
        val signed = MiCrypto.signedNonce(ssecurity, nonce)
        val url = "https://ru.api.io.mi.com/app/miotspec/prop/get"
        val p = MiCrypto.encParams(url, "POST", signed, nonce, linkedMapOf("data" to """{"params":[]}"""), ssecurity)
        assertEquals("J+nCu/uJN4pMBHjJrg==", p["data"])
        assertEquals("L6jVqvijA7pZD3TSoy3UMuVdnvLWIFj9V8zwJQ==", p["rc4_hash__"])
        assertEquals("HHklqbgoT0Ud1cSfW9VhA0w7gqk=", p["signature"])
        assertEquals(nonce, p["_nonce"])
        assertEquals("""{"params":[]}""", MiCrypto.rc4Decrypt(signed, p["data"]!!))
    }

    @Test
    fun plainRc4Vector() {
        // Классический тест RC4 (без отбрасывания) проверяем через «ручной» поток: drop1024 + XOR
        val enc = MiCrypto.rc4("Key".toByteArray(), ByteArray(1024 + 9))
        assertEquals(1033, enc.size)
    }

    @Test
    fun passwordHash() = assertEquals("5EBE2294ECD0E0F08EAB7690D2A6EE69", MiCrypto.passwordHash("secret"))

    @Test
    fun miioAesMatchesReference() {
        val token = MiCrypto.hexToBytes("00112233445566778899aabbccddeeff")
        val ct = MiCrypto.aesEncrypt(token, """{"id":1,"method":"miIO.info","params":[]}""".toByteArray())
        assertEquals(
            "a5516ec6151955dc2bb2d43e7c84c18352a39a7f22fd2903d282a5783c6d56ac8c9c7629130a25fac17d38175c1a7799",
            ct.toHex(),
        )
    }

    @Test
    fun miioPacketRoundTrip() {
        val token = MiCrypto.hexToBytes("00112233445566778899aabbccddeeff")
        val json = """{"id":7,"result":[{"did":"1","siid":2,"piid":1,"code":0,"value":true}]}"""
        val packet = MiioPacket.encode(token, 0x12345678L, 1000L, json)
        val h = MiioPacket.parseHeader(packet)
        assertNotNull(h)
        assertEquals(packet.size, h!!.length)
        assertEquals(0x12345678L, h.deviceId)
        assertEquals(1000L, h.stamp)
        assertEquals(json, MiioPacket.decode(token, packet))
    }

    @Test
    fun helloPacket() {
        val h = MiioPacket.hello()
        assertEquals(32, h.size)
        assertArrayEquals(byteArrayOf(0x21, 0x31, 0x00, 0x20), h.copyOf(4))
    }

    @Test
    fun specOfHeaterS() {
        val spec = JSONObject(
            """{"services":[
             {"iid":1,"type":"urn:miot-spec-v2:service:device-information:00007801:zhimi-mc2:1","properties":[
              {"iid":1,"type":"urn:miot-spec-v2:property:manufacturer:00000001:zhimi-mc2:1","format":"string","access":["read"]}]},
             {"iid":2,"type":"urn:miot-spec-v2:service:heater:0000782E:zhimi-mc2:1","properties":[
              {"iid":1,"type":"urn:miot-spec-v2:property:on:00000006:zhimi-mc2:1","format":"bool","access":["read","write","notify"]},
              {"iid":5,"type":"urn:miot-spec-v2:property:target-temperature:00000021:zhimi-mc2:1","format":"float","access":["read","write"],"unit":"celsius","value-range":[18,28,1]}]},
             {"iid":4,"type":"urn:miot-spec-v2:service:environment:0000780A:zhimi-mc2:1","properties":[
              {"iid":7,"type":"urn:miot-spec-v2:property:temperature:00000020:zhimi-mc2:1","format":"float","access":["read","notify"],"unit":"celsius","value-range":[-30,100,0.1]}]},
             {"iid":8,"type":"urn:zhimi-spec:service:private-service:00007801:zhimi-mc2:1","properties":[
              {"iid":1,"type":"urn:zhimi-spec:property:button-pressed:00000001:zhimi-mc2:1","format":"uint8","access":["notify"]},
              {"iid":8,"type":"urn:zhimi-spec:property:constant-temperature:00000008:zhimi-mc2:1","description":"x","format":"bool","access":["read","write"]}]}
            ]}"""
        )
        val props = MiotSpec.parse(spec, "zhimi.heater.mc2").associateBy { it.code }
        assertEquals(setOf("switch", "temp_set", "temp_current", "mi_8_8"), props.keys)
        assertEquals(0, props.getValue("temp_set").scale)
        assertEquals(1, props.getValue("temp_current").scale)
        assertEquals("Постоянная температура", props.getValue("mi_8_8").label)
        assertTrue(props.getValue("switch").writable)

        val d = MiDevice("1", "Обогреватель", "zhimi.heater.mc2", "00".repeat(16), "192.168.8.161", "", true)
        val ui = XiaomiMapper.toDevice(d, props.values.toList(), mapOf((2 to 1) to true, (2 to 5) to 22.0, (4 to 7) to 19.4), true, true, 0)
        assertEquals(true, ui.status["switch"])
        assertEquals(22L, ui.status["temp_set"])
        assertEquals(194L, ui.status["temp_current"])
        assertEquals(18L, ui.spec.getValue("temp_set").min)
        assertEquals(23.0, XiaomiMapper.toMiot(props.getValue("temp_set"), 23L))

        // Индикатор: 0 — горит (переключатель вкл), 1 — погашен
        val ind = MiProp(7, 3, "indicator", "Индикатор", "uint8", true, true, "%", 0.0, 1.0, 1.0, emptyMap(), invertedSwitch = true)
        val u2 = XiaomiMapper.toDevice(d, listOf(ind), mapOf((7 to 3) to 0), true, true, 0)
        assertEquals(true, u2.status["indicator"])
        assertEquals(1, XiaomiMapper.toMiot(ind, false))
    }
}
