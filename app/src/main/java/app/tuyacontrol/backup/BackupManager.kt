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
import java.security.MessageDigest
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
 * Резервная копия всего, что хозяин вводил руками: ключи Tuya, вход Rubetek и Xiaomi, категории, комнаты,
 * команды, зоны отопления, тарифы. История показаний не входит — она докачивается из облака.
 *
 * Файл — JSON; по желанию шифруется паролем (AES-256-GCM, ключ из PBKDF2). Без пароля ключи лежат в нём открытым
 * текстом — файл надо хранить так же бережно, как сами ключи.
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

    // ---------- Шифрование ----------

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded, "AES")
    }

    fun encode(snapshot: JSONObject, password: String?): ByteArray {
        if (password.isNullOrEmpty()) return snapshot.toString(2).toByteArray(Charsets.UTF_8)
        val plain = snapshot.toString().toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val iterations = 150_000
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        val b64 = Base64.getEncoder()
        return JSONObject().put(MARK, FORMAT).put("enc", "AES-256-GCM").put("kdf", "PBKDF2-SHA256").put("iter", iterations)
            .put("salt", b64.encodeToString(salt)).put("iv", b64.encodeToString(iv))
            .put("data", b64.encodeToString(c.doFinal(plain))).toString(2).toByteArray(Charsets.UTF_8)
    }

    /** Файл копии -> снимок. Бросает [BackupPasswordException], если нужен пароль или он неверный. */
    fun decode(bytes: ByteArray, password: String?): JSONObject {
        val o = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("Это не файл резервной копии «Мой дом»")
        }
        if (!o.has(MARK)) throw IllegalArgumentException("Это не файл резервной копии «Мой дом»")
        if (!o.has("enc")) return o
        if (password.isNullOrEmpty()) throw BackupPasswordException(false)
        return try {
            val b64 = Base64.getDecoder()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE,
                key(password, b64.decode(o.getString("salt")), o.getInt("iter")),
                GCMParameterSpec(128, b64.decode(o.getString("iv"))),
            )
            JSONObject(String(c.doFinal(b64.decode(o.getString("data"))), Charsets.UTF_8))
        } catch (e: javax.crypto.AEADBadTagException) {
            throw BackupPasswordException(true)
        }
    }

    // ---------- Восстановление ----------

    /**
     * Записать снимок поверх текущих данных: в каждом хранилище, которое есть в копии, всё заменяется.
     * После этого приложение надо перезапустить ([restartApp]).
     */
    fun restore(context: Context, snapshot: JSONObject): String {
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
        AppLog.i("Копия восстановлена: хранилищ $n, тарифов ${arr?.length() ?: 0}")
        return "Восстановлено: ${summary(snapshot)}"
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
        var password: String?
            get() = secure.getString("password", null)?.takeIf { it.isNotEmpty() }
            set(v) = secure.edit().putString("password", v ?: "").apply()
    }

    /**
     * Обновить файл копии, если он выбран и данные изменились с прошлого раза. Вызывается само, пока приложение
     * открыто. [force] — записать даже без изменений (кнопка «Сохранить сейчас»).
     */
    fun autoBackup(context: Context, force: Boolean = false): String? {
        val cfg = Config(context)
        val uri = cfg.uri?.let(Uri::parse) ?: return null
        return try {
            val snap = snapshot(context)
            val h = hash(snap) + (if (cfg.password != null) "p" else "")
            if (!force && h == cfg.lastHash && cfg.lastError == null) return null
            // Защита от потери копии: в приложении ещё нет ключей (чистая установка), а в файле уже что-то лежит —
            // не затираем, пока хозяин не восстановит копию
            if (!hasKeys(snap) && runCatching { readFrom(context, uri).isNotEmpty() }.getOrDefault(false)) {
                cfg.lastError = "В приложении пока нет ключей, а в файле уже есть копия — сначала восстановите её"
                return null
            }
            writeTo(context, uri, encode(snap, cfg.password))
            cfg.lastHash = h
            cfg.lastAt = System.currentTimeMillis()
            cfg.lastError = null
            AppLog.i("Резервная копия сохранена (${summary(snap)})")
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
