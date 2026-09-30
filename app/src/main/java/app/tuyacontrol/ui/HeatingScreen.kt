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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
    onAutopilot: (Boolean) -> Unit,
    onDeploy: () -> Unit,
    onSaveZone: (HeatZone) -> Unit,
    onDeleteZone: (String) -> Unit,
    onLocation: (Double, Double) -> Unit,
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
                item { SummaryCard(state, onAutopilot, onDeploy) }
                items(state.plans, key = { it.zone.id }) { plan ->
                    val thermostat = state.thermostats.firstOrNull { it.id == plan.zone.deviceId }
                    ZoneCard(plan, thermostat, state.prices, onEdit = { editing = plan.zone })
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
                        OutlinedButton(onClick = { editLocation = true }) { Text("Место для погоды") }
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
            onDismiss = { editLocation = false },
            onSave = { la, lo -> onLocation(la, lo); editLocation = false },
        )
    }
}

@Composable
private fun SummaryCard(state: HeatingUiState, onAutopilot: (Boolean) -> Unit, onDeploy: () -> Unit) {
    val cost = state.plans.sumOf { it.cost }
    val base = state.plans.sumOf { it.baselineCost }
    val kwh = state.plans.sumOf { it.kwh }
    val saving = base - cost
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Сегодня по плану", style = MaterialTheme.typography.labelLarge)
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
            TariffLegend(state.prices)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автопилот", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.settings.autopilot) {
                            "План записан в расписание термостатов" +
                                (if (state.deployedAt > 0) " " + SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(state.deployedAt)) else "") +
                                ". Каждую ночь пересчитывается по погоде."
                        } else {
                            "Сейчас план только показывается. Включите — и термостаты будут работать по нему, даже когда телефон выключен."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = state.settings.autopilot, onCheckedChange = onAutopilot, enabled = !state.deploying)
            }
            if (state.settings.autopilot) {
                state.deployResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                }
                TextButton(onClick = onDeploy, enabled = !state.deploying) { Text("Перезаписать расписание сейчас") }
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
private fun ZoneCard(plan: ZonePlan, thermostat: Thermostat?, prices: HourPrices, onEdit: () -> Unit) {
    val zone = plan.zone
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(zone.name, style = MaterialTheme.typography.titleMedium)
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
            Text(
                zone.windows.joinToString("; ") { "${deg(it.temp)} ${it.text()}" } + "; остальное время ${deg(zone.baseTemp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlanChart(plan, prices)
            if (plan.shortSteps > 0) {
                Text(
                    "Мощности не хватает, чтобы держать комфорт ≈ ${num(plan.shortSteps / 4.0)} ч в сутки — " +
                        "проверьте параметры пола в настройках зоны",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
            "тарифы из раздела «Тарифы» и прогноз уличной температуры. Пол прогревается с запасом ночью и в " +
            "полупик, а в пиковые часы отдаёт тепло. «Без оптимизации» — тот же комфорт, но без запаса и без " +
            "просадки в пик. Параметры пола (мощность, скорость нагрева, остывание) пока типовые — их можно " +
            "уточнить в настройках зоны.",
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
        )
    }
    val result = build()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новая зона" else initial.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                Text("Комфорт (часы с — по, температура)", style = MaterialTheme.typography.labelMedium)
                windows.forEachIndexed { i, w ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NumberField(w.from, { w.from = it }, "с", Modifier.weight(1f), decimal = false)
                        NumberField(w.to, { w.to = it }, "по", Modifier.weight(1f), decimal = false)
                        NumberField(w.temp, { w.temp = it }, "°C", Modifier.weight(1.2f))
                        IconButton(onClick = { windows.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = "Убрать") }
                    }
                }
                TextButton(onClick = { windows.add(WindowDraft("8", "22", "21")) }) { Text("+ окно комфорта") }
                Text("0–0 — круглые сутки; 23–11 — через полночь", style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField(base, { base = it }, "Дежурная °C", Modifier.weight(1f))
                    NumberField(drop, { drop = it }, "Просадка в пик", Modifier.weight(1f))
                }
                NumberField(max, { max = it }, "Максимум про запас °C", Modifier.fillMaxWidth())

                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть параметры пола" else "Параметры пола…") }
                if (advanced) {
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
            }
        },
        confirmButton = { TextButton(onClick = { result?.let(onSave) }, enabled = result != null) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить зону «${initial.name}»?") },
            text = { Text("Если включён автопилот, расписание этого термостата останется до следующей записи — снимите его вручную или выключите автопилот.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, decimal: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = if (decimal) parse(value) == null else value.trim().toIntOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun LocationDialog(lat: Double, lon: Double, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var la by remember { mutableStateOf(lat.toString().replace('.', ',')) }
    var lo by remember { mutableStateOf(lon.toString().replace('.', ',')) }
    val a = parse(la)?.takeIf { it in -90.0..90.0 }
    val b = parse(lo)?.takeIf { it in -180.0..180.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Где дом") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Координаты нужны только для прогноза погоды. Их можно скопировать из Яндекс или Google Карт " +
                        "(долгое нажатие на дом). Точность до деревни достаточна.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NumberField(la, { la = it }, "Широта", Modifier.fillMaxWidth())
                NumberField(lo, { lo = it }, "Долгота", Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (a != null && b != null) onSave(a, b) }, enabled = a != null && b != null) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
