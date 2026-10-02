package app.tuyacontrol.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tuyacontrol.MainActivity
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.data.CredentialsStore
import app.tuyacontrol.energy.EnergyDb
import app.tuyacontrol.energy.EnergySync
import app.tuyacontrol.heating.HeatingEngine
import app.tuyacontrol.sensor.SensorDb
import app.tuyacontrol.sensor.SensorSync
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Раз в сутки (около 3:00) докачивает журналы Tuya по счётчикам и датчикам, чтобы в истории
 * не было дыр, даже если приложение неделями не открывают: облако хранит журнал только 7 дней.
 */
class HistorySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val targets = SyncTargets(ctx)
        targets.lastRun = System.currentTimeMillis()

        // Аккумулятор меньше 50% — откладываем, WorkManager повторит через час
        val battery = batteryPercent(ctx)
        if (battery in 0 until MIN_BATTERY) {
            targets.lastResult = "Отложено: аккумулятор $battery%"
            AppLog.i("Фон: аккумулятор $battery%, загрузка отложена")
            return Result.retry()
        }

        val creds = CredentialsStore(ctx).load() ?: run {
            targets.lastResult = "Нет ключей Tuya"
            return Result.success()
        }

        val energy = targets.energy()
        val sensors = targets.sensors()
        if (energy.isEmpty() && sensors.isEmpty()) {
            targets.lastResult = "Нет устройств: откройте приложение, чтобы обновить список"
            return Result.success()
        }

        val client = TuyaCloudClient(creds)
        val energySync = EnergySync(EnergyDb(ctx))
        val sensorSync = SensorSync(SensorDb(ctx))
        val errors = mutableListOf<String>()
        AppLog.i("Фон: загрузка истории, счётчиков ${energy.size}, датчиков ${sensors.size}")

        for (d in energy) {
            runCatching { energySync.sync(client, d) {} }
                .onFailure { errors += "${d.name}: ${it.message ?: it.javaClass.simpleName}" }
            delay(3_000)
        }
        for (d in sensors.filterNot { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it.id) || app.tuyacontrol.xiaomi.XiaomiMapper.isXiaomi(it.id) }) {
            runCatching { sensorSync.sync(client, d) {} }
                .onFailure { errors += "${d.name}: ${it.message ?: it.javaClass.simpleName}" }
            delay(3_000)
        }

        // Автопилот отопления: после загрузки истории (в ней и уличный датчик для поправки прогноза)
        // пересчитать план по свежему прогнозу и перезаписать расписание
        val heating = HeatingEngine(ctx)
        val heatingSettings = heating.store.load()
        if (heatingSettings.zones.any { it.control }) {
            runCatching { heating.deploy(TuyaCloudClient(creds), heatingSettings) }
                .onFailure { AppLog.e("Фон: план отопления не обновлён", it) }
        }

        val now = System.currentTimeMillis()
        return if (errors.isEmpty()) {
            targets.lastSuccess = now
            targets.lastResult = "Успешно"
            AppLog.i("Фон: история загружена")
            Result.success()
        } else {
            targets.lastResult = "Ошибки: " + errors.joinToString("; ")
            AppLog.e("Фон: ${targets.lastResult}")
            warnIfStale(ctx, targets)
            // Повтор через час; если и дальше не выйдет — задача снова запустится следующей ночью
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "history_sync"
        private const val WORK_NOW = "history_sync_now"
        private const val MIN_BATTERY = 50
        private const val CHANNEL_ID = "history"
        private const val STALE_MS = 5L * 24 * 3600 * 1000

        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Ежедневная задача на ~3:00. Уже запланированную не трогаем. */
        fun schedule(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.with(LocalTime.of(3, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<HistorySyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Запустить загрузку прямо сейчас (кнопка в настройках). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<HistorySyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        private fun batteryPercent(context: Context): Int {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val fromManager = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (fromManager in 0..100) return fromManager
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            return if (level >= 0 && scale > 0) level * 100 / scale else -1
        }

        /** Уведомление, если история не обновлялась больше 5 дней — облако вот-вот начнёт её терять. */
        fun warnIfStale(context: Context, targets: SyncTargets) {
            val last = targets.lastSuccess
            if (last == 0L || System.currentTimeMillis() - last < STALE_MS) return
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "История устройств", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val days = (System.currentTimeMillis() - last) / (24 * 3600 * 1000)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("История не обновлялась $days дн.")
                .setContentText("Tuya хранит журнал 7 дней. Откройте приложение, чтобы не потерять данные.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(1, notification)
        }
    }
}
