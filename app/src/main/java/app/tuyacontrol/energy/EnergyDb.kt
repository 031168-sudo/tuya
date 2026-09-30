package app.tuyacontrol.energy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/** Суточное потребление одного устройства. */
data class DayEnergy(val deviceId: String, val day: LocalDate, val kwh: Double, val source: String)

/**
 * Тариф: цена за кВт·ч в периоде [start, end] включительно.
 * deviceId = null — для всех устройств; тариф конкретного устройства важнее общего.
 */
data class Tariff(
    val id: Long = 0,
    val deviceId: String?,
    val start: LocalDate,
    val end: LocalDate?,
    val price: Double,
    val note: String = "",
) {
    fun covers(day: LocalDate): Boolean = !day.isBefore(start) && (end == null || !day.isAfter(end))
}

/** Состояние синхронизации по устройству. */
data class EnergyMeta(
    val deviceId: String,
    val name: String,
    /** DP, по которому считается энергия (обычно add_ele). */
    val code: String?,
    /** "stats" — сервис Data Statistics, "logs" — накопление из журналов. */
    val mode: String?,
    val activeDay: LocalDate?,
    /** До какого дня (включительно) загружена статистика. */
    val syncedUntil: LocalDate?,
    /** Итог за всё время по данным Tuya, кВт·ч. */
    val total: Double?,
    val lastError: String?,
    val updatedAt: Long,
)

class EnergyDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "energy.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE energy_daily (" +
                "device_id TEXT NOT NULL, day TEXT NOT NULL, kwh REAL NOT NULL, source TEXT NOT NULL, " +
                "PRIMARY KEY (device_id, day))",
        )
        db.execSQL(
            "CREATE TABLE tariffs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT, start_day TEXT NOT NULL, " +
                "end_day TEXT, price REAL NOT NULL, note TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL(
            "CREATE TABLE energy_meta (" +
                "device_id TEXT PRIMARY KEY, name TEXT NOT NULL, code TEXT, mode TEXT, active_day TEXT, " +
                "synced_until TEXT, total REAL, last_error TEXT, updated_at INTEGER NOT NULL DEFAULT 0)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------- Суточная энергия ----------

    /**
     * Сохраняет суточные значения. Данные из статистики Tuya ("stats") точнее, поэтому
     * значения из журналов ("logs") не перезаписывают их.
     */
    fun upsertDays(deviceId: String, days: Map<LocalDate, Double>, source: String) {
        if (days.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((day, kwh) in days) {
                if (source != SOURCE_STATS) {
                    val existing = db.rawQuery(
                        "SELECT source FROM energy_daily WHERE device_id = ? AND day = ?",
                        arrayOf(deviceId, day.toString()),
                    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    if (existing == SOURCE_STATS) continue
                }
                val values = ContentValues().apply {
                    put("device_id", deviceId)
                    put("day", day.toString())
                    put("kwh", kwh)
                    put("source", source)
                }
                db.insertWithOnConflict("energy_daily", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getDays(deviceIds: Collection<String>, from: LocalDate, to: LocalDate): List<DayEnergy> {
        if (deviceIds.isEmpty()) return emptyList()
        val placeholders = deviceIds.joinToString(",") { "?" }
        val args = deviceIds.toList() + listOf(from.toString(), to.toString())
        return readableDatabase.rawQuery(
            "SELECT device_id, day, kwh, source FROM energy_daily " +
                "WHERE device_id IN ($placeholders) AND day >= ? AND day <= ? ORDER BY day",
            args.toTypedArray(),
        ).use { c ->
            val list = mutableListOf<DayEnergy>()
            while (c.moveToNext()) {
                list += DayEnergy(c.getString(0), LocalDate.parse(c.getString(1)), c.getDouble(2), c.getString(3))
            }
            list
        }
    }

    fun firstDay(deviceIds: Collection<String>): LocalDate? {
        if (deviceIds.isEmpty()) return null
        val placeholders = deviceIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT MIN(day) FROM energy_daily WHERE device_id IN ($placeholders) AND kwh > 0",
            deviceIds.toTypedArray(),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) else null }
    }

    // ---------- Тарифы ----------

    fun tariffs(): List<Tariff> = readableDatabase.rawQuery(
        "SELECT id, device_id, start_day, end_day, price, note FROM tariffs ORDER BY start_day DESC, id DESC",
        null,
    ).use { c ->
        val list = mutableListOf<Tariff>()
        while (c.moveToNext()) {
            list += Tariff(
                id = c.getLong(0),
                deviceId = if (c.isNull(1)) null else c.getString(1),
                start = LocalDate.parse(c.getString(2)),
                end = if (c.isNull(3)) null else LocalDate.parse(c.getString(3)),
                price = c.getDouble(4),
                note = c.getString(5) ?: "",
            )
        }
        list
    }

    fun saveTariff(t: Tariff) {
        val values = ContentValues().apply {
            if (t.deviceId == null) putNull("device_id") else put("device_id", t.deviceId)
            put("start_day", t.start.toString())
            if (t.end == null) putNull("end_day") else put("end_day", t.end.toString())
            put("price", t.price)
            put("note", t.note)
        }
        if (t.id == 0L) {
            writableDatabase.insert("tariffs", null, values)
        } else {
            writableDatabase.update("tariffs", values, "id = ?", arrayOf(t.id.toString()))
        }
    }

    fun deleteTariff(id: Long) {
        writableDatabase.delete("tariffs", "id = ?", arrayOf(id.toString()))
    }

    // ---------- Состояние синхронизации ----------

    fun meta(deviceId: String): EnergyMeta? = readableDatabase.rawQuery(
        "SELECT device_id, name, code, mode, active_day, synced_until, total, last_error, updated_at " +
            "FROM energy_meta WHERE device_id = ?",
        arrayOf(deviceId),
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        EnergyMeta(
            deviceId = c.getString(0),
            name = c.getString(1),
            code = if (c.isNull(2)) null else c.getString(2),
            mode = if (c.isNull(3)) null else c.getString(3),
            activeDay = if (c.isNull(4)) null else LocalDate.parse(c.getString(4)),
            syncedUntil = if (c.isNull(5)) null else LocalDate.parse(c.getString(5)),
            total = if (c.isNull(6)) null else c.getDouble(6),
            lastError = if (c.isNull(7)) null else c.getString(7),
            updatedAt = c.getLong(8),
        )
    }

    fun saveMeta(m: EnergyMeta) {
        val values = ContentValues().apply {
            put("device_id", m.deviceId)
            put("name", m.name)
            if (m.code == null) putNull("code") else put("code", m.code)
            if (m.mode == null) putNull("mode") else put("mode", m.mode)
            if (m.activeDay == null) putNull("active_day") else put("active_day", m.activeDay.toString())
            if (m.syncedUntil == null) putNull("synced_until") else put("synced_until", m.syncedUntil.toString())
            if (m.total == null) putNull("total") else put("total", m.total)
            if (m.lastError == null) putNull("last_error") else put("last_error", m.lastError)
            put("updated_at", m.updatedAt)
        }
        writableDatabase.insertWithOnConflict("energy_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val SOURCE_STATS = "stats"
        const val SOURCE_LOGS = "logs"
    }
}
