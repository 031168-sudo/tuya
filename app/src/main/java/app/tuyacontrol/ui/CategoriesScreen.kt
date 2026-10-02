package app.tuyacontrol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tuyacontrol.DeviceUi
import app.tuyacontrol.data.Category
import app.tuyacontrol.data.DevicePref

/** Нижняя панель: «Устройства» и «Категории». */
@Composable
fun AppBottomBar(
    selected: Int,
    onDevices: () -> Unit,
    onCategories: () -> Unit,
    onHeating: () -> Unit,
    onMap: () -> Unit = {},
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == 1,
            onClick = onCategories,
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text("Категории") },
        )
        NavigationBarItem(
            selected = selected == 0,
            onClick = onDevices,
            icon = { Icon(DeviceIcons.vector("devices_other"), contentDescription = null) },
            label = { Text("Устройства") },
        )
        NavigationBarItem(
            selected = selected == 3,
            onClick = onMap,
            icon = { Icon(DeviceIcons.vector("home"), contentDescription = null) },
            label = { Text("Карта") },
        )
        NavigationBarItem(
            selected = selected == 2,
            onClick = onHeating,
            icon = { Icon(DeviceIcons.vector("whatshot"), contentDescription = null) },
            label = { Text("Отопление") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    categories: List<Category>,
    devices: List<DeviceUi>,
    prefs: Map<String, DevicePref>,
    onOpen: (String) -> Unit,
    onSave: (Category) -> Unit,
    onDelete: (String) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    var editing by remember { mutableStateOf<Category?>(null) }
    val counts = devices.groupingBy { prefs[it.id]?.categoryId }.eachCount()
    val uncategorized = counts[null] ?: 0

    Scaffold(
        topBar = { TopAppBar(title = { Text("Категории") }) },
        bottomBar = bottomBar,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(categories, key = { it.id }) { c ->
                Card(
                    onClick = { onOpen(c.id) },
                    colors = CardDefaults.cardColors(containerColor = Pastel.container(c.color)),
                    modifier = Modifier.aspectRatio(1.15f),
                ) {
                    Box(Modifier.fillMaxSize().padding(14.dp)) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .background(Pastel.accent(c.color), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(DeviceIcons.vector(c.icon), contentDescription = null, modifier = Modifier.size(30.dp))
                        }
                        IconButton(
                            onClick = { editing = c },
                            modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Изменить", modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.align(Alignment.BottomStart)) {
                            Text(
                                c.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                devicesText(counts[c.id] ?: 0),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item(key = "add") {
                OutlinedCard(
                    onClick = { editing = Category(name = "", icon = "home", color = categories.size) },
                    modifier = Modifier.aspectRatio(1.15f),
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(36.dp))
                        Text("Новая категория", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (uncategorized > 0 && categories.isNotEmpty()) {
                item(key = "hint", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    Text(
                        "Без категории: ${devicesText(uncategorized)}. Категорию устройству назначают кнопкой ⋮ на его карточке.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    editing?.let { c ->
        CategoryDialog(
            initial = c,
            isNew = categories.none { it.id == c.id },
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(c.id); editing = null },
        )
    }
}

private fun devicesText(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "устройств"
        n % 10 == 1 -> "устройство"
        n % 10 in 2..4 -> "устройства"
        else -> "устройств"
    }
    return "$n $word"
}

@Composable
private fun CategoryDialog(
    initial: Category,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Category) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableStateOf(Math.floorMod(initial.color, Pastel.colors.size)) }
    var icon by remember { mutableStateOf(initial.icon) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новая категория" else "Категория") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Цвет", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Pastel.colors.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    if (i == color) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                                    else BorderStroke(0.dp, c),
                                    CircleShape,
                                )
                                .clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == color) Icon(Icons.Filled.Check, contentDescription = Pastel.names[i], tint = androidx.compose.ui.graphics.Color.Black)
                        }
                    }
                }
                Text("Иконка", style = MaterialTheme.typography.labelMedium)
                IconPicker(selected = icon, accent = Pastel.accent(color), onSelect = { icon = it })
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Удалить категорию", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = name.trim(), color = color, icon = icon)) },
                enabled = name.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${initial.name}»?") },
            text = { Text("Устройства останутся, но будут без категории.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

/** Настройки устройства: иконка и категория. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsDialog(
    device: DeviceUi,
    pref: DevicePref?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (DevicePref) -> Unit,
) {
    var icon by remember { mutableStateOf(pref?.icon ?: device.defaultIcon) }
    var categoryId by remember { mutableStateOf(pref?.categoryId?.takeIf { id -> categories.any { it.id == id } }) }
    val color = categories.firstOrNull { it.id == categoryId }?.color

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Категория", style = MaterialTheme.typography.labelMedium)
                if (categories.isEmpty()) {
                    Text(
                        "Категорий пока нет — создайте их на вкладке «Категории».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text("Без категории") })
                        categories.forEach { c ->
                            FilterChip(
                                selected = categoryId == c.id,
                                onClick = { categoryId = c.id },
                                label = { Text(c.name, maxLines = 1) },
                                leadingIcon = {
                                    Box(Modifier.size(14.dp).background(Pastel.color(c.color), CircleShape))
                                },
                            )
                        }
                    }
                }
                Text("Иконка", style = MaterialTheme.typography.labelMedium)
                IconPicker(selected = icon, accent = Pastel.accent(color), onSelect = { icon = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Иконку по умолчанию не сохраняем — она подберётся автоматически
                onSave(DevicePref(icon = icon.takeIf { it != device.defaultIcon }, categoryId = categoryId))
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Сетка иконок с подписью выбранной. */
@Composable
private fun IconPicker(selected: String, accent: androidx.compose.ui.graphics.Color, onSelect: (String) -> Unit) {
    Column {
        Text(
            DeviceIcons.label(selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(46.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.height(230.dp),
        ) {
            items(DeviceIcons.all, key = { it.key }) { def ->
                val isSelected = def.key == selected
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) accent else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .then(
                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp))
                            else Modifier,
                        )
                        .clickable { onSelect(def.key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DeviceIcons.vector(def.key), contentDescription = def.label, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
