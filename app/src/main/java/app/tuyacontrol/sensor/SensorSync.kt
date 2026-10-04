package app.tuyacontrol.sensor

import app.tuyacontrol.cloud.LogEntry
import app.tuyacontrol.cloud.TuyaApiException
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.background.SyncLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Показатель датчика: код DP, множитель и единица. */
data class SensorChannel(val code: String, val scale: Int, val unit: String)

/** Датчик, для которого собираем историю температуры и влажности. */
data class SensorDevice(
    val id: String,
    val name: String,
    val thingModel: Boolean,
    val temperature: SensorChannel?,
    val humidity: SensorChannel?,
    /** Показания верны только когда прибор включён: пока выключен — точки не пишем, ложные удаляем. */
    val onlyWhenOn: Boolean = false,
    /** Код главного выключателя (switch / switch_1), если есть. */
    val switchCode: String? = null,
) {
    val channels: List<SensorChannel> get() = listOfNotNull(temperature, humidity)

    companion object {
        val TEMPERATURE_CODES = listOf("va_temperature", "temp_current", "temp_value", "temperature", "cur_temp")
        val HUMIDITY_CODES = listOf("va_humidity", "humidity_value", "humidity", "cur_humidity")
    }
}

/**
 * Загрузка журнала показаний в локальную БД. Облако хранит журнал 7 дней, поэтому читаем
 * с места последней остановки кусками по 12 часов и сохраняем каждый кусок сразу:
 * история копится в телефоне, пока приложение открывают хотя бы раз в неделю.
 */
class SensorSync(private val db: SensorDb) {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val showTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** Загрузка под общим замком: приложение и фоновая задача не качают один журнал одновременно. */
    suspend fun sync(client: TuyaCloudClient, device: SensorDevice, progress: (String) -> Unit) =
        SyncLock.mutex.withLock { syncLocked(client, device, progress) }

    private suspend fun syncLocked(client: TuyaCloudClient, device: SensorDevice, progress: (String) -> Unit) {
        val channels = device.channels.associateBy { it.code }
        if (channels.isEmpty()) return
        // Приборам «верно только включённым» качаем и выключатель — по нему убираем ложные точки
        val switchCode = device.switchCode?.takeIf { device.onlyWhenOn }
        val codes = (channels.keys + listOfNotNull(switchCode)).joinToString(",")
        val now = System.currentTimeMillis()
        val windowStart = now - 7L * 24 * 3600 * 1000 + 60_000
        val saved = withContext(Dispatchers.IO) { db.cursor(device.id) }
        if (saved != null && saved < windowStart) {
            AppLog.i("${device.name}: перерыв больше 7 дней, часть истории датчика потеряна")
        }
        var chunkStart = maxOf(saved ?: windowStart, windowStart)
        var total = 0
        while (chunkStart < now) {
            val chunkEnd = minOf(chunkStart + CHUNK_MS, now)
            val label = Instant.ofEpochMilli(chunkStart).atZone(zone).format(showTime)
            val logs = fetch(client, device, codes, chunkStart, chunkEnd) { n ->
                progress("${device.name}: журнал с $label, стр. $n")
            }
            val readings = logs.mapNotNull { e ->
                if (e.time !in chunkStart until chunkEnd) return@mapNotNull null
                if (e.code == switchCode) {
                    return@mapNotNull Reading("power", e.time, if (e.value.equals("true", true)) 1.0 else 0.0)
                }
                val ch = channels[e.code] ?: return@mapNotNull null
                val raw = e.value.toDoubleOrNull() ?: return@mapNotNull null
                Reading(e.code, e.time, BigDecimal.valueOf(raw).movePointLeft(ch.scale).toDouble())
            }
            total += readings.size
            withContext(Dispatchers.IO) {
                db.insert(device.id, readings)
                db.setCursor(device.id, chunkEnd)
            }
            chunkStart = chunkEnd
        }
        AppLog.i("${device.name}: загружено показаний датчика: $total")
        if (device.onlyWhenOn && switchCode != null) {
            // Переключения вкл/выкл — со своего места: показания уже могли быть скачаны раньше без них
            val pFrom = maxOf(db.powerCursor(device.id) ?: windowStart, windowStart)
            val sw = runCatching { fetch(client, device, switchCode, pFrom, now) {} }
                .onFailure { AppLog.e("${device.name}: журнал вкл/выкл не прочитан", it) }
                .getOrNull()
            if (sw != null) withContext(Dispatchers.IO) {
                db.insert(device.id, sw.filter { it.code == switchCode }.map {
                    Reading("power", it.time, if (it.value.equals("true", true)) 1.0 else 0.0)
                })
                db.setPowerCursor(device.id, now)
            }
        }
        if (device.onlyWhenOn) {
            val removed = withContext(Dispatchers.IO) { db.purgeWhileOff(device.id, channels.keys, windowStart) }
            if (removed > 0) AppLog.i("${device.name}: убрано показаний, снятых при выключенном приборе: $removed")
        }
    }

    private suspend fun fetch(
        client: TuyaCloudClient,
        device: SensorDevice,
        codes: String,
        from: Long,
        to: Long,
        onPage: (Int) -> Unit,
    ): List<LogEntry> = if (device.thingModel) {
        try {
            client.getReportLogsV2(device.id, codes, from, to, 100, onPage)
        } catch (e: TuyaApiException) {
            // Журнал модели устройства принимают не все (батарея в ванной: «Parameter error») — пробуем v1
            AppLog.e("${device.name}: журнал v2 недоступен, пробую v1", e)
            try {
                client.getDeviceLogs(device.id, codes, from, to, 100, onPage)
            } catch (e2: TuyaApiException) {
                // Облако журнала не даёт — графику хватит точек, накопленных в телефоне
                AppLog.e("${device.name}: журнал облака недоступен, история только из телефона", e2)
                emptyList()
            }
        }
    } else {
        try {
            client.getDeviceLogs(device.id, codes, from, to, 100, onPage)
        } catch (e: TuyaApiException) {
            AppLog.e("${device.name}: журнал v1 недоступен, пробую v2", e)
            try {
                client.getReportLogsV2(device.id, codes, from, to, 100, onPage)
            } catch (e2: TuyaApiException) {
                // Облако журнала не даёт — графику хватит точек, накопленных в телефоне
                AppLog.e("${device.name}: журнал облака недоступен, история только из телефона", e2)
                emptyList()
            }
        }
    }

    private companion object {
        const val CHUNK_MS = 12L * 3600 * 1000
    }
}
