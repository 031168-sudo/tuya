package app.tuyacontrol.cloud

import app.tuyacontrol.data.AppLog
import app.tuyacontrol.data.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Клиент Tuya Cloud OpenAPI.
 *
 * Подпись запросов (HMAC-SHA256):
 *   stringToSign = METHOD \n SHA256(body) \n <headers> \n url
 *   str = client_id + [access_token] + t + nonce + stringToSign
 *   sign = HMAC-SHA256(secret, str).toUpperCase()
 */
/** Нет подписки на API — общая проблема проекта, а не конкретного устройства. */
val TUYA_NOT_SUBSCRIBED_CODES = setOf(1106, 28841101, 28841002)

class TuyaCloudClient(private val credentials: Credentials) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val tokenMutex = Mutex()
    private var accessToken: String? = null
    private var tokenExpiresAt = 0L

    // ---------- Публичное API ----------

    /** Проверка ключей: просто получаем токен. */
    suspend fun checkCredentials() {
        tokenMutex.withLock { accessToken = null }
        ensureToken()
    }

    /** Все устройства привязанных аккаунтов приложения (Tuya Smart / Smart Life). */
    suspend fun listDevices(): List<CloudDevice> = try {
        listAssociatedUserDevices()
    } catch (e: TuyaApiException) {
        AppLog.e("associated-users/devices не сработал (${e.code}), пробую v2.0 список устройств проекта", e)
        listProjectDevicesV2()
    }

    suspend fun getStatus(deviceId: String): Map<String, Any?> {
        val result = get("/v1.0/devices/$deviceId/status")
        return parseStatus(result as? JSONArray)
    }

    suspend fun getSpecification(deviceId: String): Map<String, DpSpec> {
        val result = get("/v1.0/iot-03/devices/$deviceId/specification") as? JSONObject
            ?: return emptyMap()
        val specs = LinkedHashMap<String, DpSpec>()
        result.optJSONArray("status")?.let { arr ->
            for (i in 0 until arr.length()) {
                val spec = parseSpec(arr.getJSONObject(i), writable = false)
                specs[spec.code] = spec
            }
        }
        result.optJSONArray("functions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val spec = parseSpec(arr.getJSONObject(i), writable = true)
                specs[spec.code] = spec
            }
        }
        return specs
    }

    /** Команды в формате Standard Instruction Set: [{"code":"switch_1","value":true}]. */
    suspend fun sendCommands(deviceId: String, commands: List<Pair<String, Any?>>) {
        val arr = JSONArray()
        commands.forEach { (code, value) ->
            arr.put(JSONObject().put("code", code).put("value", value ?: JSONObject.NULL))
        }
        post("/v1.0/iot-03/devices/$deviceId/commands", JSONObject().put("commands", arr))
    }

    // ---------- Облачное расписание (то же, что «Расписание» в приложении Tuya) ----------

    /**
     * Заменить таймеры устройства в своей категории: удалить старые и записать новые.
     * Таймеры выполняет облако Tuya каждый день (loops = 1111111), телефон для этого не нужен.
     * @param instructs время «HH:mm» -> команды
     */
    suspend fun replaceDailyTimers(
        deviceId: String,
        category: String,
        instructs: List<Pair<String, List<Pair<String, Any?>>>>,
        alias: String,
    ) {
        deleteTimers(deviceId, category)
        if (instructs.isEmpty()) return
        val zone = java.time.ZoneId.systemDefault()
        val offset = zone.rules.getOffset(java.time.Instant.now()).id.let { if (it == "Z") "+00:00" else it }
        val arr = JSONArray()
        instructs.forEach { (time, commands) ->
            val fns = JSONArray()
            commands.forEach { (code, value) ->
                fns.put(JSONObject().put("code", code).put("value", value ?: JSONObject.NULL))
            }
            arr.put(JSONObject().put("time", time).put("functions", fns))
        }
        val body = JSONObject()
            .put("category", category)
            .put("loops", "1111111")
            .put("time_zone", offset)
            .put("timezone_id", zone.id)
            .put("alias_name", alias)
            .put("instruct", arr)
        post("/v1.0/devices/$deviceId/timers", body)
    }

    /** Удалить таймеры устройства в категории (если их нет — не ошибка). */
    suspend fun deleteTimers(deviceId: String, category: String) {
        try {
            request("DELETE", "/v1.0/devices/$deviceId/timers/categories/$category", emptyMap(), null)
        } catch (e: TuyaApiException) {
            if (e.code in NOT_SUBSCRIBED_CODES) throw e
            AppLog.i("Таймеры $category не удалены (${e.code} ${e.message}) — вероятно, их не было")
        }
    }

    /** Все облачные расписания устройства как есть (для разбора формата расписаний из Tuya Smart). */
    suspend fun timersRaw(deviceId: String): String = get("/v1.0/devices/$deviceId/timers")?.toString() ?: "[]"

    /** То же через v2.0 (там у таймеров видны команды): category — например, "studio" (расписания из Tuya Smart). */
    suspend fun timersV2Raw(deviceId: String, category: String?): String =
        get("/v2.0/cloud/timer/device/$deviceId", if (category != null) mapOf("category" to category) else emptyMap())?.toString() ?: "[]"

    /**
     * Добавить одну ежедневную запись в категорию, НЕ удаляя существующие (для «studio» — общий список
     * с Tuya Smart). Возвращает group_id.
     */
    suspend fun addDailyTimer(
        deviceId: String,
        category: String,
        time: String,
        commands: List<Pair<String, Any?>>,
        alias: String,
    ): String {
        val zone = java.time.ZoneId.systemDefault()
        val offset = zone.rules.getOffset(java.time.Instant.now()).id.let { if (it == "Z") "+00:00" else it }
        val fns = JSONArray()
        commands.forEach { (code, value) -> fns.put(JSONObject().put("code", code).put("value", value ?: JSONObject.NULL)) }
        val body = JSONObject()
            .put("category", category)
            .put("loops", "1111111")
            .put("time_zone", offset)
            .put("timezone_id", zone.id)
            .put("alias_name", alias)
            .put("instruct", JSONArray().put(JSONObject().put("time", time).put("functions", fns)))
        val r = post("/v1.0/devices/$deviceId/timers", body)
        return (r as? JSONObject)?.optString("group_id").orEmpty()
    }

    /** Включить ("1") / выключить ("0") группу таймеров. */
    suspend fun setTimerGroupStatus(deviceId: String, category: String, groupId: String, enabled: Boolean) {
        request(
            "PUT", "/v1.0/devices/$deviceId/timers/categories/$category/groups/$groupId/status",
            emptyMap(), JSONObject().put("value", if (enabled) "1" else "0").toString(),
        )
    }

    /** Сколько таймеров записано в категории. */
    suspend fun countTimers(deviceId: String, category: String): Int {
        val result = get("/v1.0/devices/$deviceId/timers/categories/$category")
        val arr = result as? JSONArray ?: return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val groups = arr.optJSONObject(i)?.optJSONArray("groups") ?: continue
            for (g in 0 until groups.length()) n += groups.optJSONObject(g)?.optJSONArray("timers")?.length() ?: 0
        }
        return n
    }

    // ---------- Things Data Model (для устройств без стандартного набора команд) ----------

    /** Все DP устройства «как есть»: /v2.0/cloud/thing/{id}/shadow/properties. */
    /** Время последнего отчёта любого DP (мс) по данным shadow, 0 — неизвестно. */
    suspend fun getLastReportTime(deviceId: String): Long {
        val result = get("/v2.0/cloud/thing/$deviceId/shadow/properties") as? JSONObject ?: return 0
        val arr = result.optJSONArray("properties") ?: return 0
        var max = 0L
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i)?.optLong("time", 0) ?: 0
            if (t > max) max = t
        }
        return max
    }

    suspend fun getShadowProperties(deviceId: String): Map<String, Any?> {
        val result = get("/v2.0/cloud/thing/$deviceId/shadow/properties") as? JSONObject
            ?: return emptyMap()
        return parseStatus(result.optJSONArray("properties"))
    }

    /** Модель устройства: типы, единицы, множители, права доступа DP. */
    suspend fun getThingModel(deviceId: String): Map<String, DpSpec> {
        val result = get("/v2.0/cloud/thing/$deviceId/model") as? JSONObject ?: return emptyMap()
        val model = try {
            JSONObject(result.optString("model", "{}"))
        } catch (e: Exception) {
            return emptyMap()
        }
        val specs = LinkedHashMap<String, DpSpec>()
        val services = model.optJSONArray("services") ?: return specs
        for (s in 0 until services.length()) {
            val props = services.optJSONObject(s)?.optJSONArray("properties") ?: continue
            for (p in 0 until props.length()) {
                val prop = props.optJSONObject(p) ?: continue
                val code = prop.optString("code")
                if (code.isEmpty()) continue
                val ts = prop.optJSONObject("typeSpec") ?: JSONObject()
                val type = when (ts.optString("type")) {
                    "bool" -> "Boolean"
                    "value" -> "Integer"
                    "enum" -> "Enum"
                    "bitmap" -> "Bitmap"
                    "raw" -> "Raw"
                    else -> "String"
                }
                val range = ts.optJSONArray("range")?.let { r -> (0 until r.length()).map { r.optString(it) } }
                    ?: emptyList()
                specs[code] = DpSpec(
                    code = code,
                    type = type,
                    unit = ts.optString("unit"),
                    scale = ts.optInt("scale", 0),
                    min = if (ts.has("min")) ts.optLong("min") else null,
                    max = if (ts.has("max")) ts.optLong("max") else null,
                    step = ts.optLong("step", 1).coerceAtLeast(1),
                    range = range,
                    writable = prop.optString("accessMode").contains("w"),
                    dpId = if (prop.has("abilityId")) prop.optInt("abilityId") else null,
                )
            }
        }
        return specs
    }

    /** Команда через Things Data Model: {"properties":"{\"switch_1\":true}"}. */
    suspend fun sendProperties(deviceId: String, properties: List<Pair<String, Any?>>) {
        val props = JSONObject()
        properties.forEach { (code, value) -> props.put(code, value ?: JSONObject.NULL) }
        post(
            "/v2.0/cloud/thing/$deviceId/shadow/properties/issue",
            JSONObject().put("properties", props.toString()),
        )
    }

    // ---------- Статистика и журналы (история энергии) ----------

    /** Какие DP облако считает статистику (нужен сервис Data Statistics). */
    suspend fun getStatisticTypes(deviceId: String): List<StatType> {
        val arr = get("/v1.0/devices/$deviceId/all-statistic-type") as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { StatType(it.optString("code"), it.optString("stat_type")) }
        }
    }

    /** Суточные суммы DP за период (включительно). Значения — как их отдаёт Tuya (для энергии — кВт·ч). */
    suspend fun getStatisticsDays(
        deviceId: String,
        code: String,
        start: LocalDate,
        end: LocalDate,
    ): Map<LocalDate, Double> {
        val result = get(
            "/v1.0/devices/$deviceId/statistics/days",
            mapOf(
                "code" to code,
                "start_day" to start.format(DAY_FORMAT),
                "end_day" to end.format(DAY_FORMAT),
                "stat_type" to "sum",
            ),
        ) as? JSONObject ?: return emptyMap()
        val days = result.optJSONObject("days") ?: return emptyMap()
        val map = LinkedHashMap<LocalDate, Double>()
        days.keys().forEach { key ->
            val day = runCatching { LocalDate.parse(key, DAY_FORMAT) }.getOrNull() ?: return@forEach
            val value = days.optString(key).toDoubleOrNull() ?: return@forEach
            map[day] = value
        }
        return map
    }

    /** Почасовые суммы DP за один день: час (0..23) -> значение. Tuya разрешает только один день за запрос. */
    suspend fun getStatisticsHours(deviceId: String, code: String, day: LocalDate): Map<Int, Double> {
        val d = day.format(DAY_FORMAT)
        val result = get(
            "/v1.0/devices/$deviceId/statistics/hours",
            mapOf("code" to code, "start_hour" to "${d}00", "end_hour" to "${d}23", "stat_type" to "sum"),
        ) as? JSONObject ?: return emptyMap()
        val hours = result.optJSONObject("hours") ?: return emptyMap()
        val map = HashMap<Int, Double>()
        hours.keys().forEach { key ->
            if (key.length != 10 || !key.startsWith(d)) return@forEach
            val hour = key.substring(8).toIntOrNull() ?: return@forEach
            val value = hours.optString(key).toDoubleOrNull() ?: return@forEach
            if (hour in 0..23) map[hour] = value
        }
        return map
    }

    suspend fun getStatisticsTotal(deviceId: String, code: String): Double? {
        val result = get("/v1.0/devices/$deviceId/statistics/total", mapOf("code" to code)) as? JSONObject
        return result?.optString("total")?.toDoubleOrNull()
    }

    /** Отчёты устройства (type=7) за период. Облако хранит их ограниченное время (~7 дней). */
    suspend fun getDeviceLogs(
        deviceId: String,
        codes: String,
        startMs: Long,
        endMs: Long,
        maxPages: Int = 50,
        onPage: (Int) -> Unit = {},
        type: String = "7",
    ): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        var rowKey = ""
        var page = 0
        while (page < maxPages) {
            val query = mutableMapOf(
                "type" to type,
                "start_time" to startMs.toString(),
                "end_time" to endMs.toString(),
                "size" to "100",
            )
            if (codes.isNotEmpty()) query["codes"] = codes
            if (rowKey.isNotEmpty()) query["start_row_key"] = rowKey
            if (page > 0) delay(LOG_PAGE_DELAY_MS)
            onPage(page + 1)
            val result = logRequest { get("/v1.0/devices/$deviceId/logs", query) } as? JSONObject ?: break
            val logs = result.optJSONArray("logs") ?: JSONArray()
            for (i in 0 until logs.length()) {
                val o = logs.optJSONObject(i) ?: continue
                entries += LogEntry(
                    o.optString("code"),
                    o.optString("value"),
                    o.optLong("event_time"),
                    o.optInt("event_id", 7),
                )
            }
            rowKey = result.optString("next_row_key", "")
            if (!result.optBoolean("has_next", false) || rowKey.isEmpty()) break
            page++
        }
        return entries
    }

    /** Отчёты устройства через Things Data Model (для устройств без стандартного набора команд). */
    suspend fun getReportLogsV2(
        deviceId: String,
        codes: String,
        startMs: Long,
        endMs: Long,
        maxPages: Int = 50,
        onPage: (Int) -> Unit = {},
    ): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        var rowKey = ""
        var page = 0
        while (page < maxPages) {
            val query = mutableMapOf(
                "codes" to codes,
                "start_time" to startMs.toString(),
                "end_time" to endMs.toString(),
                "size" to "100",
            )
            if (rowKey.isNotEmpty()) query["last_row_key"] = rowKey
            if (page > 0) delay(LOG_PAGE_DELAY_MS)
            onPage(page + 1)
            val result = logRequest { get("/v2.0/cloud/thing/$deviceId/report-logs", query) } as? JSONObject ?: break
            val logs = result.optJSONArray("logs") ?: JSONArray()
            for (i in 0 until logs.length()) {
                val o = logs.optJSONObject(i) ?: continue
                entries += LogEntry(o.optString("code"), o.optString("value"), o.optLong("event_time"))
            }
            rowKey = result.optString("last_row_key", "")
            if (!result.optBoolean("has_more", false) || rowKey.isEmpty()) break
            page++
        }
        return entries
    }

    /**
     * Повтор запроса журнала: Tuya ограничивает частоту («too frequent»), а мобильная сеть
     * может ненадолго пропасть (нет DNS, таймаут). В обоих случаях ждём и пробуем снова.
     */
    private suspend fun <T> logRequest(block: suspend () -> T): T {
        var wait = 5_000L
        repeat(5) { attempt ->
            try {
                return block()
            } catch (e: TuyaApiException) {
                val tooFrequent = "frequent" in (e.message ?: "").lowercase() || e.code == 40000309
                if (!tooFrequent || attempt == 4) throw e
                AppLog.i("Журнал: слишком частые запросы, жду ${wait / 1000} с")
            } catch (e: java.io.IOException) {
                if (attempt == 4) throw e
                AppLog.i("Журнал: нет связи (${e.javaClass.simpleName}), жду ${wait / 1000} с")
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(40_000L)
        }
        error("unreachable")
    }

    // ---------- Списки устройств ----------

    private suspend fun listAssociatedUserDevices(): List<CloudDevice> {
        val devices = mutableListOf<CloudDevice>()
        var lastRowKey = ""
        var page = 0
        do {
            val query = mutableMapOf("size" to "100")
            if (lastRowKey.isNotEmpty()) query["last_row_key"] = lastRowKey
            val result = get("/v1.0/iot-01/associated-users/devices", query) as? JSONObject
                ?: break
            val arr = result.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until arr.length()) devices += parseDevice(arr.getJSONObject(i))
            val hasMore = result.optBoolean("has_more", false)
            lastRowKey = result.optString("last_row_key", "")
            page++
        } while (hasMore && lastRowKey.isNotEmpty() && page < 20)
        return devices
    }

    private suspend fun listProjectDevicesV2(): List<CloudDevice> {
        val devices = mutableListOf<CloudDevice>()
        var lastId = ""
        var page = 0
        while (page < 50) {
            val query = mutableMapOf("page_size" to "20")
            if (lastId.isNotEmpty()) query["last_id"] = lastId
            val arr = get("/v2.0/cloud/thing/device", query) as? JSONArray ?: break
            for (i in 0 until arr.length()) devices += parseDevice(arr.getJSONObject(i))
            if (arr.length() < 20) break
            lastId = arr.getJSONObject(arr.length() - 1).optString("id")
            page++
        }
        return devices
    }

    // ---------- Разбор ответов ----------

    private fun parseDevice(o: JSONObject): CloudDevice = CloudDevice(
        id = o.getString("id"),
        // v1: "name" — имя, заданное пользователем; v2: "customName"
        name = o.optString("customName").ifEmpty { o.optString("name") },
        online = if (o.has("online")) o.optBoolean("online") else o.optBoolean("isOnline"),
        productName = o.optString("product_name").ifEmpty { o.optString("productName") },
        category = o.optString("category"),
        localKey = o.optString("local_key").ifEmpty { o.optString("localKey") },
        ip = o.optString("ip"),
        status = o.optJSONArray("status")?.let { parseStatus(it) },
        activeTime = o.optLong("active_time", 0).takeIf { it > 0 } ?: o.optLong("activeTime", 0),
        // v1: update_time в секундах; v2: updateTime (секунды или мс)
        updateTime = (o.optLong("update_time", 0).takeIf { it > 0 } ?: o.optLong("updateTime", 0))
            .let { if (it in 1 until 100_000_000_000L) it * 1000 else it },
    )

    private fun parseStatus(arr: JSONArray?): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        if (arr == null) return map
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val code = item.optString("code")
            if (code.isEmpty()) continue
            val value = item.opt("value")
            map[code] = if (value == JSONObject.NULL) null else value
        }
        return map
    }

    private fun parseSpec(o: JSONObject, writable: Boolean): DpSpec {
        val values = try {
            JSONObject(o.optString("values", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        val range = values.optJSONArray("range")?.let { r ->
            (0 until r.length()).map { r.optString(it) }
        } ?: emptyList()
        return DpSpec(
            code = o.optString("code"),
            type = o.optString("type"),
            unit = values.optString("unit"),
            scale = values.optInt("scale", 0),
            min = if (values.has("min")) values.optLong("min") else null,
            max = if (values.has("max")) values.optLong("max") else null,
            step = values.optLong("step", 1).coerceAtLeast(1),
            range = range,
            writable = writable,
        )
    }

    // ---------- HTTP ----------

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): Any? =
        request("GET", path, query, null)

    private suspend fun post(path: String, body: JSONObject): Any? =
        request("POST", path, emptyMap(), body.toString())

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        allowRetry: Boolean = true,
    ): Any? {
        val token = ensureToken()
        return try {
            execute(method, path, query, body, token)
        } catch (e: TuyaApiException) {
            if (allowRetry && e.code in TOKEN_ERROR_CODES) {
                AppLog.i("Токен недействителен (${e.code}), получаю новый")
                tokenMutex.withLock { accessToken = null }
                request(method, path, query, body, allowRetry = false)
            } else {
                throw e
            }
        }
    }

    private suspend fun ensureToken(): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val current = accessToken
        // Tuya возвращает тот же токен с остатком срока (бывает 30–40 с), поэтому запас небольшой
        if (current != null && now < tokenExpiresAt - 5_000) return@withLock current

        val result = execute("GET", "/v1.0/token", mapOf("grant_type" to "1"), null, null)
            as? JSONObject ?: throw TuyaApiException(-1, "Пустой ответ при получении токена")
        val token = result.getString("access_token")
        accessToken = token
        tokenExpiresAt = now + result.optLong("expire_time", 7200) * 1000
        AppLog.i("Токен получен, действует ${result.optLong("expire_time", 7200)} с")
        token
    }

    private suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String>,
        body: String?,
        token: String?,
    ): Any? = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val bodyText = body ?: ""
        val urlPart = buildUrlPart(path, query)

        val stringToSign = "$method\n${sha256Hex(bodyText)}\n\n$urlPart"
        val signSource = credentials.accessId + (token ?: "") + t + nonce + stringToSign
        val sign = hmacSha256Hex(credentials.accessSecret, signSource)

        val builder = Request.Builder()
            .url("https://${credentials.endpoint}$urlPart")
            .header("client_id", credentials.accessId)
            .header("sign", sign)
            .header("sign_method", "HMAC-SHA256")
            .header("t", t)
            .header("nonce", nonce)
            .header("lang", "en")
        if (token != null) builder.header("access_token", token)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(bodyText.toRequestBody(JSON_MEDIA))
            "PUT" -> builder.put(bodyText.toRequestBody(JSON_MEDIA))
            "DELETE" -> builder.delete()
            else -> throw IllegalArgumentException("Unsupported method $method")
        }

        AppLog.i("→ $method $urlPart${if (body != null) " $body" else ""}")
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            AppLog.i("← ${response.code} ${text.take(600)}")
            if (!response.isSuccessful) {
                throw TuyaApiException(response.code, "HTTP ${response.code}")
            }
            val json = JSONObject(text)
            if (!json.optBoolean("success", false)) {
                throw TuyaApiException(json.optInt("code", -1), json.optString("msg", "unknown error"))
            }
            val result = json.opt("result")
            if (result == JSONObject.NULL) null else result
        }
    }

    private fun buildUrlPart(path: String, query: Map<String, String>): String {
        if (query.isEmpty()) return path
        return path + "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val LOG_PAGE_DELAY_MS = 1_200L
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** 1010 token invalid, 1011 token status invalid, 1012 token expired */
        val TOKEN_ERROR_CODES = setOf(1010, 1011, 1012)
        /** «No permissions. This API is not subscribed» и истёкшая подписка. */
        val NOT_SUBSCRIBED_CODES = setOf(1106, 28841101, 28841002)

        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun hmacSha256Hex(secret: String, text: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .uppercase(Locale.US)
        }
    }
}
