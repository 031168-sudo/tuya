package app.tuyacontrol.energy

import app.tuyacontrol.cloud.TuyaApiException
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Устройство, для которого собираем историю энергии. */
data class EnergyDevice(val id: String, val name: String, val activeTime: Long)

/**
 * Загрузка суточного потребления в локальную БД.
 *
 * 1. Если в проекте подключён сервис Data Statistics — берём суточные суммы add_ele
 *    за всё время с даты активации устройства (/v1.0/devices/{id}/statistics/days).
 * 2. Иначе — суммируем отчёты add_ele из журнала устройства за последние 7 дней
 *    (дольше облако не хранит), история копится при каждом открытии приложения.
 */
class EnergySync(private val db: EnergyDb) {

    private val zone: ZoneId = ZoneId.systemDefault()

    suspend fun sync(client: TuyaCloudClient, device: EnergyDevice, progress: (String) -> Unit) {
        val today = LocalDate.now(zone)
        val old = withContext(Dispatchers.IO) { db.meta(device.id) }
        val activeDay = if (device.activeTime > 0) {
            Instant.ofEpochSecond(device.activeTime).atZone(zone).toLocalDate()
        } else {
            old?.activeDay
        }
        var meta = (old ?: EnergyMeta(device.id, device.name, null, null, activeDay, null, null, null, 0))
            .copy(name = device.name, activeDay = activeDay)

        // Проверяем, доступна ли статистика (каждый раз, пока работаем через журналы:
        // после подключения Data Statistics приложение само перейдёт на полную историю).
        if (meta.mode != EnergyDb.SOURCE_STATS || meta.code == null) {
            val code = try {
                val types = client.getStatisticTypes(device.id)
                AppLog.i("${device.name}: статистика доступна для ${types.joinToString { "${it.code}/${it.statType}" }}")
                types.firstOrNull { it.code == "add_ele" }?.code
                    ?: types.firstOrNull { it.statType == "sum" && "ele" in it.code }?.code
                    ?: types.firstOrNull { it.statType == "sum" }?.code
            } catch (e: TuyaApiException) {
                AppLog.e("${device.name}: Data Statistics недоступен", e)
                null
            }
            meta = if (code != null) {
                meta.copy(code = code, mode = EnergyDb.SOURCE_STATS, syncedUntil = null, lastError = null)
            } else {
                meta.copy(code = "add_ele", mode = EnergyDb.SOURCE_LOGS)
            }
        }

        try {
            if (meta.mode == EnergyDb.SOURCE_STATS) {
                meta = syncStats(client, meta, today, progress)
            } else {
                meta = syncLogs(client, meta, today, progress)
            }
            meta = meta.copy(updatedAt = System.currentTimeMillis())
        } catch (e: Exception) {
            AppLog.e("${device.name}: ошибка загрузки истории", e)
            meta = meta.copy(lastError = e.message ?: e.javaClass.simpleName)
            withContext(Dispatchers.IO) { db.saveMeta(meta) }
            throw e
        }
        withContext(Dispatchers.IO) { db.saveMeta(meta) }
    }

    private suspend fun syncStats(
        client: TuyaCloudClient,
        start: EnergyMeta,
        today: LocalDate,
        progress: (String) -> Unit,
    ): EnergyMeta {
        var meta = start
        val code = meta.code ?: "add_ele"
        // Последние 2 дня перезапрашиваем всегда: сегодняшний день ещё не закончился
        var from = meta.syncedUntil?.minusDays(2)
            ?: meta.activeDay
            ?: today.minusYears(3)
        if (from.isAfter(today)) from = today
        var chunkDays = 0 // 0 — по календарным месяцам, иначе фиксированный шаг

        while (!from.isAfter(today)) {
            val to = if (chunkDays == 0) {
                earlier(from.with(TemporalAdjusters.lastDayOfMonth()), today)
            } else {
                earlier(from.plusDays(chunkDays - 1L), today)
            }
            progress("${meta.name}: ${from.monthValue.toString().padStart(2, '0')}.${from.year}")
            val days = try {
                client.getStatisticsDays(meta.deviceId, code, from, to)
            } catch (e: TuyaApiException) {
                if (chunkDays == 0) {
                    AppLog.i("${meta.name}: месяц целиком не отдаётся (${e.code}), запрашиваю по 7 дней")
                    chunkDays = 7
                    continue
                }
                throw e
            }
            // Дни без записи — нулевое потребление
            val full = LinkedHashMap<LocalDate, Double>()
            var d = from
            while (!d.isAfter(to)) {
                full[d] = days[d] ?: 0.0
                d = d.plusDays(1)
            }
            withContext(Dispatchers.IO) { db.upsertDays(meta.deviceId, full, EnergyDb.SOURCE_STATS) }
            meta = meta.copy(syncedUntil = to)
            withContext(Dispatchers.IO) { db.saveMeta(meta) }
            from = to.plusDays(1)
        }

        val total = runCatching { client.getStatisticsTotal(meta.deviceId, code) }.getOrNull()
        return meta.copy(total = total ?: meta.total, lastError = null)
    }

    private suspend fun syncLogs(
        client: TuyaCloudClient,
        start: EnergyMeta,
        today: LocalDate,
        progress: (String) -> Unit,
    ): EnergyMeta {
        progress("${start.name}: журнал за 7 дней")
        val scale = energyScale(client, start.deviceId)
        val now = System.currentTimeMillis()
        val windowStart = now - 7L * 24 * 3600 * 1000 + 60_000
        val logs = client.getDeviceLogs(start.deviceId, "add_ele", windowStart, now)
        AppLog.i("${start.name}: отчётов add_ele за 7 дней: ${logs.size}, множитель 10^-$scale")

        // Первый день окна неполный — его не пишем
        val firstFullDay = Instant.ofEpochMilli(windowStart).atZone(zone).toLocalDate().plusDays(1)
        val sums = LinkedHashMap<LocalDate, Double>()
        var d = firstFullDay
        while (!d.isAfter(today)) {
            sums[d] = 0.0
            d = d.plusDays(1)
        }
        for (entry in logs) {
            if (entry.code != "add_ele") continue
            val day = Instant.ofEpochMilli(entry.time).atZone(zone).toLocalDate()
            if (day !in sums) continue
            val raw = entry.value.toDoubleOrNull() ?: continue
            val kwh = BigDecimal.valueOf(raw).movePointLeft(scale).toDouble()
            sums[day] = (sums[day] ?: 0.0) + kwh
        }
        withContext(Dispatchers.IO) { db.upsertDays(start.deviceId, sums, EnergyDb.SOURCE_LOGS) }
        return start.copy(syncedUntil = today, lastError = null)
    }

    private fun earlier(a: LocalDate, b: LocalDate): LocalDate = if (a.isBefore(b)) a else b

    /** Множитель add_ele из спецификации (обычно 3: значение 12 = 0,012 кВт·ч). */
    private suspend fun energyScale(client: TuyaCloudClient, deviceId: String): Int {
        val spec = runCatching { client.getSpecification(deviceId) }.getOrNull()
            ?.takeIf { "add_ele" in it }
            ?: runCatching { client.getThingModel(deviceId) }.getOrNull()
        return spec?.get("add_ele")?.scale ?: 3
    }
}
