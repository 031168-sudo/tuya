package app.tuyacontrol.ui

import java.util.Locale
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Surface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tuyacontrol.EnergyUiState
import app.tuyacontrol.energy.Bucket
import app.tuyacontrol.energy.EnergyDb
import app.tuyacontrol.energy.EnergyReport
import app.tuyacontrol.energy.EnergyReports
import app.tuyacontrol.energy.PeriodType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyScreen(
    state: EnergyUiState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenTariffs: () -> Unit,
    onSelect: (String?) -> Unit,
    onPeriod: (PeriodType, LocalDate?) -> Unit,
    onShift: (Long) -> Unit,
    onToday: () -> Unit,
    onHours: (Int, Int) -> Unit = { _, _ -> },
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Энергия и расходы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onSync, enabled = !state.syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Загрузить историю")
                    }
                    TextButton(onClick = onOpenTariffs) { Text("Тарифы") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.syncing) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            state.progress ?: "Загрузка…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (state.devices.isEmpty()) {
                item {
                    Text(
                        "Нет устройств со счётчиком энергии. Вернитесь на главный экран и обновите список.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = state.selected == null,
                        onClick = { onSelect(null) },
                        label = { Text("Все") },
                    )
                    state.devices.forEach { d ->
                        FilterChip(
                            selected = state.selected == d.id,
                            onClick = { onSelect(d.id) },
                            label = { Text(d.name, maxLines = 1) },
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeriodType.entries.forEach { p ->
                        FilterChip(
                            selected = state.period == p,
                            onClick = { onPeriod(p, null) },
                            label = { Text(p.title) },
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { onShift(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Раньше")
                    }
                    Text(
                        state.report?.title ?: EnergyReports.title(state.period, state.anchor),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onShift(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Позже")
                    }
                    TextButton(onClick = onToday) { Text("Сейчас") }
                }
            }

            // Часы внутри дня: считать не всё сутки, а только с … по …
            if (state.period == PeriodType.DAY) {
                item { HoursRow(state.hourFrom, state.hourTo, onHours) }
            }

            val report = state.report
            if (report != null) {
                item { SummaryCard(report, state.tariffs.isEmpty(), onOpenTariffs) }

                if (report.buckets.isNotEmpty()) {
                    item { BarChart(report.buckets, report.title) }
                }

                if (state.selected == null && report.perDevice.size > 1) {
                    item { SectionTitle("По устройствам") }
                    items(report.perDevice) { b -> BucketRow(b, onClick = null) }
                }

                if (report.buckets.isNotEmpty()) {
                    item {
                        SectionTitle(
                            when (state.period) {
                                PeriodType.YEAR -> "По месяцам"
                                PeriodType.DAY -> "По часам"
                                else -> "По дням"
                            },
                        )
                    }
                    val rows = if (state.period == PeriodType.DAY) report.buckets.filter { it.selected } else report.buckets.reversed()
                    items(rows) { b ->
                        BucketRow(
                            b,
                            onClick = if (b.drillType != null && b.drillDate != null) {
                                { onPeriod(b.drillType, b.drillDate) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            item { SectionTitle("Источник данных") }
            val shown = state.selected?.let { id -> state.devices.filter { it.id == id } } ?: state.devices
            items(shown, key = { it.id }) { d ->
                val m = state.meta[d.id]
                val text = when {
                    m == null -> "история ещё не загружалась"
                    m.mode == EnergyDb.SOURCE_STATS ->
                        "Статистика Tuya" +
                            (m.syncedUntil?.let { ", загружено по ${it.format(DATE)}" } ?: "") +
                            (m.total?.let { ", всего по Tuya ${EnergyReports.kwh(it)}" } ?: "")
                    else -> (if (m.code == "cur_power") "Расход считается по журналу мощности (мощность × время)" else "Журнал устройства${m.code?.let { " (DP $it)" } ?: ""}") +
                        ": облако хранит 7 дней, " +
                        "история копится, пока вы открываете приложение. Статистика Tuya за всё время недоступна."
                }
                Column {
                    Text(d.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m?.lastError?.let {
                        Text("Ошибка: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** «Часы: с 17:00 до 20:00» — два выпадающих списка и сброс на весь день. */
@Composable
private fun HoursRow(from: Int, to: Int, onChange: (Int, Int) -> Unit) {
    fun hh(h: Int) = h.toString().padStart(2, '0') + ":00"
    var fromOpen by remember { mutableStateOf(false) }
    var toOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Часы", style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { fromOpen = true }) { Text("с ${hh(from)}") }
            DropdownMenu(expanded = fromOpen, onDismissRequest = { fromOpen = false }) {
                (0..23).forEach { h ->
                    DropdownMenuItem(
                        text = { Text(hh(h)) },
                        onClick = {
                            fromOpen = false
                            onChange(h, if (to <= h) h + 1 else to)
                        },
                    )
                }
            }
        }
        Box {
            OutlinedButton(onClick = { toOpen = true }) { Text("по ${hh(to)}") }
            DropdownMenu(expanded = toOpen, onDismissRequest = { toOpen = false }) {
                (1..24).forEach { h ->
                    DropdownMenuItem(
                        text = { Text(hh(h)) },
                        onClick = {
                            toOpen = false
                            onChange(if (from >= h) h - 1 else from, h)
                        },
                    )
                }
            }
        }
        if (from != 0 || to != 24) {
            TextButton(onClick = { onChange(0, 24) }) { Text("Весь день") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SummaryCard(report: EnergyReport, noTariffs: Boolean, onOpenTariffs: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (report.hourFrom != 0 || report.hourTo != 24) {
                Text(
                    "За часы %02d:00–%02d:00".format(report.hourFrom, report.hourTo),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Расход", style = MaterialTheme.typography.bodySmall)
                    Text(EnergyReports.kwh(report.kwh), style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Стоимость", style = MaterialTheme.typography.bodySmall)
                    Text(
                        (if (report.estimated) "≈ " else "") + EnergyReports.rub(report.cost) +
                            if (report.missingTariff) "*" else "",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            if (report.from != report.to) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "В среднем в день: ${EnergyReports.kwh(report.avgKwhPerDay)} · ${EnergyReports.rub(report.avgCostPerDay)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.zones.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                report.zones.forEach { z ->
                    Row {
                        Text(
                            EnergyReports.zoneName(z.index),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(EnergyReports.kwh(z.kwh), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(EnergyReports.rub(z.cost), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (report.estimated) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "≈ За часть дней Tuya не отдаёт почасовые данные — их расход разнесён по зонам " +
                        "по среднему суточному профилю счётчика.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.missingTariff) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (noTariffs) "* Тарифы не заданы — стоимость не посчитана."
                    else "* На часть дней или часов нет тарифа — они посчитаны как 0 ₽.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onOpenTariffs, contentPadding = PaddingValues(0.dp)) { Text("Указать тарифы") }
            }
        }
    }
}

@Composable
private fun BarChart(buckets: List<Bucket>, title: String) {
    val max = buckets.maxOfOrNull { it.kwh }?.takeIf { it > 0 } ?: return
    // Выбранный пальцем столбик: нажатие и проведение вдоль графика показывают значения крупно
    var picked by remember(buckets) { mutableStateOf<Int?>(null) }
    var chartWidth by remember { mutableStateOf(0) }
    fun indexAt(x: Float): Int = (x / chartWidth.coerceAtLeast(1) * buckets.size).toInt().coerceIn(0, buckets.size - 1)
    val maxCost = buckets.maxOfOrNull { it.cost }?.takeIf { it > 0 }
    val barColor = MaterialTheme.colorScheme.primary
    val costColor = Color(0xFFE5483B)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val axisStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
    val labelEvery = when {
        buckets.size <= 12 -> 1
        buckets.size <= 16 -> 2
        buckets.size == 24 -> 3
        else -> 5
    }
    // Последний столбик с данными: дальше (будущие часы/дни) линию рублей не тянем
    val lastData = buckets.indexOfLast { (it.kwh > 0 || it.cost > 0) && it.selected }
    // Линия рублей — только по выбранным часам
    val firstData = buckets.indexOfFirst { it.selected }.coerceAtLeast(0)
    val axisW = 34.dp
    val chartH = 140.dp
    val ticks = listOf(1.0, 0.5, 0.0)
    fun kwhTick(v: Double) = when {
        v == 0.0 -> "0"
        v < 1 -> String.format(Locale("ru"), "%.2f", v)
        v < 10 -> String.format(Locale("ru"), "%.1f", v)
        else -> String.format(Locale("ru"), "%.0f", v)
    }
    fun rubTick(v: Double) = when {
        v == 0.0 -> "0"
        v < 10 -> String.format(Locale("ru"), "%.1f", v)
        else -> String.format(Locale("ru"), "%.0f", v)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Подписи шкал: слева кВт·ч (столбики), справа ₽ (линия)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(barColor, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text("кВт·ч", style = MaterialTheme.typography.labelSmall, color = barColor)
                Spacer(Modifier.weight(1f))
                if (maxCost != null) {
                    Text("₽", style = MaterialTheme.typography.labelSmall, color = costColor)
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.width(14.dp).height(2.dp).background(costColor))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().height(chartH)) {
                // Шкала кВт·ч
                Column(Modifier.width(axisW).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    ticks.forEach { f ->
                        Text(kwhTick(max * f), style = axisStyle, color = barColor, maxLines = 1, softWrap = false)
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onSizeChanged { chartWidth = it.width }
                        .pointerInput(buckets) {
                            detectTapGestures { o ->
                                val i = indexAt(o.x)
                                picked = if (picked == i) null else i
                            }
                        }
                        .pointerInput(buckets) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> picked = indexAt(o.x) },
                                onHorizontalDrag = { change, _ -> picked = indexAt(change.position.x) },
                            )
                        },
                ) {
                    // Сетка
                    Canvas(Modifier.matchParentSize()) {
                        ticks.forEach { f ->
                            val y = size.height * (1 - f.toFloat())
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.matchParentSize(),
                    ) {
                        buckets.forEach { b ->
                            val fraction = (b.kwh / max).toFloat().coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(if (b.kwh > 0) fraction.coerceAtLeast(0.02f) else 0f)
                                    .background(
                                        if (b.selected) barColor else barColor.copy(alpha = 0.22f),
                                        RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                    ),
                            )
                        }
                    }
                    // Линия рублей — плавная, по центрам столбиков
                    if (maxCost != null && lastData >= 0) {
                        Canvas(Modifier.matchParentSize()) {
                            val n = buckets.size
                            val pts = (firstData..maxOf(lastData, firstData)).map { i ->
                                Offset(
                                    size.width * (i + 0.5f) / n,
                                    size.height * (1 - (buckets[i].cost / maxCost).toFloat().coerceIn(0f, 1f)),
                                )
                            }
                            val path = Path()
                            pts.forEachIndexed { i, pt ->
                                if (i == 0) path.moveTo(pt.x, pt.y) else {
                                    val prev = pts[i - 1]
                                    val mx = (prev.x + pt.x) / 2
                                    path.cubicTo(mx, prev.y, mx, pt.y, pt.x, pt.y)
                                }
                            }
                            drawPath(path, costColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                    // Метка выбранной точки: вертикальная линия и кружок на линии рублей
                    picked?.let { i ->
                        val n = buckets.size
                        Canvas(Modifier.matchParentSize()) {
                            val x = size.width * (i + 0.5f) / n
                            drawLine(markerColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                            if (maxCost != null) {
                                val y = size.height * (1 - (buckets[i].cost / maxCost).toFloat().coerceIn(0f, 1f))
                                drawCircle(costColor, 5.dp.toPx(), Offset(x, y))
                                drawCircle(Color.White, 2.dp.toPx(), Offset(x, y))
                            }
                        }
                        val b = buckets[i]
                        val n2 = buckets.size
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shadowElevation = 6.dp,
                            modifier = Modifier.layout { m, c ->
                                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                                val cx = c.maxWidth * (i + 0.5f) / n2
                                // Сбоку от точки, чтобы не закрывать её пальцем: в левой половине — справа, в правой — слева
                                val gap = 18.dp.roundToPx()
                                val raw = if (cx < c.maxWidth / 2f) cx + gap else cx - gap - p.width
                                val x = raw.toInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0))
                                layout(p.width, p.height) { p.place(x, 0) }
                            },
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    bucketWhen(b.label, i, buckets.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    EnergyReports.kwh(b.kwh),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = barColor,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                Text(
                                    (if (b.estimated) "≈" else "") + EnergyReports.rub(b.cost) + if (b.missingTariff) "*" else "",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = costColor,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
                // Шкала ₽
                Column(
                    Modifier.width(axisW).fillMaxHeight().padding(start = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    if (maxCost != null) ticks.forEach { f ->
                        Text(rubTick(maxCost * f), style = axisStyle, color = costColor, maxLines = 1, softWrap = false)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(axisW))
                Row(Modifier.weight(1f)) {
                    buckets.forEachIndexed { i, b ->
                        Text(
                            if (i % labelEvery == 0) shortLabel(b.label, buckets.size) else "",
                            style = axisStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            softWrap = false,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.width(axisW))
            }
        }
    }
}

/** Что показать в подписи выбранной точки: «17:00–18:00» для часа, остальное как в списке. */
private fun bucketWhen(label: String, index: Int, count: Int): String =
    if (count == 24) {
        val h = label.substringBefore(":").toIntOrNull()
        if (h != null) "%02d:00–%02d:00".format(h, (h + 1) % 24) else label
    } else label

/** «05.03, пн» → «05» (для месяца) / «пн» (для недели); «Январь» → «янв». */
private fun shortLabel(label: String, count: Int): String = when {
    count == 12 -> label.take(3).lowercase()
    count == 24 -> label.substringBefore(":")
    count == 7 -> label.substringAfter(", ", label)
    else -> label.substringBefore(".")
}

@Composable
private fun BucketRow(b: Bucket, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                b.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                EnergyReports.kwh(b.kwh),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(110.dp),
                textAlign = TextAlign.End,
            )
            Text(
                (if (b.estimated) "≈" else "") + EnergyReports.rub(b.cost) + if (b.missingTariff) "*" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(100.dp),
                textAlign = TextAlign.End,
            )
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.size(18.dp))
            }
        }
        HorizontalDivider()
    }
}
