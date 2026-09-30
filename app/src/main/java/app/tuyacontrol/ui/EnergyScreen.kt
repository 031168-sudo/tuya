package app.tuyacontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tuyacontrol.EnergyUiState
import app.tuyacontrol.energy.Bucket
import app.tuyacontrol.energy.EnergyDb
import app.tuyacontrol.energy.EnergyReport
import app.tuyacontrol.energy.EnergyReports
import app.tuyacontrol.energy.PeriodType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyScreen(
    state: EnergyUiState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onOpenTariffs: () -> Unit,
    onSelect: (String?) -> Unit,
    onPeriod: (PeriodType, LocalDate?) -> Unit,
    onShift: (Long) -> Unit,
    onToday: () -> Unit,
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
                title = { Text("Энергия и расходы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onSync, enabled = !state.syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Загрузить историю")
                    }
                    TextButton(onClick = onOpenTariffs) { Text("Тарифы") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.syncing) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            state.progress ?: "Загрузка…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (state.devices.isEmpty()) {
                item {
                    Text(
                        "Нет устройств со счётчиком энергии. Вернитесь на главный экран и обновите список.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(
                        selected = state.selected == null,
                        onClick = { onSelect(null) },
                        label = { Text("Все") },
                    )
                    state.devices.forEach { d ->
                        FilterChip(
                            selected = state.selected == d.id,
                            onClick = { onSelect(d.id) },
                            label = { Text(d.name, maxLines = 1) },
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PeriodType.entries.forEach { p ->
                        FilterChip(
                            selected = state.period == p,
                            onClick = { onPeriod(p, null) },
                            label = { Text(p.title) },
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { onShift(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Раньше")
                    }
                    Text(
                        state.report?.title ?: EnergyReports.title(state.period, state.anchor),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onShift(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Позже")
                    }
                    TextButton(onClick = onToday) { Text("Сейчас") }
                }
            }

            val report = state.report
            if (report != null) {
                item { SummaryCard(report, state.tariffs.isEmpty(), onOpenTariffs) }

                if (report.buckets.isNotEmpty()) {
                    item { BarChart(report.buckets) }
                }

                if (state.selected == null && report.perDevice.size > 1) {
                    item { SectionTitle("По устройствам") }
                    items(report.perDevice) { b -> BucketRow(b, onClick = null) }
                }

                if (report.buckets.isNotEmpty()) {
                    item {
                        SectionTitle(
                            when (state.period) {
                                PeriodType.YEAR -> "По месяцам"
                                PeriodType.DAY -> "По часам"
                                else -> "По дням"
                            },
                        )
                    }
                    val rows = if (state.period == PeriodType.DAY) report.buckets else report.buckets.reversed()
                    items(rows) { b ->
                        BucketRow(
                            b,
                            onClick = if (b.drillType != null && b.drillDate != null) {
                                { onPeriod(b.drillType, b.drillDate) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            item { SectionTitle("Источник данных") }
            val shown = state.selected?.let { id -> state.devices.filter { it.id == id } } ?: state.devices
            items(shown, key = { it.id }) { d ->
                val m = state.meta[d.id]
                val text = when {
                    m == null -> "история ещё не загружалась"
                    m.mode == EnergyDb.SOURCE_STATS ->
                        "Статистика Tuya" +
                            (m.syncedUntil?.let { ", загружено по ${it.format(DATE)}" } ?: "") +
                            (m.total?.let { ", всего по Tuya ${EnergyReports.kwh(it)}" } ?: "")
                    else -> "Журнал устройства (облако хранит 7 дней): история копится, пока вы открываете приложение. " +
                        "Для полной истории подключите сервис Data Statistics в проекте Tuya."
                }
                Column {
                    Text(d.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    m?.lastError?.let {
                        Text("Ошибка: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SummaryCard(report: EnergyReport, noTariffs: Boolean, onOpenTariffs: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Расход", style = MaterialTheme.typography.bodySmall)
                    Text(EnergyReports.kwh(report.kwh), style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("Стоимость", style = MaterialTheme.typography.bodySmall)
                    Text(
                        (if (report.estimated) "≈ " else "") + EnergyReports.rub(report.cost) +
                            if (report.missingTariff) "*" else "",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            if (report.from != report.to) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "В среднем в день: ${EnergyReports.kwh(report.avgKwhPerDay)} · ${EnergyReports.rub(report.avgCostPerDay)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.zones.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                report.zones.forEach { z ->
                    Row {
                        Text(
                            EnergyReports.zoneName(z.index),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(EnergyReports.kwh(z.kwh), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(EnergyReports.rub(z.cost), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (report.estimated) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "≈ За часть дней Tuya не отдаёт почасовые данные — их расход разнесён по зонам " +
                        "по среднему суточному профилю счётчика.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (report.missingTariff) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (noTariffs) "* Тарифы не заданы — стоимость не посчитана."
                    else "* На часть дней или часов нет тарифа — они посчитаны как 0 ₽.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onOpenTariffs, contentPadding = PaddingValues(0.dp)) { Text("Указать тарифы") }
            }
        }
    }
}

@Composable
private fun BarChart(buckets: List<Bucket>) {
    val max = buckets.maxOfOrNull { it.kwh }?.takeIf { it > 0 } ?: return
    val barColor = MaterialTheme.colorScheme.primary
    val labelEvery = when {
        buckets.size <= 12 -> 1
        buckets.size <= 16 -> 2
        buckets.size == 24 -> 3
        else -> 5
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "макс. ${EnergyReports.kwh(max)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                buckets.forEach { b ->
                    val fraction = (b.kwh / max).toFloat().coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(if (b.kwh > 0) fraction.coerceAtLeast(0.02f) else 0f)
                            .background(barColor, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                buckets.forEachIndexed { i, b ->
                    Text(
                        if (i % labelEvery == 0) shortLabel(b.label, buckets.size) else "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** «05.03, пн» → «05» (для месяца) / «пн» (для недели); «Январь» → «янв». */
private fun shortLabel(label: String, count: Int): String = when {
    count == 12 -> label.take(3).lowercase()
    count == 24 -> label.substringBefore(":")
    count == 7 -> label.substringAfter(", ", label)
    else -> label.substringBefore(".")
}

@Composable
private fun BucketRow(b: Bucket, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                b.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                EnergyReports.kwh(b.kwh),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(110.dp),
                textAlign = TextAlign.End,
            )
            Text(
                (if (b.estimated) "≈" else "") + EnergyReports.rub(b.cost) + if (b.missingTariff) "*" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(100.dp),
                textAlign = TextAlign.End,
            )
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.size(18.dp))
            }
        }
        HorizontalDivider()
    }
}
