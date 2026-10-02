package app.tuyacontrol.ui

import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import app.tuyacontrol.background.SyncTargets
import app.tuyacontrol.background.HistorySyncWorker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.PaddingValues
import android.widget.Toast
import android.provider.Settings
import android.os.PowerManager
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tuyacontrol.UiState
import app.tuyacontrol.data.TuyaRegion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: UiState,
    onSave: (accessId: String, accessSecret: String, endpoint: String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenLocal: () -> Unit = {},
    onRubetekSendCode: (String) -> Unit = {},
    onRubetekSignIn: (String) -> Unit = {},
    onRubetekCancel: () -> Unit = {},
    onRubetekSignOut: () -> Unit = {},
    onXiaomiSignIn: (login: String, password: String, country: String) -> Unit = { _, _, _ -> },
    onXiaomiCaptcha: (String) -> Unit = {},
    onXiaomiVerify: (String) -> Unit = {},
    onXiaomiCancel: () -> Unit = {},
    onXiaomiSignOut: () -> Unit = {},
) {
    val saved = state.credentials
    var accessId by rememberSaveable { mutableStateOf(saved?.accessId.orEmpty()) }
    var accessSecret by rememberSaveable { mutableStateOf(saved?.accessSecret.orEmpty()) }
    var endpoint by rememberSaveable {
        mutableStateOf(saved?.endpoint ?: TuyaRegion.CENTRAL_EUROPE.host)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройка Tuya") },
                navigationIcon = {
                    if (saved != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Filled.Info, contentDescription = "Логи")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Ключи берутся на platform.tuya.com → Cloud → ваш проект → Overview. " +
                    "Они хранятся только на этом телефоне в зашифрованном виде.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = accessId,
                onValueChange = { accessId = it },
                label = { Text("Access ID / Client ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = accessSecret,
                onValueChange = { accessSecret = it },
                label = { Text("Access Secret / Client Secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Дата-центр", style = MaterialTheme.typography.titleSmall)
            TuyaRegion.entries.forEach { region ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = endpoint == region.host,
                            onClick = { endpoint = region.host },
                            role = Role.RadioButton,
                        ),
                ) {
                    RadioButton(selected = endpoint == region.host, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(region.title)
                        Text(
                            region.host,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            state.setupError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { onSave(accessId, accessSecret, endpoint) },
                enabled = !state.setupInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.setupInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Проверить и сохранить")
                }
            }

            if (saved != null) {
                OutlinedButton(onClick = onOpenLocal, modifier = Modifier.fillMaxWidth()) {
                    Text("Устройства в локальной сети (Wi-Fi)")
                }
                BackgroundSyncSection()
                RubetekSection(state, onRubetekSendCode, onRubetekSignIn, onRubetekCancel, onRubetekSignOut)
                XiaomiSection(state, onXiaomiSignIn, onXiaomiCaptcha, onXiaomiVerify, onXiaomiCancel, onXiaomiSignOut)
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text("Удалить ключи с телефона")
                }
            }
        }
    }
}

/** Фоновая загрузка истории: состояние, запуск вручную, разрешение работать в фоне. */
@Composable
private fun BackgroundSyncSection() {
    val context = LocalContext.current
    val targets = remember { SyncTargets(context) }
    var tick by remember { mutableStateOf(0) }
    // Обновляем показания раз в 5 секунд, пока экран открыт
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            tick++
        }
    }
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = remember(tick) { power?.isIgnoringBatteryOptimizations(context.packageName) == true }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.US) }
    fun time(ms: Long) = if (ms > 0) fmt.format(Date(ms)) else "ещё не было"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Фоновая загрузка истории", style = MaterialTheme.typography.titleSmall)
            Text(
                "Каждую ночь около 3:00, при любом интернете и заряде от 50%, приложение само докачивает " +
                    "журналы счётчиков и датчиков — чтобы в истории не было дыр (Tuya хранит журнал 7 дней).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            key(tick) {
                Text("Последний запуск: ${time(targets.lastRun)}", style = MaterialTheme.typography.bodySmall)
                Text("Последняя успешная загрузка: ${time(targets.lastSuccess)}", style = MaterialTheme.typography.bodySmall)
                if (targets.lastResult.isNotEmpty()) {
                    Text("Результат: ${targets.lastResult}", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                onClick = {
                    HistorySyncWorker.runNow(context)
                    Toast.makeText(context, "Загрузка запущена в фоне", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Загрузить сейчас") }

            if (unrestricted) {
                Text(
                    "✓ Работа в фоне разрешена",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnlineColor,
                )
            } else {
                Text(
                    "Телефон может усыплять фоновую загрузку. Разрешите приложению работать в фоне без ограничений.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + context.packageName))
                        runCatching { context.startActivity(intent) }
                            .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Разрешить работу в фоне") }
            }
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:" + context.packageName)),
                    )
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Настройки приложения: автозапуск и батарея →") }
        }
    }
}


/** Подключение аккаунта Rubetek: телефон/почта -> код -> готово. */
@Composable
private fun RubetekSection(
    state: UiState,
    onSendCode: (String) -> Unit,
    onSignIn: (String) -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rubetek", style = MaterialTheme.typography.titleMedium)
            val connected = state.rubetekLogin
            val sentTo = state.rubetekCodeSentTo
            when {
                connected != null -> {
                    Text(
                        "Подключён" + (if (connected.isNotEmpty()) " ($connected)" else "") +
                            " · устройств: ${state.rubetekCount}. Они показываются в общем списке.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Отключить Rubetek") }
                }
                sentTo != null -> {
                    Text(state.rubetekCodeHint ?: "Код отправлен на $sentTo", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("Код") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onSignIn(code) },
                        enabled = !state.rubetekBusy && code.length >= 4,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.rubetekBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Подключить")
                    }
                    TextButton(onClick = { code = ""; onCancel() }) { Text("Другой телефон или почта") }
                }
                else -> {
                    Text(
                        "Телефон или почта от приложения Rubetek. На телефон позвонят (код — последние 4 цифры номера), на почту придёт письмо. " +
                            "Код нужен один раз; часто запрашивать не стоит, Rubetek может временно заблокировать.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it },
                        label = { Text("Телефон (+7…) или почта") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onSendCode(login) },
                        enabled = !state.rubetekBusy && login.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.rubetekBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Получить код")
                    }
                }
            }
            state.rubetekError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


/** Подключение Mi-аккаунта: логин и пароль -> (капча) -> (код подтверждения) -> готово. */
@Composable
private fun XiaomiSection(
    state: UiState,
    onSignIn: (String, String, String) -> Unit,
    onCaptcha: (String) -> Unit,
    onVerify: (String) -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("ru") }
    var answer by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Xiaomi (Mi Home)", style = MaterialTheme.typography.titleMedium)
            val connected = state.xiaomiLogin
            val captcha = state.xiaomiCaptcha
            val verifyTo = state.xiaomiVerifyTo
            when {
                connected != null -> {
                    Text(
                        "Подключён" + (if (connected.isNotEmpty()) " ($connected)" else "") +
                            " · устройств: ${state.xiaomiCount}. Управление по Wi-Fi напрямую; " +
                            "если устройство по Wi-Fi не отвечает — через облако (в режиме «Авто»).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Отключить Xiaomi") }
                }
                captcha != null -> {
                    Text("Xiaomi просит ввести символы с картинки", style = MaterialTheme.typography.bodyMedium)
                    val bmp = remember(captcha) {
                        android.graphics.BitmapFactory.decodeByteArray(captcha, 0, captcha.size)?.asImageBitmap()
                    }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(bmp, contentDescription = "Капча", modifier = Modifier.fillMaxWidth().height(80.dp))
                    }
                    OutlinedTextField(
                        value = answer, onValueChange = { answer = it.trim() },
                        label = { Text("Символы") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onCaptcha(answer); answer = "" },
                        enabled = !state.xiaomiBusy && answer.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { if (state.xiaomiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Продолжить") }
                    TextButton(onClick = { answer = ""; onCancel() }) { Text("Отмена") }
                }
                verifyTo != null -> {
                    Text("Xiaomi отправил код подтверждения на $verifyTo. Введите его:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = answer, onValueChange = { answer = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("Код") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onVerify(answer); answer = "" },
                        enabled = !state.xiaomiBusy && answer.length >= 4,
                        modifier = Modifier.fillMaxWidth(),
                    ) { if (state.xiaomiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Подтвердить") }
                    TextButton(onClick = { answer = ""; onCancel() }) { Text("Отмена") }
                }
                else -> {
                    Text(
                        "Логин (почта, телефон или Xiaomi ID) и пароль от приложения Mi Home. Нужны один раз, чтобы получить " +
                            "список устройств и их ключи (token); пароль на телефоне не сохраняется.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = login, onValueChange = { login = it },
                        label = { Text("Логин Mi-аккаунта") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Сервер (регион в Mi Home)", style = MaterialTheme.typography.titleSmall)
                    app.tuyacontrol.xiaomi.XiaomiCloud.COUNTRIES.forEach { (code, title) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = country == code, onClick = { country = code }, role = Role.RadioButton,
                            ),
                        ) {
                            RadioButton(selected = country == code, onClick = null)
                            Text(title, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Button(
                        onClick = { onSignIn(login, password, country); password = "" },
                        enabled = !state.xiaomiBusy && login.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { if (state.xiaomiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Подключить") }
                }
            }
            state.xiaomiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
