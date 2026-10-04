package app.tuyacontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tuyacontrol.HeatingUiState
import app.tuyacontrol.Thermostat
import app.tuyacontrol.heating.ComfortWindow
import app.tuyacontrol.heating.HeatZone
import app.tuyacontrol.heating.HeatingPlanner
import app.tuyacontrol.heating.HourPrices
import app.tuyacontrol.heating.ZonePlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Цвета зон тарифа по дороговизне: дорогая — коралловая, дешёвая — голубая, средняя — песочная. */
private val PEAK_COLOR = Color(0xFFF4A9A8)
private val MID_COLOR = Color(0xFFF9E2A2)
private val NIGHT_COLOR = Color(0xFFA4D8E6)
private val HEAT_COLOR = Color(0xFFF08A5D)

private fun zoneColor(prices: HourPrices, hour: Int): Color {
    val p = prices.price[hour]
    val max = prices.price.max()
    val min = prices.price.min()
    return when {
        max - min < 1e-9 -> MID_COLOR
        p >= max - 1e-9 -> PEAK_COLOR
        p <= min + 1e-9 -> NIGHT_COLOR
        else -> MID_COLOR
    }
}

private fun deg(t: Double) = String.format(Locale("ru"), if (t % 1.0 == 0.0) "%.0f°" else "%.1f°", t)
private fun rub(v: Double) = String.format(Locale("ru"), "%,.0f ₽", v).replace(' ', ' ')
private fun num(v: Double, digits: Int = 1) = String.format(Locale("ru"), "%.${digits}f", v)
private fun hh(h: Int) = "${h.toString().padStart(2, '0')}:00"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatingScreen(
    state: HeatingUiState,
    onRecompute: () -> Unit,
    onDeploy: () -> Unit,
    onZoneControl: (String, Boolean) -> Unit,
    onTestStudio: (String) -> Unit,
    onSaveZone: (HeatZone) -> Unit,
    onLearn: () -> Unit = {},
    onDeleteZone: (String) -> Unit,
    onLocation: (Double, Double, app.tuyacontrol.OutdoorSensor?) -> Unit,
    onAddRubetek: () -> Unit,
    onInTotal: (String, Boolean) -> Unit,
    onMessageShown: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    var editing by remember { mutableStateOf<HeatZone?>(null) }
    var editLocation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Отопление") },
                actions = {
                    IconButton(onClick = onRecompute) { Icon(Icons.Filled.Refresh, contentDescription = "Пересчитать") }
                },
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.computing || state.deploying) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SummaryCard(state, onDeploy) }
                items(state.plans, key = { it.zone.id }) { plan ->
                    val thermostat = state.thermostats.firstOrNull { it.id == plan.zone.deviceId }
                    ZoneCard(
                        plan, thermostat, state.prices,
                        onEdit = { editing = plan.zone },
                        onInTotal = { onInTotal(plan.zone.id, it) },
                        onControl = { onZoneControl(plan.zone.id, it) },
                        onTestStudio = { onTestStudio(plan.zone.id) },
                        busy = state.deploying,
                        deployed = plan.zone.deviceId?.let { state.deployedTimers[it] },
                        deployedProgram = plan.zone.deviceId?.let { state.deployedPrograms[it] },
                        learned = state.learned[plan.zone.id],
                        learning = state.learning,
                        onLearn = onLearn,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            editing = HeatZone(
                                id = UUID.randomUUID().toString().take(8),
                                name = "",
                                windows = listOf(ComfortWindow(7, 23, 21.0)),
                            )
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Зона")
                        }
                        OutlinedButton(onClick = { editLocation = true }) { Text("Погода и улица") }
                    }
                }
                val used = state.settings.zones.mapNotNull { it.deviceId }.toSet()
                val freeRubetek = state.thermostats.count {
                    app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it.id) && it.id !in used
                }
                if (freeRubetek > 0) item {
                    OutlinedButton(onClick = onAddRubetek, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Конвекторы Rubetek в зоны ($freeRubetek)")
                    }
                }
                item { HowItWorks() }
            }
        }
    }

    editing?.let { z ->
        ZoneDialog(
            initial = z,
            thermostats = state.thermostats,
            isNew = state.settings.zones.none { it.id == z.id },
            onDismiss = { editing = null },
            onSave = { onSaveZone(it); editing = null },
            onDelete = { onDeleteZone(z.id); editing = null },
        )
    }
    if (editLocation) {
        LocationDialog(
            lat = state.settings.latitude,
            lon = state.settings.longitude,
            sensors = state.outdoorSensors,
            selected = state.settings.outdoorSensorId,
            onDismiss = { editLocation = false },
            onSave = { la, lo, sensor -> onLocation(la, lo, sensor); editLocation = false },
        )
    }
}

@Composable
private fun SummaryCard(state: HeatingUiState, onDeploy: () -> Unit) {
    // В сводку — только зоны с включённым «в общем расчёте»
    val counted = state.plans.filter { it.zone.inTotal }
    val cost = counted.sumOf { it.cost }
    val base = counted.sumOf { it.baselineCost }
    val kwh = counted.sumOf { it.kwh }
    val saving = base - cost
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (counted.size < state.plans.size) "Сегодня по плану (зон в расчёте: ${counted.size} из ${state.plans.size})"
                else "Сегодня по плану",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text("≈ ${rub(cost)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text("${num(kwh)} кВт·ч", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (base > 0) {
                val pct = if (base > 0) (saving / base * 100).toInt() else 0
                Text(
                    "Без оптимизации ≈ ${rub(base)} · экономия ${rub(saving)} ($pct%), за месяц ≈ ${rub(saving * 30)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.plans.isNotEmpty()) {
                val today = state.outdoor.take(24)
                val weather = "Улица сегодня: от ${deg(Math.round(today.min()).toDouble())} до ${deg(Math.round(today.max()).toDouble())}"
                Text(
                    if (state.forecastFresh) weather else "$weather (нет свежего прогноза)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutdoorLine(state)
            TariffLegend(state.prices)
            if (state.settings.zones.any { it.control }) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Последняя запись уставок" +
                        (if (state.deployedAt > 0) " " + SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(state.deployedAt)) else "") +
                        ". Каждую ночь план пересчитывается по погоде.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TariffLegend(prices: HourPrices) {
    // Группируем часы по цене: «3,57 ₽ ночь 23–7»
    val byPrice = (0 until 24).groupBy { prices.price[it] }.toSortedMap(compareByDescending { it })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        byPrice.forEach { (price, hours) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(zoneColor(prices, hours.first())))
                Spacer(Modifier.width(4.dp))
                Text("${num(price, 2)} ₽", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneCard(
    plan: ZonePlan,
    thermostat: Thermostat?,
    prices: HourPrices,
    onEdit: () -> Unit,
    onInTotal: (Boolean) -> Unit,
    onControl: (Boolean) -> Unit,
    onTestStudio: () -> Unit,
    busy: Boolean,
    deployed: List<Pair<Int, Boolean>>? = null,
    deployedProgram: String? = null,
    learned: app.tuyacontrol.heating.LearnInfo? = null,
    learning: Boolean = false,
    onLearn: () -> Unit = {},
) {
    val zone = plan.zone
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(zone.name, style = MaterialTheme.typography.titleMedium)
                        thermostat?.heating?.let {
                            Spacer(Modifier.width(6.dp))
                            HeatingIndicator(it)
                        }
                        thermostat?.powerOn?.let {
                            Spacer(Modifier.width(6.dp))
                            PowerIndicator(it)
                        }
                    }
                    Text(
                        when {
                            thermostat == null -> "Термостат не выбран"
                            else -> buildString {
                                append(thermostat.name)
                                thermostat.current?.let { append(" · сейчас ${deg(it)}") }
                                thermostat.setpoint?.let { append(", уставка ${deg(it)}") }
                                if (!thermostat.online) append(" · не в сети")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onEdit) { Text("Настроить") }
            }
            Text(
                "${num(plan.kwh)} кВт·ч · ≈ ${rub(plan.cost)}  (без оптимизации ${rub(plan.baselineCost)})",
                style = MaterialTheme.typography.bodyMedium,
            )
            // Управление: включено — уставки по плану выставляются и обновляются; выключено — устройство не трогаем
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Управление по плану", style = MaterialTheme.typography.titleSmall)
                    val rubetek = zone.deviceId?.let { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it) } == true
                    Text(
                        when {
                            rubetek && zone.control -> "Таймеры вкл/выкл записаны в модуль конвектора; температура — та, что на конвекторе"
                            rubetek -> "Выключено: таймеры модуля отключены, сам конвектор не трогаем"
                            zone.control -> "Уставки выставляются по плану и обновляются каждую ночь"
                            else -> "Выключено: приложение уставки не меняет, на устройстве остаётся что есть"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = zone.control, onCheckedChange = onControl, enabled = !busy && zone.deviceId != null)
            }
            // Временно: проверка записи в «Heating mode schedule» — только для батареи с порогами нагрева
            if (thermostat != null && thermostat.id in setOf("bfe74dac4ad9e53858ebtn")) {
                TextButton(onClick = onTestStudio, enabled = !busy) { Text("Тест: записать в Heating mode schedule") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (zone.inTotal) "В общем расчёте" else "Не входит в общий расчёт",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = zone.inTotal, onCheckedChange = onInTotal)
            }
            Text(
                zone.windows.joinToString("; ") { "${deg(it.temp)} ${it.text()}" } +
                    (if (zone.windows.any { it.from == it.to }) "" else "; остальное время ${deg(zone.baseTemp)}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlanChart(plan, prices)
            LearnLine(zone, learned, learning, onLearn)
            if (plan.shortSteps > 0) {
                Text(
                    "Мощности не хватает, чтобы держать комфорт ≈ ${num(plan.shortSteps / 4.0)} ч в сутки — " +
                        "проверьте параметры ${if (zone.isFloor) "пола" else "обогревателя"} в настройках зоны",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (zone.deviceId?.let { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it) } == true) {
                ModuleTimersBlock(zone, thermostat, plan, prices, deployed, busy, onControl)
                Text("По плану на сегодня", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    app.tuyacontrol.heating.HeatingEngine.onOffEvents(plan).forEach { (m, on) ->
                        Text(
                            "%02d:%02d %s".format(m / 60, m % 60, if (on) "вкл" else "выкл"),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(zoneColor(prices, m / 60).copy(alpha = 0.45f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            } else if (thermostat?.programCode != null) {
                ProgramBlock(zone, thermostat, plan, prices, deployedProgram, busy, onControl)
            } else {
            Text("Уставки термостата", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (h in 0 until 24) {
                    val sp = plan.setpoints[h]
                    if (h != 0 && sp == plan.setpoints[h - 1]) continue
                    Text(
                        "${hh(h)} ${deg(sp)}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(zoneColor(prices, h).copy(alpha = 0.45f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun PlanChart(plan: ZonePlan, prices: HourPrices) {
    val lineColor = MaterialTheme.colorScheme.primary
    val boundColor = MaterialTheme.colorScheme.onSurfaceVariant
    var cursor by remember(plan) { mutableStateOf<Int?>(null) }
    val steps = plan.heat.size
    val yMin = kotlin.math.floor(minOf(plan.temps.min(), plan.minTemps.min()) - 0.5)
    val yMax = kotlin.math.ceil(maxOf(plan.temps.max(), plan.minTemps.max()) + 0.5)

    Column {
        val c = cursor
        Text(
            if (c == null) {
                "Линия — температура по плану, пунктир — нижняя граница, внизу — нагрев. Коснитесь графика."
            } else {
                val hour = c / HeatingPlanner.STEPS_PER_HOUR
                val minute = (c % HeatingPlanner.STEPS_PER_HOUR) * 15
                "%02d:%02d · %s · нагрев %d%% · уставка %s · %s ₽/кВт·ч".format(
                    hour, minute, deg(Math.round(plan.temps[c] * 10) / 10.0), (plan.heat[c] * 100).toInt(),
                    deg(plan.setpoints[hour]), num(prices.price[hour], 2),
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (c != null) lineColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            Column(Modifier.height(130.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(deg(yMax), style = MaterialTheme.typography.labelSmall)
                Text(deg(yMin), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .pointerInput(plan) {
                            detectTapGestures { o -> cursor = (o.x / size.width * steps).toInt().coerceIn(0, steps - 1) }
                        }
                        .pointerInput(plan) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> cursor = (o.x / size.width * steps).toInt().coerceIn(0, steps - 1) },
                            ) { change, _ ->
                                change.consume()
                                cursor = (change.position.x / size.width * steps).toInt().coerceIn(0, steps - 1)
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    val heatBand = h * 0.22f
                    val top = h - heatBand - 4.dp.toPx()
                    fun x(k: Int) = k.toFloat() / steps * w
                    fun y(t: Double) = (top - (t - yMin) / (yMax - yMin) * top).toFloat()

                    // Фон — зоны тарифа по часам
                    for (hr in 0 until 24) {
                        drawRect(
                            zoneColor(prices, hr).copy(alpha = 0.28f),
                            topLeft = Offset(hr * w / 24f, 0f),
                            size = Size(w / 24f + 0.5f, h),
                        )
                    }
                    // Нагрев — столбики внизу
                    for (k in 0 until steps) {
                        val bh = (plan.heat[k] * heatBand).toFloat()
                        if (bh > 0) drawRect(HEAT_COLOR, Offset(x(k), h - bh), Size(w / steps + 0.5f, bh))
                    }
                    // Нижняя граница комфорта — пунктир
                    val bound = Path()
                    for (k in 0..steps) {
                        val px = x(k)
                        val py = y(plan.minTemps[k])
                        if (k == 0) bound.moveTo(px, py) else bound.lineTo(px, py)
                    }
                    drawPath(
                        bound, boundColor.copy(alpha = 0.7f),
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                    )
                    // Температура по плану
                    val line = Path()
                    for (k in 0..steps) {
                        val px = x(k)
                        val py = y(plan.temps[k])
                        if (k == 0) line.moveTo(px, py) else line.lineTo(px, py)
                    }
                    drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx()))
                    cursor?.let { k ->
                        drawLine(lineColor.copy(alpha = 0.6f), Offset(x(k), 0f), Offset(x(k), h), strokeWidth = 1.dp.toPx())
                        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x(k), y(plan.temps[k])))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun HowItWorks() {
    Text(
        "Как считается: для каждой комнаты приложение перебирает варианты нагрева на двое суток вперёд с шагом " +
            "15 минут и выбирает самый дешёвый, при котором температура не опускается ниже заданной. Учитываются " +
            "тарифы из раздела «Тарифы» и прогноз уличной температуры. Где включено «Копить тепло заранее», " +
            "комната прогревается с запасом ночью и в полупик, а в пиковые часы отдаёт тепло. «Без оптимизации» — " +
            "тот же комфорт, но без запаса и без просадки в пик. Параметры (мощность, скорость нагрева, остывание) " +
            "пока типовые — их можно уточнить в настройках зоны. План записывается прямо в устройства: в программу " +
            "термостата или в таймеры модуля конвектора — работает без интернета и телефона.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ---------- Диалоги ----------

private class WindowDraft(from: String, to: String, temp: String) {
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var temp by mutableStateOf(temp)
}

private fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun fieldText(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString().replace('.', ',')

/** Подбор параметров по истории: что подобрано и применено, или почему ещё нет. */
@Composable
private fun LearnLine(zone: HeatZone, info: app.tuyacontrol.heating.LearnInfo?, learning: Boolean, onLearn: () -> Unit) {
    val now = "нагрев ${num(zone.heatRate)}°/ч, остывание ${num(zone.lossRate * 100)}%/ч"
    val date = info?.let { SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(it.at)) }
    val text = when {
        info == null -> "Параметры ($now) типовые — подберутся по истории этой ночью"
        info.problem == null && info.applied -> "Параметры по истории за ${info.days} дн.: $now ($date)"
        info.problem == null -> "По истории за ${info.days} дн.: нагрев ${num(info.heatRate ?: 0.0)}°/ч, остывание " +
            "${num((info.lossRate ?: 0.0) * 100)}%/ч — не применено (подбор выключен в настройках зоны). Сейчас: $now"
        info.applied -> "По истории ($date) подобрано частично: ${info.problem}. Сейчас: $now"
        else -> "По истории пока не подобрать ($date): ${info.problem}. Сейчас: $now"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (info?.problem == null && info != null) Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onLearn, enabled = !learning) { Text(if (learning) "Подбираю…" else "Подобрать") }
    }
}

/** Ряд периодов программы, окрашенных по тарифу. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodChips(periods: List<app.tuyacontrol.heating.WeekProgram.Period>, prices: HourPrices, bold: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        periods.sortedBy { it.minutes }.forEach { p ->
            Text(
                p.text(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (bold) FontWeight.Bold else null,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(zoneColor(prices, p.minutes / 60).copy(alpha = if (bold) 0.6f else 0.45f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Термостат с недельной программой на борту (гостиная, коридор, спальня): что в нём записано сейчас,
 * совпадает ли с тем, что записало приложение, и что получится по плану (6 периодов в сутки).
 */
@Composable
private fun ProgramBlock(
    zone: HeatZone,
    thermostat: Thermostat,
    plan: ZonePlan,
    prices: HourPrices,
    deployed: String?,
    busy: Boolean,
    onControl: (Boolean) -> Unit,
) {
    val ok = Color(0xFF43A047)
    val program = thermostat.program.orEmpty()
    Text("В термостате сейчас (рабочий день)", style = MaterialTheme.typography.labelMedium)
    if (program.isEmpty()) {
        Text("Программа не прочитана", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        PeriodChips(program.take(app.tuyacontrol.heating.WeekProgram.PERIODS), prices, bold = true)
    }
    val matches = deployed != null && thermostat.programRaw == deployed && thermostat.programOn == true
    when {
        zone.control && matches -> Text(
            "✓ План записан в термостат и проверен. Работает сам, даже без интернета",
            style = MaterialTheme.typography.bodySmall, color = ok,
        )
        zone.control -> {
            Text(
                if (thermostat.programOn == false) "⚠ Термостат не в режиме программы — работает по ручной уставке"
                else "⚠ В термостате не то, что записало приложение",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
            )
            Button(onClick = { onControl(true) }, enabled = !busy) { Text("Записать план в термостат") }
        }
        else -> Text(
            if (thermostat.programOn == true) "Управление выключено: термостат работает по своей программе, приложение её не меняет"
            else "Управление выключено: термостат на ручной уставке, приложение её не меняет",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Что запишется по плану: 6 периодов вместо почасовых уставок
    val planned = app.tuyacontrol.heating.WeekProgram.compress(plan.setpoints, prices.price)
    val hourly = app.tuyacontrol.heating.WeekProgram.hourly(planned)
    // Переплата за то, что период держит максимум: лишние градусо-часы × теплопотери, пересчитанные в кВт·ч
    val extraRub = (0 until 24).sumOf { h ->
        (hourly[h] - plan.setpoints[h]) * zone.lossRate / zone.heatRate * zone.powerKw * prices.price[h]
    }
    Text("По плану на сегодня (${planned.size} периодов)", style = MaterialTheme.typography.labelMedium)
    PeriodChips(planned, prices, bold = false)
    if (extraRub >= 0.5) {
        Text(
            "Из-за ограничения в 6 периодов ≈ +${Math.round(extraRub)} ₽ в сутки к плану",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (zone.control && matches && program.take(planned.size) != planned) {
        Text(
            "План на сегодня изменился по погоде — новая программа запишется ночью",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onControl(true) }, enabled = !busy) { Text("Записать сейчас") }
    }
}

/**
 * Что на самом деле стоит в модуле конвектора Rubetek. Приложение Rubetek таймеры, записанные нами, не
 * показывает — поэтому здесь видно всё, что модуль будет выполнять, и предупреждение, если что-то не так.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleTimersBlock(
    zone: HeatZone,
    thermostat: Thermostat?,
    plan: ZonePlan,
    prices: HourPrices,
    deployed: List<Pair<Int, Boolean>>?,
    busy: Boolean,
    onControl: (Boolean) -> Unit,
) {
    val timers = thermostat?.moduleTimers
    val ok = Color(0xFF43A047)
    // Конвектор держит [S + h, S]: если окно комфорта выше низа полосы — подсказать, что поставить на конвекторе
    thermostat?.setpoint?.let { s ->
        val low = s + minOf(zone.hyst, 0.0)
        val need = zone.windows.maxOfOrNull { it.temp } ?: return@let
        Text(
            if (need > low + 1e-6) {
                "На конвекторе ${deg(s)} — в комнате будет ${deg(low)}…${deg(s)}. Для комфорта ${deg(need)} " +
                    "поставьте на конвекторе ${deg(need - minOf(zone.hyst, 0.0))}"
            } else {
                "На конвекторе ${deg(s)} — в комнате будет ${deg(low)}…${deg(s)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (need > low + 1e-6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text("В модуле конвектора сейчас", style = MaterialTheme.typography.labelMedium)
    if (timers == null) {
        Text("Ещё не прочитано из Rubetek…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (timers.isEmpty()) {
        Text("Таймеров нет", style = MaterialTheme.typography.bodyMedium)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            timers.forEach { t ->
                Text(
                    t.text(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(zoneColor(prices, t.minutes / 60).copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
    val actual = timers.map { it.minutes to it.on }.sortedWith(compareBy({ it.first }, { it.second }))
    val written = deployed?.sortedWith(compareBy({ it.first }, { it.second }))
    val matches = written != null && actual == written && timers.all { it.everyDay }
    when {
        zone.control && matches -> Text(
            "✓ Записано по плану и проверено в модуле. Конвектор включается и выключается сам, даже без интернета",
            style = MaterialTheme.typography.bodySmall, color = ok,
        )
        zone.control -> {
            Text(
                "⚠ В модуле не то, что записало приложение",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
            )
            Button(onClick = { onControl(true) }, enabled = !busy) { Text("Записать план в модуль") }
        }
        timers.isNotEmpty() -> {
            Text(
                "⚠ Управление выключено, но в модуле стоят таймеры — конвектор будет сам включаться и выключаться",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
            )
            Button(
                onClick = { onControl(false) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Снять все таймеры") }
        }
        else -> Text(
            "✓ Таймеров нет — сам конвектор не включается и не выключается",
            style = MaterialTheme.typography.bodySmall, color = ok,
        )
    }
    val planned = app.tuyacontrol.heating.HeatingEngine.onOffEvents(plan)
    if (zone.control && matches && planned.sortedWith(compareBy({ it.first }, { it.second })) != written) {
        Text(
            "План на сегодня изменился по погоде — новые таймеры запишутся ночью",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onControl(true) }, enabled = !busy) { Text("Записать сейчас") }
    }
}

@Composable
private fun ZoneDialog(
    initial: HeatZone,
    thermostats: List<Thermostat>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (HeatZone) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var device by remember { mutableStateOf(initial.deviceId) }
    val windows = remember {
        mutableStateListOf<WindowDraft>().apply {
            initial.windows.forEach { add(WindowDraft(it.from.toString(), it.to.toString(), fieldText(it.temp))) }
        }
    }
    var base by remember { mutableStateOf(fieldText(initial.baseTemp)) }
    var drop by remember { mutableStateOf(fieldText(initial.peakDrop)) }
    var max by remember { mutableStateOf(fieldText(initial.maxTemp)) }
    var power by remember { mutableStateOf(fieldText(initial.powerKw)) }
    var heat by remember { mutableStateOf(fieldText(initial.heatRate)) }
    var loss by remember { mutableStateOf(fieldText(initial.lossRate * 100)) }
    var peakHeat by remember { mutableStateOf(initial.peakHeat) }
    var hyst by remember { mutableStateOf(fieldText(initial.hyst)) }
    var storeHeat by remember { mutableStateOf(initial.storeHeat ?: initial.isFloor) }
    var autoTune by remember { mutableStateOf(initial.autoTune) }
    var advanced by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun build(): HeatZone? {
        val ws = windows.map {
            val f = it.from.trim().toIntOrNull() ?: return null
            val t = it.to.trim().toIntOrNull() ?: return null
            val temp = parse(it.temp) ?: return null
            if (f !in 0..24 || t !in 0..24) return null
            ComfortWindow(f % 24, t % 24, temp)
        }
        return initial.copy(
            name = name.trim().ifEmpty { return null },
            deviceId = device,
            windows = ws,
            baseTemp = parse(base) ?: return null,
            peakDrop = (parse(drop) ?: return null).coerceIn(0.0, 5.0),
            maxTemp = parse(max) ?: return null,
            powerKw = (parse(power) ?: return null).coerceIn(0.1, 20.0),
            heatRate = (parse(heat) ?: return null).coerceIn(0.1, 10.0),
            lossRate = ((parse(loss) ?: return null) / 100).coerceIn(0.001, 0.5),
            peakHeat = peakHeat,
            hysteresis = (parse(hyst) ?: return null).coerceIn(-3.0, 3.0),
            storeHeat = storeHeat,
            autoTune = autoTune,
        )
    }
    val result = build()

    // Во весь экран: в обычном окне поля интервалов получались слишком узкими
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // Отступы под системные панели и клавиатуру: поле ввода прокручивается над клавиатурой
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
                Text(
                    if (isNew) "Новая зона" else initial.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { result?.let(onSave) }, enabled = result != null) { Text("Сохранить") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Термостат", style = MaterialTheme.typography.labelMedium)
                if (thermostats.isEmpty()) Text("Термостаты не найдены — обновите список устройств", style = MaterialTheme.typography.bodySmall)
                thermostats.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { device = t.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = device == t.id, onClick = { device = t.id })
                        Text(t.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text("Окна комфорта", style = MaterialTheme.typography.titleSmall)
                if (windows.isEmpty()) Text("Нет — весь день дежурная температура", style = MaterialTheme.typography.bodySmall)
                windows.forEachIndexed { i, w ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Окно ${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { windows.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = "Убрать окно") }
                            }
                            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberField(w.from, { w.from = it }, "С, час", Modifier.weight(1f), decimal = false)
                                NumberField(w.to, { w.to = it }, "По, час", Modifier.weight(1f), decimal = false)
                                NumberField(w.temp, { w.temp = it }, "°C", Modifier.weight(1f))
                            }
                        }
                    }
                }
                TextButton(onClick = { windows.add(WindowDraft("8", "22", "21")) }) { Text("+ окно комфорта") }
                Text("0–0 — круглые сутки; 23–11 — через полночь", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField(base, { base = it }, "Дежурная °C", Modifier.weight(1f))
                    NumberField(drop, { drop = it }, "Просадка в пик", Modifier.weight(1f))
                }
                Text(
                    "Просадка в пик — на сколько градусов можно опустить температуру ниже комфортной в часы пикового " +
                        "тарифа (7–10, 17–21). В пик нагрев не включается, пока комната не остынет на эту величину. " +
                        "0 — держать комфорт всегда.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberField(max, { max = it }, "Максимум про запас °C", Modifier.fillMaxWidth())

                if (device?.let { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it) } != true) {
                    Row(
                        Modifier.fillMaxWidth().clickable { storeHeat = !storeHeat },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Копить тепло заранее", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = storeHeat, onCheckedChange = { storeHeat = it })
                    }
                    Text(
                        if (storeHeat) {
                            "Греть по дешёвому тарифу выше нужного (до «максимума про запас»), чтобы потом не греть " +
                                "по дорогому. Выгодно для тёплого пола — он долго отдаёт тепло."
                        } else {
                            "Вне окон комфорта держится только дежурная температура, прогрев начинается впритык " +
                                "перед окном. Для обогревателей, которые быстро греют и быстро остывают."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                NumberField(hyst, { hyst = it }, "Гистерезис термостата °C", Modifier.fillMaxWidth(), signed = true)
                Text(
                    "Как термостат держит уставку S. Плюс — греет до S + гистерезис и включается при S " +
                        "(тёплые полы: 0,5, спальня: 1). Минус — греет до S и включается при S − гистерезис " +
                        "(конвекторы Rubetek: −1, батарея в ванной: −0,5). Уставки пола ставятся с этой поправкой.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Конвектор Rubetek управляется только таймерами вкл/выкл
                if (device?.let { app.tuyacontrol.rubetek.RubetekMapper.isRubetek(it) } == true) {
                    Row(
                        Modifier.fillMaxWidth().clickable { peakHeat = !peakHeat },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Включать в пик", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = peakHeat, onCheckedChange = { peakHeat = it })
                    }
                    Text(
                        if (peakHeat) {
                            "Конвектор может включаться в пиковые часы (7–10, 17–21), чтобы комната не остыла " +
                                "больше чем на «просадку в пик»."
                        } else {
                            "В пиковые часы (7–10, 17–21) конвектор не включается. Исключение — комната остыла " +
                                "ниже дежурной температуры. После пика включается сразу и догревает комнату."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val what = if (initial.isFloor) "пола" else "обогревателя"
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть параметры $what" else "Параметры $what…") }
                if (advanced) {
                    Row(Modifier.fillMaxWidth().clickable { autoTune = !autoTune }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Подбирать по истории", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(checked = autoTune, onCheckedChange = { autoTune = it })
                    }
                    Text(
                        "Каждую ночь скорость нагрева и остывания подбираются по последней неделе: как менялась " +
                            "температура, когда прибор грел и когда нет, и сколько было на улице. Выключите, если хотите задать вручную.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NumberField(power, { power = it }, "Мощность, кВт", Modifier.fillMaxWidth())
                    NumberField(heat, { heat = it }, "Нагрев, °C в час при полной мощности", Modifier.fillMaxWidth())
                    NumberField(loss, { loss = it }, "Остывание, % разницы с улицей в час", Modifier.fillMaxWidth())
                    Text(
                        "Пример: при остывании 3% и разнице с улицей 30° комната теряет 0,9° в час.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Удалить зону", color = MaterialTheme.colorScheme.error) }
                }
                if (result == null) {
                    Text("Проверьте поля, отмеченные красным", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить зону «${initial.name}»?") },
            text = { Text("Если у зоны включено управление, приложение перестанет обновлять уставки. Текущие значения на устройстве останутся.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    decimal: Boolean = true,
    signed: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = if (decimal) parse(value) == null else value.trim().toIntOrNull() == null,
        // Со знаком: на цифровой клавиатуре некоторых телефонов нет минуса
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

/** Уличный датчик: сейчас на улице и поправка прогноза; если датчик молчит — предупреждение. */
@Composable
private fun OutdoorLine(state: HeatingUiState) {
    val id = state.settings.outdoorSensorId?.takeIf { it != "-" } ?: return
    val name = state.outdoorSensors.firstOrNull { it.id == id }?.name ?: "датчик"
    val now: Double? = state.outdoorNow
    val stale = state.outdoorTime > 0 && System.currentTimeMillis() - state.outdoorTime > 3 * 3600_000L
    val text = if (now == null || stale) {
        "Уличный датчик «$name» не присылает данные — прогноз без поправки"
    } else {
        val t = deg(Math.round(now * 10) / 10.0)
        if (state.outdoorBias != 0.0) {
            "На улице сейчас $t («$name»). Прогноз поправлен по датчику: " +
                String.format(Locale("ru"), "%+.1f°", state.outdoorBias)
        } else {
            "На улице сейчас $t («$name»). Для поправки прогноза копится история датчика"
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (now == null || stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun LocationDialog(
    lat: Double,
    lon: Double,
    sensors: List<app.tuyacontrol.OutdoorSensor>,
    selected: String?,
    onDismiss: () -> Unit,
    onSave: (Double, Double, app.tuyacontrol.OutdoorSensor?) -> Unit,
) {
    var sensorId by remember { mutableStateOf(selected?.takeIf { it != "-" }) }
    var la by remember { mutableStateOf(lat.toString().replace('.', ',')) }
    var lo by remember { mutableStateOf(lon.toString().replace('.', ',')) }
    val a = parse(la)?.takeIf { it in -90.0..90.0 }
    val b = parse(lo)?.takeIf { it in -180.0..180.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Погода и улица") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Координаты нужны только для прогноза погоды. Их можно скопировать из Яндекс или Google Карт " +
                        "(долгое нажатие на дом). Точность до деревни достаточна.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField(la, { la = it }, "Широта", Modifier.fillMaxWidth())
                NumberField(lo, { lo = it }, "Долгота", Modifier.fillMaxWidth())
                Text("Уличный датчик", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Показывает температуру на улице сейчас и поправляет прогноз: если датчик стабильно " +
                        "холоднее или теплее прогноза, план это учитывает.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().clickable { sensorId = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sensorId == null, onClick = { sensorId = null })
                    Text("Нет", style = MaterialTheme.typography.bodyMedium)
                }
                sensors.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { sensorId = s.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = sensorId == s.id, onClick = { sensorId = s.id })
                        Text(s.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (a != null && b != null) onSave(a, b, sensors.firstOrNull { it.id == sensorId }) },
                enabled = a != null && b != null,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
