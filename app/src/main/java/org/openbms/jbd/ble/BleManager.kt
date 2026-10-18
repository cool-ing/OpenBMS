package org.openbms.jbd.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openbms.jbd.model.BmsBasicInfo
import org.openbms.jbd.model.BmsCellInfo
import org.openbms.jbd.model.BmsSettings
import org.openbms.jbd.protocol.JbdProtocol
import java.util.UUID
import kotlin.math.abs

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null

    private val accumulator = JbdProtocol.FrameAccumulator()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private val bleMutex = Mutex()
    private var isPollingPaused = false

    private val SERVICE_UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_UUID  = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID   = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    val scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val isScanning = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val connectedDevice = MutableStateFlow<BluetoothDevice?>(null)

    val basicInfo = MutableStateFlow(BmsBasicInfo())
    val cellInfo = MutableStateFlow(BmsCellInfo())
    val settings = MutableStateFlow(BmsSettings())
    val isReadingSettings = MutableStateFlow(false)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val current = scanResults.value.toMutableList()
            if (device.name != null && !current.any { it.address == device.address }) {
                current.add(device)
                scanResults.value = current
            }
        }
    }

    fun startScan() {
        if (adapter?.isEnabled != true) return
        scanResults.value = emptyList()
        isScanning.value = true
        adapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    fun stopScan() {
        isScanning.value = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        connectedDevice.value = device
        settings.value = settings.value.copy(bluetoothName = device.name ?: "")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        pollingJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected.value = false
        connectedDevice.value = null
        isReadingSettings.value = false
    }

    fun toggleMosfets(charge: Boolean, discharge: Boolean) {
        scope.launch {
            sendPacketSync(JbdProtocol.buildMosfetCommand(charge, discharge), 100)
        }
    }

    fun readAllSettings() {
        scope.launch {
            if (isReadingSettings.value) return@launch
            isReadingSettings.value = true
            isPollingPaused = true
            delay(250)

            // Read BMS hardware model (0x05)
            sendPacketSync(JbdProtocol.buildReadCommand(JbdProtocol.REG_DEVICE_MODEL_READ), 150)

            sendPacketSync(JbdProtocol.buildEnterFactoryMode(), 200)

            val registers = listOf(
                JbdProtocol.REG_FUNC_CONFIG,
                JbdProtocol.REG_CELL_CNT,
                JbdProtocol.REG_COVP, JbdProtocol.REG_RCOVP,
                JbdProtocol.REG_CUVP, JbdProtocol.REG_RCUVP,
                JbdProtocol.REG_CVDELAY,
                JbdProtocol.REG_POVP, JbdProtocol.REG_RPOVP,
                JbdProtocol.REG_PUVP, JbdProtocol.REG_RPUVP,
                JbdProtocol.REG_BALVOL, JbdProtocol.REG_BALPREC,
                JbdProtocol.REG_CHGOT, JbdProtocol.REG_RCHGOT,
                JbdProtocol.REG_CHGUT, JbdProtocol.REG_RCHGUT,
                JbdProtocol.REG_DSGOT, JbdProtocol.REG_RDSGOT,
                JbdProtocol.REG_DSGUT, JbdProtocol.REG_RDSGUT,
                JbdProtocol.REG_DCAP, JbdProtocol.REG_CCAP,
                JbdProtocol.REG_CHGOC, JbdProtocol.REG_DSGOC,
                JbdProtocol.REG_CHGOCDELAY, JbdProtocol.REG_DSGOCDELAY
            )

            for (reg in registers) {
                sendPacketSync(JbdProtocol.buildReadCommand(reg), 120)
            }

            sendPacketSync(JbdProtocol.buildExitFactoryModeWithoutSaving(), 200)
            isPollingPaused = false
            isReadingSettings.value = false
        }
    }

    fun writeSetting(register: Byte, data: ByteArray) {
        scope.launch {
            isPollingPaused = true
            delay(150)

            sendPacketSync(JbdProtocol.buildEnterFactoryMode(), 200)
            sendPacketSync(JbdProtocol.buildWriteCommand(register, data), 200)
            sendPacketSync(JbdProtocol.buildExitFactoryModeAndSave(), 300)

            sendPacketSync(JbdProtocol.buildEnterFactoryMode(), 200)
            sendPacketSync(JbdProtocol.buildReadCommand(register), 200)
            sendPacketSync(JbdProtocol.buildExitFactoryModeWithoutSaving(), 200)

            isPollingPaused = false
        }
    }

    fun writeBluetoothName(name: String) {
        scope.launch {
            isPollingPaused = true
            delay(150)

            // 1. Direct BLE dongle hardware rename packet (Overkill / JBD BLE protocol)
            val bleRenamePacket = JbdProtocol.buildBleRenamePacket(name)
            sendPacketSync(bleRenamePacket, 250)

            // 2. Also write EEPROM register 0xA1 for integrated-BLE boards
            sendPacketSync(JbdProtocol.buildEnterFactoryMode(), 200)
            sendPacketSync(JbdProtocol.buildEepromNameCommand(name), 250)
            sendPacketSync(JbdProtocol.buildExitFactoryModeAndSave(), 300)

            settings.value = settings.value.copy(bluetoothName = name)

            isPollingPaused = false
        }
    }

    private suspend fun sendPacketSync(packet: ByteArray, delayAfterMs: Long = 120) {
        bleMutex.withLock {
            sendPacketDirect(packet)
            delay(delayAfterMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun sendPacketDirect(packet: ByteArray) {
        val characteristic = writeCharacteristic ?: return
        val g = gatt ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            characteristic.value = packet
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(characteristic)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected.value = true
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected.value = false
                connectedDevice.value = null
                pollingJob?.cancel()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = g.getService(SERVICE_UUID) ?: g.services.firstOrNull { it.characteristics.size >= 2 }
                if (service != null) {
                    val notifyChar = service.getCharacteristic(NOTIFY_UUID) ?: service.characteristics[0]
                    writeCharacteristic = service.getCharacteristic(WRITE_UUID) ?: service.characteristics[1]

                    g.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(CCCD_UUID)
                    descriptor?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(it)
                        }
                    }

                    startPolling()
                }
            }
        }

        @Deprecated("Maintained for Android 12 compatibility")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            handleIncomingBytes(char.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }
    }

    private fun handleIncomingBytes(data: ByteArray?) {
        if (data == null) return
        val frames = accumulator.feed(data)
        for (frame in frames) {
            if (frame.size < 7 || frame[2] != 0x00.toByte()) continue
            val cmd = frame[1]

            when (cmd) {
                JbdProtocol.REG_BASIC_INFO -> {
                    JbdProtocol.parseBasicInfo(frame)?.let { basicInfo.value = it }
                }
                JbdProtocol.REG_CELL_VOLTAGES -> {
                    JbdProtocol.parseCellVoltages(frame)?.let { cellInfo.value = it }
                }
                JbdProtocol.REG_DEVICE_MODEL_READ -> {
                    JbdProtocol.parseString(frame)?.let { model ->
                        if (model.isNotBlank()) {
                            settings.value = settings.value.copy(hardwareModel = model)
                        }
                    }
                }
                else -> {
                    val payloadLen = frame[3].toInt() and 0xFF
                    if (payloadLen < 2) continue

                    val b0 = frame[4].toInt() and 0xFF
                    val b1 = frame[5].toInt() and 0xFF
                    val u16 = (b0 shl 8) or b1
                    val s16 = u16.toShort().toInt()

                    val cur = settings.value
                    settings.value = when (cmd) {
                        JbdProtocol.REG_FUNC_CONFIG -> cur.copy(funcConfig = u16)
                        JbdProtocol.REG_CELL_CNT -> cur.copy(cellCount = u16)
                        JbdProtocol.REG_COVP -> cur.copy(cellOvervoltageMv = u16)
                        JbdProtocol.REG_RCOVP -> cur.copy(cellOvervoltageReleaseMv = u16)
                        JbdProtocol.REG_CUVP -> cur.copy(cellUndervoltageMv = u16)
                        JbdProtocol.REG_RCUVP -> cur.copy(cellUndervoltageReleaseMv = u16)
                        JbdProtocol.REG_CVDELAY -> cur.copy(
                            cellUndervoltageDelayS = b0,
                            cellOvervoltageDelayS = b1
                        )
                        JbdProtocol.REG_POVP -> cur.copy(packOvervoltageMv = u16 * 10)
                        JbdProtocol.REG_RPOVP -> cur.copy(packOvervoltageReleaseMv = u16 * 10)
                        JbdProtocol.REG_PUVP -> cur.copy(packUndervoltageMv = u16 * 10)
                        JbdProtocol.REG_RPUVP -> cur.copy(packUndervoltageReleaseMv = u16 * 10)
                        JbdProtocol.REG_BALVOL -> cur.copy(balanceStartMv = u16)
                        JbdProtocol.REG_BALPREC -> cur.copy(balancePrecisionMv = u16)

                        JbdProtocol.REG_CHGOT -> cur.copy(chargeOverTempC = (u16 - 2731) / 10.0f)
                        JbdProtocol.REG_RCHGOT -> cur.copy(chargeOverTempReleaseC = (u16 - 2731) / 10.0f)
                        JbdProtocol.REG_CHGUT -> cur.copy(chargeUnderTempC = (u16 - 2731) / 10.0f)
                        JbdProtocol.REG_RCHGUT -> cur.copy(chargeUnderTempReleaseC = (u16 - 2731) / 10.0f)
                        JbdProtocol.REG_DSGOT -> cur.copy(dischargeOverTempC = (u16 - 2731) / 10.0f)
                        JbdProtocol.REG_RDSGOT -> cur.copy(dischargeOverTempReleaseC = (u16 - 2731) / 10.0f)
                        JbdProtocol.REG_DSGUT -> cur.copy(dischargeUnderTempC = (u16 - 2731) / 10.0f)
                        JbdProtocol.REG_RDSGUT -> cur.copy(dischargeUnderTempReleaseC = (u16 - 2731) / 10.0f)

                        JbdProtocol.REG_DCAP -> cur.copy(designCapacityAh = u16 / 100.0f)
                        JbdProtocol.REG_CCAP -> cur.copy(cycleCapacityAh = u16 / 100.0f)
                        JbdProtocol.REG_CHGOC -> cur.copy(chargeOvercurrentA = u16 / 100.0f)
                        JbdProtocol.REG_DSGOC -> cur.copy(dischargeOvercurrentA = abs(s16) / 100.0f)
                        JbdProtocol.REG_CHGOCDELAY -> cur.copy(chargeOvercurrentDelayS = b0)
                        JbdProtocol.REG_DSGOCDELAY -> cur.copy(dischargeOvercurrentDelayS = b0)
                        else -> cur
                    }
                }
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                if (!isPollingPaused) {
                    sendPacketSync(JbdProtocol.buildReadCommand(JbdProtocol.REG_BASIC_INFO), 120)
                    delay(1000)
                }
                if (!isPollingPaused) {
                    sendPacketSync(JbdProtocol.buildReadCommand(JbdProtocol.REG_CELL_VOLTAGES), 120)
                    delay(1000)
                }
            }
        }
    }
}
