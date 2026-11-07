package app.tuyacontrol

import app.tuyacontrol.cloud.LogEntry
import app.tuyacontrol.cloud.TuyaCloudClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Отчёт по розетке/выключателю Tuya: все параметры как есть и журнал включений за 7 дней.
 * Нужен, чтобы понять, ОТКУДА приходит включение: команда из облака (приложение, расписание, сценарий)
 * или розетка включилась сама (таймер в прошивке, кнопка).
 * Только чтение, ничего не меняет.
 */
object DeviceDiagnostics {
    private val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.systemDefault())
    private fun at(ms: Long) = fmt.format(Instant.ofEpochMilli(ms))

    /** Команда и отчёт о смене состояния считаются одним событием, если они ближе друг к другу, чем это. */
    private const val PAIR_MS = 90_000L

    suspend fun build(client: TuyaCloudClient, device: DeviceUi): String {
        val sb = StringBuilder()
        val code = DeviceUi.MAIN_SWITCHES.firstOrNull { it in device.status } ?: "switch_1"
        sb.appendLine("${device.name} · ${device.id}")
        sb.appendLine("Связь: " + (if (device.online) "в сети" else "не в сети") + (if (device.viaLocal) ", Wi-Fi" else ", облако"))

        // 1. Все параметры
        sb.appendLine().appendLine("ВСЕ ПАРАМЕТРЫ (облако)")
        device.status.toSortedMap().forEach { (k, v) -> sb.appendLine("$k = $v") }
        val shadow = runCatching { client.getShadowProperties(device.id) }
        shadow.getOrNull()?.takeIf { it.isNotEmpty() }?.let { m ->
            val extra = m.filterKeys { it !in device.status }
            if (extra.isNotEmpty()) {
                sb.appendLine().appendLine("ЕЩЁ В МОДЕЛИ УСТРОЙСТВА")
                extra.toSortedMap().forEach { (k, v) -> sb.appendLine("$k = $v") }
            }
        }
        sb.appendLine().appendLine("РАСПИСАНИЯ В ОБЛАКЕ")
        sb.appendLine(runCatching { client.timersRaw(device.id) }.getOrElse { "ошибка: ${it.message}" })
        sb.appendLine(runCatching { client.timersV2Raw(device.id, null) }.getOrElse { "v2: ошибка: ${it.message}" })

        // 2. Журнал за 7 дней: 5 — команды из облака, 7 — отчёты устройства, 10 — информация о таймерах
        val now = System.currentTimeMillis()
        val from = now - 7 * 24 * 3600_000L
        val main = runCatching { client.getDeviceLogs(device.id, code, from, now, maxPages = 20, type = "5,7", keepRaw = true) }
        val timing = runCatching { client.getDeviceLogs(device.id, "", from, now, maxPages = 20, type = "10", keepRaw = true) }

        sb.appendLine().appendLine("ЖУРНАЛ «$code» ЗА 7 ДНЕЙ (новые сверху)")
        val logs = main.getOrNull()
        if (logs == null) {
            sb.appendLine("не прочитан: ${main.exceptionOrNull()?.message}")
        } else if (logs.isEmpty()) {
            sb.appendLine("записей нет")
        } else {
            val commands = logs.filter { it.eventId == 5 }
            val reports = logs.filter { it.eventId != 5 }
            // Сколько раз розетка включилась по команде, а сколько сама
            var byCloud = 0
            var byItself = 0
            val lines = mutableListOf<Pair<Long, String>>()
            for (c in commands) {
                lines += c.time to "${at(c.time)}  команда из облака: ${word(c)}  (приложение, расписание или сценарий)${src(c)}"
            }
            var last: String? = null
            for (r in reports.sortedBy { it.time }) {
                val cmd = commands.minByOrNull { kotlin.math.abs(it.time - r.time) }
                val paired = cmd != null && kotlin.math.abs(cmd.time - r.time) <= PAIR_MS && cmd.value == r.value
                val repeat = r.value == last
                last = r.value
                val on = r.value == "true"
                if (on && !repeat) if (paired) byCloud++ else byItself++
                val who = when {
                    repeat -> "повторный отчёт, состояние не менялось"
                    paired -> "по команде из облака"
                    else -> "БЕЗ команды из облака — сама розетка (таймер в прошивке, кнопка)"
                }
                lines += r.time to "${at(r.time)}  розетка сообщила: ${word(r)}  — $who${src(r)}"
            }
            lines.sortedByDescending { it.first }.take(150).forEach { sb.appendLine(it.second) }
            sb.appendLine()
            sb.appendLine("Включений за 7 дней: по команде из облака — $byCloud, сама — $byItself")
        }

        sb.appendLine().appendLine("ТАЙМЕРЫ УСТРОЙСТВА (события типа 10)")
        val t = timing.getOrNull()
        when {
            t == null -> sb.appendLine("не прочитаны: ${timing.exceptionOrNull()?.message}")
            t.isEmpty() -> sb.appendLine("записей нет")
            else -> {
                sb.appendLine("Все поля как отдаёт облако (${t.size} шт.):")
                t.sortedByDescending { it.time }.take(300).forEach { sb.appendLine("${at(it.time)}  ${it.raw}") }
            }
        }
        return sb.toString().trimEnd()
    }

    /** Источник события, если облако его указывает (поле event_from и т.п.). */
    private fun src(e: LogEntry): String {
        val o = runCatching { org.json.JSONObject(e.raw) }.getOrNull() ?: return ""
        val from = listOf("event_from", "source", "from").firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.takeIf { it.isNotEmpty() }?.let { k to it } }
        return from?.let { "  [${it.first}=${it.second}]" } ?: ""
    }

    private fun word(e: LogEntry) = when (e.value) {
        "true" -> "ВКЛ"
        "false" -> "выкл"
        else -> e.value
    }
}
