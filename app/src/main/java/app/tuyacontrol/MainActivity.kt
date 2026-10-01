package app.tuyacontrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import app.tuyacontrol.background.HistorySyncWorker
import app.tuyacontrol.background.SyncTargets
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
import app.tuyacontrol.ui.LocalScreen
import app.tuyacontrol.ui.HeatingScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val energyViewModel: EnergyViewModel by viewModels()
    private val sensorViewModel: SensorViewModel by viewModels()
    private val heatingViewModel: HeatingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ежедневная фоновая загрузка истории (~3:00)
        HistorySyncWorker.schedule(this)
        // Уведомление «история давно не обновлялась» на Android 13+ требует разрешения
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            AppTheme {
                App(viewModel, energyViewModel, sensorViewModel, heatingViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        // Будильник переключения конвекторов мог потеряться (обновление приложения) — заводим заново
        app.tuyacontrol.heating.HeatingAlarm.scheduleNext(this)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }
}

@Composable
private fun App(
    viewModel: MainViewModel,
    energyViewModel: EnergyViewModel,
    sensorViewModel: SensorViewModel,
    heatingViewModel: HeatingViewModel,
) {
    val state by viewModel.state.collectAsState()
    val energy by energyViewModel.state.collectAsState()
    val sensor by sensorViewModel.state.collectAsState()
    val heating by heatingViewModel.state.collectAsState()

    // Устройства со счётчиком энергии передаём на экран «Энергия»
    val energyDevices = remember(state.devices) {
        state.devices.filter { it.hasEnergy }.map { EnergyDevice(it.id, it.name, it.activeTime, it.status.keys + it.spec.keys, it.thingModel) }
    }
    // Для фоновой загрузки запоминаем, какие счётчики и датчики качать
    val context = LocalContext.current
    LaunchedEffect(state.devices) {
        if (state.devices.isNotEmpty()) {
            SyncTargets(context).save(energyDevices, state.devices.mapNotNull { it.sensorDevice })
        }
    }
    // Термостаты для экрана «Отопление»
    LaunchedEffect(state.devices) {
        if (state.devices.isNotEmpty()) heatingViewModel.setDevices(state.devices)
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
    val bottomBar: @Composable (Int) -> Unit = { selected ->
        AppBottomBar(
            selected = selected,
            onDevices = { viewModel.open(Screen.Devices) },
            onCategories = { viewModel.open(Screen.Categories) },
            onHeating = { viewModel.open(Screen.Heating) },
        )
    }

    when (state.screen) {
        Screen.Setup -> SetupScreen(
            state = state,
            onSave = viewModel::saveCredentials,
            onClear = viewModel::clearCredentials,
            onBack = { viewModel.back() },
            onOpenLog = { viewModel.open(Screen.Log) },
            onOpenLocal = { viewModel.open(Screen.Local) },
            onRubetekSendCode = viewModel::rubetekSendCode,
            onRubetekSignIn = viewModel::rubetekSignIn,
            onRubetekCancel = viewModel::rubetekCancelCode,
            onRubetekSignOut = viewModel::rubetekSignOut,
        )
        Screen.Local -> LocalScreen(
            state = state,
            onBack = { viewModel.open(Screen.Setup) },
            onScan = viewModel::scanLocal,
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
            bottomBar = { bottomBar(0) },
            onModeChange = viewModel::setMode,
        )
        Screen.Categories -> CategoriesScreen(
            categories = state.categories,
            devices = state.devices,
            prefs = state.devicePrefs,
            onOpen = viewModel::openCategory,
            onSave = viewModel::saveCategory,
            onDelete = viewModel::deleteCategory,
            bottomBar = { bottomBar(1) },
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
                bottomBar = { bottomBar(1) },
                onModeChange = viewModel::setMode,
            )
        }
        Screen.Heating -> HeatingScreen(
            state = heating,
            onRecompute = heatingViewModel::recompute,
            onAutopilot = heatingViewModel::setAutopilot,
            onDeploy = heatingViewModel::deploy,
            onSaveZone = heatingViewModel::saveZone,
            onDeleteZone = heatingViewModel::deleteZone,
            onLocation = heatingViewModel::setLocation,
            onAddRubetek = heatingViewModel::addRubetekZones,
            onMessageShown = heatingViewModel::messageShown,
            bottomBar = { bottomBar(2) },
        )
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
