package app.tuyacontrol.commands

import android.content.Context
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.cloud.TuyaCloudClient
import app.tuyacontrol.data.AppLog
import app.tuyacontrol.data.Category
import app.tuyacontrol.data.CredentialsStore
import app.tuyacontrol.data.DevicePref
import app.tuyacontrol.heating.HeatingEngine
import app.tuyacontrol.rubetek.RubetekClient
import app.tuyacontrol.rubetek.RubetekMapper
import app.tuyacontrol.rubetek.RubetekStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.math.pow
import kotlin.math.roundToLong

enum class StepStatus { WAIT, RUNNING, OK, FAIL }

/** Строка окна выполнения: одно действие на одно устройство (или зону). */
data class Step(val title: String, val status: StepStatus = StepStatus.WAIT, val detail: String? = null)

/**
 * Выполнение команды: действия по очереди, каждое — на каждое устройство группы, и каждое ОБЯЗАТЕЛЬНО
 * подтверждается чтением состояния обратно. Нет подтверждения — красный крест.
 */
class CommandRunner(context: Context) {

    private val app = context.applicationContext
    private val engine = HeatingEngine(app)
    private val tuya: TuyaCloudClient? by lazy { CredentialsStore(app).load()?.let { TuyaCloudClient(it) } }
    private val rubetek: RubetekClient by lazy { RubetekClient(RubetekStore(app)) }

    /** Единица работы: заголовок строки и само выполнение (null — подтверждено, текст — почему нет). */
    private class Job(val title: String, val run: suspend () -> String?)

    suspend fun run(
        command: Command,
        devices: List<DeviceUi>,
        categories: List<Category>,
        prefs: Map<String, DevicePref>,
        onUpdate: (List<Step>) -> Unit,
    ) {
        val jobs = command.actions.flatMap { expand(it, devices, categories, prefs) }
        val steps = jobs.map { Step(it.title) }.toMutableList()
        onUpdate(steps.toList())
        AppLog.i("Команда «${command.name}»: шагов ${jobs.size}")
        jobs.forEachIndexed { i, job ->
            steps[i] = steps[i].copy(status = StepStatus.RUNNING)
            onUpdate(steps.toList())
            val error = try {
                withTimeout(90_000) { job.run() }
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            steps[i] = steps[i].copy(status = if (error == null) StepStatus.OK else StepStatus.FAIL, detail = error)
            AppLog.i("Команда «${command.name}»: ${job.title} — ${error ?: "подтверждено"}")
            onUpdate(steps.toList())
        }
    }

    // ---------- Разворачивание действий по устройствам ----------

    private fun expand(a: Action, devices: List<DeviceUi>, categories: List<Category>, prefs: Map<String, DevicePref>): List<Job> {
        if (a.kind.target == Target.ZONES) return zoneJobs(a.kind == ActionKind.ZONES_CONTROL_ON)
        if (a.kind == ActionKind.ZONE_CONTROL_ON) {
            val one = zoneJobs(true, a.deviceId)
            return one.ifEmpty { listOf(Job("Зона «${a.deviceName}»: управление по плану вкл") { "зона не найдена" }) }
        }
        val label = "${a.kind.title}${a.temp?.let { " ${fmt(it)}°" } ?: ""}"
        // Конкретное устройство
        if (a.deviceId != null) {
            val d = devices.firstOrNull { it.id == a.deviceId }
                ?: return listOf(Job("${a.deviceName ?: a.deviceId}: $label") { "устройство не найдено в списке — обновите устройства" })
            return listOf(Job("${d.name}: $label") { perform(a, d) })
        }
        // Старые команды: вся группа
        val group = groupDevices(a.kind.target, devices, categories, prefs)
        if (group.isEmpty()) return listOf(Job("${a.text()} — нет устройств") { "в группе «${a.kind.target.title}» нет устройств" })
        return group.map { d -> Job("${d.name}: $label") { perform(a, d) } }
    }

    private fun zoneJobs(on: Boolean, onlyId: String? = null): List<Job> {
        val zones = engine.store.load().zones.filter { it.deviceId != null && (onlyId == null || it.id == onlyId) }
        if (zones.isEmpty()) return if (onlyId != null) emptyList() else listOf(Job("Отопление: зон нет") { "нет зон отопления" })
        return zones.map { z ->
            Job("Зона «${z.name}»: управление по плану ${if (on) "вкл" else "выкл"}") {
                val c = tuya ?: return@Job "нет ключей Tuya"
                val s = engine.store.load()
                val updated = s.copy(zones = s.zones.map { if (it.id == z.id) it.copy(control = on) else it })
                engine.store.save(updated)
                val zone = updated.zones.first { it.id == z.id }
                val report = if (on) engine.deploy(c, updated, onlyZone = z.id) else engine.stopZone(c, zone)
                if ("ошибка" in report.lowercase()) report else null
            }
        }
    }

    // ---------- Выполнение на устройстве ----------

    private suspend fun perform(a: Action, d: DeviceUi): String? { return when (a.kind) {
        ActionKind.RUBETEK_ON, ActionKind.RUBETEK_OFF -> rubetekSet(d, "switch", a.kind == ActionKind.RUBETEK_ON)
        ActionKind.RUBETEK_SET_TEMP -> rubetekSet(d, "temp_set", (a.temp ?: return "не задана температура").roundToLong())
        ActionKind.RUBETEK_CLEAR_TIMERS -> {
            engine.clearRubetekTimers(d.id)
            null
        }

        ActionKind.HEATING_ON, ActionKind.HEATING_OFF, ActionKind.WATER_ON, ActionKind.WATER_OFF -> {
            val code = DeviceUi.MAIN_SWITCHES.firstOrNull { it in d.status } ?: return "у устройства нет выключателя"
            tuyaSet(d, listOf(code to (a.kind == ActionKind.HEATING_ON || a.kind == ActionKind.WATER_ON)))
        }

        ActionKind.HEATING_MANUAL, ActionKind.HEATING_AUTO -> {
            val (manual, auto) = modes(d) ?: return "у термостата нет режима"
            tuyaSet(d, listOf("mode" to if (a.kind == ActionKind.HEATING_MANUAL) manual else auto))
        }

        ActionKind.HEATING_SET_TEMP -> {
            val (manual, _) = modes(d) ?: return "у термостата нет режима"
            val code = d.setpointCode ?: return "у термостата нет уставки"
            val raw = encodeTemp(d, code, a.temp ?: return "не задана температура")
            tuyaSet(d, listOf("mode" to manual, code to raw))
        }

        ActionKind.BATH_ON, ActionKind.BATH_OFF -> {
            val on = a.kind == ActionKind.BATH_ON
            val (start, stop) = if (on) DeviceUi.PRESET_ON else DeviceUi.PRESET_OFF
            val s = encodeTemp(d, "heating_temp_start", start)
            val t = encodeTemp(d, "heating_temp_stop", stop)
            // Порог включения всегда ниже порога выключения
            tuyaSet(d, if (on) listOf("heating_temp_stop" to t, "heating_temp_start" to s) else listOf("heating_temp_start" to s, "heating_temp_stop" to t))
        }

        ActionKind.WATER_TIMERS_ON, ActionKind.WATER_TIMERS_OFF -> waterTimers(d, a.kind == ActionKind.WATER_TIMERS_ON)
        ActionKind.ZONES_CONTROL_ON, ActionKind.ZONES_CONTROL_OFF, ActionKind.ZONE_CONTROL_ON -> "не для устройства"
    } }

    /** Ручной и «по программе» режимы термостата: спальня — manual/auto, «Temp» — cold/hot. */
    private fun modes(d: DeviceUi): Pair<String, String>? {
        // Сначала по текущему значению: в общей спецификации Tuya у «Temp» в списке режимов есть и auto/manual,
        // хотя сам прибор понимает только cold/hot/wind
        val now = d.status["mode"]?.toString()
        val range = d.spec["mode"]?.range.orEmpty()
        return when {
            now in setOf("cold", "hot", "wind") -> "cold" to "hot"
            now in setOf("manual", "auto") -> "manual" to "auto"
            "manual" in range && "auto" in range -> "manual" to "auto"
            "cold" in range && "hot" in range -> "cold" to "hot"
            else -> null
        }
    }

    private fun encodeTemp(d: DeviceUi, code: String, t: Double): Long {
        val spec = d.spec[code]
        val scale = spec?.scale ?: 0
        val step = (spec?.step ?: 1).coerceAtLeast(1)
        var v = (t * 10.0.pow(scale)).roundToLong()
        v = (v / step) * step
        spec?.min?.let { v = maxOf(v, it) }
        spec?.max?.let { v = minOf(v, it) }
        return v
    }

    private fun fmt(t: Double) = if (t % 1.0 == 0.0) t.toInt().toString() else t.toString().replace('.', ',')

    /** Отправить DP по одному (порядок важен) и дождаться, что устройство сообщит именно эти значения. */
    private suspend fun tuyaSet(d: DeviceUi, cmds: List<Pair<String, Any>>): String? {
        val c = tuya ?: return "нет ключей Tuya"
        for ((i, cmd) in cmds.withIndex()) {
            if (i > 0) delay(2000)
            if (d.thingModel) {
                c.sendProperties(d.id, listOf(cmd))
            } else {
                try {
                    c.sendCommands(d.id, listOf(cmd))
                } catch (e: Exception) {
                    c.sendProperties(d.id, listOf(cmd))
                }
            }
        }
        var last: Map<String, Any?> = emptyMap()
        repeat(6) {
            delay(2500)
            last = runCatching { c.getStatus(d.id) }.getOrDefault(emptyMap()) +
                runCatching { c.getShadowProperties(d.id) }.getOrDefault(emptyMap())
            if (cmds.all { (k, v) -> same(last[k], v) }) return null
        }
        return "устройство не подтвердило: " + cmds.filter { (k, v) -> !same(last[k], v) }.joinToString { (k, v) -> "$k=${last[k]} вместо $v" }
    }

    private fun same(actual: Any?, wanted: Any): Boolean = when (wanted) {
        is Boolean -> actual == wanted || actual?.toString() == wanted.toString()
        is Number -> (actual as? Number)?.toDouble() == wanted.toDouble() || actual?.toString()?.toDoubleOrNull() == wanted.toDouble()
        else -> actual?.toString() == wanted.toString()
    }

    /** Rubetek: команда в облако модуля и ожидание, что модуль сообщит новое значение. */
    private suspend fun rubetekSet(d: DeviceUi, code: String, value: Any): String? {
        val (house, device) = RubetekMapper.parseId(d.id) ?: return "неизвестное устройство"
        val state = RubetekMapper.toState(code, value) ?: return "команда не поддерживается"
        rubetek.setState(house, device, state)
        var got: Any? = null
        repeat(8) {
            delay(3000)
            val now = rubetek.devices(house).firstOrNull { it.optString("id") == device }
                ?.let { RubetekMapper.toDevice(house, "", false, it) }
            got = now?.status?.get(code)
            if (got != null && same(got, value)) return null
        }
        return "модуль показывает $code=$got"
    }

    /** Все облачные таймеры розетки включить/выключить и проверить. */
    private suspend fun waterTimers(d: DeviceUi, on: Boolean): String? {
        val c = tuya ?: return "нет ключей Tuya"
        val timers = c.listTimers(d.id)
        if (timers.isEmpty()) return null
        timers.filter { it.enabled != on }.map { it.category to it.groupId }.distinct()
            .forEach { (cat, gid) -> c.setTimerEnabled(d.id, cat, gid, on) }
        delay(1000)
        val fresh = c.listTimers(d.id)
        val wrong = fresh.count { it.enabled != on }
        return if (wrong == 0) null else "не переключено $wrong из ${fresh.size} событий"
    }
}
