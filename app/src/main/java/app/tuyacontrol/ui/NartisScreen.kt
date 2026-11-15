package app.tuyacontrol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.tuyacontrol.NartisUiState
import app.tuyacontrol.energy.Tariff
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HISTORY_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale("ru"))

/** Экран «Счётчик НАРТИС»: свой счётчик по серийному номеру, чтение и история. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NartisScreen(
    state: NartisUiState,
    onBack: () -> Unit,
    onSerial: (String) -> Unit,
    onRead: () -> Unit,
    onAutoRead: () -> Unit,
    onStopScan: () -> Unit,
    onPin: (String) -> Unit,
    onPassword: (String) -> Unit,
    onClearLog: () -> Unit,
    tariffs: List<Tariff> = emptyList(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.all { it }) onRead() }

    fun readWithPermission() {
        val ok = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) onRead() else launcher.launch(permissions)
    }

    LaunchedEffect(Unit) { onAutoRead() }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onStopScan() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Счётчик НАРТИС", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (state.log.isNotEmpty()) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(state.log.joinToString("\n")))
                            Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
                        }) { Text("Копировать") }
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Журнал НАРТИС")
                                putExtra(Intent.EXTRA_TEXT, state.log.joinToString("\n"))
                            }
                            context.startActivity(Intent.createChooser(intent, "Передать журнал"))
                        }) { Text("Поделиться") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Text(
                    "Счётчик читается по Bluetooth, когда телефон рядом с ним (5–10 метров). " +
                        "Перед чтением закройте «Нартис Пульт»: счётчик пускает только одно подключение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                OutlinedTextField(
                    value = state.serial,
                    onValueChange = onSerial,
                    label = { Text("Серийный номер счётчика") },
                    placeholder = { Text("например, 023260164390") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    "Подключаемся только к прибору с этим номером. Чтобы читать другой счётчик, " +
                        "введите его серийный номер.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.pin,
                        onValueChange = onPin,
                        label = { Text("PIN Bluetooth") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPassword,
                        label = { Text("Пароль чтения") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { readWithPermission() }, enabled = !state.busy && state.serial.isNotBlank()) {
                        Text(if (state.busy) "Читаю…" else "Прочитать")
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.weight(1f))
                }
            }

            if (state.status.isNotBlank()) {
                item {
                    Text(state.status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }

            val reading = state.reading
            if (reading != null) {
                val tariff = NartisPeriods.tariffFor(tariffs, LocalDate.now())
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Показания", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            NartisReadings(reading, tariff)
                        }
                    }
                }
            }

            if (state.history.size > 1) {
                item { HistoryPeriods(state, tariffs) }
                item { SectionTitle("Последние чтения") }
                val rows = state.history.takeLast(20).reversed()
                rows.forEach { snap ->
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                HISTORY_TIME.format(Instant.ofEpochMilli(snap.time).atZone(ZoneId.systemDefault())),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "%.3f кВт·ч".format(Locale("ru"), snap.total ?: 0.0),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }

            if (state.log.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Журнал", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(state.log.joinToString("\n")))
                            Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
                        }) { Text("Копировать") }
                        TextButton(onClick = onClearLog) { Text("Очистить") }
                    }
                }
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    ) {
                        state.log.takeLast(80).forEach { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPeriods(state: NartisUiState, tariffs: List<Tariff>) {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val tariff = NartisPeriods.tariffFor(tariffs, today)
    val todayPeriod = NartisPeriods.period(state.history, todayStart, tariff)
    val monthPeriod = NartisPeriods.period(state.history, monthStart, tariff)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Расход и стоимость", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            PeriodSummary("Сегодня", todayPeriod)
            PeriodSummary("С начала месяца", monthPeriod)
        }
    }
}

@Composable
private fun PeriodSummary(name: String, period: MeterPeriod) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "%.3f кВт·ч · %.2f ₽".format(Locale("ru"), period.kwh, period.cost),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}
