package app.tuyacontrol.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.HorizontalDivider
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
import app.tuyacontrol.energy.EnergyReports
import app.tuyacontrol.energy.Tariff
import app.tuyacontrol.energy.TariffZone
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val RU = Locale("ru")

private fun price(v: Double) = String.format(RU, "%.2f ₽", v)

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
                onClick = { editing = newTariff(tariffs, template = null) },
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
                    "Тариф действует с даты начала по дату окончания включительно (без даты окончания — " +
                        "по сей день) и состоит из 1–3 зон по времени суток: Т1, Т2, Т3. " +
                        "Расход каждого часа умножается на цену зоны, в которую попадает этот час. " +
                        "Тариф отдельного счётчика важнее общего.",
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
                            "с ${t.start.format(DATE_FORMAT)} " +
                                (t.end?.let { "по ${it.format(DATE_FORMAT)}" } ?: "по настоящее время"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            t.deviceId?.let { names[it] ?: it } ?: "Все счётчики",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        t.zones.forEachIndexed { i, z ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text(
                                    EnergyReports.zoneName(i),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.width(36.dp),
                                )
                                Text(z.intervalsText(), modifier = Modifier.weight(1f))
                                Text(price(z.price) + "/кВт·ч")
                            }
                        }
                        if (t.note.isNotBlank()) {
                            Text(t.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                        Row {
                            TextButton(onClick = { editing = t }) { Text("Изменить") }
                            TextButton(onClick = { editing = newTariff(tariffs, template = t) }) { Text("Копировать") }
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
            text = { Text("Тариф с ${t.start.format(DATE_FORMAT)}") },
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

/** Новый тариф начинается на следующий день после последнего закрытого; «Копировать» переносит зоны. */
private fun newTariff(existing: List<Tariff>, template: Tariff?): Tariff {
    val start = template?.end?.plusDays(1)
        ?: existing.mapNotNull { it.end }.maxByOrNull { it.toEpochDay() }?.plusDays(1)
        ?: LocalDate.now().withDayOfMonth(1)
    return Tariff(
        id = 0,
        deviceId = template?.deviceId,
        start = start,
        end = null,
        zones = template?.zones ?: listOf(TariffZone(0.0, 0, 24)),
    )
}

/** Поля одной зоны в форме (строки, пока пользователь вводит). */
private data class ZoneForm(
    val price: String = "",
    val from1: String = "",
    val to1: String = "",
    val from2: String = "",
    val to2: String = "",
) {
    val isEmpty: Boolean get() = listOf(price, from1, to1, from2, to2).all { it.isBlank() }

    companion object {
        fun of(z: TariffZone?): ZoneForm = if (z == null) ZoneForm() else ZoneForm(
            price = if (z.price > 0) z.price.toString().replace('.', ',') else "",
            from1 = z.from1.toString(),
            to1 = z.to1.toString(),
            from2 = z.from2?.toString().orEmpty(),
            to2 = z.to2?.toString().orEmpty(),
        )
    }
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
    var zones by remember { mutableStateOf(List(3) { ZoneForm.of(initial.zones.getOrNull(it)) }) }
    var note by remember { mutableStateOf(initial.note) }
    var error by remember { mutableStateOf<String?>(null) }

    fun updateZone(i: Int, f: (ZoneForm) -> ZoneForm) {
        zones = zones.mapIndexed { idx, z -> if (idx == i) f(z) else z }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый тариф" else "Изменить тариф") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallField(start, { start = it }, "Действует с", Modifier.weight(1f), KeyboardType.Number)
                    SmallField(end, { end = it }, "по (пусто — сейчас)", Modifier.weight(1f), KeyboardType.Number)
                }
                Text(
                    "Даты в формате дд.мм.гггг. Часы — целые от 0 до 24, «с» включительно, «по» не включая: " +
                        "ночь 23–7, день 7–23, весь день 0–24. Второй интервал нужен, если зона делится на два " +
                        "куска (например, пик 7–10 и 17–21). Незаполненные зоны не используются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                zones.forEachIndexed { i, z ->
                    HorizontalDivider()
                    Text(EnergyReports.zoneName(i), style = MaterialTheme.typography.titleSmall)
                    SmallField(z.price, { v -> updateZone(i) { it.copy(price = v) } }, "Цена за кВт·ч, ₽",
                        Modifier.fillMaxWidth(), KeyboardType.Decimal)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallField(z.from1, { v -> updateZone(i) { it.copy(from1 = v) } }, "с, ч", Modifier.weight(1f), KeyboardType.Number)
                        SmallField(z.to1, { v -> updateZone(i) { it.copy(to1 = v) } }, "по, ч", Modifier.weight(1f), KeyboardType.Number)
                        SmallField(z.from2, { v -> updateZone(i) { it.copy(from2 = v) } }, "и с", Modifier.weight(1f), KeyboardType.Number)
                        SmallField(z.to2, { v -> updateZone(i) { it.copy(to2 = v) } }, "по", Modifier.weight(1f), KeyboardType.Number)
                    }
                }
                HorizontalDivider()
                Text("Для каких счётчиков", style = MaterialTheme.typography.labelMedium)
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
                SmallField(note, { note = it }, "Примечание", Modifier.fillMaxWidth(), KeyboardType.Text)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = validate(start, end, zones)
                error = result.error
                val s = result.start
                if (result.error == null && s != null) {
                    onSave(
                        initial.copy(
                            deviceId = deviceId,
                            start = s,
                            end = result.end,
                            zones = result.zones,
                            note = note.trim(),
                        ),
                    )
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun SmallField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    keyboard: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
    )
}

private class Validated(
    val start: LocalDate?,
    val end: LocalDate?,
    val zones: List<TariffZone>,
    val error: String?,
)

private fun validate(startText: String, endText: String, forms: List<ZoneForm>): Validated {
    fun fail(msg: String) = Validated(null, null, emptyList(), msg)

    val start = parseDate(startText) ?: return fail("Неверная дата начала, формат дд.мм.гггг")
    val end = if (endText.isBlank()) null else parseDate(endText) ?: return fail("Неверная дата окончания, формат дд.мм.гггг")
    if (end != null && end.isBefore(start)) return fail("Дата окончания раньше даты начала")

    val zones = mutableListOf<TariffZone>()
    forms.forEachIndexed { i, f ->
        if (f.isEmpty) return@forEachIndexed
        val name = EnergyReports.zoneName(i)
        if (zones.size != i) return fail("Заполняйте зоны по порядку: $name заполнена, а предыдущая пустая")
        val price = f.price.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()
        if (price == null || price <= 0) return fail("$name: укажите цену больше нуля")
        val from1 = hour(f.from1) ?: return fail("$name: час «с» — число от 0 до 24")
        val to1 = hour(f.to1) ?: return fail("$name: час «по» — число от 0 до 24")
        if (from1 % 24 == to1 % 24 && !(from1 == 0 && to1 == 24)) return fail("$name: интервал $from1–$to1 пустой")
        var from2: Int? = null
        var to2: Int? = null
        if (f.from2.isNotBlank() || f.to2.isNotBlank()) {
            from2 = hour(f.from2) ?: return fail("$name: второй интервал, час «с» — число от 0 до 24")
            to2 = hour(f.to2) ?: return fail("$name: второй интервал, час «по» — число от 0 до 24")
            if (from2 % 24 == to2 % 24) return fail("$name: второй интервал $from2–$to2 пустой")
        }
        zones += TariffZone(price, from1 % 24, if (to1 == 0) 24 else to1, from2?.rem(24), to2?.let { if (it == 0) 24 else it })
    }
    if (zones.isEmpty()) return fail("Заполните хотя бы зону Т1")

    // Каждый час суток должен попадать ровно в одну зону
    val uncovered = mutableListOf<Int>()
    val overlapped = mutableListOf<Int>()
    for (h in 0 until 24) {
        when (zones.count { it.covers(h) }) {
            0 -> uncovered += h
            1 -> Unit
            else -> overlapped += h
        }
    }
    if (uncovered.isNotEmpty()) return fail("Часы без зоны: ${hoursText(uncovered)}")
    if (overlapped.isNotEmpty()) return fail("Часы попадают в несколько зон: ${hoursText(overlapped)}")
    return Validated(start, end, zones, null)
}

private fun hoursText(hours: List<Int>): String = hours.joinToString(", ") { "$it–${it + 1}" }

/** «7», «07», «7:00», «07:00» -> 7. */
private fun hour(text: String): Int? {
    val h = text.trim().substringBefore(':').toIntOrNull() ?: return null
    return h.takeIf { it in 0..24 }
}

private fun parseDate(text: String): LocalDate? = try {
    LocalDate.parse(text.trim(), DATE_FORMAT)
} catch (e: DateTimeParseException) {
    null
}
