package app.tuyacontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.tuyacontrol.energy.EnergyDevice
import app.tuyacontrol.ui.EnergyScreen
import app.tuyacontrol.ui.TariffsScreen
import app.tuyacontrol.ui.AppTheme
import app.tuyacontrol.ui.DevicesScreen
import app.tuyacontrol.ui.LogScreen
import app.tuyacontrol.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val energyViewModel: EnergyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App(viewModel, energyViewModel)
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
private fun App(viewModel: MainViewModel, energyViewModel: EnergyViewModel) {
    val state by viewModel.state.collectAsState()
    val energy by energyViewModel.state.collectAsState()

    // Устройства со счётчиком энергии передаём на экран «Энергия»
    val energyDevices = remember(state.devices) {
        state.devices.filter { it.hasEnergy }.map { EnergyDevice(it.id, it.name, it.activeTime, it.status.keys + it.spec.keys, it.thingModel) }
    }
    LaunchedEffect(energyDevices, state.screen) {
        if (state.screen == Screen.Energy || state.screen == Screen.Tariffs) {
            energyViewModel.setDevices(energyDevices)
        }
    }

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
            onOpenEnergy = { deviceId ->
                energyViewModel.select(deviceId)
                viewModel.open(Screen.Energy)
            },
        )
        Screen.Energy -> EnergyScreen(
            state = energy,
            onBack = { viewModel.back() },
            onSync = energyViewModel::syncAll,
            onOpenTariffs = { viewModel.open(Screen.Tariffs) },
            onSelect = energyViewModel::select,
            onPeriod = { type, date ->
                if (date != null) energyViewModel.setPeriod(type, date) else energyViewModel.setPeriod(type)
            },
            onShift = energyViewModel::shift,
            onToday = energyViewModel::today,
            onMessageShown = energyViewModel::messageShown,
        )
        Screen.Tariffs -> TariffsScreen(
            tariffs = energy.tariffs,
            devices = energy.devices,
            onBack = { viewModel.back() },
            onSave = energyViewModel::saveTariff,
            onDelete = energyViewModel::deleteTariff,
        )
        Screen.Log -> LogScreen(
            onBack = {
                if (!viewModel.back()) viewModel.open(Screen.Setup)
            },
        )
    }
}
