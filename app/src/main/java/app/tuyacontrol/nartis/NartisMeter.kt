package app.tuyacontrol.nartis

import com.pivotaccess.dlmscosem.apdu.AarqApdu
import com.pivotaccess.dlmscosem.cosem.CosemClassId
import com.pivotaccess.dlmscosem.cosem.CosemClient
import com.pivotaccess.dlmscosem.cosem.CosemClientConfig
import com.pivotaccess.dlmscosem.cosem.ObisCode
import com.pivotaccess.dlmscosem.transport.TransportConfig
import com.pivotaccess.dlmscosem.util.MeterAddress

/** Показания, которые удалось прочитать со счётчика. */
data class MeterReading(
    val totalKwh: Double?,
    val t1Kwh: Double?,
    val t2Kwh: Double?,
    val t3Kwh: Double?,
    val powerW: Double?,
    val voltageV: Double?,
    val currentA: Double?,
    val serial: String?,
    val withPassword: Boolean,
)

/**
 * Чтение счётчика НАРТИС по DLMS/COSEM через Bluetooth.
 *
 * Счётчик со стороны Bluetooth выглядит как обычный последовательный порт,
 * поэтому библиотека DLMS работает через локальный TCP-порт (см. [NusBridge]).
 */
object NartisMeter {

    private data class Attempt(
        val server: MeterAddress,
        val serverLabel: String,
        val clientAddress: Int,
        val level: AarqApdu.AuthenticationLevel,
        val password: String?,
    )

    fun read(port: Int, password: String, log: (String) -> Unit): MeterReading {
        // У НАРТИС-И300 адрес счётчика — два байта: логический 1, физический 17.
        // Однобайтовый адрес 1 оставлен как запасной вариант.
        val twoByte = MeterAddress.hdlcServer(1, 17)
        val oneByte = MeterAddress.hdlcServer(1)
        val attempts = buildList {
            if (password.isNotBlank()) {
                add(Attempt(twoByte, "1/17", 32, AarqApdu.AuthenticationLevel.LOW, password))
            }
            add(Attempt(twoByte, "1/17", 16, AarqApdu.AuthenticationLevel.NONE, null))
            add(Attempt(twoByte, "1/17", 32, AarqApdu.AuthenticationLevel.NONE, null))
            add(Attempt(oneByte, "1", 16, AarqApdu.AuthenticationLevel.NONE, null))
            add(Attempt(oneByte, "1", 32, AarqApdu.AuthenticationLevel.NONE, null))
        }
        var last: Exception? = null
        for (attempt in attempts) {
            try {
                val how = if (attempt.password != null) "с паролем" else "без пароля"
                log("Попытка: адрес ${attempt.serverLabel}, клиент ${attempt.clientAddress}, $how")
                return readOnce(port, attempt, log)
            } catch (e: Exception) {
                last = e
                log("Не вышло (клиент ${attempt.clientAddress}): ${e.message ?: e.javaClass.simpleName}")
                Thread.sleep(400)
            }
        }
        throw last ?: IllegalStateException("не удалось открыть связь")
    }

    private fun readOnce(port: Int, attempt: Attempt, log: (String) -> Unit): MeterReading {
        val config = CosemClientConfig.builder()
            .transport(
                TransportConfig.tcp("127.0.0.1", port)
                    .withReadTimeoutMs(6_000)
                    .withConnectTimeoutMs(5_000),
            )
            .serverAddress(attempt.server)
            .clientAddress(MeterAddress.hdlcClient(attempt.clientAddress))
            .authentication(attempt.level, attempt.password?.toByteArray())
            .maxReceivePduSize(512)
            .timeoutMs(6_000)
            .maxRetries(1)
            .build()

        CosemClient(config).use { client ->
            client.open()
            log("Связь со счётчиком открыта (клиент ${attempt.clientAddress})")

            fun energy(obis: String): Double? = try {
                val register = client.getRegister(ObisCode.of(obis))
                val value = register.physicalValueOrNull
                val unit = runCatching { register.scalerUnit?.unit?.symbol }.getOrNull()
                // Для энергии НАРТИС обычно отдаёт ватт-часы
                if (value != null && unit == "Wh") value / 1000.0 else value
            } catch (e: Exception) {
                log("Нет $obis: ${e.message ?: e.javaClass.simpleName}")
                null
            }

            fun register(obis: String): Double? = try {
                client.getRegister(ObisCode.of(obis)).physicalValueOrNull
            } catch (e: Exception) {
                log("Нет $obis: ${e.message ?: e.javaClass.simpleName}")
                null
            }

            val total = energy("1.0.1.8.0.255")
            val t1 = energy("1.0.1.8.1.255")
            val t2 = energy("1.0.1.8.2.255")
            val t3 = energy("1.0.1.8.3.255")
            val power = register("1.0.1.7.0.255")
            val voltage = register("1.0.12.7.0.255")
            val current = register("1.0.11.7.0.255")
            val serial = try {
                client.get(ObisCode.of("0.0.96.1.0.255"), CosemClassId.DATA, 2)
                    .getOctetStringAsTextOrNull(Charsets.UTF_8)
            } catch (e: Exception) {
                log("Серийный номер не прочитан: ${e.message}")
                null
            }

            if (total == null && t1 == null && t2 == null && t3 == null && power == null) {
                throw IllegalStateException("счётчик отвечает, но показания не прочитаны (см. журнал)")
            }
            return MeterReading(
                totalKwh = total,
                t1Kwh = t1,
                t2Kwh = t2,
                t3Kwh = t3,
                powerW = power,
                voltageV = voltage,
                currentA = current,
                serial = serial,
                withPassword = attempt.password != null,
            )
        }
    }
}
