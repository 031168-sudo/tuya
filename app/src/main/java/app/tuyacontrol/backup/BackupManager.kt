package app.tuyacontrol.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.energy.EnergyDb
import app.tuyacontrol.energy.Tariff
import app.tuyacontrol.energy.TariffZone
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Файл копии зашифрован паролем, а пароль не дан или неверный. */
class BackupPasswordException(val wrong: Boolean) : Exception(if (wrong) "Неверный пароль" else "Файл зашифрован — нужен пароль")

/**
 * Резервная копия всего: ключи Tuya, вход Rubetek и Xiaomi, категории, комнаты, команды, зоны отопления, тарифы
 * и вся накопленная история — показания датчиков и энергия (облако хранит журнал лишь 7 дней, дальше история
 * есть только на телефоне).
 *
 * Файл — ZIP: settings.json (настройки и тарифы) и db/<база>/<таблица>.jsonl (история, по строке на запись).
 * По желанию шифруется паролем (AES-256-GCM, ключ из PBKDF2). Без пароля ключи лежат в нём открытым текстом —
 * файл надо хранить так же бережно, как сами ключи. Копии прежнего формата (один JSON) тоже читаются.
 */
object BackupManager {

    private const val FORMAT = 1
    private const val MARK = "moydom_backup"

    /** Обычные хранилища настроек. */
    private val PLAIN = listOf("settings", "background_sync", "heating", "categories", "house_rooms", "commands")

    /** Зашифрованные хранилища: ключи, вход в аккаунты. */
    private val SECURE = listOf("tuya_secure", "rubetek_secure", "xiaomi_secure")

    private fun securePrefs(context: Context, name: String) = EncryptedSharedPreferences.create(
        context.applicationContext, name,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun prefsOf(context: Context, name: String) =
        if (name in SECURE) securePrefs(context, name) else context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ---------- Снимок ----------

    /** Всё, что сохраняем. Порядок ключей фиксирован, чтобы одинаковые данные давали одинаковый файл. */
    fun snapshot(context: Context): JSONObject {
        val root = JSONObject().put(MARK, FORMAT)
        val prefs = JSONObject()
        for (name in PLAIN + SECURE) {
            val p = runCatching { prefsOf(context, name) }
                .onFailure { AppLog.e("Копия: хранилище «$name» не прочитано", it) }
                .getOrNull() ?: continue
            val o = JSONObject()
            TreeMap(p.all).forEach { (k, v) ->
                val e = JSONObject()
                when (v) {
                    is String -> e.put("t", "s").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }.sorted()))
                    else -> return@forEach
                }
                o.put(k, e)
            }
            prefs.put(name, o)
        }
        root.put("prefs", prefs)

        val tariffs = JSONArray()
        runCatching { EnergyDb(context).tariffs() }.getOrDefault(emptyList()).sortedBy { it.id }.forEach { t ->
            tariffs.put(
                JSONObject().put("device", t.deviceId ?: JSONObject.NULL).put("start", t.start.toString())
                    .put("end", t.end?.toString() ?: JSONObject.NULL).put("note", t.note)
                    .put(
                        "zones",
                        JSONArray(t.zones.map { z ->
                            JSONObject().put("price", z.price).put("from1", z.from1).put("to1", z.to1)
                                .put("from2", z.from2 ?: JSONObject.NULL).put("to2", z.to2 ?: JSONObject.NULL)
                        }),
                    ),
            )
        }
        root.put("tariffs", tariffs)
        return root
    }

    /** Сколько записей в снимке — для сообщения «сохранено». */
    fun summary(snapshot: JSONObject): String {
        val prefs = snapshot.optJSONObject("prefs")
        val n = (prefs?.length() ?: 0)
        val t = snapshot.optJSONArray("tariffs")?.length() ?: 0
        return "$n хранилищ настроек, тарифов: $t"
    }

    // ---------- История (базы данных) ----------

    /** Базы с историей и их таблицы (тарифы живут в settings.json, поэтому их тут нет). */
    private val DATA = linkedMapOf(
        "sensor.db" to listOf("readings", "sensor_meta"),
        "energy.db" to listOf("energy_daily", "energy_hourly", "energy_meta"),
    )

    private fun openHelper(context: Context, db: String): android.database.sqlite.SQLiteOpenHelper = when (db) {
        "sensor.db" -> app.tuyacontrol.sensor.SensorDb(context)
        else -> EnergyDb(context)
    }

    /** Дешёвая «отпечаток» баз: изменился ли файл с прошлой копии. */
    private fun dataPrint(context: Context): String = DATA.keys.joinToString("|") { name ->
        val f = context.getDatabasePath(name)
        val w = File(f.path + "-wal")
        "${f.length()}:${f.lastModified()}:${w.length()}:${w.lastModified()}"
    }

    /** Выгрузить таблицы истории в zip-файл (по строке JSON-массива на запись; первая строка — имена столбцов). */
    private fun dumpTables(context: Context, target: File) {
        val tmp = File(target.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            for ((dbName, tables) in DATA) {
                if (!context.getDatabasePath(dbName).exists()) continue
                val helper = openHelper(context, dbName)
                try {
                    val db = helper.readableDatabase
                    for (t in tables) {
                        zip.putNextEntry(ZipEntry("db/$dbName/$t.jsonl"))
                        val w = zip.bufferedWriter(Charsets.UTF_8)
                        db.rawQuery("SELECT * FROM $t", null).use { c ->
                            w.write(JSONArray(c.columnNames.toList()).toString()); w.newLine()
                            while (c.moveToNext()) {
                                val a = JSONArray()
                                for (k in 0 until c.columnCount) {
                                    when (c.getType(k)) {
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> a.put(c.getLong(k))
                                        android.database.Cursor.FIELD_TYPE_FLOAT -> a.put(c.getDouble(k))
                                        android.database.Cursor.FIELD_TYPE_STRING -> a.put(c.getString(k))
                                        else -> a.put(JSONObject.NULL)
                                    }
                                }
                                w.write(a.toString()); w.newLine()
                            }
                        }
                        w.flush()
                        zip.closeEntry()
                    }
                } finally {
                    helper.close()
                }
            }
        }
        tmp.renameTo(target)
    }

    /** Загрузить выгруженную таблицу обратно (вместо текущей, в одной транзакции). */
    private fun loadTable(context: Context, dbName: String, table: String, reader: BufferedReader, helpers: MutableMap<String, android.database.sqlite.SQLiteOpenHelper>): Int {
        val helper = helpers.getOrPut(dbName) { openHelper(context, dbName) }
        val db = helper.writableDatabase
        val cols = JSONArray(reader.readLine() ?: return 0).let { a -> (0 until a.length()).map { a.getString(it) } }
        var n = 0
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $table")
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO $table (${cols.joinToString { "\"$it\"" }}) VALUES (${cols.joinToString { "?" }})",
            )
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val a = JSONArray(line)
                stmt.clearBindings()
                for (k in cols.indices) {
                    if (a.isNull(k)) continue
                    when (val v = a.get(k)) {
                        is Int -> stmt.bindLong(k + 1, v.toLong())
                        is Long -> stmt.bindLong(k + 1, v)
                        is Double -> stmt.bindDouble(k + 1, v)
                        else -> stmt.bindString(k + 1, v.toString())
                    }
                }
                stmt.executeInsert()
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** Собрать файл копии: настройки + история (из кэша выгрузки). */
    private fun buildPayload(snapshot: JSONObject, tables: File?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(snapshot.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (tables != null && tables.exists()) {
                ZipInputStream(tables.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        zip.putNextEntry(ZipEntry(e.name))
                        zin.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
        }
        return out.toByteArray()
    }

    private fun isZip(b: ByteArray) = b.size > 3 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** Настройки из файла копии (нового формата — zip, прежнего — просто JSON). */
    fun snapshotOf(payload: ByteArray): JSONObject {
        if (!isZip(payload)) return JSONObject(String(payload, Charsets.UTF_8))
        ZipInputStream(ByteArrayInputStream(payload)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "settings.json") return JSONObject(String(z.readBytes(), Charsets.UTF_8))
            }
        }
        throw IllegalArgumentException("В копии нет настроек")
    }

    /** Что в файле копии — для вопроса перед восстановлением. */
    fun describe(payload: ByteArray): String {
        val base = summary(snapshotOf(payload))
        if (!isZip(payload)) return "$base; без истории"
        var tables = 0
        ZipInputStream(ByteArrayInputStream(payload)).use { z -> while (z.nextEntry != null) tables++ }
        return if (tables > 1) "$base, с историей показаний и энергии" else "$base; без истории"
    }

    // ---------- Шифрование ----------

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded, "AES")
    }

    /** Содержимое копии -> байты файла: без пароля как есть (zip), с паролем — зашифрованная обёртка. */
    fun encode(payload: ByteArray, password: String?): ByteArray {
        if (password.isNullOrEmpty()) return payload
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val iterations = 150_000
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        val b64 = Base64.getEncoder()
        return JSONObject().put(MARK, FORMAT).put("enc", "AES-256-GCM").put("kdf", "PBKDF2-SHA256").put("iter", iterations)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(c.doFinal(payload))).toString(2).toByteArray(Charsets.UTF_8)
    }

    /** Файл копии -> содержимое. Бросает [BackupPasswordException], если нужен пароль или он неверный. */
    fun decode(bytes: ByteArray, password: String?): ByteArray {
        if (isZip(bytes)) return bytes
        val o = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("Это не файл резервной копии «Мой дом»")
        }
        if (!o.has(MARK)) throw IllegalArgumentException("Это не файл резервной копии «Мой дом»")
        if (!o.has("enc")) return bytes
        if (password.isNullOrEmpty()) throw BackupPasswordException(false)
        return try {
            val b64 = Base64.getDecoder()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE,
                key(password, b64.decode(o.getString("salt")), o.getInt("iter")),
                GCMParameterSpec(128, b64.decode(o.getString("iv"))),
            )
            c.doFinal(b64.decode(o.getString("data")))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw BackupPasswordException(true)
        }
    }

    // ---------- Восстановление ----------

    /**
     * Записать снимок поверх текущих данных: в каждом хранилище, которое есть в копии, всё заменяется.
     * После этого приложение надо перезапустить ([restartApp]).
     */
    fun restore(context: Context, payload: ByteArray): String {
        val snapshot = snapshotOf(payload)
        val prefs = snapshot.optJSONObject("prefs") ?: throw IllegalArgumentException("В копии нет настроек")
        var n = 0
        for (name in PLAIN + SECURE) {
            val o = prefs.optJSONObject(name) ?: continue
            val p = prefsOf(context, name)
            val e = p.edit().clear()
            o.keys().forEach { k ->
                val x = o.getJSONObject(k)
                when (x.getString("t")) {
                    "s" -> e.putString(k, x.getString("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "set" -> e.putStringSet(k, x.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                }
            }
            e.commit()
            n++
        }
        val arr = snapshot.optJSONArray("tariffs")
        if (arr != null) {
            val db = EnergyDb(context)
            db.tariffs().forEach { db.deleteTariff(it.id) }
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val zones = t.getJSONArray("zones").let { z ->
                    (0 until z.length()).map { j ->
                        val q = z.getJSONObject(j)
                        TariffZone(
                            q.getDouble("price"), q.getInt("from1"), q.getInt("to1"),
                            if (q.isNull("from2")) null else q.getInt("from2"),
                            if (q.isNull("to2")) null else q.getInt("to2"),
                        )
                    }
                }
                db.saveTariff(
                    Tariff(
                        deviceId = if (t.isNull("device")) null else t.getString("device"),
                        start = LocalDate.parse(t.getString("start")),
                        end = if (t.isNull("end")) null else LocalDate.parse(t.getString("end")),
                        zones = zones,
                        note = t.optString("note"),
                    ),
                )
            }
        }
        // История показаний и энергии
        var rows = 0
        if (isZip(payload)) {
            val helpers = mutableMapOf<String, android.database.sqlite.SQLiteOpenHelper>()
            try {
                ZipInputStream(ByteArrayInputStream(payload)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        // По читателю на запись: ZipInputStream сам заканчивает чтение на границе записи
                        val reader = BufferedReader(InputStreamReader(z, Charsets.UTF_8))
                        val m = Regex("db/([^/]+)/([^/]+)\\.jsonl").matchEntire(e.name) ?: continue
                        val (dbName, table) = m.destructured
                        // Только свои базы и таблицы: имя из файла в SQL не подставляем
                        if (DATA[dbName]?.contains(table) != true) continue
                        rows += runCatching { loadTable(context, dbName, table, reader, helpers) }
                            .onFailure { AppLog.e("Копия: таблица $dbName/$table не восстановлена", it) }
                            .getOrDefault(0)
                    }
                }
            } finally {
                helpers.values.forEach { it.close() }
            }
        }
        AppLog.i("Копия восстановлена: хранилищ $n, тарифов ${arr?.length() ?: 0}, записей истории $rows")
        return "Восстановлено: ${summary(snapshot)}" + if (rows > 0) ", записей истории: $rows" else ""
    }

    /** Перезапуск приложения — чтобы все экраны прочитали восстановленные данные. */
    fun restartApp(context: Context) {
        val app = context.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------- Файл копии ----------

    /** В снимке есть ключи Tuya — то есть приложение уже настроено. */
    private fun hasKeys(snapshot: JSONObject): Boolean =
        snapshot.optJSONObject("prefs")?.optJSONObject("tuya_secure")?.has("access_id") == true

    fun hash(snapshot: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest(snapshot.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    fun writeTo(context: Context, uri: Uri, bytes: ByteArray) {
        // "wt" — перезаписать файл целиком
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Файл недоступен")
        out.use { it.write(bytes) }
    }

    fun readFrom(context: Context, uri: Uri): ByteArray =
        (context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Файл недоступен")).use { it.readBytes() }

    // ---------- Настройки автокопии ----------

    /** Какой файл обновляем сам, когда было и чем кончилось. Пароль хранится зашифрованно и в копию не попадает. */
    class Config(context: Context) {
        private val app = context.applicationContext
        private val prefs = app.getSharedPreferences("backup_cfg", Context.MODE_PRIVATE)
        private val secure by lazy {
            runCatching { securePrefs(app, "backup_secure") }.getOrElse {
                app.deleteSharedPreferences("backup_secure")
                securePrefs(app, "backup_secure")
            }
        }

        var uri: String?
            get() = prefs.getString("uri", null)
            set(v) = prefs.edit().putString("uri", v).apply()
        var lastAt: Long
            get() = prefs.getLong("last_at", 0)
            set(v) = prefs.edit().putLong("last_at", v).apply()
        var lastHash: String?
            get() = prefs.getString("last_hash", null)
            set(v) = prefs.edit().putString("last_hash", v).apply()
        var lastError: String?
            get() = prefs.getString("last_error", null)
            set(v) = prefs.edit().putString("last_error", v).apply()
        /** Когда выгружалась история (таблицы) и какой у баз был «отпечаток». */
        var lastDbAt: Long
            get() = prefs.getLong("last_db_at", 0)
            set(v) = prefs.edit().putLong("last_db_at", v).apply()
        var lastDbPrint: String?
            get() = prefs.getString("last_db_print", null)
            set(v) = prefs.edit().putString("last_db_print", v).apply()
        var lastSize: Long
            get() = prefs.getLong("last_size", 0)
            set(v) = prefs.edit().putLong("last_size", v).apply()
        var password: String?
            get() = secure.getString("password", null)?.takeIf { it.isNotEmpty() }
            set(v) = secure.edit().putString("password", v ?: "").apply()
    }

    private const val DATA_EVERY_MS = 30 * 60_000L
    private const val BIG_BYTES = 2_000_000L
    private const val BIG_EVERY_MS = 10 * 60_000L

    private fun tablesCache(context: Context) = File(context.cacheDir, "backup_tables.zip")

    /** Файл копии для ручного сохранения: настройки и свежая выгрузка истории. */
    fun buildNow(context: Context): ByteArray {
        val cfg = Config(context)
        val cache = tablesCache(context)
        dumpTables(context, cache)
        cfg.lastDbPrint = dataPrint(context)
        cfg.lastDbAt = System.currentTimeMillis()
        return encode(buildPayload(snapshot(context), cache), cfg.password)
    }

    /**
     * Обновить файл копии, если он выбран и что-то изменилось. Вызывается само, пока приложение открыто.
     * Настройки проверяются каждую минуту, история (базы) выгружается не чаще раза в 30 минут и при уходе из
     * приложения ([flushData]). [force] — записать всё заново (кнопка «Сохранить сейчас»).
     */
    fun autoBackup(context: Context, force: Boolean = false, flushData: Boolean = false): String? {
        val cfg = Config(context)
        val uri = cfg.uri?.let(Uri::parse) ?: return null
        return try {
            val snap = snapshot(context)
            // Защита от потери копии: в приложении ещё нет ключей (чистая установка), а в файле уже что-то лежит —
            // не затираем, пока хозяин не восстановит копию
            if (!hasKeys(snap) && runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true }.getOrDefault(false)) {
                cfg.lastError = "В приложении пока нет ключей, а в файле уже есть копия — сначала восстановите её"
                return null
            }
            val now = System.currentTimeMillis()
            val cache = tablesCache(context)
            val print = dataPrint(context)
            val changed = print != cfg.lastDbPrint
            if (force || !cache.exists() || (changed && (flushData || now - cfg.lastDbAt >= DATA_EVERY_MS))) {
                dumpTables(context, cache)
                cfg.lastDbPrint = print
                cfg.lastDbAt = now
            }
            val h = hash(snap) + "|" + cfg.lastDbAt + (if (cfg.password != null) "p" else "")
            if (!force && h == cfg.lastHash && cfg.lastError == null) return null
            // Большой файл (годы истории) не переписываем чаще раза в 10 минут
            if (!force && !flushData && cache.length() > BIG_BYTES && cfg.lastAt > 0 && now - cfg.lastAt < BIG_EVERY_MS && cfg.lastError == null) return null
            val bytes = encode(buildPayload(snap, cache), cfg.password)
            writeTo(context, uri, bytes)
            cfg.lastHash = h
            cfg.lastAt = System.currentTimeMillis()
            cfg.lastSize = bytes.size.toLong()
            cfg.lastError = null
            AppLog.i("Резервная копия сохранена (${summary(snap)}, файл ${bytes.size / 1024} КБ)")
            "Копия сохранена"
        } catch (e: Exception) {
            cfg.lastError = e.message ?: e.javaClass.simpleName
            AppLog.e("Резервная копия не сохранена", e)
            null
        }
    }

    /** Запомнить за приложением право писать в выбранный файл (переживает перезапуск). */
    fun rememberUri(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { AppLog.e("Копия: право на файл не закреплено", it) }
    }
}
