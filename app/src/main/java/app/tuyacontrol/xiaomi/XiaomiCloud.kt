package app.tuyacontrol.xiaomi

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class XiaomiException(message: String, val authExpired: Boolean = false) : Exception(message)

/** Устройство из облака Xiaomi: всё, что нужно для управления по Wi-Fi. */
data class MiDevice(
    val did: String,
    val name: String,
    val model: String,
    /** 32 hex-символа; пусто — у устройства нет Wi-Fi (Bluetooth, Zigbee). */
    val token: String,
    val ip: String,
    val mac: String,
    val online: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("did", did).put("name", name).put("model", model)
        .put("token", token).put("ip", ip).put("mac", mac).put("online", online)

    companion object {
        fun fromJson(o: JSONObject) = MiDevice(
            did = o.optString("did"), name = o.optString("name"), model = o.optString("model"),
            token = o.optString("token"), ip = o.optString("ip"), mac = o.optString("mac"),
            online = o.optBoolean("online", true),
        )
    }
}

/** Сессия Mi-аккаунта и список устройств (с token) — только на телефоне, зашифрованно. Пароль не хранится. */
class XiaomiStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = try {
        create()
    } catch (e: Exception) {
        AppLog.e("Хранилище Xiaomi повреждено, создаю заново", e)
        app.deleteSharedPreferences(FILE)
        create()
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        app, FILE,
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun str(key: String) = prefs.getString(key, null)
    private fun put(key: String, v: String?) = prefs.edit().putString(key, v).apply()

    var login: String?
        get() = str("login")
        set(v) = put("login", v)
    var country: String
        get() = str("country") ?: "ru"
        set(v) = put("country", v)
    var userId: String?
        get() = str("user_id")
        set(v) = put("user_id", v)
    var passToken: String?
        get() = str("pass_token")
        set(v) = put("pass_token", v)
    var ssecurity: String?
        get() = str("ssecurity")
        set(v) = put("ssecurity", v)
    var serviceToken: String?
        get() = str("service_token")
        set(v) = put("service_token", v)

    /** Постоянный «id телефона» для Xiaomi: с ним подтверждённый вход не просит код повторно. */
    val deviceId: String
        get() = str("device_id") ?: (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("").also { put("device_id", it) }

    val agent: String
        get() = str("agent") ?: run {
            val id = (1..13).map { ('A'..'E').random() }.joinToString("")
            val rnd = (1..18).map { ('a'..'z').random() }.joinToString("")
            "$rnd-$id APP/com.xiaomi.mihome APPV/10.5.201".also { put("agent", it) }
        }

    var devices: List<MiDevice>
        get() = runCatching {
            val a = JSONArray(str("devices") ?: "[]")
            (0 until a.length()).map { MiDevice.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) = put("devices", JSONArray().apply { v.forEach { put(it.toJson()) } }.toString())

    val connected: Boolean get() = userId != null && ssecurity != null

    fun clear() {
        val keepId = str("device_id")
        val keepAgent = str("agent")
        prefs.edit().clear().apply()
        keepId?.let { put("device_id", it) }
        keepAgent?.let { put("agent", it) }
    }

    private companion object {
        const val FILE = "xiaomi_secure"
    }
}

/** Что дальше при входе. */
sealed class MiLoginStep {
    object Done : MiLoginStep()
    /** Xiaomi просит ввести символы с картинки. */
    class Captcha(val image: ByteArray) : MiLoginStep()
    /** Нужно подтверждение входа: код отправлен на почту или телефон. */
    class Verify(val sentTo: String) : MiLoginStep()
    /** Подтвердить вход на странице Xiaomi: адрес и cookie (адрес сайта -> строка Set-Cookie). */
    class Browser(val url: String, val cookies: List<Pair<String, String>>) : MiLoginStep()
}

/**
 * Облако Mi Home (неофициальное API, по образцу github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor).
 * Нужно, чтобы получить список устройств с token и IP; дальше они управляются по Wi-Fi.
 * Если Wi-Fi не отвечает, свойства MIoT читаются и пишутся через облако.
 */
class XiaomiCloud(private val store: XiaomiStore) {

    private val cookies = mutableListOf<Cookie>()
    private val jar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(this@XiaomiCloud.cookies) {
            cookies.forEach { c ->
                this@XiaomiCloud.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                this@XiaomiCloud.cookies += c
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this@XiaomiCloud.cookies) {
            val now = System.currentTimeMillis()
            this@XiaomiCloud.cookies.removeAll { it.expiresAt < now }
            this@XiaomiCloud.cookies.filter { it.matches(url) }
        }
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val authMutex = Mutex()

    // Состояние незаконченного входа (между шагами «пароль» -> «капча» / «код»)
    private var pendingLogin: String? = null
    private var pendingHash: String? = null
    private var pendingSign: String? = null
    private var verifyContext: String? = null
    private var verifyFlag = 8

    val connected: Boolean get() = store.connected

    private fun cookie(domain: String, name: String, value: String) {
        jar.saveFromResponse(
            "https://$domain/".toHttpUrl(),
            listOf(Cookie.Builder().domain(domain).path("/").name(name).value(value).build()),
        )
    }

    private fun cookieValue(name: String): String? = synchronized(cookies) { cookies.lastOrNull { it.name == name }?.value }

    private fun baseCookies() {
        for (d in listOf("xiaomi.com", "mi.com")) {
            cookie(d, "sdkVersion", "accountsdk-18.8.15")
            cookie(d, "deviceId", store.deviceId)
        }
    }

    // ---------- Вход ----------

    /** Шаг 1–2: логин и пароль (и, если просили, символы с картинки). */
    suspend fun signIn(login: String, password: String?, country: String, captcha: String? = null): MiLoginStep = authMutex.withLock {
        store.country = country
        if (password != null) {
            pendingLogin = login.trim()
            pendingHash = MiCrypto.passwordHash(password)
        }
        val user = pendingLogin ?: throw XiaomiException("Введите логин и пароль")
        val hash = pendingHash ?: throw XiaomiException("Введите пароль")
        baseCookies()
        if (captcha == null || pendingSign == null) {
            cookie("account.xiaomi.com", "userId", user)
            val s1 = getJson("$ACCOUNT/pass/serviceLogin?sid=xiaomiio&_json=true")
            pendingSign = s1.str("_sign")
        }
        val url = "$ACCOUNT/pass/serviceLoginAuth2".toHttpUrl().newBuilder()
            .addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("hash", hash)
            .addQueryParameter("callback", "https://sts.api.io.mi.com/sts")
            .addQueryParameter("qs", "%3Fsid%3Dxiaomiio%26_json%3Dtrue")
            .addQueryParameter("user", user)
            .addQueryParameter("_sign", pendingSign.orEmpty())
            .addQueryParameter("_json", "true")
            .apply { if (captcha != null) addQueryParameter("captCode", captcha.trim()) }
            .build()
        AppLog.i("Xiaomi → вход ($user, сервер $country)")
        val r = exec(noRedirect, Request.Builder().url(url).post(FormBody.Builder().build()), logUrl = "$ACCOUNT/pass/serviceLoginAuth2")
        val o = parse(r.second)

        o.str("captchaUrl")?.let { path ->
            val cu = if (path.startsWith("/")) ACCOUNT + path else path
            AppLog.i("Xiaomi: просит капчу")
            val img = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(cu).header("User-Agent", store.agent).build()).execute().use { it.body?.bytes() }
            } ?: throw XiaomiException("Не удалось загрузить картинку с кодом")
            // Капча привязана к cookie «ick», она уже в нашем хранилище
            return@withLock MiLoginStep.Captcha(img)
        }
        o.str("notificationUrl")?.let { n ->
            AppLog.i("Xiaomi: нужно подтверждение входа")
            return@withLock startVerify(if (n.startsWith("/")) ACCOUNT + n else n)
        }
        val ssecurity = o.str("ssecurity")
        if (ssecurity == null) {
            val code = o.optInt("code", -1)
            throw XiaomiException(
                when (code) {
                    70016 -> "Неверный логин или пароль"
                    87001 -> "Неверные символы с картинки"
                    else -> "Xiaomi не пустил: " + (o.str("desc") ?: o.str("description") ?: "код $code")
                }
            )
        }
        finishLogin(ssecurity, o.str("userId") ?: o.opt("userId")?.toString(), o.str("passToken"), o.str("location"))
        MiLoginStep.Done
    }

    /**
     * Подтверждение входа делаем на странице самого Xiaomi (в приложении, во встроенном браузере): она сама
     * выбирает способ — письмо, SMS, капча. Браузеру передаём наши cookie (deviceId и сессию входа), чтобы
     * подтверждение относилось к этому телефону.
     */
    private fun startVerify(notificationUrl: String): MiLoginStep {
        verifyContext = notificationUrl.toHttpUrl().queryParameter("context")
        val list = synchronized(cookies) {
            cookies.filter { it.domain.endsWith("xiaomi.com") && it.name != "userId" }
                .map { "https://${it.domain.trimStart('.')}" to "${it.name}=${it.value}; Domain=${it.domain}; Path=${it.path}" }
        }
        return MiLoginStep.Browser(notificationUrl, list)
    }

    /** После подтверждения в браузере: cookie userId и passToken -> новая сессия без пароля. */
    suspend fun finishFromBrowser(userId: String, passToken: String) = authMutex.withLock {
        AppLog.i("Xiaomi: вход подтверждён в браузере")
        store.userId = userId
        store.passToken = passToken
        relogin()
        verifyContext = null
    }

    /** Код подтверждения из письма или SMS. */
    suspend fun verify(code: String): MiLoginStep = authMutex.withLock {
        val context = verifyContext ?: throw XiaomiException("Сначала войдите по паролю")
        val kind = if (verifyFlag == 8) "Email" else "Phone"
        val url = "$ACCOUNT/identity/auth/verify$kind".toHttpUrl().newBuilder()
            .addQueryParameter("_flag", verifyFlag.toString())
            .addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("context", context)
            .addQueryParameter("_dc", System.currentTimeMillis().toString())
            .build()
        val body = FormBody.Builder()
            .add("_flag", verifyFlag.toString()).add("ticket", code.trim()).add("trust", "true").add("_json", "true")
            .build()
        val o = parse(exec(http, Request.Builder().url(url).post(body)).second)
        if (o.optInt("code", -1) != 0) throw XiaomiException("Неверный код подтверждения (${o.str("desc") ?: o.optInt("code")})")
        var next = o.str("location") ?: throw XiaomiException("Xiaomi не вернул адрес продолжения")
        // Цепочка переадресаций: identity/result/check -> pass/serviceLoginAuth2/end (там ssecurity) -> sts
        var ssecurity: String? = null
        for (step in 0 until 8) {
            if (next.startsWith("/")) next = ACCOUNT + next
            AppLog.i("Xiaomi → GET ${next.substringBefore("?")}")
            val (status, loc, body, pragma) = withContext(Dispatchers.IO) {
                noRedirect.newCall(Request.Builder().url(next).header("User-Agent", store.agent).build()).execute().use { r ->
                    Quad(r.code, r.header("Location"), r.body?.string().orEmpty(), r.header("extension-pragma"))
                }
            }
            AppLog.i("Xiaomi ← $status")
            pragma?.let { ep -> runCatching { JSONObject(ep).str("ssecurity") }.getOrNull()?.let { ssecurity = it } }
            if (status !in 300..399 || loc == null) {
                if (ssecurity == null) runCatching { parse(body).str("ssecurity") }.getOrNull()?.let { ssecurity = it }
                break
            }
            next = loc
        }
        val sec = ssecurity ?: throw XiaomiException("Вход подтверждён, но Xiaomi не выдал ключ сессии. Попробуйте войти ещё раз")
        val uid = cookieValue("userId") ?: throw XiaomiException("Xiaomi не сообщил id пользователя")
        finishLogin(sec, uid, cookieValue("passToken"), null)
        verifyContext = null
        MiLoginStep.Done
    }

    private suspend fun finishLogin(ssecurity: String, userId: String?, passToken: String?, location: String?) {
        if (location != null) {
            exec(http, Request.Builder().url(location).get())
        }
        val service = cookieValue("serviceToken") ?: throw XiaomiException("Xiaomi не выдал serviceToken")
        store.ssecurity = ssecurity
        store.userId = userId ?: throw XiaomiException("Xiaomi не сообщил id пользователя")
        store.serviceToken = service
        passToken?.let { store.passToken = it }
        store.login = pendingLogin ?: store.login
        pendingHash = null
        pendingSign = null
        AppLog.i("Xiaomi: вход выполнен")
    }

    /** Новая сессия по сохранённому passToken (без пароля). */
    private suspend fun relogin() {
        val uid = store.userId
        val pass = store.passToken
        if (uid == null || pass == null) throw XiaomiException("Вход в Xiaomi устарел — войдите заново", authExpired = true)
        baseCookies()
        cookie("account.xiaomi.com", "userId", uid)
        cookie("account.xiaomi.com", "passToken", pass)
        val o = getJson("$ACCOUNT/pass/serviceLogin?sid=xiaomiio&_json=true")
        val sec = o.str("ssecurity") ?: throw XiaomiException("Вход в Xiaomi устарел — войдите заново", authExpired = true)
        finishLogin(sec, o.str("userId") ?: uid, o.str("passToken") ?: pass, o.str("location"))
    }

    fun signOut() {
        synchronized(cookies) { cookies.clear() }
        pendingLogin = null; pendingHash = null; pendingSign = null; verifyContext = null
        store.clear()
    }

    // ---------- Данные ----------

    /** Все устройства всех домов (свои и общие с вами). */
    suspend fun devices(): List<MiDevice> {
        val out = LinkedHashMap<String, MiDevice>()
        val homes = runCatching { api("/v2/homeroom/gethome", JSONObject().put("fg", true).put("fetch_share", true).put("fetch_share_dev", true).put("limit", 300).put("app_ver", 7)) }
            .onFailure { AppLog.e("Xiaomi: список домов", it) }
            .getOrNull() as? JSONObject
        val homeList = mutableListOf<Pair<Long, Long>>() // дом, владелец
        homes?.optJSONArray("homelist")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { h -> homeList += h.optLong("id") to (h.optLong("uid").takeIf { it > 0 } ?: store.userId!!.toLong()) }
        }
        homes?.optJSONArray("share_home_list")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { h -> homeList += h.optLong("id") to h.optLong("uid") }
        }
        for ((home, owner) in homeList) {
            val r = runCatching {
                api("/v2/home/home_device_list", JSONObject().put("home_owner", owner).put("home_id", home).put("limit", 200).put("get_split_device", true).put("support_smart_home", true))
            }.onFailure { AppLog.e("Xiaomi: устройства дома $home", it) }.getOrNull() as? JSONObject
            addDevices(r?.optJSONArray("device_info"), out)
        }
        if (out.isEmpty()) {
            // Старый способ — общий список устройств аккаунта
            val r = api("/home/device_list", JSONObject().put("getVirtualModel", false).put("getHuamiDevices", 0)) as? JSONObject
            addDevices(r?.optJSONArray("list"), out)
        }
        return out.values.toList()
    }

    private fun addDevices(a: JSONArray?, out: MutableMap<String, MiDevice>) {
        a ?: return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val did = o.optString("did")
            if (did.isEmpty()) continue
            out[did] = MiDevice(
                did = did,
                name = o.optString("name").ifEmpty { o.optString("model") },
                model = o.optString("model"),
                token = o.optString("token").takeIf { it.length == 32 }.orEmpty(),
                ip = o.optString("localip"),
                mac = o.optString("mac"),
                online = o.optBoolean("isOnline", true),
            )
        }
    }

    /** MIoT через облако: прочитать свойства. */
    suspend fun getProperties(did: String, props: List<Pair<Int, Int>>): Map<Pair<Int, Int>, Any?> {
        val arr = JSONArray()
        props.forEach { (si, pi) -> arr.put(JSONObject().put("did", did).put("siid", si).put("piid", pi)) }
        val r = api("/miotspec/prop/get", JSONObject().put("params", arr)) as? JSONArray ?: return emptyMap()
        val out = HashMap<Pair<Int, Int>, Any?>()
        for (i in 0 until r.length()) {
            val o = r.optJSONObject(i) ?: continue
            if (o.optInt("code", 0) != 0) continue
            out[o.optInt("siid") to o.optInt("piid")] = o.opt("value")
        }
        return out
    }

    suspend fun setProperty(did: String, siid: Int, piid: Int, value: Any) {
        val arr = JSONArray().put(JSONObject().put("did", did).put("siid", siid).put("piid", piid).put("value", value))
        val r = api("/miotspec/prop/set", JSONObject().put("params", arr)) as? JSONArray
        val code = r?.optJSONObject(0)?.optInt("code", 0) ?: 0
        if (code != 0) throw XiaomiException("облако Xiaomi: устройство не приняло значение (код $code)")
    }

    // ---------- HTTP ----------

    private fun apiUrl(path: String): String {
        val c = store.country
        return "https://" + (if (c == "cn") "" else "$c.") + "api.io.mi.com/app" + path
    }

    /** Зашифрованный вызов API; при истёкшей сессии — повторный вход по passToken и ещё одна попытка. */
    private suspend fun api(path: String, data: JSONObject): Any? = try {
        apiOnce(path, data)
    } catch (e: XiaomiException) {
        if (!e.authExpired) throw e
        AppLog.i("Xiaomi: сессия устарела, обновляю")
        authMutex.withLock { relogin() }
        apiOnce(path, data)
    }

    private suspend fun apiOnce(path: String, data: JSONObject): Any? = withContext(Dispatchers.IO) {
        val ssecurity = store.ssecurity ?: throw XiaomiException("Xiaomi не подключён", authExpired = true)
        val url = apiUrl(path)
        val nonce = MiCrypto.nonce()
        val signed = MiCrypto.signedNonce(ssecurity, nonce)
        val fields = MiCrypto.encParams(url, "POST", signed, nonce, linkedMapOf("data" to data.toString()), ssecurity)
        val hb = url.toHttpUrl().newBuilder()
        fields.forEach { (k, v) -> hb.addQueryParameter(k, v) }
        val req = Request.Builder().url(hb.build())
            .post(FormBody.Builder().build())
            .header("User-Agent", store.agent)
            .header("Accept-Encoding", "identity")
            .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
            .header("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4")
            .header(
                "Cookie",
                "userId=${store.userId}; yetAnotherServiceToken=${store.serviceToken}; serviceToken=${store.serviceToken}; " +
                    "locale=ru_RU; timezone=GMT+03:00; is_daylight=0; dst_offset=0; channel=MI_APP_STORE",
            )
            .build()
        AppLog.i("Xiaomi → POST $path ${AppLog.mask(data.toString()).take(200)}")
        // Свой клиент без хранилища cookie: здесь они задаются заголовком
        plain.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw XiaomiException("сессия Xiaomi устарела (${r.code})", authExpired = true)
            if (r.code !in 200..299) throw XiaomiException("облако Xiaomi: HTTP ${r.code}")
            val plainText = runCatching { MiCrypto.rc4Decrypt(MiCrypto.signedNonce(ssecurity, nonce), text) }.getOrNull()
                ?.takeIf { it.trimStart().startsWith("{") } ?: text
            AppLog.i("Xiaomi ← ${AppLog.mask(plainText).take(400)}")
            val o = runCatching { JSONObject(plainText) }.getOrElse { throw XiaomiException("облако Xiaomi: непонятный ответ") }
            val code = o.optInt("code", 0)
            if (code == 3 || code == -8 || o.optString("message").contains("auth", true) && code != 0) {
                throw XiaomiException("сессия Xiaomi устарела (код $code)", authExpired = true)
            }
            if (code != 0) throw XiaomiException("облако Xiaomi: ${o.optString("message").ifEmpty { "код $code" }}")
            o.opt("result")
        }
    }

    private val plain = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private suspend fun getJson(url: String): JSONObject = parse(exec(http, Request.Builder().url(url).get()).second)

    private suspend fun exec(client: OkHttpClient, b: Request.Builder, logUrl: String? = null): Pair<Response, String> = withContext(Dispatchers.IO) {
        val req = b.header("User-Agent", store.agent).build()
        AppLog.i("Xiaomi → ${req.method} ${logUrl ?: req.url.toString().substringBefore("?")}")
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            AppLog.i("Xiaomi ← ${r.code} ${AppLog.mask(text).take(300)}")
            r to text
        }
    }

    private fun parse(text: String): JSONObject {
        val t = text.removePrefix("&&&START&&&").trim()
        return runCatching { JSONObject(t) }.getOrElse { throw XiaomiException("Xiaomi: непонятный ответ сервера") }
    }

    companion object {
        private const val ACCOUNT = "https://account.xiaomi.com"

        /** Серверы Mi Home: код -> название. */
        val COUNTRIES = listOf(
            "ru" to "Россия", "de" to "Европа", "cn" to "Китай", "i2" to "Индия",
            "sg" to "Сингапур", "us" to "США", "tw" to "Тайвань",
        )
    }
}

private data class Quad(val code: Int, val location: String?, val body: String, val pragma: String?)

/** null вместо JSON null и пустой строки. */
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }
