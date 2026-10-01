package app.tuyacontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tuyacontrol.DeviceUi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.FilterChip
import app.tuyacontrol.ControlMode
import app.tuyacontrol.data.Category
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CardDefaults
import app.tuyacontrol.sensor.SensorDevice
import androidx.compose.foundation.clickable
import app.tuyacontrol.UiState
import app.tuyacontrol.cloud.DpSpec
import app.tuyacontrol.data.DpFormat
import app.tuyacontrol.data.DpLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onCommand: (deviceId: String, code: String, value: Any) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onMessageShown: () -> Unit,
    onOpenEnergy: (deviceId: String?) -> Unit,
    onOpenSensor: (SensorDevice) -> Unit,
    onEditDevice: (DeviceUi) -> Unit,
    title: String = "Устройства",
    devices: List<DeviceUi> = state.devices,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    onModeChange: (ControlMode) -> Unit = {},
    /** Положение прокрутки хранится снаружи: после графиков возвращаемся на то же устройство. */
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val snackbar = remember { SnackbarHostState() }
    val categories = state.categories.associateBy { it.id }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.lastUpdated?.let {
                            Text(
                                "обновлено " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (devices.any { it.hasEnergy }) {
                        TextButton(onClick = { onOpenEnergy(null) }) { Text("₽") }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Filled.Info, contentDescription = "Логи")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        CircularProgressIndicator()
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (state.devices.isEmpty()) "Устройств нет" else "В этой категории пока нет устройств")
                            TextButton(onClick = onRefresh) { Text("Обновить") }
                            TextButton(onClick = onOpenLog) { Text("Открыть логи") }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "mode") {
                        ModeBar(state, devices, onModeChange)
                    }
                    // Группы по категориям в порядке списка категорий; без категории — в конце
                    val order = state.categories.mapIndexed { i, c -> c.id to i }.toMap()
                    val groups = devices
                        .groupBy { d -> state.devicePrefs[d.id]?.categoryId?.takeIf { it in categories } }
                        .toList()
                        .sortedBy { (id, _) -> id?.let { order[it] } ?: Int.MAX_VALUE }
                    groups.forEach { (catId, list) ->
                        if (groups.size > 1) {
                            item(key = "header_${catId ?: "none"}") {
                                Text(
                                    catId?.let { categories[it]?.name } ?: "Без категории",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                                )
                            }
                        }
                    items(list, key = { it.id }) { device ->
                        val pref = state.devicePrefs[device.id]
                        DeviceCard(
                            device = device,
                            category = pref?.categoryId?.let { categories[it] },
                            icon = pref?.icon ?: device.defaultIcon,
                            onEdit = { onEditDevice(device) },
                            onCommand = onCommand,
                            onOpenEnergy = { onOpenEnergy(device.id) },
                            onOpenSensor = onOpenSensor,
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceUi,
    category: Category?,
    icon: String,
    onEdit: () -> Unit,
    onCommand: (deviceId: String, code: String, value: Any) -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenSensor: (SensorDevice) -> Unit,
) {
    val sensor = device.sensorDevice
    var expanded by rememberSaveable(device.id) { mutableStateOf(false) }

    // У датчика температуры нет выключателя — логические DP (вкл/выкл) не показываем
    // У «温控仪» (батарея в ванной) switch — реле нагрева: его не переключают, он показан значком «греет»
    val entries = device.status.entries.toList().filter {
        !(device.isSensor && it.value is Boolean) &&
            !(device.switchIsRelay && it.key in DeviceUi.MAIN_SWITCHES)
    }
    val primary = entries.filter { DpLabels.isPrimary(it.key, device.spec[it.key]) || isFallbackSwitch(it, device) }
    val secondary = entries - primary.toSet()

    // Карточку с температурой/влажностью можно нажать — откроется история показаний
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (sensor != null) Modifier.clickable { onOpenSensor(sensor) } else Modifier),
        colors = CardDefaults.cardColors(containerColor = Pastel.container(category?.color)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Иконка устройства в цветном круге, точка статуса в углу
                Box(Modifier.size(48.dp)) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(Pastel.accent(category?.color), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            DeviceIcons.vector(icon),
                            contentDescription = DeviceIcons.label(icon),
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .background(Pastel.container(category?.color), CircleShape)
                            .padding(2.dp)
                            .background(if (device.online) OnlineColor else OfflineColor, CircleShape),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        device.heatingNow?.let {
                            Spacer(Modifier.width(6.dp))
                            HeatingIndicator(it)
                        }
                    }
                    if (category != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                DeviceIcons.vector(category.icon),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                category.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionBadge(device)
                        Spacer(Modifier.width(6.dp))
                    Text(
                        (if (device.online) "онлайн" else "не в сети") +
                            if (device.productName.isNotEmpty()) " · ${device.productName}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    }
                    val since = if (device.lastDataTime > 0) {
                        "данные от " + staleFormat(device.lastDataTime) + " (" + ageText(device.lastDataTime) + ")"
                    } else {
                        "время последних данных неизвестно"
                    }
                    when {
                        // Нет связи — изменения до облака не доходят, значения могут быть неверными
                        !device.online -> Text(
                            "Показания устарели: $since",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // Выключено — показания могут не обновляться
                        device.switchedOff -> Text(
                            "Выключено, $since",
                            style = MaterialTheme.typography.bodySmall,
                            color = SwitchedOffColor,
                        )
                        // В сети и включено — устройство сообщает только об изменениях
                        device.lastDataTime > 0 -> Text(
                            since,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Иконка и категория")
                }
            }

            if (device.status.isEmpty()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    "Облако не вернуло данных",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (primary.isNotEmpty()) Spacer(Modifier.size(6.dp))
            primary.forEach { (code, value) ->
                DpRow(device, code, value, onCommand)
            }

            if (sensor != null) {
                TextButton(onClick = { onOpenSensor(sensor) }, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        if (sensor.humidity != null && sensor.temperature != null) "История температуры и влажности →"
                        else if (sensor.humidity != null) "История влажности →" else "История температуры →",
                    )
                }
            }

            if (device.hasEnergy) {
                TextButton(onClick = onOpenEnergy, contentPadding = PaddingValues(0.dp)) {
                    Text("История потребления и расходы →")
                }
            }

            if (secondary.isNotEmpty()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (expanded) "Скрыть" else "Подробнее (${secondary.size})")
                }
                if (expanded) {
                    HorizontalDivider()
                    secondary.forEach { (code, value) ->
                        DpRow(device, code, value, onCommand)
                    }
                }
            }
        }
    }
}

/** Если спецификация не загрузилась, всё равно даём управлять switch-кодами. */
private fun isFallbackSwitch(entry: Map.Entry<String, Any?>, device: DeviceUi): Boolean =
    device.spec.isEmpty() && entry.value is Boolean && entry.key.startsWith("switch")

@Composable
private fun DpRow(
    device: DeviceUi,
    code: String,
    value: Any?,
    onCommand: (deviceId: String, code: String, value: Any) -> Unit,
) {
    val spec = device.spec[code]
    val writable = spec?.writable ?: (device.spec.isEmpty() && value is Boolean && code.startsWith("switch"))
    val pending = code in device.pending
    val enabled = device.online && !pending

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            DpLabels.label(code),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            writable && value is Boolean -> {
                if (pending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Switch(
                    checked = value,
                    enabled = enabled,
                    onCheckedChange = { onCommand(device.id, code, it) },
                )
            }
            writable && spec != null && spec.type == "Integer" && DpFormat.asLong(value) != null -> {
                IntegerStepper(spec, DpFormat.asLong(value)!!, enabled) { newValue ->
                    onCommand(device.id, code, newValue)
                }
            }
            else -> Text(
                DpFormat.format(value, spec),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // Устройство не в сети — значения последние известные, показываем приглушённо
                color = if (device.online && !device.switchedOff) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Для уставок (например, temp_set термостата): кнопки − и +. */
@Composable
private fun IntegerStepper(spec: DpSpec, raw: Long, enabled: Boolean, onChange: (Long) -> Unit) {
    fun clamp(v: Long): Long {
        var r = v
        spec.min?.let { if (r < it) r = it }
        spec.max?.let { if (r > it) r = it }
        return r
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange(clamp(raw - spec.step)) },
            enabled = enabled,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(36.dp),
        ) { Text("−") }
        Text(
            DpFormat.formatNumber(raw, spec),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        OutlinedButton(
            onClick = { onChange(clamp(raw + spec.step)) },
            enabled = enabled,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(36.dp),
        ) { Text("+") }
    }
}

/** «только что», «12 мин назад», «3 ч назад», «5 дн назад». */
private fun ageText(ms: Long): String {
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 48 * 60 -> "${minutes / 60} ч назад"
        else -> "${minutes / 1440} дн назад"
    }
}

/** «14:32» для сегодняшнего дня, иначе «28.09 14:32». */
private fun staleFormat(ms: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val pattern = if (t.toLocalDate() == today) "HH:mm" else if (t.year == today.year) "dd.MM HH:mm" else "dd.MM.yyyy HH:mm"
    return t.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}

/** Переключатель режима управления и сводка подключений по Wi-Fi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeBar(state: UiState, devices: List<DeviceUi>, onModeChange: (ControlMode) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlMode.entries.forEach { m ->
                FilterChip(
                    selected = state.mode == m,
                    onClick = { onModeChange(m) },
                    label = { Text(m.title) },
                )
            }
        }
        val viaWifi = devices.count { it.viaLocal }
        Text(
            when (state.mode) {
                ControlMode.CLOUD -> "Управление через облако Tuya"
                ControlMode.LOCAL -> "Только по Wi-Fi, без интернета: подключено $viaWifi из ${devices.size}"
                ControlMode.AUTO -> if (viaWifi > 0) "По Wi-Fi: $viaWifi, остальные — через облако" else "Через облако (устройств в этой Wi-Fi сети не найдено)"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Маленькая метка: как сейчас идёт связь с устройством. */
@Composable
private fun ConnectionBadge(device: DeviceUi) {
    val (text, color) = if (device.viaLocal) "Wi-Fi" to OnlineColor else "облако" to MaterialTheme.colorScheme.outline
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}


private val HeatOnColor = androidx.compose.ui.graphics.Color(0xFFE5483B)

/** Греет — красные волны тепла; не греет — те же волны серым и перечёркнуты. */
@Composable
fun HeatingIndicator(heating: Boolean, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            DeviceIcons.vector("heat_wave"),
            contentDescription = if (heating) "Греет" else "Не греет",
            tint = if (heating) HeatOnColor else grey,
            modifier = Modifier.size(size),
        )
        if (!heating) {
            androidx.compose.foundation.Canvas(Modifier.size(size)) {
                drawLine(
                    grey,
                    start = androidx.compose.ui.geometry.Offset(this.size.width * 0.12f, this.size.height * 0.12f),
                    end = androidx.compose.ui.geometry.Offset(this.size.width * 0.88f, this.size.height * 0.88f),
                    strokeWidth = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}
