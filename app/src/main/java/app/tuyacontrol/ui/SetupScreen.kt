package app.tuyacontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text("Удалить ключи с телефона")
                }
            }
        }
    }
}
