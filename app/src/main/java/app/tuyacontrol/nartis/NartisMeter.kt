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

    /** Пароль чтения по умолчанию: если пусто — пробуем без пароля (публичный клиент). */
    fun read(port: Int, password: String, log: (String) -> Unit): MeterReading {
        val withPassword = password.isNotBlank()
        val config = CosemClientConfig.builder()
            .transport(
                TransportConfig.tcp("127.0.0.1", port)
                    .withReadTimeoutMs(6_000)
                    .withConnectTimeoutMs(5_000),
            )
            .serverAddress(MeterAddress.hdlcServer(1))
            .clientAddress(MeterAddress.hdlcClient(if (withPassword) 32 else 16))
            .authentication(
                if (withPassword) AarqApdu.AuthenticationLevel.LOW else AarqApdu.AuthenticationLevel.NONE,
                if (withPassword) password.toByteArray() else null,
            )
            .maxReceivePduSize(512)
            .timeoutMs(6_000)
            .maxRetries(1)
            .build()

        CosemClient(config).use { client ->
            client.open()
            log(if (withPassword) "Связь со счётчиком открыта (с паролем)" else "Связь со счётчиком открыта (без пароля)")

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
            return MeterReading(total, t1, t2, t3, power, voltage, current, serial, withPassword)
        }
    }
}
