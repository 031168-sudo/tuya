package app.tuyacontrol.energy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Суточное потребление одного устройства. */
data class DayEnergy(
    val deviceId: String,
    val day: LocalDate,
    val kwh: Double,
    val source: String,
    /** 0 — почасовые данные ещё не запрашивались, 1 — есть, 2 — Tuya их не отдаёт. */
    val hourly: Int = HOURLY_UNKNOWN,
) {
    companion object {
        const val HOURLY_UNKNOWN = 0
        const val HOURLY_PRESENT = 1
        const val HOURLY_NONE = 2
    }
}

/**
 * Зона тарифа: цена и 1–2 интервала часов [from, to). to = 24 — полночь.
 * Интервал может переходить через полночь: 23–7.
 */
data class TariffZone(
    val price: Double,
    val from1: Int,
    val to1: Int,
    val from2: Int? = null,
    val to2: Int? = null,
) {
    fun covers(hour: Int): Boolean =
        inRange(hour, from1, to1) || (from2 != null && to2 != null && inRange(hour, from2, to2))

    fun intervalsText(): String {
        val first = "${hh(from1)}–${hh(to1)}"
        return if (from2 != null && to2 != null) "$first, ${hh(from2)}–${hh(to2)}" else first
    }

    companion object {
        fun inRange(h: Int, from: Int, to: Int): Boolean = when {
            from < to -> h in from until to
            from > to -> h >= from || h < to
            else -> false
        }

        private fun hh(h: Int) = h.toString().padStart(2, '0')
    }
}

/**
 * Тариф на период [start, end] включительно (end = null — действует по сей день).
 * deviceId = null — для всех счётчиков; тариф конкретного счётчика важнее общего.
 * zones — до трёх зон (Т1, Т2, Т3) по времени суток.
 */
data class Tariff(
    val id: Long = 0,
    val deviceId: String?,
    val start: LocalDate,
    val end: LocalDate?,
    val zones: List<TariffZone>,
    val note: String = "",
) {
    fun covers(day: LocalDate): Boolean = !day.isBefore(start) && (end == null || !day.isAfter(end))

    /** Номер зоны (0..2) для часа или null, если час не покрыт ни одной зоной. */
    fun zoneAt(hour: Int): Int? = zones.indexOfFirst { it.covers(hour) }.takeIf { it >= 0 }
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
    /** До какого дня (включительно) загружена суточная статистика. */
    val syncedUntil: LocalDate?,
    /** Итог за всё время по данным Tuya, кВт·ч. */
    val total: Double?,
    val lastError: String?,
    val updatedAt: Long,
    /** Дни раньше этой даты Tuya почасово не отдаёт. */
    val hourlyFloor: LocalDate? = null,
)

class EnergyDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "energy.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE energy_daily (" +
                "device_id TEXT NOT NULL, day TEXT NOT NULL, kwh REAL NOT NULL, source TEXT NOT NULL, " +
                "hourly INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (device_id, day))",
        )
        db.execSQL(
            "CREATE TABLE tariffs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT, start_day TEXT NOT NULL, " +
                "end_day TEXT, price REAL NOT NULL, note TEXT NOT NULL DEFAULT '', zones TEXT)",
        )
        db.execSQL(
            "CREATE TABLE energy_meta (" +
                "device_id TEXT PRIMARY KEY, name TEXT NOT NULL, code TEXT, mode TEXT, active_day TEXT, " +
                "synced_until TEXT, total REAL, last_error TEXT, updated_at INTEGER NOT NULL DEFAULT 0, " +
                "hourly_floor TEXT)",
        )
        createHourly(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE energy_daily ADD COLUMN hourly INTEGER NOT NULL DEFAULT 0")
            // Старые тарифы (одна цена) превращаются в одну зону на весь день
            db.execSQL("ALTER TABLE tariffs ADD COLUMN zones TEXT")
            db.execSQL("ALTER TABLE energy_meta ADD COLUMN hourly_floor TEXT")
            createHourly(db)
        }
    }

    private fun createHourly(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE energy_hourly (" +
                "device_id TEXT NOT NULL, day TEXT NOT NULL, hour INTEGER NOT NULL, kwh REAL NOT NULL, " +
                "PRIMARY KEY (device_id, day, hour))",
        )
    }

    // ---------- Суточная энергия ----------

    /**
     * Сохраняет суточные значения. Данные статистики Tuya ("stats") точнее журналов ("logs"),
     * поэтому журналы их не перезаписывают. Если значение дня изменилось, почасовые данные
     * этого дня помечаются к повторной загрузке.
     */
    fun upsertDays(deviceId: String, days: Map<LocalDate, Double>, source: String) {
        if (days.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((day, kwh) in days) {
                val existing = db.rawQuery(
                    "SELECT source, kwh, hourly FROM energy_daily WHERE device_id = ? AND day = ?",
                    arrayOf(deviceId, day.toString()),
                ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getDouble(1), c.getInt(2)) else null }
                if (source != SOURCE_STATS && existing?.first == SOURCE_STATS) continue
                val hourly = if (existing != null && existing.second == kwh) existing.third else DayEnergy.HOURLY_UNKNOWN
                val values = ContentValues().apply {
                    put("device_id", deviceId)
                    put("day", day.toString())
                    put("kwh", kwh)
                    put("source", source)
                    put("hourly", hourly)
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
            "SELECT device_id, day, kwh, source, hourly FROM energy_daily " +
                "WHERE device_id IN ($placeholders) AND day >= ? AND day <= ? ORDER BY day",
            args.toTypedArray(),
        ).use { c ->
            val list = mutableListOf<DayEnergy>()
            while (c.moveToNext()) {
                list += DayEnergy(c.getString(0), LocalDate.parse(c.getString(1)), c.getDouble(2), c.getString(3), c.getInt(4))
            }
            list
        }
    }

    /** Дни с потреблением, для которых ещё не запрашивали почасовые данные (новые — первыми). */
    fun daysNeedingHourly(deviceId: String, floor: LocalDate?): List<LocalDate> = readableDatabase.rawQuery(
        "SELECT day FROM energy_daily WHERE device_id = ? AND hourly = 0 AND kwh > 0 AND day >= ? ORDER BY day DESC",
        arrayOf(deviceId, floor?.toString() ?: "0000-01-01"),
    ).use { c ->
        val list = mutableListOf<LocalDate>()
        while (c.moveToNext()) list += LocalDate.parse(c.getString(0))
        list
    }

    fun setHourlyState(deviceId: String, days: Collection<LocalDate>, state: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (day in days) {
                db.execSQL(
                    "UPDATE energy_daily SET hourly = ? WHERE device_id = ? AND day = ?",
                    arrayOf<Any>(state, deviceId, day.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Почасовая энергия ----------

    fun saveHours(deviceId: String, day: LocalDate, hours: Map<Int, Double>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("energy_hourly", "device_id = ? AND day = ?", arrayOf(deviceId, day.toString()))
            for ((hour, kwh) in hours) {
                val values = ContentValues().apply {
                    put("device_id", deviceId)
                    put("day", day.toString())
                    put("hour", hour)
                    put("kwh", kwh)
                }
                db.insert("energy_hourly", null, values)
            }
            db.execSQL(
                "UPDATE energy_daily SET hourly = ? WHERE device_id = ? AND day = ?",
                arrayOf<Any>(DayEnergy.HOURLY_PRESENT, deviceId, day.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** (устройство, день) -> 24 значения по часам. */
    fun getHours(deviceIds: Collection<String>, from: LocalDate, to: LocalDate): Map<Pair<String, LocalDate>, DoubleArray> {
        if (deviceIds.isEmpty()) return emptyMap()
        val placeholders = deviceIds.joinToString(",") { "?" }
        val args = deviceIds.toList() + listOf(from.toString(), to.toString())
        val map = HashMap<Pair<String, LocalDate>, DoubleArray>()
        readableDatabase.rawQuery(
            "SELECT device_id, day, hour, kwh FROM energy_hourly " +
                "WHERE device_id IN ($placeholders) AND day >= ? AND day <= ?",
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                val hour = c.getInt(2)
                if (hour !in 0..23) continue
                val arr = map.getOrPut(c.getString(0) to LocalDate.parse(c.getString(1))) { DoubleArray(24) }
                arr[hour] += c.getDouble(3)
            }
        }
        return map
    }

    /** Средний профиль потребления по часам для устройства (сумма за все дни с почасовыми данными). */
    fun hourlyProfile(deviceIds: Collection<String>): Map<String, DoubleArray> {
        if (deviceIds.isEmpty()) return emptyMap()
        val placeholders = deviceIds.joinToString(",") { "?" }
        val map = HashMap<String, DoubleArray>()
        readableDatabase.rawQuery(
            "SELECT device_id, hour, SUM(kwh) FROM energy_hourly WHERE device_id IN ($placeholders) GROUP BY device_id, hour",
            deviceIds.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                val hour = c.getInt(1)
                if (hour !in 0..23) continue
                map.getOrPut(c.getString(0)) { DoubleArray(24) }[hour] = c.getDouble(2)
            }
        }
        return map
    }

    // ---------- Тарифы ----------

    fun tariffs(): List<Tariff> = readableDatabase.rawQuery(
        "SELECT id, device_id, start_day, end_day, price, note, zones FROM tariffs ORDER BY start_day DESC, id DESC",
        null,
    ).use { c ->
        val list = mutableListOf<Tariff>()
        while (c.moveToNext()) {
            val price = c.getDouble(4)
            val zones = if (c.isNull(6)) null else parseZones(c.getString(6))
            list += Tariff(
                id = c.getLong(0),
                deviceId = if (c.isNull(1)) null else c.getString(1),
                start = LocalDate.parse(c.getString(2)),
                end = if (c.isNull(3)) null else LocalDate.parse(c.getString(3)),
                zones = zones?.takeIf { it.isNotEmpty() } ?: listOf(TariffZone(price, 0, 24)),
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
            put("price", t.zones.firstOrNull()?.price ?: 0.0)
            put("note", t.note)
            put("zones", zonesJson(t.zones))
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

    private fun zonesJson(zones: List<TariffZone>): String {
        val arr = JSONArray()
        zones.forEach { z ->
            val o = JSONObject().put("price", z.price).put("from1", z.from1).put("to1", z.to1)
            if (z.from2 != null && z.to2 != null) o.put("from2", z.from2).put("to2", z.to2)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun parseZones(text: String): List<TariffZone> = try {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TariffZone(
                price = o.optDouble("price", 0.0),
                from1 = o.optInt("from1", 0),
                to1 = o.optInt("to1", 24),
                from2 = if (o.has("from2")) o.optInt("from2") else null,
                to2 = if (o.has("to2")) o.optInt("to2") else null,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- Состояние синхронизации ----------

    fun meta(deviceId: String): EnergyMeta? = readableDatabase.rawQuery(
        "SELECT device_id, name, code, mode, active_day, synced_until, total, last_error, updated_at, hourly_floor " +
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
            hourlyFloor = if (c.isNull(9)) null else LocalDate.parse(c.getString(9)),
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
            if (m.hourlyFloor == null) putNull("hourly_floor") else put("hourly_floor", m.hourlyFloor.toString())
        }
        writableDatabase.insertWithOnConflict("energy_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val SOURCE_STATS = "stats"
        const val SOURCE_LOGS = "logs"
    }
}
