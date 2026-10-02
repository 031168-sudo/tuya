package app.tuyacontrol.xiaomi

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tuyacontrol.ControlMode
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.sensor.LocalHistory
import app.tuyacontrol.sensor.SensorDb
import java.util.concurrent.TimeUnit

/**
 * Облако Xiaomi историю температуры не отдаёт, поэтому копим её в телефоне — в ту же базу, что и история
 * датчиков Tuya, графики работают без изменений. Пока приложение открыто, точки пишет общий LocalHistory
 * (раз в 5 минут); в фоне — эта задача раз в 15 минут (по Wi-Fi, если телефон дома, иначе через облако).
 */
object XiaomiHistory {

    fun write(context: Context, d: DeviceUi) {
        if (!d.online) return
        val sensor = d.sensorDevice ?: return
        val readings = LocalHistory.readingsOf(sensor, d.status, System.currentTimeMillis())
        if (readings.isEmpty()) return
        runCatching { SensorDb(context).insert(d.id, readings) }
            .onFailure { AppLog.e("${d.name}: точка истории не записана", it) }
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<XiaomiSampleWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("xiaomi_sample", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("xiaomi_sample")
    }
}

/** Фоновый замер температуры устройств Xiaomi. */
class XiaomiSampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val hub = XiaomiHub(applicationContext)
        if (!hub.connected && hub.store.devices.isEmpty()) return Result.success()
        try {
            hub.poll(ControlMode.AUTO, reloadList = false, allowListRefresh = false)
                .forEach { XiaomiHistory.write(applicationContext, it) }
        } catch (e: Exception) {
            AppLog.e("Xiaomi: фоновый замер не удался", e)
        }
        return Result.success()
    }
}
