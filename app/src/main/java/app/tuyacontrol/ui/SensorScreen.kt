package app.tuyacontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tuyacontrol.SensorUiState
import app.tuyacontrol.SeriesUi
import app.tuyacontrol.energy.EnergyReports
import app.tuyacontrol.energy.PeriodType
import app.tuyacontrol.sensor.SensorChannel
import app.tuyacontrol.sensor.SeriesPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")
private val TEMP_COLOR = Color(0xFFE0663A)
private val HUM_COLOR = Color(0xFF3A8FE0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorScreen(
    state: SensorUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPeriod: (PeriodType) -> Unit,
    onShift: (Long) -> Unit,
    onToday: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }
    val device = state.device

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "Датчик", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
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

            // Текущие показания
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp)) {
                        device?.channels?.forEach { ch ->
                            Column(Modifier.weight(1f)) {
                                Text(channelTitle(ch), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    state.current[ch.code]?.let { formatValue(it, ch) } ?: "—",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = channelColor(ch),
                                )
                            }
                        }
                    }
                    state.currentTime?.let {
                        Text(
                            "запрошено в " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeriodType.entries.forEach { p ->
                        FilterChip(selected = state.period == p, onClick = { onPeriod(p) }, label = { Text(p.title) })
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { onShift(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Раньше")
                    }
                    Text(
                        state.title.ifEmpty { EnergyReports.title(state.period, state.anchor) },
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

            items(state.series, key = { it.channel.code }) { s ->
                SeriesCard(s, state.period, onShift)
            }

            item {
                Text(
                    "Облако Tuya хранит журнал 7 дней; более ранняя история копится в телефоне, " +
                        "пока приложение открывают хотя бы раз в неделю." +
                        (state.historySince?.let {
                            " История есть с " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + "."
                        } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeriesCard(s: SeriesUi, period: PeriodType, onShift: (Long) -> Unit) {
    val ch = s.channel
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // Название в одну строку, мин/ср/макс — отдельной строкой под ним
            Text(
                channelTitle(ch),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            s.stats?.let {
                Text(
                    "мин ${formatValue(it.min, ch)} · ср ${formatValue(it.avg, ch)} · макс ${formatValue(it.max, ch)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (s.points.isEmpty()) {
                Text(
                    "Нет данных за этот период",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LineChart(s, period, channelColor(ch), onShift)
            }
        }
    }
}

/**
 * График: линия — среднее по интервалу, полоса — от минимума до максимума.
 * Касание или ведение пальцем показывает курсор с датой, временем и значением.
 */
@Composable
private fun LineChart(s: SeriesUi, period: PeriodType, color: Color, onShift: (Long) -> Unit) {
    val ch = s.channel
    var selected by remember(s) { mutableStateOf<SeriesPoint?>(null) }
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val lo = s.points.minOf { it.min }
    val hi = s.points.maxOf { it.max }
    val pad = ((hi - lo) * 0.1).coerceAtLeast(0.5)
    val yMin = lo - pad
    val yMax = hi + pad
    val span = (s.to - s.from).toFloat()
    val textMeasurer = rememberTextMeasurer()
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipFg = MaterialTheme.colorScheme.inverseOnSurface
    val tooltipTitle = MaterialTheme.typography.labelSmall.copy(color = tooltipFg)
    val tooltipValue = MaterialTheme.typography.labelLarge.copy(color = tooltipFg, fontWeight = FontWeight.SemiBold)

    /** Ближайшая точка к доле ширины графика (0..1). */
    fun nearest(fraction: Float): SeriesPoint? {
        val t = s.from + fraction.coerceIn(0f, 1f) * span
        return s.points.minByOrNull { kotlin.math.abs(it.time + s.bucketMs / 2 - t) }
    }

    Column {
        Text(
            selected?.let { p -> pointTime(p, s.bucketMs, period) + ": " + formatValue(p.avg, ch) +
                if (p.max - p.min > 0.05) " (${formatValue(p.min, ch)} … ${formatValue(p.max, ch)})" else "" }
                ?: "Коснитесь графика или ведите по нему пальцем",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected != null) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            Column(Modifier.height(170.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(formatValue(yMax, ch), style = MaterialTheme.typography.labelSmall)
                Text(formatValue((yMax + yMin) / 2, ch), style = MaterialTheme.typography.labelSmall)
                Text(formatValue(yMin, ch), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.padding(start = 4.dp))
            Canvas(
                Modifier
                    .weight(1f)
                    .height(170.dp)
                    // Касание — значение в точке; ведение пальцем вдоль графика — курсор следует за пальцем.
                    // Вертикальная прокрутка экрана при этом продолжает работать.
                    .pointerInput(s) {
                        detectTapGestures { offset -> selected = nearest(offset.x / size.width) }
                    }
                    .pointerInput(s) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> selected = nearest(offset.x / size.width) },
                        ) { change, _ ->
                            change.consume()
                            selected = nearest(change.position.x / size.width)
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                fun x(t: Long) = ((t - s.from) / span) * w
                fun y(v: Double) = (h - ((v - yMin) / (yMax - yMin)) * h).toFloat()

                // Сетка
                for (i in 0..4) {
                    val gy = h * i / 4
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                }

                // Разрыв линии только при реальном пропуске данных: датчик шлёт показания
                // при изменении, поэтому паузы до 3 часов (или до трёх интервалов) соединяем
                val maxGap = maxOf(s.bucketMs * 3, 3L * 3600_000)
                val segments = mutableListOf<MutableList<SeriesPoint>>()
                for (p in s.points) {
                    val last = segments.lastOrNull()?.lastOrNull()
                    if (last == null || p.time - last.time > maxGap) segments += mutableListOf(p) else segments.last() += p
                }
                for (seg in segments) {
                    val mid = { p: SeriesPoint -> x(p.time + s.bucketMs / 2) }
                    if (seg.size == 1) {
                        val p = seg[0]
                        drawCircle(color, 3.dp.toPx(), Offset(mid(p), y(p.avg)))
                        continue
                    }
                    val band = Path().apply {
                        moveTo(mid(seg[0]), y(seg[0].max))
                        seg.drop(1).forEach { lineTo(mid(it), y(it.max)) }
                        seg.reversed().forEach { lineTo(mid(it), y(it.min)) }
                        close()
                    }
                    drawPath(band, color.copy(alpha = 0.18f))
                    val line = Path().apply {
                        moveTo(mid(seg[0]), y(seg[0].avg))
                        seg.drop(1).forEach { lineTo(mid(it), y(it.avg)) }
                    }
                    drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
                }

                selected?.let { p ->
                    val px = x(p.time + s.bucketMs / 2)
                    val py = y(p.avg)
                    drawLine(color.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, h), strokeWidth = 1.dp.toPx())
                    drawCircle(tooltipFg, 6.dp.toPx(), Offset(px, py))
                    drawCircle(color, 4.dp.toPx(), Offset(px, py))

                    // Подсказка: дата и время, значение
                    val title = textMeasurer.measure(pointTime(p, s.bucketMs, period), tooltipTitle)
                    val value = textMeasurer.measure(formatValue(p.avg, ch), tooltipValue)
                    val padH = 8.dp.toPx()
                    val padV = 5.dp.toPx()
                    val boxW = maxOf(title.size.width, value.size.width) + padH * 2
                    val boxH = title.size.height + value.size.height + padV * 2
                    // Справа от курсора, у правого края — слева; по высоте — над точкой, если есть место
                    val bx = if (px + 10.dp.toPx() + boxW <= w) px + 10.dp.toPx() else (px - 10.dp.toPx() - boxW).coerceAtLeast(0f)
                    val by = (py - boxH - 8.dp.toPx()).let { if (it < 0f) (py + 8.dp.toPx()).coerceAtMost(h - boxH) else it }
                    drawRoundRect(
                        tooltipBg,
                        topLeft = Offset(bx, by),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                    drawText(title, topLeft = Offset(bx + padH, by + padV))
                    drawText(value, topLeft = Offset(bx + padH, by + padV + title.size.height))
                }
            }
        }
        // Подписи оси времени
        Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..4) {
                val t = s.from + ((s.to - s.from) * i / 4)
                Text(timeLabel(t, period, detailed = false), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Дата и время точки графика; для интервалов в несколько часов — «с–по». */
private fun pointTime(p: SeriesPoint, bucketMs: Long, period: PeriodType): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(p.time).atZone(zone)
    return when (period) {
        PeriodType.DAY -> start.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", RU))
        PeriodType.WEEK -> start.format(DateTimeFormatter.ofPattern("EE, d MMM, HH:mm", RU))
        PeriodType.MONTH -> start.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)) + "–" +
            Instant.ofEpochMilli(p.time + bucketMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", RU))
        PeriodType.YEAR -> start.format(DateTimeFormatter.ofPattern("d MMMM yyyy", RU))
    }
}

private fun timeLabel(ms: Long, period: PeriodType, detailed: Boolean): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val pattern = when (period) {
        PeriodType.DAY -> "HH:mm"
        PeriodType.WEEK -> if (detailed) "EE dd.MM HH:mm" else "EE"
        PeriodType.MONTH -> if (detailed) "dd.MM HH:mm" else "d"
        PeriodType.YEAR -> if (detailed) "d MMMM" else "LLL"
    }
    return t.format(DateTimeFormatter.ofPattern(pattern, RU))
}

private fun channelTitle(ch: SensorChannel) =
    if (ch.code in app.tuyacontrol.sensor.SensorDevice.HUMIDITY_CODES) "Влажность" else "Температура"

private fun channelColor(ch: SensorChannel) =
    if (ch.code in app.tuyacontrol.sensor.SensorDevice.HUMIDITY_CODES) HUM_COLOR else TEMP_COLOR

private fun formatValue(v: Double, ch: SensorChannel): String {
    val unit = ch.unit.ifEmpty { if (ch.code in app.tuyacontrol.sensor.SensorDevice.HUMIDITY_CODES) "%" else "°C" }
    return String.format(RU, "%.1f %s", v, unit)
}
