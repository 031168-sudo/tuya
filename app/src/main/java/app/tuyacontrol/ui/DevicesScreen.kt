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
                title = {
                    Column {
                        Text("Устройства")
                        state.lastUpdated?.let {
                            Text(
                                "обновлено " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
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
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        CircularProgressIndicator()
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Устройств нет")
                            TextButton(onClick = onRefresh) { Text("Обновить") }
                            TextButton(onClick = onOpenLog) { Text("Открыть логи") }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.devices, key = { it.id }) { device ->
                        DeviceCard(device = device, onCommand = onCommand)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceUi,
    onCommand: (deviceId: String, code: String, value: Any) -> Unit,
) {
    var expanded by rememberSaveable(device.id) { mutableStateOf(false) }

    val entries = device.status.entries.toList()
    val primary = entries.filter { DpLabels.isPrimary(it.key, device.spec[it.key]) || isFallbackSwitch(it, device) }
    val secondary = entries - primary.toSet()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (device.online) OnlineColor else OfflineColor, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (device.online) "онлайн" else "не в сети") +
                            if (device.productName.isNotEmpty()) " · ${device.productName}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (primary.isNotEmpty()) Spacer(Modifier.size(6.dp))
            primary.forEach { (code, value) ->
                DpRow(device, code, value, onCommand)
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
