package app.tuyacontrol.nartis

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Найденный по Bluetooth прибор. */
data class NartisDevice(val address: String, val name: String, val rssi: Int)

/**
 * Связь со счётчиком НАРТИС по Bluetooth: поиск, сопряжение и «последовательный порт»
 * через Nordic UART Service (две характеристики: запись и уведомления).
 */
class NartisBle(private val context: Context, private val log: (String) -> Unit) {

    companion object {
        private val NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dc4179")
        private val NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dc4179")
        private val NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dc4179")
        private val NUS_SERVICE_STD = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_STD = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_STD = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null

    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val written = LinkedBlockingQueue<Boolean>()
    private var servicesReady: CompletableDeferred<Unit>? = null
    private var notifyReady: CompletableDeferred<Boolean>? = null

    val isConnected: Boolean get() = gatt != null

    // ---------- Поиск ----------

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (NartisDevice) -> Unit, onError: (String) -> Unit) {
        stopScan()
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            onError("Bluetooth выключен")
            return
        }
        val le = adapter.bluetoothLeScanner ?: run {
            onError("Bluetooth LE не поддерживается")
            return
        }
        scanner = le
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = runCatching { result.scanRecord?.deviceName ?: device.name }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                val nus = result.scanRecord?.serviceUuids?.any {
                    it.uuid == NUS_SERVICE || it.uuid == NUS_SERVICE_STD
                } == true
                // Безымянные приборы показываем, только если они рядом или сразу говорят, что это NUS
                if (name == null && !nus && result.rssi < -80) return
                onFound(NartisDevice(device.address, name ?: "Без имени", result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                onError("Поиск не удался (код $errorCode)")
            }
        }
        scanCallback = cb
        le.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        runCatching { scanner?.stopScan(cb) }
        scanCallback = null
        scanner = null
    }

    // ---------- Подключение ----------

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, pin: String) {
        closeGatt()
        val adapter = manager.adapter ?: throw IOException("Bluetooth выключен")
        val device = adapter.getRemoteDevice(address)
        log("Подключаюсь к ${device.name ?: address}…")

        val services = CompletableDeferred<Unit>()
        servicesReady = services
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        withTimeout(25_000) { services.await() }

        val g = gatt ?: throw IOException("нет соединения")
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("Сопряжение: телефон спросит PIN — введите $pin")
            runCatching { device.createBond() }
            val bonded = runCatching {
                withTimeout(30_000) {
                    while (device.bondState != BluetoothDevice.BOND_BONDED) delay(250)
                }
                true
            }.getOrDefault(false)
            if (!bonded) log("Сопряжение не завершено — пробую без него")
        }

        val nus = g.getService(NUS_SERVICE) ?: g.getService(NUS_SERVICE_STD)
        val rxChar = nus?.getCharacteristic(NUS_RX) ?: nus?.getCharacteristic(NUS_RX_STD)
        val txChar = nus?.getCharacteristic(NUS_TX) ?: nus?.getCharacteristic(NUS_TX_STD)
        if (nus == null || rxChar == null || txChar == null) {
            val found = g.services.joinToString { it.uuid.toString() }
            throw IOException("у счётчика нет службы NUS. Найдено: $found")
        }
        rx = rxChar
        tx = txChar
        log("Служба NUS найдена, включаю уведомления…")

        val notify = CompletableDeferred<Boolean>()
        notifyReady = notify
        g.setCharacteristicNotification(txChar, true)
        val descriptor = txChar.getDescriptor(CCCD)
            ?: throw IOException("у характеристики нет CCCD")
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(descriptor)
        }
        if (!withTimeout(15_000) { notify.await() }) {
            throw IOException("не удалось включить уведомления")
        }
        log("Связь по Bluetooth установлена")
    }

    // ---------- Обмен ----------

    /** Ждёт данные от счётчика; пустой массив — за [timeoutMs] ничего не пришло. */
    fun read(timeoutMs: Long): ByteArray {
        val first = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        out.write(first)
        while (true) {
            val more = incoming.poll() ?: break
            out.write(more)
        }
        return out.toByteArray()
    }

    /** Отправляет байты кусками по 20 — столько гарантированно проходит без настройки MTU. */
    @SuppressLint("MissingPermission")
    fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("нет соединения")
        val c = rx ?: throw IOException("нет характеристики записи")
        var offset = 0
        while (offset < data.size) {
            val n = min(20, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + n)
            written.clear()
            val sent = if (Build.VERSION.SDK_INT >= 33) {
                // 0 — BluetoothStatusCodes.SUCCESS; константу не берём, чтобы не тянуть класс API 31
                g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = chunk
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!sent) throw IOException("не удалось отправить данные")
            val ok = written.poll(4, TimeUnit.SECONDS) ?: throw IOException("нет ответа на запись")
            if (!ok) throw IOException("счётчик отклонил запись")
            offset += n
            // Небольшая пауза между кусками — счётчик успевает принять
            if (offset < data.size) Thread.sleep(15)
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        incoming.clear()
        written.clear()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        rx = null
        tx = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Bluetooth подключён, ищу службы…")
                    g.requestMtu(247)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Bluetooth отключён (статус $status)")
                    servicesReady?.completeExceptionally(IOException("счётчик разорвал соединение"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("Размер пакета Bluetooth: $mtu")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                servicesReady?.completeExceptionally(IOException("не удалось получить службы"))
                return
            }
            servicesReady?.complete(Unit)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            notifyReady?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            written.offer(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { incoming.offer(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            incoming.offer(value)
        }
    }
}
