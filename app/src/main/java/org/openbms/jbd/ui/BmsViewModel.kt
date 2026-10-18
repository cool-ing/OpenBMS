package org.openbms.jbd.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openbms.jbd.ble.BleManager
import org.openbms.jbd.location.SpeedTracker
import org.openbms.jbd.model.AppTheme
import org.openbms.jbd.model.BatteryChemistry
import org.openbms.jbd.model.CalibrationRun
import org.openbms.jbd.model.SpeedUnit
import org.openbms.jbd.protocol.JbdProtocol
import kotlin.math.abs

class BmsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("openbms_prefs", Context.MODE_PRIVATE)

    val bleManager = BleManager(application.applicationContext)
    val speedTracker = SpeedTracker(application.applicationContext)

    val scanResults = bleManager.scanResults
    val isScanning = bleManager.isScanning
    val isConnected = bleManager.isConnected
    val connectedDevice = bleManager.connectedDevice

    val basicInfo = bleManager.basicInfo
    val cellInfo = bleManager.cellInfo
    val settings = bleManager.settings
    val isReadingSettings = bleManager.isReadingSettings

    val isSwitchingCharge = MutableStateFlow(false)
    val isSwitchingDischarge = MutableStateFlow(false)

    val theme = MutableStateFlow(
        AppTheme.valueOf(prefs.getString("theme", AppTheme.SYSTEM.name) ?: AppTheme.SYSTEM.name)
    )

    val speedUnit = MutableStateFlow(
        SpeedUnit.valueOf(prefs.getString("speed_unit", SpeedUnit.OFF.name) ?: SpeedUnit.OFF.name)
    )
    val speedVal = speedTracker.speedVal

    val batteryChemistry = MutableStateFlow(
        BatteryChemistry.valueOf(prefs.getString("battery_chem", BatteryChemistry.LFP.name) ?: BatteryChemistry.LFP.name)
    )
    val customNominalVoltage = MutableStateFlow(
        prefs.getFloat("custom_nominal_v", 3.20f)
    )

    val nominalCellVoltage = MutableStateFlow(
        if (batteryChemistry.value == BatteryChemistry.CUSTOM) customNominalVoltage.value else batteryChemistry.value.nominalCellVoltage
    )

    private val _calibration = MutableStateFlow(CalibrationRun())
    val calibration = _calibration.asStateFlow()

    init {
        speedTracker.setUnit(speedUnit.value)

        viewModelScope.launch {
            var lastTimestamp = System.currentTimeMillis()
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val deltaHours = (now - lastTimestamp) / 3_600_000.0f
                lastTimestamp = now

                val run = _calibration.value
                if (run.isRunning) {
                    val current = basicInfo.value.currentA
                    if (current < 0) { // Discharging
                        val drawnAh = abs(current) * deltaHours
                        val deliveredWh = abs(basicInfo.value.powerW) * deltaHours
                        _calibration.value = run.copy(
                            drawnAh = run.drawnAh + drawnAh,
                            deliveredWh = run.deliveredWh + deliveredWh
                        )
                    }
                }
            }
        }
    }

    fun setBatteryChemistry(chem: BatteryChemistry, customV: Float = 3.20f) {
        batteryChemistry.value = chem
        customNominalVoltage.value = customV
        nominalCellVoltage.value = if (chem == BatteryChemistry.CUSTOM) customV else chem.nominalCellVoltage
        prefs.edit()
            .putString("battery_chem", chem.name)
            .putFloat("custom_nominal_v", customV)
            .apply()
    }

    fun setTheme(newTheme: AppTheme) {
        theme.value = newTheme
        prefs.edit().putString("theme", newTheme.name).apply()
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        speedUnit.value = unit
        prefs.edit().putString("speed_unit", unit.name).apply()
        speedTracker.setUnit(unit)
    }

    fun startCalibration() {
        _calibration.value = CalibrationRun(
            isRunning = true,
            startTimestamp = System.currentTimeMillis(),
            drawnAh = 0.0f,
            deliveredWh = 0.0f
        )
    }

    fun stopCalibration() {
        _calibration.value = _calibration.value.copy(isRunning = false)
    }

    fun applyCalibratedCapacity(capacityAh: Float) {
        writeU16(JbdProtocol.REG_DCAP, (capacityAh * 100).toInt())
        writeU16(JbdProtocol.REG_CCAP, (capacityAh * 100).toInt())
    }

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()
    fun connect(device: BluetoothDevice) = bleManager.connect(device)
    fun disconnect() = bleManager.disconnect()

    fun toggleCharge(enable: Boolean) {
        viewModelScope.launch {
            isSwitchingCharge.value = true
            bleManager.toggleMosfets(enable, basicInfo.value.dischargeFetActive)
            val start = System.currentTimeMillis()
            while (basicInfo.value.chargeFetActive != enable && System.currentTimeMillis() - start < 3000) {
                delay(150)
            }
            isSwitchingCharge.value = false
        }
    }

    fun toggleDischarge(enable: Boolean) {
        viewModelScope.launch {
            isSwitchingDischarge.value = true
            bleManager.toggleMosfets(basicInfo.value.chargeFetActive, enable)
            val start = System.currentTimeMillis()
            while (basicInfo.value.dischargeFetActive != enable && System.currentTimeMillis() - start < 3000) {
                delay(150)
            }
            isSwitchingDischarge.value = false
        }
    }

    fun toggleSwitchEnabled(enable: Boolean) {
        val current = settings.value.funcConfig ?: 0x000C
        val updated = if (enable) current or 0x01 else current and 0x01.inv()
        writeU16(JbdProtocol.REG_FUNC_CONFIG, updated)
    }

    fun toggleBalanceEnabled(enable: Boolean) {
        val current = settings.value.funcConfig ?: 0x000C
        val updated = if (enable) current or (1 shl 2) else current and (1 shl 2).inv()
        writeU16(JbdProtocol.REG_FUNC_CONFIG, updated)
    }

    fun toggleChargeBalanceOnly(enable: Boolean) {
        val current = settings.value.funcConfig ?: 0x000C
        val updated = if (enable) current or (1 shl 3) else current and (1 shl 3).inv()
        writeU16(JbdProtocol.REG_FUNC_CONFIG, updated)
    }

    fun writeBluetoothName(name: String) {
        bleManager.writeBluetoothName(name)
    }

    fun refreshSettings() = bleManager.readAllSettings()

    fun writeU16(register: Byte, value: Int) {
        val cur = settings.value
        settings.value = when (register) {
            JbdProtocol.REG_COVP -> cur.copy(cellOvervoltageMv = value)
            JbdProtocol.REG_RCOVP -> cur.copy(cellOvervoltageReleaseMv = value)
            JbdProtocol.REG_CUVP -> cur.copy(cellUndervoltageMv = value)
            JbdProtocol.REG_RCUVP -> cur.copy(cellUndervoltageReleaseMv = value)
            JbdProtocol.REG_POVP -> cur.copy(packOvervoltageMv = value * 10)
            JbdProtocol.REG_RPOVP -> cur.copy(packOvervoltageReleaseMv = value * 10)
            JbdProtocol.REG_PUVP -> cur.copy(packUndervoltageMv = value * 10)
            JbdProtocol.REG_RPUVP -> cur.copy(packUndervoltageReleaseMv = value * 10)
            JbdProtocol.REG_BALVOL -> cur.copy(balanceStartMv = value)
            JbdProtocol.REG_BALPREC -> cur.copy(balancePrecisionMv = value)
            JbdProtocol.REG_CELL_CNT -> cur.copy(cellCount = value)
            JbdProtocol.REG_DCAP -> cur.copy(designCapacityAh = value / 100.0f)
            JbdProtocol.REG_CCAP -> cur.copy(cycleCapacityAh = value / 100.0f)
            JbdProtocol.REG_CHGOC -> cur.copy(chargeOvercurrentA = value / 100.0f)
            JbdProtocol.REG_FUNC_CONFIG -> cur.copy(funcConfig = value)
            else -> cur
        }
        bleManager.writeSetting(register, byteArrayOf((value shr 8).toByte(), (value and 0xFF).toByte()))
    }

    fun writeS16(register: Byte, value: Int) {
        if (register == JbdProtocol.REG_DSGOC) {
            settings.value = settings.value.copy(dischargeOvercurrentA = abs(value) / 100.0f)
        }
        writeU16(register, value and 0xFFFF)
    }

    fun writeTemp(register: Byte, celsius: Float) {
        val cur = settings.value
        settings.value = when (register) {
            JbdProtocol.REG_CHGOT -> cur.copy(chargeOverTempC = celsius)
            JbdProtocol.REG_RCHGOT -> cur.copy(chargeOverTempReleaseC = celsius)
            JbdProtocol.REG_CHGUT -> cur.copy(chargeUnderTempC = celsius)
            JbdProtocol.REG_RCHGUT -> cur.copy(chargeUnderTempReleaseC = celsius)
            JbdProtocol.REG_DSGOT -> cur.copy(dischargeOverTempC = celsius)
            JbdProtocol.REG_RDSGOT -> cur.copy(dischargeOverTempReleaseC = celsius)
            JbdProtocol.REG_DSGUT -> cur.copy(dischargeUnderTempC = celsius)
            JbdProtocol.REG_RDSGUT -> cur.copy(dischargeUnderTempReleaseC = celsius)
            else -> cur
        }
        writeU16(register, ((celsius * 10f) + 2731f).toInt())
    }

    fun writeDelays(register: Byte, delay0: Int, delay1: Int) {
        val cur = settings.value
        settings.value = when (register) {
            JbdProtocol.REG_CVDELAY -> cur.copy(cellUndervoltageDelayS = delay0, cellOvervoltageDelayS = delay1)
            JbdProtocol.REG_CHGOCDELAY -> cur.copy(chargeOvercurrentDelayS = delay0)
            JbdProtocol.REG_DSGOCDELAY -> cur.copy(dischargeOvercurrentDelayS = delay0)
            else -> cur
        }
        bleManager.writeSetting(register, byteArrayOf(delay0.toByte(), delay1.toByte()))
    }

    override fun onCleared() {
        super.onCleared()
        speedTracker.stop()
        bleManager.disconnect()
    }
}
