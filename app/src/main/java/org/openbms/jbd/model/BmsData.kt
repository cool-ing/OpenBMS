package org.openbms.jbd.model

enum class BatteryChemistry(val label: String, val nominalCellVoltage: Float) {
    LFP("LiFePO4 (3.20 V)", 3.20f),
    NMC("Li-ion / NMC (3.65 V)", 3.65f),
    CUSTOM("Custom Voltage", 3.20f)
}

data class BmsBasicInfo(
    val voltageMv: Int = 0,
    val currentA: Float = 0.0f,
    val remainingCapacityAh: Float = 0f,
    val nominalCapacityAh: Float = 0f,
    val cycles: Int = 0,
    val soc: Int = 0,
    val chargeFetActive: Boolean = false,
    val dischargeFetActive: Boolean = false,
    val cellCount: Int = 0,
    val temperaturesC: List<Float> = emptyList(),
    val protectionBits: Int = 0
) {
    val voltageV: Float get() = voltageMv / 1000.0f
    val powerW: Float get() = voltageV * currentA

    fun calculateRemainingEnergyWh(nominalCellV: Float): Float {
        val packNominalV = if (cellCount > 0) cellCount * nominalCellV else if (voltageV > 0f) voltageV else (4 * nominalCellV)
        return packNominalV * remainingCapacityAh
    }

    fun calculateNominalEnergyWh(nominalCellV: Float): Float {
        val packNominalV = if (cellCount > 0) cellCount * nominalCellV else if (voltageV > 0f) voltageV else (4 * nominalCellV)
        return packNominalV * nominalCapacityAh
    }
}

data class BmsCellInfo(
    val cellVoltagesMv: List<Int> = emptyList()
) {
    val minMv: Int get() = cellVoltagesMv.minOrNull() ?: 0
    val maxMv: Int get() = cellVoltagesMv.maxOrNull() ?: 0
    val deltaMv: Int get() = if (cellVoltagesMv.isNotEmpty()) maxMv - minMv else 0
}

data class BmsSettings(
    val hardwareModel: String = "",
    val bluetoothName: String = "",
    val cellCount: Int? = null,
    val funcConfig: Int? = null,

    // Cell cutoffs
    val cellOvervoltageMv: Int? = null,
    val cellOvervoltageReleaseMv: Int? = null,
    val cellUndervoltageMv: Int? = null,
    val cellUndervoltageReleaseMv: Int? = null,
    val cellOvervoltageDelayS: Int? = null,
    val cellUndervoltageDelayS: Int? = null,

    // Pack cutoffs
    val packOvervoltageMv: Int? = null,
    val packOvervoltageReleaseMv: Int? = null,
    val packUndervoltageMv: Int? = null,
    val packUndervoltageReleaseMv: Int? = null,

    // Balancing
    val balanceStartMv: Int? = null,
    val balancePrecisionMv: Int? = null,

    // Temperature thresholds
    val chargeOverTempC: Float? = null,
    val chargeOverTempReleaseC: Float? = null,
    val chargeUnderTempC: Float? = null,
    val chargeUnderTempReleaseC: Float? = null,
    val dischargeOverTempC: Float? = null,
    val dischargeOverTempReleaseC: Float? = null,
    val dischargeUnderTempC: Float? = null,
    val dischargeUnderTempReleaseC: Float? = null,

    // Capacity & Current
    val designCapacityAh: Float? = null,
    val cycleCapacityAh: Float? = null,
    val chargeOvercurrentA: Float? = null,
    val dischargeOvercurrentA: Float? = null,
    val chargeOvercurrentDelayS: Int? = null,
    val dischargeOvercurrentDelayS: Int? = null
) {
    val switchEnabled: Boolean get() = (funcConfig ?: 0) and 0x01 != 0
    val balanceEnabled: Boolean get() = (funcConfig ?: 0) and (1 shl 2) != 0
    val chargeBalanceOnly: Boolean get() = (funcConfig ?: 0) and (1 shl 3) != 0
}

enum class AppTheme {
    SYSTEM, LIGHT, BLACK
}

enum class SpeedUnit(val label: String, val consumptionLabel: String) {
    OFF("Off", ""),
    KM_H("km/h", "Wh/km"),
    NMI_H("nmi/h", "Wh/nmi")
}

data class CalibrationRun(
    val isRunning: Boolean = false,
    val startTimestamp: Long = 0L,
    val drawnAh: Float = 0.0f,
    val deliveredWh: Float = 0.0f
)
