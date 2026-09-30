package app.tuyacontrol.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tuyacontrol.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Поиск устройств в Wi-Fi сети: кто отвечает локально, по какому адресу и версии протокола. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(state: UiState, onBack: () -> Unit, onScan: () -> Unit) {
    // Первый поиск — сразу при открытии экрана
    LaunchedEffect(Unit) { if (state.localScannedAt == null) onScan() }

    val found = state.devices.filter { it.id in state.localFound }
    val missing = state.devices.filter { it.id !in state.localFound }
    val known = state.devices.map { it.id }.toSet()
    val strangers = state.localFound.values.filter { it.deviceId !in known }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Локальная сеть") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Text(
                    "Телефон слушает объявления устройств Tuya в текущей Wi-Fi сети. Найденные устройства " +
                        "можно будет включать и выключать напрямую, без интернета.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(onClick = onScan, enabled = !state.localScanning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.localScanning) "Ищу в сети… (7 с)" else "Искать ещё раз")
                }
                if (state.localScanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                state.localScannedAt?.let {
                    Text(
                        "Последний поиск: " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item { SectionHeader("Найдены в сети: ${found.size}") }
            items(found, key = { "f" + it.id }) { d ->
                val a = state.localFound.getValue(d.id)
                val pref = state.devicePrefs[d.id]
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            DeviceIcons.vector(pref?.icon ?: d.defaultIcon),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${a.ip} · протокол ${a.version}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            if (d.localKey.isNotEmpty()) "ключ ✓" else "нет ключа",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (d.localKey.isNotEmpty()) OnlineColor else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (missing.isNotEmpty()) {
                item {
                    SectionHeader("Не найдены: ${missing.size}")
                    Text(
                        "Обычно это устройства в другой сети (другой дом), батарейные датчики, которые спят, " +
                            "Zigbee-устройства за шлюзом или выключенные из розетки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(missing, key = { "m" + it.id }) { d ->
                    Text(
                        "• ${d.name}" + if (d.online) "" else " (не в сети)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (strangers.isNotEmpty()) {
                item {
                    SectionHeader("Не из вашего аккаунта: ${strangers.size}")
                    strangers.forEach { a ->
                        Text(
                            "${a.ip} · протокол ${a.version} · ${a.deviceId}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}
