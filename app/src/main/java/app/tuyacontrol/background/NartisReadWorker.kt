package app.tuyacontrol.background

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.nartis.NartisBle
import app.tuyacontrol.nartis.NartisDevice
import app.tuyacontrol.nartis.NartisMeter
import app.tuyacontrol.nartis.NartisSnapshot
import app.tuyacontrol.nartis.NartisStore
import app.tuyacontrol.nartis.NusBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Ночное чтение счётчика НАРТИС около 3:00: если прибор виден по Bluetooth,
 * показания забираются и ложатся в историю. Если не виден — ничего не делаем.
 */
class NartisReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = NartisStore(ctx)
        val serial = store.serial.trim()
        if (serial.isBlank()) return Result.success()
        if (!hasBluetoothPermission(ctx)) {
            AppLog.i("НАРТИС: нет разрешения Bluetooth, ночное чтение пропущено")
            return Result.success()
        }

        val ble = NartisBle(ctx) { AppLog.i("НАРТИС (фон): $it") }
        var bridge: NusBridge? = null
        return try {
            val found = CompletableDeferred<NartisDevice>()
            ble.startScan(
                onFound = { d ->
                    if (d.name.contains(serial, ignoreCase = true)) {
                        found.complete(d)
                        ble.stopScan()
                    }
                },
                onError = { AppLog.i("НАРТИС (фон): $it") },
            )
            val device = withTimeoutOrNull(15_000) { found.await() }
            if (device == null) {
                AppLog.i("НАРТИС: счётчик $serial не виден, ночное чтение пропущено")
                return Result.success()
            }

            withContext(Dispatchers.IO) {
                ble.connect(device.address, store.pin)
                delay(500)
                bridge = NusBridge(ble) { AppLog.i("НАРТИС (фон): $it") }.also { it.start() }
                val reading = NartisMeter.read(bridge!!.port, store.password) { AppLog.i("НАРТИС (фон): $it") }
                store.add(
                    NartisSnapshot(
                        time = System.currentTimeMillis(),
                        total = reading.totalKwh,
                        t1 = reading.t1Kwh,
                        t2 = reading.t2Kwh,
                        t3 = reading.t3Kwh,
                    ),
                )
            }
            AppLog.i("НАРТИС: ночное чтение выполнено")
            Result.success()
        } catch (e: Exception) {
            AppLog.e("НАРТИС: ночное чтение не удалось", e)
            Result.success()
        } finally {
            runCatching { bridge?.close() }
            runCatching { ble.close() }
        }
    }

    private fun hasBluetoothPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 31) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val WORK_NAME = "nartis_read"

        /** Ежедневно около 3:00, как и облачная история. */
        fun schedule(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.with(LocalTime.of(3, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<NartisReadWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
