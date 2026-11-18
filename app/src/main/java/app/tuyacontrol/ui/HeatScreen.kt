package app.tuyacontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tuyacontrol.data.DevicePref
import app.tuyacontrol.HeatingUiState
import app.tuyacontrol.data.HousePlan
import app.tuyacontrol.data.MmSeg
import app.tuyacontrol.data.PlanFloor
import app.tuyacontrol.data.PlanRoom
import app.tuyacontrol.heating.HeatZone
import app.tuyacontrol.heating.LearnInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

private val HeatWall = Color(0xFF5B5B5B)
private val HeatGlass = Color(0xFF8FB8D8)
private val HeatPlanBg = Color(0xFFF7F4EE)
private val HeatText = Color(0xFF2B2B2B)
private val HeatSub = Color(0xFF6B6B6B)
private val HeatUnknown = Color(0xFFE2E0DC)

/** Цвет «теплосохранности»: меньше потерь — зеленее, больше — краснее. */
private object HeatScale {
    private val good = Color(0xFFB7DDBF)
    private val mid = Color(0xFFF3E2A6)
    private val bad = Color(0xFFF0B7B0)

    fun color(loss: Double?): Color {
        if (loss == null || loss <= 0.0) return HeatUnknown
        val x = ((loss - 0.005) / 0.045).coerceIn(0.0, 1.0)
        return if (x < 0.5) lerp(good, mid, (x / 0.5).toFloat())
        else lerp(mid, bad, ((x - 0.5) / 0.5).toFloat())
    }

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}

/** Отдельный экран «Тепло»: теплосохранность зон и плане дома. Не связан с «Картой». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatScreen(
    state: HeatingUiState,
    devicePrefs: Map<String, DevicePref>,
    roomNames: List<Pair<String, String>>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val floors = remember { HousePlan.load(context) }
    val names = remember(roomNames) { roomNames.toMap() }
    val zones = state.settings.zones

    fun zoneForRoom(room: PlanRoom): HeatZone? =
        zones.firstOrNull { it.id == room.id }
            ?: zones.firstOrNull { z -> z.deviceId?.let { devicePrefs[it]?.roomId } == room.id }
            ?: zones.firstOrNull { z -> z.name.isNotBlank() && (z.name.contains(room.name, true) || room.name.contains(z.name, true)) }

    val ranked = zones.sortedBy { it.lossRate }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Тепло") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Text(
                    "Оценка по истории зон за последние две недели: как быстро комната остывает и как быстро нагревается. " +
                        "Чем меньше потерь, тем лучше держится тепло.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (zones.isEmpty()) {
                item { Text("Зоны отопления ещё не заданы — создайте их на вкладке «Отопление».") }
            } else {
                item { SectionTitle("План дома") }
                if (floors.isEmpty()) {
                    item { Text("План дома не найден.", color = MaterialTheme.colorScheme.error) }
                }
                val widest = floors.maxOfOrNull { it.width } ?: 1f
                floors.forEach { floor ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(floor.title, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(6.dp))
                                HeatFloorPlan(
                                    floor = floor,
                                    widthFraction = (floor.width + 2 * heatPadOf(floor)) / (widest + 2 * HEAT_PAD),
                                    names = floor.rooms.associate { it.id to (names[it.id] ?: it.name) },
                                    zoneOf = { room -> zoneForRoom(room) },
                                )
                            }
                        }
                    }
                }
                item { HeatLegend() }

                item { SectionTitle("Кто лучше держит тепло") }
                items(ranked, key = { it.id }) { zone ->
                    ZoneHeatCard(zone, state.learned[zone.id])
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

/** План этажа, комнаты закрашены по теплосохранности. */
@Composable
private fun HeatFloorPlan(
    floor: PlanFloor,
    widthFraction: Float,
    names: Map<String, String>,
    zoneOf: (PlanRoom) -> HeatZone?,
) {
    val measurer = rememberTextMeasurer()
    val pad = heatPadOf(floor)
    val totalW = floor.width + 2 * pad
    val totalH = floor.height + 2 * pad

    Canvas(
        Modifier
            .fillMaxWidth(widthFraction)
            .aspectRatio(totalW / totalH)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(HeatPlanBg),
    ) {
        val s = size.width / totalW
        fun px(v: Float) = (v + pad) * s
        fun rectOf(x1: Float, y1: Float, x2: Float, y2: Float) =
            Offset(px(minOf(x1, x2)), px(minOf(y1, y2))) to Size(abs(x2 - x1) * s, abs(y2 - y1) * s)

        floor.terraces.forEach { t ->
            val (tl, sz) = rectOf(t.rect.x1, t.rect.y1, t.rect.x2, t.rect.y2)
            drawRect(Color(0xFFE8E4DA), tl, sz)
            drawRect(
                Color(0xFFBEB8AA), tl, sz,
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
        }

        floor.rooms.forEach { r ->
            val zone = zoneOf(r)
            val (tl, sz) = rectOf(r.rect.x1, r.rect.y1, r.rect.x2, r.rect.y2)
            drawRect(HeatScale.color(zone?.lossRate), tl, sz)
        }

        floor.walls.forEach { w ->
            val h = w.extra / 2
            val (tl, sz) = if (w.vertical) rectOf(w.x1 - h, minOf(w.y1, w.y2) - h, w.x1 + h, maxOf(w.y1, w.y2) + h)
            else rectOf(minOf(w.x1, w.x2) - h, w.y1 - h, maxOf(w.x1, w.x2) + h, w.y1 + h)
            drawRect(HeatWall, tl, sz)
        }

        floor.holes.forEach { h ->
            val (tl, sz) = rectOf(h.x1, h.y1, h.x2, h.y2)
            drawRect(HeatPlanBg, tl, sz)
        }

        val glass = 1.4.dp.toPx()
        floor.windows.forEach { win ->
            val h = floor.wallUnder(win) / 2
            val g = h / 5
            if (win.vertical) {
                val (tl, sz) = rectOf(win.x1 - h, win.y1, win.x1 + h, win.y2)
                drawRect(Color.White, tl, sz)
                for (d in listOf(-g, g)) {
                    drawLine(HeatGlass, Offset(px(win.x1 + d), px(win.y1)), Offset(px(win.x1 + d), px(win.y2)), glass)
                }
            } else {
                val (tl, sz) = rectOf(win.x1, win.y1 - h, win.x2, win.y1 + h)
                drawRect(Color.White, tl, sz)
                for (d in listOf(-g, g)) {
                    drawLine(HeatGlass, Offset(px(win.x1), px(win.y1 + d)), Offset(px(win.x2), px(win.y1 + d)), glass)
                }
            }
        }

        floor.doors.forEach { d ->
            val len = hypot(d.x2 - d.x1, d.y2 - d.y1)
            val t = floor.wallUnder(d) / 2
            val side = if (d.extra < 0) -1f else 1f
            val (tl, sz) = if (d.vertical) rectOf(d.x1 - t, d.y1, d.x1 + t, d.y2) else rectOf(d.x1, d.y1 - t, d.x2, d.y1 + t)
            drawRect(HeatPlanBg, tl, sz)
            val ex = if (d.vertical) d.x1 + side * len else d.x1
            val ey = if (d.vertical) d.y1 else d.y1 + side * len
            drawLine(HeatWall, Offset(px(d.x1), px(d.y1)), Offset(px(ex), px(ey)), 2.dp.toPx())
            val a1 = Math.toDegrees(atan2((ey - d.y1).toDouble(), (ex - d.x1).toDouble())).toFloat()
            val a2 = Math.toDegrees(atan2((d.y2 - d.y1).toDouble(), (d.x2 - d.x1).toDouble())).toFloat()
            var sweep = a2 - a1
            if (sweep > 180f) sweep -= 360f
            if (sweep < -180f) sweep += 360f
            val r = len * s
            drawArc(
                Color(0xFFB0AAA0), a1, sweep, useCenter = false,
                topLeft = Offset(px(d.x1) - r, px(d.y1) - r), size = Size(2 * r, 2 * r),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
            )
        }

        floor.rooms.forEach { r ->
            val inner = floor.interior(r.rect)
            val (tl, sz) = rectOf(inner.x1, inner.y1, inner.x2, inner.y2)
            val zone = zoneOf(r)
            val title = names[r.id] ?: r.name
            val loss = zone?.lossRate
            val sub = when {
                loss == null -> "нет зоны"
                loss <= 0.0 -> "нет данных"
                else -> "%.1f %% / ч".format(Locale("ru"), loss * 100)
            }
            val titleStyle = TextStyle(fontSize = 12.sp, color = HeatText, fontWeight = FontWeight.SemiBold)
            val subStyle = TextStyle(fontSize = 10.sp, color = HeatSub)
            val t1 = measurer.measure(title, titleStyle)
            val t2 = measurer.measure(sub, subStyle)
            val cx = tl.x + sz.width / 2
            val cy = tl.y + sz.height / 2
            drawText(t1, topLeft = Offset(cx - t1.size.width / 2, cy - t1.size.height))
            drawText(t2, topLeft = Offset(cx - t2.size.width / 2, cy + 2.dp.toPx()))
        }
    }
}

private const val HEAT_PAD = PlanFloor.OUTER_WALL / 2 + 150f

private fun heatPadOf(floor: PlanFloor): Float {
    var out = 0f
    for (d in floor.doors) {
        val len = hypot(d.x2 - d.x1, d.y2 - d.y1)
        val side = if (d.extra < 0) -1f else 1f
        val ex = if (d.vertical) d.x1 + side * len else d.x1
        val ey = if (d.vertical) d.y1 else d.y1 + side * len
        out = maxOf(out, -ex, ex - floor.width, -ey, ey - floor.height)
    }
    return maxOf(HEAT_PAD, out + 100f)
}

@Composable
private fun HeatLegend() {
    Column {
        Text("Теплосохранность", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(18.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))) {
            val stops = listOf(
                0.0f to HeatScale.color(0.005),
                0.5f to HeatScale.color(0.025),
                1.0f to HeatScale.color(0.05),
            ).toTypedArray()
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(*stops),
                size = size,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text("лучше держит", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("хуже держит", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ZoneHeatCard(zone: HeatZone, learned: LearnInfo?) {
    val loss = zone.lossRate
    val heat = zone.heatRate
    val cool = coolHours(zone)
    val warm = heatHours(zone)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(zone.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (learned?.problem == null && learned?.applied == true &&
                            learned.heatRate != null && learned.lossRate != null
                        ) "по истории" else "предварительная оценка",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "%.1f %% / ч".format(Locale("ru"), loss * 100),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric("Остывание", "%.1f %% / ч".format(Locale("ru"), loss * 100), Modifier.weight(1f))
                Metric("Нагрев", "%.1f ° / ч".format(Locale("ru"), heat), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric("22° → 18° при −10°", cool?.let { "%.1f ч".format(Locale("ru"), it) } ?: "—", Modifier.weight(1f))
                Metric("Дежурная → 21°", warm?.let { "%.1f ч".format(Locale("ru"), it) } ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            CoolingChart(zone)
        }
    }
}

@Composable
private fun Metric(name: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Кривые остывания: 0°, −10°, −20° на улице. */
@Composable
private fun CoolingChart(zone: HeatZone) {
    val colors = listOf(Color(0xFF7FB3D5), Color(0xFF5D8AA8), Color(0xFF34568B))
    val temps = listOf(0.0, -10.0, -20.0)
    val measurer = rememberTextMeasurer()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(110.dp)
            .padding(top = 4.dp),
    ) {
        val left = 26.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 14.dp.toPx()
        val minT = 10.0
        val maxT = 24.0
        val hours = 12.0
        fun x(h: Double) = left + ((h / hours) * (right - left)).toFloat()
        fun y(t: Double) = bottom - (((t - minT) / (maxT - minT)) * (bottom - top)).toFloat()

        drawLine(Color(0x22000000), Offset(left, y(18.0)), Offset(right, y(18.0)), 1.dp.toPx())
        for (t in listOf(12.0, 18.0, 24.0)) {
            drawLine(Color(0x22000000), Offset(left, y(t)), Offset(right, y(t)), 1.dp.toPx())
        }

        val a = zone.lossRate.coerceAtLeast(0.001)
        temps.forEachIndexed { index, outside ->
            val path = Path()
            for (i in 0..48) {
                val h = i * hours / 48.0
                val t = outside + (22.0 - outside) * kotlin.math.exp(-a * h)
                val px = x(h)
                val py = y(t)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, colors[index], style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        val style = TextStyle(fontSize = 9.sp, color = HeatSub)
        drawText(measurer.measure("12 ч", style), topLeft = Offset(right - 20.dp.toPx(), bottom + 2.dp.toPx()))
        drawText(measurer.measure("0°", style), topLeft = Offset(2.dp.toPx(), y(0.0) - 4.dp.toPx()))
        drawText(measurer.measure("−10°", style), topLeft = Offset(2.dp.toPx(), y(-10.0) - 4.dp.toPx()))
        drawText(measurer.measure("−20°", style), topLeft = Offset(2.dp.toPx(), y(-20.0) - 4.dp.toPx()))
    }
}

/** Сколько часов остывать с 22° до 18° при улице −10°. */
private fun coolHours(zone: HeatZone): Double? {
    val a = zone.lossRate
    if (a <= 0.0) return null
    return ln((22.0 + 10.0) / (18.0 + 10.0)) / a
}

/** Сколько часов нагревать от дежурной до 21° при улице −10°. */
private fun heatHours(zone: HeatZone): Double? {
    val a = zone.lossRate
    val h = zone.heatRate
    if (a <= 0.0 || h <= 0.0) return null
    val outside = -10.0
    val eq = outside + h / a
    val target = 21.0
    val from = zone.baseTemp
    if (eq <= target) return null
    if (from >= target) return 0.0
    if (from <= outside) return null
    return ln((eq - from) / (eq - target)) / a
}
