package app.tuyacontrol.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tuyacontrol.backup.BackupManager
import app.tuyacontrol.backup.BackupPasswordException
import app.tuyacontrol.data.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fileName(context: android.content.Context, uri: String?): String? {
    if (uri == null) return null
    return runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

/**
 * «Восстановить из резервной копии»: выбор файла, пароль (если файл зашифрован), восстановление и перезапуск.
 * [confirm] — переспросить перед заменой текущих настроек (в настройках — да, на чистой установке — нет).
 */
@Composable
fun RestoreFromBackup(confirm: Boolean, title: String = "Восстановить из резервной копии…", primary: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun fail(e: Throwable) {
        AppLog.e("Копия: восстановление не удалось", e)
        Toast.makeText(context, e.message ?: "Не удалось восстановить", Toast.LENGTH_LONG).show()
    }

    fun doRestore(snap: org.json.JSONObject) {
        scope.launch {
            busy = true
            val msg = withContext(Dispatchers.IO) { runCatching { BackupManager.restore(context, snap) } }
            busy = false
            msg.onSuccess {
                Toast.makeText(context, "$it. Перезапускаю…", Toast.LENGTH_LONG).show()
                BackupManager.restartApp(context)
            }.onFailure { fail(it) }
        }
    }

    fun decode(b: ByteArray, pw: String?) {
        try {
            val snap = BackupManager.decode(b, pw)
            askPassword = false
            if (confirm) snapshot = snap else doRestore(snap)
        } catch (e: BackupPasswordException) {
            wrong = e.wrong
            askPassword = true
        } catch (e: Exception) {
            fail(e)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { BackupManager.readFrom(context, uri) } }
            b.onSuccess { bytes = it; password = ""; decode(it, null) }.onFailure { fail(it) }
        }
    }

    if (primary) {
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(title) }
    } else {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(title) }
    }

    if (askPassword) {
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("Пароль копии") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Файл зашифрован. Введите пароль, который был задан при сохранении.")
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; wrong = false },
                        label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = wrong, supportingText = { if (wrong) Text("Неверный пароль") },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { bytes?.let { decode(it, password) } }, enabled = password.isNotEmpty()) { Text("Открыть") } },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text("Отмена") } },
        )
    }
    snapshot?.let { snap ->
        AlertDialog(
            onDismissRequest = { snapshot = null },
            title = { Text("Заменить настройки?") },
            text = { Text("Все текущие настройки, ключи и тарифы будут заменены данными из копии (${BackupManager.summary(snap)}). Приложение перезапустится.") },
            confirmButton = { TextButton(onClick = { snapshot = null; doRestore(snap) }) { Text("Заменить") } },
            dismissButton = { TextButton(onClick = { snapshot = null }) { Text("Отмена") } },
        )
    }
}

/** Экран «Резервная копия»: автокопия в выбранный файл, ручное сохранение, восстановление, пароль. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { BackupManager.Config(context) }
    var version by remember { mutableStateOf(0) }
    var passwordDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }

    fun saveNow(force: Boolean) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { BackupManager.autoBackup(context, force) }
            version++
            val err = cfg.lastError
            Toast.makeText(context, if (err != null) "Не сохранено: $err" else (r ?: "Копия уже актуальна"), Toast.LENGTH_LONG).show()
        }
    }

    val enable = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        BackupManager.rememberUri(context, uri)
        cfg.uri = uri.toString()
        cfg.lastHash = null
        cfg.lastError = null
        saveNow(force = true)
    }
    val once = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { BackupManager.writeTo(context, uri, BackupManager.encode(BackupManager.snapshot(context), cfg.password)) }
            }
            Toast.makeText(context, r.fold({ "Копия сохранена в файл" }, { "Не сохранено: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Резервная копия", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        // version меняется после записи — экран перечитывает настройки
        @Suppress("UNUSED_VARIABLE") val tick = version
        val on = cfg.uri != null
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Копия нужна, чтобы при переустановке или переносе на другой телефон не вводить всё заново: ключи Tuya, " +
                    "вход в Rubetek и Xiaomi, категории, комнаты, команды, зоны отопления и тарифы. " +
                    "История показаний не входит — она докачивается из облака.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Автоматическая копия", style = MaterialTheme.typography.titleMedium)
                    if (!on) {
                        Text(
                            "Выберите файл (лучше в Google Диске или в «Загрузках»): пока приложение открыто, оно само обновляет его " +
                                "каждую минуту, если что-то изменилось.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { enable.launch("moydom-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Включить автокопию…") }
                    } else {
                        Text("Файл: ${fileName(context, cfg.uri) ?: "выбран"}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (cfg.lastAt > 0) "Последняя копия: " + SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(cfg.lastAt))
                            else "Копия ещё не сохранялась",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        cfg.lastError?.let { Text("Ошибка: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { saveNow(force = true) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить сейчас") }
                        OutlinedButton(onClick = { enable.launch("moydom-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать другой файл…") }
                        TextButton(onClick = { cfg.uri = null; cfg.lastHash = null; version++ }) { Text("Выключить автокопию") }
                    }
                }
            }

            OutlinedButton(onClick = { once.launch("moydom-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить в файл вручную…") }
            RestoreFromBackup(confirm = true)

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пароль копии", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (cfg.password != null) "Задан: файл зашифрован. Без пароля его не открыть — запомните его."
                        else "Не задан: в файле ключи лежат открытым текстом. Храните его так же бережно, как сами ключи, " +
                            "или задайте пароль.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { newPassword = ""; passwordDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (cfg.password != null) "Изменить или убрать пароль…" else "Задать пароль…")
                    }
                }
            }
        }
    }

    if (passwordDialog) {
        AlertDialog(
            onDismissRequest = { passwordDialog = false },
            title = { Text("Пароль копии") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пароль хранится на телефоне зашифрованно и в копию не попадает. При восстановлении его спросят.")
                    OutlinedTextField(
                        value = newPassword, onValueChange = { newPassword = it },
                        label = { Text("Новый пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        cfg.password = newPassword.ifEmpty { null }
                        passwordDialog = false
                        if (cfg.uri != null) saveNow(force = true) else version++
                    },
                    enabled = newPassword.isNotEmpty(),
                ) { Text("Задать") }
            },
            dismissButton = {
                Row2(
                    cfg.password != null,
                    onRemove = {
                        cfg.password = null
                        passwordDialog = false
                        if (cfg.uri != null) saveNow(force = true) else version++
                    },
                    onCancel = { passwordDialog = false },
                )
            },
        )
    }
}

@Composable
private fun Row2(canRemove: Boolean, onRemove: () -> Unit, onCancel: () -> Unit) {
    androidx.compose.foundation.layout.Row {
        if (canRemove) TextButton(onClick = onRemove) { Text("Убрать пароль") }
        TextButton(onClick = onCancel) { Text("Отмена") }
    }
}
