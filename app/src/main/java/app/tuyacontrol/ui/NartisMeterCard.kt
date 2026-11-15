package app.tuyacontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.data.Category
import app.tuyacontrol.energy.Tariff
import app.tuyacontrol.nartis.MeterReading
import app.tuyacontrol.nartis.NartisSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val READ_AT = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))

/**
 * Карточка счётчика НАРТИС в списке устройств: показания, тарифы по зонам и
 * напряжение/ток по каждой фазе.
 */
@Composable
fun NartisMeterCard(
    device: DeviceUi,
    reading: MeterReading?,
    lastReadAt: Long,
    history: List<NartisSnapshot>,
    tariffs: List<Tariff>,
    category: Category?,
    room: String?,
    busy: Boolean,
    onEdit: () -> Unit,
    onRead: () -> Unit,
    onHistory: () -> Unit,
) {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val tariff = NartisPeriods.tariffFor(tariffs, today)
    val todayPeriod = NartisPeriods.period(history, todayStart, tariff)
    val monthPeriod = NartisPeriods.period(history, monthStart, tariff)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    DeviceIcons.vector("electric_meter"),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(if (lastReadAt > 0) "прочитан ${formatTime(lastReadAt)}" else "ещё не читался")
                            category?.let { append(" · ${it.name}") }
                            room?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Настройка")
                }
            }

            if (reading == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Показаний пока нет. Поднесите телефон к счётчику и нажмите «Прочитать».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                EnergyLine("Всего", reading.totalKwh, null)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                EnergyLine("Т1", reading.t1Kwh, NartisPeriods.price(tariff, 0))
                EnergyLine("Т2", reading.t2Kwh, NartisPeriods.price(tariff, 1))
                EnergyLine("Т3", reading.t3Kwh, NartisPeriods.price(tariff, 2))

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                PhaseLine("A", reading.voltageA, reading.currentA)
                PhaseLine("B", reading.voltageB, reading.currentB)
                PhaseLine("C", reading.voltageC, reading.currentC)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Мощность", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(fmt(reading.powerW, "Вт", 0), style = MaterialTheme.typography.bodyMedium)
                }
                reading.frequency?.let {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Частота", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(fmt(it, "Гц", 1), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (history.size > 1) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                PeriodLine("Сегодня", todayPeriod)
                PeriodLine("С начала месяца", monthPeriod)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRead, enabled = !busy) {
                    Text(if (busy) "Читаю…" else "Прочитать")
                }
                OutlinedButton(onClick = onHistory) { Text("История") }
            }
        }
    }
}

@Composable
private fun EnergyLine(name: String, kwh: Double?, price: Double?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt(kwh, "кВт·ч", 3), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (price != null) {
                Text(
                    "%.2f ₽/кВт·ч".format(Locale("ru"), price),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhaseLine(phase: String, voltage: Double?, current: Double?) {
    Row(Modifier.fillMaxWidth()) {
        Text("Фаза $phase", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "${fmt(voltage, "В", 1)} · ${fmt(current, "А", 2)}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PeriodLine(name: String, period: MeterPeriod) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(
            "${fmt(period.kwh, "кВт·ч", 3)} · ${fmt(period.cost, "₽", 2)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Показания для экрана счётчика (те же строки, без рамки). */
@Composable
fun NartisReadings(reading: MeterReading, tariff: Tariff?) {
    EnergyLine("Всего", reading.totalKwh, null)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    EnergyLine("Т1", reading.t1Kwh, NartisPeriods.price(tariff, 0))
    EnergyLine("Т2", reading.t2Kwh, NartisPeriods.price(tariff, 1))
    EnergyLine("Т3", reading.t3Kwh, NartisPeriods.price(tariff, 2))
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(6.dp))
    PhaseLine("A", reading.voltageA, reading.currentA)
    PhaseLine("B", reading.voltageB, reading.currentB)
    PhaseLine("C", reading.voltageC, reading.currentC)
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth()) {
        Text("Мощность", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(fmt(reading.powerW, "Вт", 0), style = MaterialTheme.typography.bodyMedium)
    }
    reading.frequency?.let {
        Row(Modifier.fillMaxWidth()) {
            Text("Частота", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(fmt(it, "Гц", 1), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatTime(time: Long): String =
    READ_AT.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

private fun fmt(value: Double?, unit: String, decimals: Int): String =
    if (value == null) "—" else String.format(Locale("ru"), "%.${decimals}f %s", value, unit)
