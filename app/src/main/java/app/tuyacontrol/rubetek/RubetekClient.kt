package app.tuyacontrol.rubetek

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RubetekException(val status: Int, message: String) : Exception(message)

/** Refresh token Rubetek и логин (телефон или почта) — только на телефоне, зашифрованно. */
class RubetekStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = try {
        create()
    } catch (e: Exception) {
        AppLog.e("Хранилище Rubetek повреждено, создаю заново", e)
        app.deleteSharedPreferences(FILE)
        create()
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        app,
        FILE,
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    var login: String?
        get() = prefs.getString("login", null)
        set(v) = prefs.edit().putString("login", v).apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val FILE = "rubetek_secure"
    }
}

/**
 * Облако мобильного приложения Rubetek (неофициальное API, по образцу github.com/regenara/rubetek_socket_api).
 * Вход: код по SMS/почте -> токен IoT -> токен основного API; дальше живём на refresh token.
 */
class RubetekClient(private val store: RubetekStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val tokenMutex = Mutex()
    private var accessToken: String? = null

    val connected: Boolean get() = store.refreshToken != null

    /**
     * Шаг 1: попросить код. Логин с «@» — почта (код в письме), иначе телефон.
     * Приложение Rubetek для телефона использует звонок: код — последние 4 цифры номера, который звонит.
     * Точное название этого способа в API неизвестно, поэтому пробуем варианты по очереди, в конце — SMS.
     * Возвращает описание способа для пользователя.
     */
    suspend fun sendCode(login: String): String {
        if (isEmail(login)) {
            AppLog.i("Rubetek: запрашиваю код на почту")
            requestCode(JSONObject().put("length", 6).put("method", "email").put("email", login))
            return "email"
        }
        val phone = normalizePhone(login)
        var last: Exception? = null
        for ((method, length) in PHONE_METHODS) {
            try {
                AppLog.i("Rubetek: запрашиваю код, способ «$method»")
                requestCode(JSONObject().put("length", length).put("method", method).put("phone", phone))
                AppLog.i("Rubetek: код запрошен способом «$method»")
                return method
            } catch (e: RubetekException) {
                // 4xx — способ не подошёл, пробуем следующий; 429 и 5xx — дальше не пробуем
                if (e.status == 429 || e.status >= 500) throw e
                AppLog.i("Rubetek: способ «$method» не принят (${e.status} ${e.message})")
                last = e
            }
        }
        throw last ?: RubetekException(-1, "Rubetek не принял запрос кода")
    }

    private suspend fun requestCode(req: JSONObject) {
        call(
            "POST", "$IOT/api/v1/code_requests",
            body = JSONObject().put("code_request", req),
            headers = mapOf("User-Agent" to "okhttp/4.12.0", "Cookie" to "locale=ru"),
            auth = false,
        )
    }

    /** Шаг 2: код -> токены. Refresh token сохраняется в зашифрованном хранилище. */
    suspend fun signIn(login: String, code: String) {
        val url = "$IOT/oauth/token".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("client_secret", CLIENT_SECRET)
            .addQueryParameter("grant_type", "password")
            .addQueryParameter("code", code.trim())
            .apply {
                if (isEmail(login)) addQueryParameter("email", login) else addQueryParameter("phone", normalizePhone(login))
            }
            .build().toString()
        AppLog.i("Rubetek: вход по коду")
        val iot = call("POST", url, body = JSONObject(), auth = false, logUrl = "$IOT/oauth/token") as JSONObject
        val main = call(
            "POST", "$CCC/v5/oauth/iot",
            body = JSONObject().put("client_id", "rubetek_android").put("token", "Bearer " + iot.getString("access_token")),
            auth = false,
        ) as JSONObject
        tokenMutex.withLock {
            accessToken = main.getString("access_token")
            store.refreshToken = main.getString("refresh_token")
            store.login = login
        }
        AppLog.i("Rubetek: подключено")
    }

    fun signOut() {
        accessToken = null
        store.clear()
    }

    /** Дома: [{id, name, …}]. */
    suspend fun houses(): List<JSONObject> {
        val r = get("$CCC/v6/houses?per_page=1000&include_deleted=true")
        val arr = when (r) {
            is JSONArray -> r
            is JSONObject -> r.optJSONArray("houses") ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Устройства дома. */
    suspend fun devices(houseId: String): List<JSONObject> {
        val r = get("$CCC/v6/houses/$houseId/devices?per_page=500&include_deleted=true") as? JSONObject ?: return emptyList()
        val arr = r.optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Изменить состояние: {"relay:on[0]": true}. */
    suspend fun setState(houseId: String, deviceId: String, state: Map<String, Any>) {
        val s = JSONObject()
        state.forEach { (k, v) -> s.put(k, v) }
        authed("PATCH", "$CCC/v5/houses/$houseId/devices/$deviceId/state", JSONObject().put("state", s))
    }

    // ---------- HTTP ----------

    private suspend fun get(url: String): Any? = authed("GET", url, null)

    /** Запрос с токеном; при 401 обновляем токен и повторяем один раз. */
    private suspend fun authed(method: String, url: String, body: JSONObject?): Any? {
        val token = ensureToken()
        return try {
            call(method, url, body, headers = mapOf("Authorization" to "Bearer $token"))
        } catch (e: RubetekException) {
            if (e.status != 401) throw e
            AppLog.i("Rubetek: токен устарел, обновляю")
            tokenMutex.withLock { accessToken = null }
            call(method, url, body, headers = mapOf("Authorization" to "Bearer ${ensureToken()}"))
        }
    }

    private suspend fun ensureToken(): String = tokenMutex.withLock {
        accessToken?.let { return@withLock it }
        val refresh = store.refreshToken ?: throw RubetekException(401, "Rubetek не подключён")
        val r = call(
            "POST", "$CCC/v5/oauth/access_token",
            body = JSONObject()
                .put("client_id", "rubetek_android")
                .put("grant_type", "refresh_token")
                .put("refresh_token", refresh),
            auth = false,
        ) as? JSONObject ?: throw RubetekException(-1, "Пустой ответ при обновлении токена")
        val token = r.getString("access_token")
        accessToken = token
        // Сервер может выдать новый refresh token — сохраняем, иначе через время вход «слетит»
        r.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refreshToken = it }
        token
    }

    private suspend fun call(
        method: String,
        url: String,
        body: JSONObject?,
        headers: Map<String, String> = emptyMap(),
        @Suppress("UNUSED_PARAMETER") auth: Boolean = true,
        logUrl: String = url,
    ): Any? = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        val text = body?.toString()
        val rb = (text ?: "").toRequestBody(JSON)
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post(rb)
            "PATCH" -> b.patch(rb)
            else -> throw IllegalArgumentException(method)
        }
        AppLog.i("Rubetek → $method $logUrl")
        http.newCall(b.build()).execute().use { r ->
            val resp = r.body?.string().orEmpty()
            AppLog.i("Rubetek ← ${r.code} ${AppLog.mask(resp).take(400)}")
            if (r.code !in 200..299) {
                val msg = runCatching {
                    val o = JSONObject(resp)
                    o.optString("error_description").ifEmpty { o.optString("error") }.ifEmpty { o.optString("message") }
                }.getOrNull().orEmpty()
                throw RubetekException(r.code, msg.ifEmpty { "HTTP ${r.code}" })
            }
            val t = resp.trim()
            when {
                t.isEmpty() -> null
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t)
                else -> null
            }
        }
    }

    companion object {
        private const val IOT = "https://iot.rubetek.com"
        private const val CCC = "https://ccc.rubetek.com"
        // Ключи приложения Rubetek для Android (из открытой библиотеки rubetek_socket_api), не пользовательские
        private const val CLIENT_ID = "ckvfvkClm2IdPrkSlvWSe3KiEWJOAbyKOQR5giCYYAo"
        private const val CLIENT_SECRET = "_TiXiy8xkVmVEpTBoYndqvyYbldXFs00wBtgLNmSOCE"
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        /** Способ доставки кода на телефон и длина кода: сначала звонок (как в приложении Rubetek), потом SMS. */
        private val PHONE_METHODS = listOf("call" to 4, "flash_call" to 4, "flashcall" to 4, "voice" to 4, "sms" to 6)

        fun isEmail(login: String) = "@" in login

        /** 8 999 … / 9 99… -> +7999…; остальное оставляем как есть, только цифры и «+». */
        fun normalizePhone(login: String): String {
            val digits = login.filter { it.isDigit() }
            return when {
                login.trim().startsWith("+") -> "+$digits"
                digits.length == 11 && (digits.startsWith("8") || digits.startsWith("7")) -> "+7" + digits.drop(1)
                digits.length == 10 -> "+7$digits"
                else -> "+$digits"
            }
        }
    }
}
