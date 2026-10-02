package app.tuyacontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tuyacontrol.CommandRun
import app.tuyacontrol.commands.Action
import app.tuyacontrol.commands.ActionKind
import app.tuyacontrol.commands.Command
import app.tuyacontrol.commands.StepStatus
import app.tuyacontrol.commands.Target

private val OkGreen = Color(0xFF2E9D4F)

/** Блок «Команды» над категориями: заголовок (открывает редактор) и плитки (выполняют команду). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandsBlock(commands: List<Command>, onOpenEditor: () -> Unit, onRun: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenEditor).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Команды", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Edit, contentDescription = "Создание и редактирование команд", modifier = Modifier.size(20.dp))
        }
        if (commands.isEmpty()) {
            Text(
                "Команд пока нет — нажмите «Команды», чтобы создать",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                commands.forEach { c ->
                    Text(
                        c.name.ifEmpty { "Без названия" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Pastel.accent(c.color))
                            .clickable { onRun(c.id) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Text("Категории", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Окно выполнения: каждое действие с галкой или крестом. Закрывается только кнопкой «ОК». */
@Composable
fun CommandRunDialog(run: CommandRun, onOk: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(run.name, style = MaterialTheme.typography.titleLarge)
                val ok = run.steps.count { it.status == StepStatus.OK }
                val fail = run.steps.count { it.status == StepStatus.FAIL }
                Text(
                    if (run.done) "Готово: подтверждено $ok, не подтверждено $fail" else "Выполняется… ($ok из ${run.steps.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (run.done && fail > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(run.steps) { s ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                when (s.status) {
                                    StepStatus.WAIT -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.outline, CircleShape))
                                    StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    StepStatus.OK -> Icon(Icons.Filled.Check, contentDescription = "подтверждено", tint = OkGreen)
                                    StepStatus.FAIL -> Icon(Icons.Filled.Close, contentDescription = "не подтверждено", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.bodyMedium)
                                s.detail?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Button(onClick = onOk, enabled = run.done, modifier = Modifier.fillMaxWidth()) {
                    Text(if (run.done) "ОК" else "Выполняется…")
                }
            }
        }
    }
}

/** Экран «Создание и редактирование команд»: нажатие на команду — только редактирование, не выполнение. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(
    commands: List<Command>,
    devices: List<app.tuyacontrol.DeviceUi>,
    categories: List<app.tuyacontrol.data.Category>,
    prefs: Map<String, app.tuyacontrol.data.DevicePref>,
    onBack: () -> Unit,
    onSave: (Command) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<Command?>(null) }
    // Группы устройств для выбора в действиях: устройство выбирается отдельно, группы лишь объединяют
    val groups = remember(devices, categories, prefs) {
        Target.entries.filter { it != Target.ZONES }.associateWith { app.tuyacontrol.commands.groupDevices(it, devices, categories, prefs) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Создание и редактирование команд") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Button(onClick = { editing = Command(name = "", color = commands.size, actions = emptyList()) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить команду")
                }
            }
            items(commands, key = { it.id }) { c ->
                Card(
                    onClick = { editing = c },
                    colors = CardDefaults.cardColors(containerColor = Pastel.container(c.color)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(c.name.ifEmpty { "Без названия" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (c.actions.isEmpty()) {
                            Text("Нет действий", style = MaterialTheme.typography.bodySmall)
                        } else {
                            c.actions.forEachIndexed { i, a ->
                                Text("${i + 1}. ${a.text()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { c ->
        CommandEditDialog(
            groups = groups,
            initial = c,
            isNew = commands.none { it.id == c.id },
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(c.id); editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandEditDialog(
    groups: Map<Target, List<app.tuyacontrol.DeviceUi>>,
    initial: Command,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Command) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableStateOf(initial.color) }
    val actions = remember { mutableStateListOf<Action>().apply { addAll(initial.actions) } }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
                    Text(if (isNew) "Новая команда" else "Команда", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { onSave(initial.copy(name = name.trim(), color = color, actions = actions.toList())) },
                        enabled = name.isNotBlank(),
                    ) { Text("Сохранить") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Цвет", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pastel.colors.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Pastel.accent(i))
                                    .clickable { color = i },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (Math.floorMod(color, Pastel.colors.size) == i) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Действия (выполняются по порядку)", style = MaterialTheme.typography.titleSmall)
                    if (actions.isEmpty()) {
                        Text("Пока нет действий", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    actions.forEachIndexed { i, a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. ${a.text()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { if (i > 0) { actions.removeAt(i); actions.add(i - 1, a) } }, enabled = i > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Выше")
                            }
                            IconButton(onClick = { if (i < actions.size - 1) { actions.removeAt(i); actions.add(i + 1, a) } }, enabled = i < actions.size - 1) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Ниже")
                            }
                            IconButton(onClick = { actions.removeAt(i) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Убрать")
                            }
                        }
                    }
                    TextButton(onClick = { picking = true }) { Text("+ Добавить действие") }
                    if (!isNew) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Удалить команду", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }

    if (picking) {
        ActionPicker(groups, onDismiss = { picking = false }, onPick = { actions.add(it); picking = false })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить команду «${initial.name}»?") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Выбор действия: «все зоны отопления» — сразу действие; для устройств — сначала само устройство
 * (сгруппированы по Rubetek / отопление / ванна / водогрейки), потом что с ним сделать, для температуры — число.
 */
@Composable
private fun ActionPicker(
    groups: Map<Target, List<app.tuyacontrol.DeviceUi>>,
    onDismiss: () -> Unit,
    onPick: (Action) -> Unit,
) {
    var device by remember { mutableStateOf<Pair<Target, app.tuyacontrol.DeviceUi>?>(null) }
    var kind by remember { mutableStateOf<ActionKind?>(null) }
    var temp by remember { mutableStateOf("21") }
    val k = kind
    val dev = device
    fun pick(a: ActionKind) {
        if (a.needsTemp) kind = a else onPick(Action(a, null, dev?.second?.id, dev?.second?.name))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dev?.second?.name ?: "Действие") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    k != null -> {
                        Text(k.title)
                        Spacer(Modifier.size(8.dp))
                        OutlinedTextField(
                            temp, { temp = it },
                            label = { Text("Температура, °C") },
                            singleLine = true,
                            isError = temp.replace(',', '.').toDoubleOrNull() == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    dev != null -> ActionKind.entries.filter { it.target == dev.first }.forEach { a ->
                        PickRow(a.title) { pick(a) }
                    }
                    else -> {
                        Text(Target.ZONES.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                        ActionKind.entries.filter { it.target == Target.ZONES }.forEach { a -> PickRow(a.title) { onPick(Action(a)) } }
                        groups.forEach { (t, list) ->
                            Text(t.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                            if (list.isEmpty()) {
                                Text("нет устройств", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            list.forEach { d -> PickRow(d.name) { device = t to d } }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (k != null) {
                val t = temp.replace(',', '.').toDoubleOrNull()
                TextButton(
                    onClick = { onPick(Action(k, t, dev?.second?.id, dev?.second?.name)) },
                    enabled = t != null && t in 5.0..35.0,
                ) { Text("Добавить") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                when {
                    k != null -> kind = null
                    dev != null -> device = null
                    else -> onDismiss()
                }
            }) { Text(if (k != null || dev != null) "Назад" else "Отмена") }
        },
    )
}

@Composable
private fun PickRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
    )
}
