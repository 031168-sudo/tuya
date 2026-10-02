package app.tuyacontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.data.HousePlan
import app.tuyacontrol.data.PlanFloor
import app.tuyacontrol.data.PlanRoom
import app.tuyacontrol.data.RoomSetting
import app.tuyacontrol.data.RoomStore
import app.tuyacontrol.sensor.SensorDevice
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Температура комнаты. value == null — неизвестна (нет датчика или данных);
 * stale — устройство не в сети или выключено: в карточке его показания серые, на карте — со штриховкой.
 */
data class RoomTemp(val value: Double?, val stale: Boolean)

/** Текущая температура устройства — как в его карточке. null — у устройства нет датчика температуры. */
fun deviceTemperature(d: DeviceUi): RoomTemp? {
    val code = SensorDevice.TEMPERATURE_CODES.firstOrNull { it in d.status } ?: return null
    val raw = d.status[code]
    val number = (raw as? Number)?.toDouble() ?: raw?.toString()?.toDoubleOrNull()
    val scale = d.spec[code]?.scale ?: 0
    return RoomTemp(number?.let { it / 10.0.pow(scale) }, stale = !d.online || d.switchedOff)
}

/** Цветовая шкала температур (та же, что T_STOPS в gen_plans.py.txt). */
object TempScale {
    const val MIN = 14f
    const val MAX = 26f
    val stops = listOf(
        14f to Color(0xFF78A0EB),   // холодно
        18f to Color(0xFF8CCDE1),
        21f to Color(0xFF9BD7A0),   // комфорт
        23.5f to Color(0xFFFACD6E),
        26f to Color(0xFFEE785F),   // жарко
    )
    /** Температура неизвестна. */
    val unknown = Color(0xFFD5D5D5)

    fun color(t: Double): Color {
        val v = t.toFloat().coerceIn(MIN, MAX)
        for (i in 0 until stops.size - 1) {
            val (a, ca) = stops[i]
            val (b, cb) = stops[i + 1]
            if (v <= b) return lerp(ca, cb, (v - a) / (b - a))
        }
        return stops.last().second
    }
}

private val PlanBackground = Color(0xFFFBFAF7)
private val WallColor = Color(0xFF33363B)
private val TerraceFill = Color(0xFFEFEBE3)
private val TerraceStroke = Color(0xFFB9B1A3)
private val TerraceText = Color(0xFF8A8273)
private val GlassColor = Color(0xFF6FA8DC)
private val ArcColor = Color(0xFF9AA0A6)
private val TextMain = Color(0xFF2B2B2B)
private val TextSub = Color(0xFF4A4A4A)
private val HatchColor = Color(0x8CFFFFFF)

private fun formatTemp(v: Double?): String =
    if (v == null) "—" else String.format(Locale("ru"), "%.1f°", v)

private fun formatArea(a: Double): String = String.format(Locale("ru"), "%.1f м²", a)

/** Экран «Карта»: планы этажей, комнаты закрашены по температуре. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    devices: List<DeviceUi>,
    heating: app.tuyacontrol.HeatingUiState,
    bottomBar: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val floors = remember { HousePlan.load(context) }
    val store = remember { RoomStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<PlanRoom?>(null) }
    val byId = remember(devices) { devices.associateBy { it.id } }

    fun tempOf(room: PlanRoom): RoomTemp {
        val device = settings[room.id]?.deviceId?.let { byId[it] } ?: return RoomTemp(null, false)
        return deviceTemperature(device) ?: RoomTemp(null, false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Карта") },
                actions = { OutdoorWeather(devices, heating, Modifier.padding(end = 16.dp)) },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (floors.isEmpty()) {
                Text("План дома не найден", color = MaterialTheme.colorScheme.error)
            }
            floors.forEach { floor ->
                Text(floor.title, style = MaterialTheme.typography.titleMedium)
                FloorPlan(
                    floor = floor,
                    names = floor.rooms.associate { it.id to (settings[it.id]?.name ?: it.name) },
                    temps = floor.rooms.associate { it.id to tempOf(it) },
                    heating = floor.rooms.associate { r ->
                        r.id to settings[r.id]?.deviceId?.let { byId[it] }?.heatingNow
                    },
                    onRoom = { editing = it },
                )
            }
            Spacer(Modifier.height(4.dp))
            TempLegend()
            Text(
                "Нажмите на комнату, чтобы задать её название и датчик температуры",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    editing?.let { room ->
        RoomDialog(
            room = room,
            setting = settings[room.id],
            devices = devices,
            onDismiss = { editing = null },
            onSave = { s ->
                settings = settings + (room.id to s)
                store.save(settings)
                editing = null
            },
        )
    }
}

/** Один этаж: стены, окна, двери, крыльцо; комнаты — цвет по температуре. Нажатие на комнату — onRoom. */
@Composable
private fun FloorPlan(
    floor: PlanFloor,
    names: Map<String, String>,
    temps: Map<String, RoomTemp>,
    /** Греет ли устройство комнаты; null — устройство таких данных не даёт (значок не рисуем). */
    heating: Map<String, Boolean?>,
    onRoom: (PlanRoom) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val heatIcon = rememberVectorPainter(DeviceIcons.vector("heat_wave"))
    // Поле вокруг дома, мм: стены стоят осью на линии, наружу выступает половина толщины
    val pad = PlanFloor.OUTER_WALL / 2 + 150f
    val totalW = floor.width + 2 * pad
    val totalH = floor.height + 2 * pad

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(totalW / totalH)
            .clip(RoundedCornerShape(12.dp))
            .background(PlanBackground)
            .pointerInput(floor) {
                detectTapGestures { p ->
                    val s = size.width / totalW
                    val x = p.x / s - pad
                    val y = p.y / s - pad
                    floor.rooms.firstOrNull { it.rect.contains(x, y) }?.let(onRoom)
                }
            },
    ) {
        val s = size.width / totalW
        fun px(v: Float) = (v + pad) * s
        fun rectOf(x1: Float, y1: Float, x2: Float, y2: Float) =
            Offset(px(minOf(x1, x2)), px(minOf(y1, y2))) to Size(abs(x2 - x1) * s, abs(y2 - y1) * s)

        // Крыльцо, терраса
        floor.terraces.forEach { t ->
            val (tl, sz) = rectOf(t.rect.x1, t.rect.y1, t.rect.x2, t.rect.y2)
            drawRect(TerraceFill, tl, sz)
            drawRect(
                TerraceStroke, tl, sz,
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
            drawLabels(measurer, tl, sz, listOf(Label(t.name, 12.sp, FontWeight.Normal, TerraceText)))
        }

        // Комнаты: цвет по шкале; устаревшие показания — со штриховкой; неизвестно — серым
        floor.rooms.forEach { r ->
            val temp = temps[r.id]
            val (tl, sz) = rectOf(r.rect.x1, r.rect.y1, r.rect.x2, r.rect.y2)
            val value = temp?.value
            drawRect(if (value != null) TempScale.color(value) else TempScale.unknown, tl, sz)
            if (value != null && temp?.stale == true) hatch(tl, sz)
        }

        // Стены
        floor.walls.forEach { w ->
            val h = w.extra / 2
            val (tl, sz) = if (w.vertical) rectOf(w.x1 - h, minOf(w.y1, w.y2) - h, w.x1 + h, maxOf(w.y1, w.y2) + h)
            else rectOf(minOf(w.x1, w.x2) - h, w.y1 - h, maxOf(w.x1, w.x2) + h, w.y1 + h)
            drawRect(WallColor, tl, sz)
        }

        // Окна: проём в наружной стене и две линии стекла
        val glass = 1.6.dp.toPx()
        floor.windows.forEach { win ->
            val h = PlanFloor.OUTER_WALL / 2
            if (win.vertical) {
                val (tl, sz) = rectOf(win.x1 - h, win.y1, win.x1 + h, win.y2)
                drawRect(Color.White, tl, sz)
                for (d in listOf(-35f, 35f)) {
                    drawLine(GlassColor, Offset(px(win.x1 + d), px(win.y1)), Offset(px(win.x1 + d), px(win.y2)), glass)
                }
            } else {
                val (tl, sz) = rectOf(win.x1, win.y1 - h, win.x2, win.y1 + h)
                drawRect(Color.White, tl, sz)
                for (d in listOf(-35f, 35f)) {
                    drawLine(GlassColor, Offset(px(win.x1), px(win.y1 + d)), Offset(px(win.x2), px(win.y1 + d)), glass)
                }
            }
        }

        // Двери: проём, полотно от петель и пунктирная дуга открывания
        floor.doors.forEach { d ->
            val len = hypot(d.x2 - d.x1, d.y2 - d.y1)
            val t = floor.wallUnder(d) / 2
            val side = if (d.extra < 0) -1f else 1f
            val (tl, sz) = if (d.vertical) rectOf(d.x1 - t, d.y1, d.x1 + t, d.y2) else rectOf(d.x1, d.y1 - t, d.x2, d.y1 + t)
            drawRect(PlanBackground, tl, sz)
            val ex = if (d.vertical) d.x1 + side * len else d.x1
            val ey = if (d.vertical) d.y1 else d.y1 + side * len
            drawLine(WallColor, Offset(px(d.x1), px(d.y1)), Offset(px(ex), px(ey)), 2.dp.toPx())
            val a1 = Math.toDegrees(atan2((ey - d.y1).toDouble(), (ex - d.x1).toDouble())).toFloat()
            val a2 = Math.toDegrees(atan2((d.y2 - d.y1).toDouble(), (d.x2 - d.x1).toDouble())).toFloat()
            var sweep = a2 - a1
            if (sweep > 180f) sweep -= 360f
            if (sweep < -180f) sweep += 360f
            val r = len * s
            drawArc(
                ArcColor, a1, sweep, useCenter = false,
                topLeft = Offset(px(d.x1) - r, px(d.y1) - r), size = Size(2 * r, 2 * r),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
            )
        }

        // Подписи: температура, название, площадь — внутри стен, с одинаковыми отступами
        floor.rooms.forEach { r ->
            val inner = floor.interior(r.rect)
            val (tl, sz) = rectOf(inner.x1, inner.y1, inner.x2, inner.y2)
            val temp = temps[r.id]
            drawLabels(
                measurer, tl, sz,
                listOf(
                    Label(formatTemp(temp?.value), 20.sp, FontWeight.Bold, TextMain, heating = heating[r.id]),
                    Label(names[r.id] ?: r.name, 12.sp, FontWeight.Normal, TextMain),
                    Label(formatArea(r.area), 11.sp, FontWeight.Normal, TextSub),
                ),
                heatIcon,
            )
        }
    }
}

/** Доля места внутри комнаты под подписи (по 6% — поля до стен). */
private const val FILL = 0.88f

/** heating != null — справа от строки значок «греет / не греет» (в 2 раза меньше шрифта строки). */
private class Label(
    val text: String,
    val size: TextUnit,
    val weight: FontWeight,
    val color: Color,
    val heating: Boolean? = null,
)

/** Значок «греет» — половина размера шрифта; зазор до текста — четверть значка. */
private const val BADGE_RATIO = 0.5f
private val HeatOnColor = Color(0xFFE5483B)   // как в карточке устройства
private val HeatOffColor = Color(0x8C2B2B2B)

/** Строки по центру прямоугольника с полями 6%; если не влезают — шрифт уменьшается (не меньше 45%), длинное обрезается «…». */
private fun DrawScope.drawLabels(
    measurer: TextMeasurer,
    tl: Offset,
    sz: Size,
    lines: List<Label>,
    heatIcon: Painter? = null,
) {
    // Место под значок справа от строки (значок + зазор), px
    fun badge(l: Label, k: Float) = if (l.heating != null && heatIcon != null) l.size.toPx() * k * BADGE_RATIO else 0f
    fun extra(l: Label, k: Float) = badge(l, k) * 1.25f
    val maxW = sz.width * FILL
    fun layout(k: Float) = lines.map { l ->
        measurer.measure(
            l.text,
            style = TextStyle(fontSize = l.size * k, fontWeight = l.weight, color = l.color),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = Constraints(maxWidth = (maxW - extra(l, k)).toInt().coerceAtLeast(1)),
        )
    }
    val natural = lines.map { l ->
        measurer.measure(l.text, style = TextStyle(fontSize = l.size, fontWeight = l.weight)).size
    }
    val needW = lines.indices.maxOf { natural[it].width + extra(lines[it], 1f) }
    val needH = natural.sumOf { it.height }.toFloat()
    val k = minOf(1f, maxW / needW, sz.height * FILL / needH).coerceAtLeast(0.45f)
    val laid = layout(k)
    var y = tl.y + (sz.height - laid.sumOf { it.size.height }) / 2
    laid.forEachIndexed { i, t ->
        val l = lines[i]
        val ex = extra(l, k)
        val x = tl.x + (sz.width - t.size.width - ex) / 2
        drawText(t, topLeft = Offset(x, y))
        if (ex > 0f && heatIcon != null && l.heating != null) {
            val s = badge(l, k)
            heatBadge(heatIcon, l.heating, Offset(x + t.size.width + s * 0.25f, y + (t.size.height - s) / 2), s)
        }
        y += t.size.height
    }
}

/** Значок «греет» (красный) / «не греет» (серый, перечёркнут) — как в карточке устройства. */
private fun DrawScope.heatBadge(icon: Painter, on: Boolean, at: Offset, s: Float) {
    val color = if (on) HeatOnColor else HeatOffColor
    translate(at.x, at.y) {
        with(icon) { draw(Size(s, s), colorFilter = ColorFilter.tint(color)) }
    }
    if (!on) {
        drawLine(
            HeatOffColor,
            at + Offset(s * 0.12f, s * 0.12f),
            at + Offset(s * 0.88f, s * 0.88f),
            strokeWidth = maxOf(1.dp.toPx(), s * 0.1f),
            cap = StrokeCap.Round,
        )
    }
}

/** Штриховка «показания устарели»: светлые диагональные полосы поверх цвета. */
private fun DrawScope.hatch(tl: Offset, sz: Size) {
    clipRect(tl.x, tl.y, tl.x + sz.width, tl.y + sz.height) {
        val step = 7.dp.toPx()
        val w = 1.6.dp.toPx()
        var d = -sz.height
        while (d < sz.width) {
            drawLine(HatchColor, Offset(tl.x + d, tl.y + sz.height), Offset(tl.x + d + sz.height, tl.y), w)
            d += step
        }
    }
}

/** Горизонтальная шкала температур и обозначения «устарело» / «нет данных». */
@Composable
private fun TempLegend() {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Температура, °C", style = MaterialTheme.typography.titleSmall)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp),
        ) {
            val side = 14.dp.toPx()
            val barH = 16.dp.toPx()
            val w = size.width - 2 * side
            val range = TempScale.MAX - TempScale.MIN
            val stops = TempScale.stops.map { (t, c) -> (t - TempScale.MIN) / range to c }.toTypedArray()
            drawRoundRect(
                Brush.horizontalGradient(*stops, startX = side, endX = side + w),
                topLeft = Offset(side, 0f),
                size = Size(w, barH),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            for (t in TempScale.MIN.toInt()..TempScale.MAX.toInt() step 2) {
                val x = side + (t - TempScale.MIN) / range * w
                drawLine(labelColor, Offset(x, barH), Offset(x, barH + 4.dp.toPx()), 1.dp.toPx())
                val text = when (t) {
                    TempScale.MIN.toInt() -> "≤$t"
                    TempScale.MAX.toInt() -> "≥$t"
                    else -> "$t"
                }
                val layout = measurer.measure(text, style = TextStyle(fontSize = 11.sp, color = labelColor))
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, barH + 6.dp.toPx()))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 28.dp, height = 16.dp).clip(RoundedCornerShape(3.dp))) {
                drawRect(TempScale.color(21.0))
                hatch(Offset.Zero, size)
            }
            Spacer(Modifier.width(6.dp))
            Text("показания устарели", style = MaterialTheme.typography.bodySmall, color = labelColor)
            Spacer(Modifier.width(16.dp))
            Canvas(Modifier.size(width = 28.dp, height = 16.dp).clip(RoundedCornerShape(3.dp))) {
                drawRect(TempScale.unknown)
            }
            Spacer(Modifier.width(6.dp))
            Text("нет данных", style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
    }
}

/** Настройки комнаты: название и одно устройство, по которому определяется температура. */
@Composable
private fun RoomDialog(
    room: PlanRoom,
    setting: RoomSetting?,
    devices: List<DeviceUi>,
    onDismiss: () -> Unit,
    onSave: (RoomSetting) -> Unit,
) {
    var name by remember { mutableStateOf(setting?.name ?: room.name) }
    var deviceId by remember { mutableStateOf(setting?.deviceId) }
    val candidates = remember(devices) {
        devices.mapNotNull { d -> deviceTemperature(d)?.let { d to it } }.sortedBy { it.first.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Комната · ${formatArea(room.area)}") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Температура по устройству", style = MaterialTheme.typography.labelLarge)
                if (deviceId != null && candidates.none { it.first.id == deviceId }) {
                    Text(
                        "Выбранное устройство сейчас не найдено",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item {
                        DeviceOption("Не выбрано", null, deviceId == null) { deviceId = null }
                    }
                    items(candidates, key = { it.first.id }) { (d, t) ->
                        DeviceOption(d.name, t, deviceId == d.id) { deviceId = d.id }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                onSave(RoomSetting(name = n.takeIf { it.isNotEmpty() && it != room.name }, deviceId = deviceId))
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun DeviceOption(title: String, temp: RoomTemp?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (temp != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatTemp(temp.value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // Как в карточке устройства: не в сети или выключено — серым
                color = if (temp.stale || temp.value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
