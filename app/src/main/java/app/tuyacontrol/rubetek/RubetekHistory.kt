package app.tuyacontrol.rubetek

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.sensor.Reading
import app.tuyacontrol.sensor.SensorDb
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Облако Rubetek отдаёт только текущее состояние, поэтому историю температуры копим в телефоне:
 * при каждом обновлении списка (раз в минуту, пока приложение открыто) и в фоне раз в 15 минут.
 * Точки пишутся в ту же базу, что и история датчиков Tuya, — графики работают без изменений.
 */
object RubetekHistory {

    /** Не чаще одной точки в 5 минут на устройство. */
    private const val MIN_GAP_MS = 5 * 60_000L
    private val last = ConcurrentHashMap<String, Long>()

    fun record(context: Context, devices: List<DeviceUi>) {
        val now = System.currentTimeMillis()
        val db = SensorDb(context)
        for (d in devices) {
            if (!RubetekMapper.isRubetek(d.id) || !d.online) continue
            val t = (d.status["temp_current"] as? Number)?.toDouble() ?: continue
            if (now - (last[d.id] ?: 0L) < MIN_GAP_MS) continue
            last[d.id] = now
            // Минуты округляем: точки разных запусков ложатся ровно
            val time = now / 60_000 * 60_000
            runCatching { db.insert(d.id, listOf(Reading("temp_current", time, t))) }
                .onFailure { AppLog.e("Rubetek: точка истории не записана", it) }
        }
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RubetekSampleWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("rubetek_sample", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("rubetek_sample")
    }
}

/** Фоновый замер температуры конвекторов Rubetek. */
class RubetekSampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = RubetekStore(applicationContext)
        if (store.refreshToken == null) return Result.success()
        return try {
            RubetekHistory.record(applicationContext, RubetekClient(store).allDevices())
            Result.success()
        } catch (e: Exception) {
            AppLog.e("Rubetek: фоновый замер не удался", e)
            Result.success()
        }
    }
}
