package app.tuyacontrol.sensor

import android.content.Context
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.rubetek.RubetekMapper
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Свои точки истории из текущих показаний устройств Tuya. Не у всех устройств облако хранит журнал
 * (батарея в ванной его почти не пишет), поэтому текущие значения копим в телефоне — в ту же базу,
 * что и журнал облака: графики берут и то, и другое.
 */
object LocalHistory {

    /** Не чаще одной точки в 5 минут на устройство. */
    private const val MIN_GAP_MS = 5 * 60_000L
    /** Показания старше получаса считаем несвежими и не пишем. */
    private const val MAX_AGE_MS = 30 * 60_000L
    private val last = ConcurrentHashMap<String, Long>()

    /** Из списка устройств приложения (облако или Wi-Fi), пока оно открыто. Rubetek пишет свой модуль. */
    fun record(context: Context, devices: List<DeviceUi>) {
        val now = System.currentTimeMillis()
        val db = SensorDb(context)
        for (d in devices) {
            if (RubetekMapper.isRubetek(d.id) || !d.online) continue
            val sensor = d.sensorDevice ?: continue
            val at = d.lastDataTime.takeIf { it > 0 } ?: continue
            if (now - at > MAX_AGE_MS) continue
            if (now - (last[d.id] ?: 0L) < MIN_GAP_MS) continue
            val readings = readingsOf(sensor, d.status, at)
            if (readings.isEmpty()) continue
            last[d.id] = now
            runCatching { db.insert(d.id, readings) }.onFailure { AppLog.e("${d.name}: точка истории не записана", it) }
        }
    }

    /** Фоновый замер: текущее состояние из облака для указанных датчиков. */
    suspend fun sample(context: Context, client: TuyaCloudClient, sensors: List<SensorDevice>) {
        val db = SensorDb(context)
        for (s in sensors) {
            try {
                val raw = if (s.thingModel) client.getShadowProperties(s.id) else
                    runCatching { client.getStatus(s.id) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: client.getShadowProperties(s.id)
                val readings = readingsOf(s, raw, System.currentTimeMillis())
                db.insert(s.id, readings)
            } catch (e: Exception) {
                AppLog.e("${s.name}: фоновый замер не удался", e)
            }
        }
    }

    fun readingsOf(sensor: SensorDevice, status: Map<String, Any?>, time: Long): List<Reading> {
        val t = time / 60_000 * 60_000
        return sensor.channels.mapNotNull { ch ->
            val v = (status[ch.code] as? Number)?.toDouble() ?: status[ch.code]?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
            Reading(ch.code, t, BigDecimal.valueOf(v).movePointLeft(ch.scale).toDouble())
        }
    }
}
