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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tuyacontrol.sensor.SensorDevice
import app.tuyacontrol.ui.AppBottomBar
import app.tuyacontrol.ui.CategoriesScreen
import app.tuyacontrol.ui.DeviceSettingsDialog
import app.tuyacontrol.energy.EnergyDevice
import app.tuyacontrol.ui.EnergyScreen
import app.tuyacontrol.ui.TariffsScreen
import app.tuyacontrol.ui.SensorScreen
import app.tuyacontrol.ui.AppTheme
import app.tuyacontrol.ui.DevicesScreen
import app.tuyacontrol.ui.LogScreen
import app.tuyacontrol.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val energyViewModel: EnergyViewModel by viewModels()
    private val sensorViewModel: SensorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App(viewModel, energyViewModel, sensorViewModel)
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
private fun App(viewModel: MainViewModel, energyViewModel: EnergyViewModel, sensorViewModel: SensorViewModel) {
    val state by viewModel.state.collectAsState()
    val energy by energyViewModel.state.collectAsState()
    val sensor by sensorViewModel.state.collectAsState()

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

    var editDevice by remember { mutableStateOf<DeviceUi?>(null) }
    val openEnergy: (String?) -> Unit = { deviceId ->
        energyViewModel.select(deviceId)
        viewModel.open(Screen.Energy)
    }
    val openSensor: (SensorDevice) -> Unit = { device ->
        sensorViewModel.open(device)
        viewModel.open(Screen.Sensor)
    }
    val bottomBar: @Composable (Boolean) -> Unit = { categoriesSelected ->
        AppBottomBar(
            categoriesSelected = categoriesSelected,
            onDevices = { viewModel.open(Screen.Devices) },
            onCategories = { viewModel.open(Screen.Categories) },
        )
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
            onOpenEnergy = openEnergy,
            onOpenSensor = openSensor,
            onEditDevice = { editDevice = it },
            bottomBar = { bottomBar(false) },
        )
        Screen.Categories -> CategoriesScreen(
            categories = state.categories,
            devices = state.devices,
            prefs = state.devicePrefs,
            onOpen = viewModel::openCategory,
            onSave = viewModel::saveCategory,
            onDelete = viewModel::deleteCategory,
            bottomBar = { bottomBar(true) },
        )
        Screen.CategoryDevices -> {
            val category = state.categories.firstOrNull { it.id == state.categoryId }
            DevicesScreen(
                state = state,
                onRefresh = { viewModel.refresh() },
                onCommand = viewModel::sendCommand,
                onOpenSettings = { viewModel.open(Screen.Setup) },
                onOpenLog = { viewModel.open(Screen.Log) },
                onMessageShown = viewModel::messageShown,
                onOpenEnergy = openEnergy,
                onOpenSensor = openSensor,
                onEditDevice = { editDevice = it },
                title = category?.name ?: "Категория",
                devices = state.devices.filter { state.devicePrefs[it.id]?.categoryId == state.categoryId },
                onBack = { viewModel.back() },
                bottomBar = { bottomBar(true) },
            )
        }
        Screen.Sensor -> SensorScreen(
            state = sensor,
            onBack = { viewModel.back() },
            onRefresh = sensorViewModel::refresh,
            onPeriod = { sensorViewModel.setPeriod(it) },
            onShift = sensorViewModel::shift,
            onToday = sensorViewModel::today,
            onMessageShown = sensorViewModel::messageShown,
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

    editDevice?.let { d ->
        DeviceSettingsDialog(
            device = d,
            pref = state.devicePrefs[d.id],
            categories = state.categories,
            onDismiss = { editDevice = null },
            onSave = {
                viewModel.setDevicePref(d.id, it)
                editDevice = null
            },
        )
    }
}
