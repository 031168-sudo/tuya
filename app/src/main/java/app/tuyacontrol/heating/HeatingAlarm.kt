package app.tuyacontrol.heating

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * Будильник для устройств без своего расписания (конвекторы Rubetek): в начале часа, когда по плану
 * меняется уставка, телефон просыпается и отправляет её. Работает и в режиме сна (setAndAllowWhileIdle,
 * возможна задержка на несколько минут — для отопления это не важно).
 */
object HeatingAlarm {

    fun scheduleNext(context: Context) {
        val ctx = context.applicationContext
        val store = HeatingStore(ctx)
        val schedules = store.loadSchedules()
        if (store.load().zones.none { it.control } || schedules.isEmpty()) {
            cancel(ctx)
            return
        }
        val now = ZonedDateTime.now()
        // Ближайший час, в который у кого-то меняется уставка (в пределах суток)
        var next: ZonedDateTime? = null
        for (i in 1..24) {
            val t = now.plusHours(i.toLong()).withMinute(0).withSecond(5).withNano(0)
            val h = t.hour
            if (schedules.values.any { it[h] != it[(h + 23) % 24] }) {
                next = t
                break
            }
        }
        val at = next ?: run {
            cancel(ctx)
            return
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pending(ctx))
        AppLog.i("Отопление: следующее переключение Rubetek в %02d:00".format(at.hour))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context.applicationContext))
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, HeatingAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Срабатывание будильника и перезагрузка телефона: выставить текущую уставку и завести следующий. */
class HeatingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val ctx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(50_000) { HeatingEngine(ctx).applyRubetekNow() }
            } catch (e: Exception) {
                AppLog.e("Отопление: будильник", e)
            } finally {
                result.finish()
            }
        }
    }
}
