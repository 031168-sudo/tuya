package app.tuyacontrol.sensor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Одно показание датчика: время (мс) и значение с учётом множителя. */
data class Reading(val code: String, val time: Long, val value: Double)

/** Точка графика: интервал [time, time + bucket), среднее, минимум и максимум за интервал. */
data class SeriesPoint(val time: Long, val avg: Double, val min: Double, val max: Double)

data class SeriesStats(val min: Double, val max: Double, val avg: Double, val count: Int)

/** Локальный архив показаний датчиков (облако Tuya хранит журнал только 7 дней). */
class SensorDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "sensor.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE readings (device_id TEXT NOT NULL, code TEXT NOT NULL, time INTEGER NOT NULL, " +
                "value REAL NOT NULL, PRIMARY KEY (device_id, code, time)) WITHOUT ROWID",
        )
        db.execSQL("CREATE TABLE sensor_meta (device_id TEXT PRIMARY KEY, log_cursor INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(deviceId: String, readings: List<Reading>) {
        if (readings.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO readings (device_id, code, time, value) VALUES (?, ?, ?, ?)",
            )
            for (r in readings) {
                stmt.clearBindings()
                stmt.bindString(1, deviceId)
                stmt.bindString(2, r.code)
                stmt.bindLong(3, r.time)
                stmt.bindDouble(4, r.value)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cursor(deviceId: String): Long? = readableDatabase.rawQuery(
        "SELECT log_cursor FROM sensor_meta WHERE device_id = ?",
        arrayOf(deviceId),
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun setCursor(deviceId: String, cursor: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO sensor_meta (device_id, log_cursor) VALUES (?, ?)",
            arrayOf<Any>(deviceId, cursor),
        )
    }

    /** Показания за [from, to), сгруппированные по интервалам bucketMs. */
    fun series(deviceId: String, code: String, from: Long, to: Long, bucketMs: Long): List<SeriesPoint> =
        readableDatabase.rawQuery(
            "SELECT (time - ?) / ? AS b, AVG(value), MIN(value), MAX(value) FROM readings " +
                "WHERE device_id = ? AND code = ? AND time >= ? AND time < ? GROUP BY b ORDER BY b",
            arrayOf(from.toString(), bucketMs.toString(), deviceId, code, from.toString(), to.toString()),
        ).use { c ->
            val list = mutableListOf<SeriesPoint>()
            while (c.moveToNext()) {
                list += SeriesPoint(from + c.getLong(0) * bucketMs, c.getDouble(1), c.getDouble(2), c.getDouble(3))
            }
            list
        }

    fun stats(deviceId: String, code: String, from: Long, to: Long): SeriesStats? = readableDatabase.rawQuery(
        "SELECT MIN(value), MAX(value), AVG(value), COUNT(*) FROM readings " +
            "WHERE device_id = ? AND code = ? AND time >= ? AND time < ?",
        arrayOf(deviceId, code, from.toString(), to.toString()),
    ).use { c ->
        if (c.moveToFirst() && c.getInt(3) > 0) SeriesStats(c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getInt(3)) else null
    }

    /** Последнее показание кода. */
    fun last(deviceId: String, code: String): Reading? = readableDatabase.rawQuery(
        "SELECT time, value FROM readings WHERE device_id = ? AND code = ? ORDER BY time DESC LIMIT 1",
        arrayOf(deviceId, code),
    ).use { c -> if (c.moveToFirst()) Reading(code, c.getLong(0), c.getDouble(1)) else null }

    /** Самое раннее показание — с какого момента есть история. */
    fun firstTime(deviceId: String): Long? = readableDatabase.rawQuery(
        "SELECT MIN(time) FROM readings WHERE device_id = ?",
        arrayOf(deviceId),
    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    /**
     * Удалить показания [codes], снятые, пока прибор был выключен (по точкам «power»: 1 — вкл, 0 — выкл),
     * начиная с [from]. Точку в момент выключения оставляем (в ней ещё верное значение).
     */
    fun purgeWhileOff(deviceId: String, codes: Collection<String>, from: Long): Int {
        if (codes.isEmpty()) return 0
        val power = readableDatabase.rawQuery(
            "SELECT time, value FROM readings WHERE device_id = ? AND code = 'power' AND time >= ? ORDER BY time",
            arrayOf(deviceId, (from - 24 * 3600_000L).toString()),
        ).use { c ->
            val l = mutableListOf<Pair<Long, Boolean>>()
            while (c.moveToNext()) l += c.getLong(0) to (c.getDouble(1) >= 0.5)
            l
        }
        if (power.isEmpty()) return 0
        val db = writableDatabase
        val inList = codes.joinToString(",") { "?" }
        var removed = 0
        var offAt: Long? = null
        fun purge(a: Long, b: Long) {
            val start = maxOf(a + 60_000L, from)
            if (b <= start) return
            removed += db.delete(
                "readings",
                "device_id = ? AND code IN ($inList) AND time > ? AND time < ?",
                arrayOf(deviceId) + codes.toTypedArray() + arrayOf(start.toString(), b.toString()),
            )
        }
        for ((t, on) in power) {
            if (!on && offAt == null) offAt = t
            if (on && offAt != null) { purge(offAt!!, t); offAt = null }
        }
        offAt?.let { purge(it, Long.MAX_VALUE) }
        return removed
    }
}
