package app.tuyacontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.tuyacontrol.ui.AppTheme
import app.tuyacontrol.ui.DevicesScreen
import app.tuyacontrol.ui.LogScreen
import app.tuyacontrol.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }
}

@Composable
private fun App(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()

    BackHandler(enabled = state.screen != Screen.Devices && state.credentials != null) {
        viewModel.back()
    }

    when (state.screen) {
        Screen.Setup -> SetupScreen(
            state = state,
            onSave = viewModel::saveCredentials,
            onClear = viewModel::clearCredentials,
            onBack = { viewModel.back() },
            onOpenLog = { viewModel.open(Screen.Log) },
        )
        Screen.Devices -> DevicesScreen(
            state = state,
            onRefresh = { viewModel.refresh() },
            onCommand = viewModel::sendCommand,
            onOpenSettings = { viewModel.open(Screen.Setup) },
            onOpenLog = { viewModel.open(Screen.Log) },
            onMessageShown = viewModel::messageShown,
        )
        Screen.Log -> LogScreen(
            onBack = {
                if (!viewModel.back()) viewModel.open(Screen.Setup)
            },
        )
    }
}
