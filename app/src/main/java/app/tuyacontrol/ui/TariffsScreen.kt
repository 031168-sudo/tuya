package app.tuyacontrol.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tuyacontrol.energy.EnergyDevice
import app.tuyacontrol.energy.Tariff
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffsScreen(
    tariffs: List<Tariff>,
    devices: List<EnergyDevice>,
    onBack: () -> Unit,
    onSave: (Tariff) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var editing by remember { mutableStateOf<Tariff?>(null) }
    var confirmDelete by remember { mutableStateOf<Tariff?>(null) }
    val names = devices.associate { it.id to it.name }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Тарифы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = newTariff(tariffs) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Добавить") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Text(
                    "Цена за 1 кВт·ч действует с даты начала по дату окончания включительно. " +
                        "Без даты окончания — действует по сей день. Тариф конкретного устройства " +
                        "важнее общего; при пересечении периодов берётся тариф с более поздней датой начала.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tariffs.isEmpty()) {
                item { Text("Тарифов пока нет. Нажмите «Добавить».") }
            }
            items(tariffs, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            String.format(Locale("ru"), "%.2f ₽ за кВт·ч", t.price),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "с ${t.start.format(DATE_FORMAT)} " +
                                (t.end?.let { "по ${it.format(DATE_FORMAT)}" } ?: "по настоящее время"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            t.deviceId?.let { names[it] ?: it } ?: "Все устройства",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (t.note.isNotBlank()) {
                            Text(t.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Row {
                            TextButton(onClick = { editing = t }) { Text("Изменить") }
                            TextButton(onClick = { confirmDelete = t }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { t ->
        TariffDialog(
            initial = t,
            devices = devices,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить тариф?") },
            text = { Text(String.format(Locale("ru"), "%.2f ₽ с %s", t.price, t.start.format(DATE_FORMAT))) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(t.id)
                    confirmDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } },
        )
    }
}

/** Новый тариф начинается на следующий день после последнего закрытого. */
private fun newTariff(existing: List<Tariff>): Tariff {
    val lastEnd = existing.mapNotNull { it.end }.maxByOrNull { it.toEpochDay() }
    val start = lastEnd?.plusDays(1) ?: LocalDate.now().withDayOfMonth(1)
    return Tariff(id = 0, deviceId = null, start = start, end = null, price = 0.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TariffDialog(
    initial: Tariff,
    devices: List<EnergyDevice>,
    onDismiss: () -> Unit,
    onSave: (Tariff) -> Unit,
) {
    var deviceId by remember { mutableStateOf(initial.deviceId) }
    var start by remember { mutableStateOf(initial.start.format(DATE_FORMAT)) }
    var end by remember { mutableStateOf(initial.end?.format(DATE_FORMAT).orEmpty()) }
    var price by remember {
        mutableStateOf(if (initial.price > 0) initial.price.toString().replace('.', ',') else "")
    }
    var note by remember { mutableStateOf(initial.note) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый тариф" else "Изменить тариф") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Цена за кВт·ч, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Действует с (дд.мм.гггг)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text("Действует по (пусто — по сей день)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Для каких устройств", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(selected = deviceId == null, onClick = { deviceId = null }, label = { Text("Все") })
                    devices.forEach { d ->
                        FilterChip(
                            selected = deviceId == d.id,
                            onClick = { deviceId = d.id },
                            label = { Text(d.name, maxLines = 1) },
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Примечание") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()
                val s = parseDate(start)
                val e = if (end.isBlank()) null else parseDate(end)
                error = when {
                    p == null || p <= 0 -> "Укажите цену больше нуля"
                    s == null -> "Неверная дата начала, формат дд.мм.гггг"
                    end.isNotBlank() && e == null -> "Неверная дата окончания, формат дд.мм.гггг"
                    e != null && e.isBefore(s) -> "Дата окончания раньше даты начала"
                    else -> null
                }
                if (error == null && p != null && s != null) {
                    onSave(initial.copy(deviceId = deviceId, start = s, end = e, price = p, note = note.trim()))
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), DATE_FORMAT)
} catch (e: DateTimeParseException) {
    null
}
