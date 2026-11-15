package app.tuyacontrol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import app.tuyacontrol.NartisUiState
import app.tuyacontrol.nartis.MeterReading
import app.tuyacontrol.nartis.NartisDevice
import java.util.Locale

/** Экран «Счётчик НАРТИС»: поиск по Bluetooth и чтение показаний. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NartisScreen(
    state: NartisUiState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onRead: (String) -> Unit,
    onPin: (String) -> Unit,
    onPassword: (String) -> Unit,
    onClearLog: () -> Unit,
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
    ) { granted -> if (granted.values.all { it }) onScan() }

    fun scanWithPermission() {
        val ok = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) onScan() else launcher.launch(permissions)
    }

    // Ушли с экрана — поиск не должен продолжать работать
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
                    "Счётчик отдаёт показания по Bluetooth (DLMS/СПОДЭС). " +
                        "Сначала закройте «Нартис Пульт»: счётчик пускает только одно подключение.",
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
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Text(
                    "PIN обычно 123456. Пароль чтения у НАРТИС общий для всех счётчиков; " +
                        "если не знаете — оставьте пустым, попробуем стандартные 00000001 и 00002080.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (state.scanning) onStopScan() else scanWithPermission() },
                        enabled = !state.busy,
                    ) {
                        Text(if (state.scanning) "Остановить поиск" else "Найти счётчик")
                    }
                    if (state.scanning) {
                        LinearProgressIndicator(Modifier.weight(1f))
                    }
                }
            }

            if (state.scanning && state.devices.isEmpty()) {
                item {
                    Text("Ничего не найдено. Счётчик должен быть рядом (до 5–10 метров).",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            items(state.devices, key = { it.address }) { d ->
                DeviceRow(d, enabled = !state.busy, onClick = { onRead(d.address) })
            }

            val reading = state.reading
            if (reading != null) {
                item { ReadingCard(reading) }
            }

            if (state.busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            if (state.status.isNotBlank()) {
                item {
                    Text(state.status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
private fun DeviceRow(device: NartisDevice, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${device.rssi} дБм",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${device.address} · нажмите, чтобы прочитать",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
    }
}

@Composable
private fun ReadingCard(reading: MeterReading) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Показания", style = MaterialTheme.typography.titleSmall)
            if (reading.serial != null) {
                Text("Серийный номер: ${reading.serial}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (reading.withPassword) "Прочитано с паролем" else "Прочитано без пароля",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            reading.totalKwh?.let { EnergyRow("Всего", it) }
            if (reading.t1Kwh != null || reading.t2Kwh != null || reading.t3Kwh != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                reading.t1Kwh?.let { EnergyRow("Т1", it) }
                reading.t2Kwh?.let { EnergyRow("Т2", it) }
                reading.t3Kwh?.let { EnergyRow("Т3", it) }
            }
            if (reading.powerW != null || reading.voltageV != null || reading.currentA != null) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                reading.powerW?.let { ValueRow("Мощность", fmt(it, "Вт", 0)) }
                reading.voltageV?.let { ValueRow("Напряжение", fmt(it, "В", 1)) }
                reading.currentA?.let { ValueRow("Ток", fmt(it, "А", 2)) }
            }
        }
    }
}

@Composable
private fun EnergyRow(name: String, kwh: Double) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(fmt(kwh, "кВт·ч", 3), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ValueRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun fmt(value: Double, unit: String, decimals: Int): String =
    String.format(Locale("ru"), "%.${decimals}f %s", value, unit)
