package org.openbms.jbd.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbms.jbd.model.AppTheme
import org.openbms.jbd.model.BatteryChemistry
import org.openbms.jbd.model.SpeedUnit
import org.openbms.jbd.protocol.JbdProtocol
import kotlin.math.abs

private val BlackColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFF1C1C1C),
    onBackground = Color.White,
    onSurface = Color(0xFFEEEEEE),
    outline = Color(0xFF484848),
    primary = Color(0xFF64B5F6),
    secondary = Color(0xFF81C784)
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFF8F9FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9ECEF),
    onBackground = Color(0xFF212529),
    onSurface = Color(0xFF212529),
    outline = Color(0xFFCED4DA),
    primary = Color(0xFF1976D2),
    secondary = Color(0xFF388E3C)
)

class MainActivity : ComponentActivity() {
    private val viewModel: BmsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appTheme by viewModel.theme.collectAsState()
            val useBlack = when (appTheme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.BLACK -> true
            }

            MaterialTheme(colorScheme = if (useBlack) BlackColorScheme else LightColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BmsApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BmsApp(viewModel: BmsViewModel) {
    val isConnected by viewModel.isConnected.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(selectedTab, isConnected) {
        if (isConnected && selectedTab == 1) {
            viewModel.refreshSettings()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenBMS", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (isConnected && connectedDevice != null) {
                            Text(
                                text = "Connected: ${connectedDevice?.name ?: connectedDevice?.address}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    if (isConnected) {
                        Button(
                            onClick = { viewModel.disconnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.BluetoothDisabled,
                                contentDescription = "Disconnect",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnect", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isConnected) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                        label = { Text("Dashboard") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                        label = { Text("Battery Config") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (!isConnected) {
                ScanScreen(viewModel)
            } else {
                when (selectedTab) {
                    0 -> DashboardScreen(viewModel)
                    1 -> BatteryConfigurationScreen(viewModel)
                    2 -> SettingsScreen(viewModel)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(viewModel: BmsViewModel) {
    val devices by viewModel.scanResults.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { if (isScanning) viewModel.stopScan() else viewModel.startScan() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isScanning) "Scanning... (Tap to Stop)" else "Scan BMS")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(devices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.connect(device) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(device.name ?: "Unknown BMS", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(device.address, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: BmsViewModel) {
    val basic by viewModel.basicInfo.collectAsState()
    val cells by viewModel.cellInfo.collectAsState()
    val isSwitchingCharge by viewModel.isSwitchingCharge.collectAsState()
    val isSwitchingDischarge by viewModel.isSwitchingDischarge.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val speed by viewModel.speedVal.collectAsState()
    val nominalCellV by viewModel.nominalCellVoltage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("State", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${basic.soc} %", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .padding(2.5.dp)
                ) {
                    val fraction = (basic.soc / 100f).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Capacity: ${"%.2f".format(basic.remainingCapacityAh)} / ${"%.2f".format(basic.nominalCapacityAh)} Ah",
                    fontSize = 13.sp
                )

                val remainingWh = basic.calculateRemainingEnergyWh(nominalCellV)
                val nominalWh = basic.calculateNominalEnergyWh(nominalCellV)
                Text(
                    text = "Energy: ${"%.1f".format(remainingWh)} / ${"%.1f".format(nominalWh)} Wh",
                    fontSize = 13.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Voltage", color = Color.Gray, fontSize = 12.sp)
                        Text("${"%.2f".format(basic.voltageV)} V", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Current", color = Color.Gray, fontSize = 12.sp)
                        Text("${"%.2f".format(basic.currentA)} A", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Power", color = Color.Gray, fontSize = 12.sp)
                        Text("${"%.1f".format(basic.powerW)} W", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (speedUnit != SpeedUnit.OFF) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(14.dp))

                    val consumption = if (speed > 1.0f && basic.currentA < 0) {
                        abs(basic.powerW) / speed
                    } else null

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Speed", color = Color.Gray, fontSize = 12.sp)
                            Text("${"%.1f".format(speed)} ${speedUnit.label}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Column {
                            Text("Consumption", color = Color.Gray, fontSize = 12.sp)
                            val text = if (consumption != null) "${"%.1f".format(consumption)} ${speedUnit.consumptionLabel}" else "--"
                            Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Controls", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))

                MosfetControlRow(
                    label = "Charge MOSFET",
                    isActive = basic.chargeFetActive,
                    isSwitching = isSwitchingCharge,
                    onToggle = { viewModel.toggleCharge(it) }
                )
                Spacer(modifier = Modifier.height(10.dp))
                MosfetControlRow(
                    label = "Discharge MOSFET",
                    isActive = basic.dischargeFetActive,
                    isSwitching = isSwitchingDischarge,
                    onToggle = { viewModel.toggleDischarge(it) }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cell Voltages", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Δ ${cells.deltaMv} mV", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(12.dp))
                cells.cellVoltagesMv.forEachIndexed { index, mv ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cell ${index + 1}", fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val vFraction = ((mv - 2500f) / 1150f).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(8.dp)
                                    .background(Color.DarkGray, RoundedCornerShape(4.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(vFraction)
                                        .fillMaxHeight()
                                        .background(Color(0xFF2196F3), RoundedCornerShape(4.dp))
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("$mv mV", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Environment & Health", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Cycle Count: ${basic.cycles}")
                basic.temperaturesC.forEachIndexed { idx, t ->
                    Text("NTC Probe ${idx + 1}: ${"%.1f".format(t)} °C")
                }
            }
        }
    }
}

@Composable
fun MosfetControlRow(
    label: String,
    isActive: Boolean,
    isSwitching: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            if (isSwitching) {
                Text(
                    text = "SWITCHING...",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alpha(pulseAlpha)
                )
            } else {
                Text(
                    text = if (isActive) "ACTIVE" else "DISABLED",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color(0xFF4CAF50) else Color.Gray
                )
            }
        }

        Switch(
            checked = isActive,
            onCheckedChange = { onToggle(it) },
            enabled = !isSwitching
        )
    }
}

@Composable
fun BatteryConfigurationScreen(viewModel: BmsViewModel) {
    val s by viewModel.settings.collectAsState()
    val isReading by viewModel.isReadingSettings.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val chemistry by viewModel.batteryChemistry.collectAsState()
    val customV by viewModel.customNominalVoltage.collectAsState()

    var editDialogItem by remember { mutableStateOf<Pair<String, Byte>?>(null) }
    var editUnit by remember { mutableStateOf("") }
    var editCurrentVal by remember { mutableStateOf("") }
    var onCommitAction by remember { mutableStateOf<(Float) -> Unit>({}) }

    var showNameDialog by remember { mutableStateOf(false) }
    var showChemDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Advanced Configuration", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("EEPROM Registers", color = Color.Gray, fontSize = 12.sp)
            }
            IconButton(onClick = { viewModel.refreshSettings() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
            }
        }

        if (isReading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        ConfigSection(title = "Hardware & Bluetooth") {
            val displayName = s.bluetoothName.ifBlank { connectedDevice?.name ?: "Tap to set" }
            ConfigRow("Bluetooth Broadcast Name", displayName, "") {
                showNameDialog = true
            }
            if (s.hardwareModel.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("BMS Hardware Model", fontSize = 14.sp)
                    Text(s.hardwareModel, fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("External Physical Switch (SW)", fontSize = 14.sp)
                    Text("Hardware SW pads control discharge MOSFET", fontSize = 11.sp, color = Color.Gray)
                }
                Switch(checked = s.switchEnabled, onCheckedChange = { viewModel.toggleSwitchEnabled(it) })
            }
        }

        ConfigSection(title = "Cell Protection") {
            ConfigRow("Cell Overvoltage", s.cellOvervoltageMv, "mV") {
                editDialogItem = Pair("Cell Overvoltage", JbdProtocol.REG_COVP)
                editUnit = "mV"; editCurrentVal = "${s.cellOvervoltageMv ?: 3650}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_COVP, it.toInt()) }
            }
            ConfigRow("Cell Overvoltage Release", s.cellOvervoltageReleaseMv, "mV") {
                editDialogItem = Pair("Cell Overvoltage Release", JbdProtocol.REG_RCOVP)
                editUnit = "mV"; editCurrentVal = "${s.cellOvervoltageReleaseMv ?: 3550}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_RCOVP, it.toInt()) }
            }
            ConfigRow("Cell Undervoltage", s.cellUndervoltageMv, "mV") {
                editDialogItem = Pair("Cell Undervoltage", JbdProtocol.REG_CUVP)
                editUnit = "mV"; editCurrentVal = "${s.cellUndervoltageMv ?: 2500}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_CUVP, it.toInt()) }
            }
            ConfigRow("Cell Undervoltage Release", s.cellUndervoltageReleaseMv, "mV") {
                editDialogItem = Pair("Cell Undervoltage Release", JbdProtocol.REG_RCUVP)
                editUnit = "mV"; editCurrentVal = "${s.cellUndervoltageReleaseMv ?: 2700}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_RCUVP, it.toInt()) }
            }
            ConfigRow("Cell Overvoltage Delay", s.cellOvervoltageDelayS, "s") {
                editDialogItem = Pair("Cell Overvoltage Delay", JbdProtocol.REG_CVDELAY)
                editUnit = "s"; editCurrentVal = "${s.cellOvervoltageDelayS ?: 2}"
                onCommitAction = { viewModel.writeDelays(JbdProtocol.REG_CVDELAY, s.cellUndervoltageDelayS ?: 2, it.toInt()) }
            }
            ConfigRow("Cell Undervoltage Delay", s.cellUndervoltageDelayS, "s") {
                editDialogItem = Pair("Cell Undervoltage Delay", JbdProtocol.REG_CVDELAY)
                editUnit = "s"; editCurrentVal = "${s.cellUndervoltageDelayS ?: 2}"
                onCommitAction = { viewModel.writeDelays(JbdProtocol.REG_CVDELAY, it.toInt(), s.cellOvervoltageDelayS ?: 2) }
            }
        }

        ConfigSection(title = "Pack Protection") {
            ConfigRow("Pack Overvoltage", s.packOvervoltageMv, "mV") {
                editDialogItem = Pair("Pack Overvoltage", JbdProtocol.REG_POVP)
                editUnit = "mV"; editCurrentVal = "${s.packOvervoltageMv ?: 14600}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_POVP, (it / 10).toInt()) }
            }
            ConfigRow("Pack Overvoltage Release", s.packOvervoltageReleaseMv, "mV") {
                editDialogItem = Pair("Pack Overvoltage Release", JbdProtocol.REG_RPOVP)
                editUnit = "mV"; editCurrentVal = "${s.packOvervoltageReleaseMv ?: 14200}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_RPOVP, (it / 10).toInt()) }
            }
            ConfigRow("Pack Undervoltage", s.packUndervoltageMv, "mV") {
                editDialogItem = Pair("Pack Undervoltage", JbdProtocol.REG_PUVP)
                editUnit = "mV"; editCurrentVal = "${s.packUndervoltageMv ?: 10000}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_PUVP, (it / 10).toInt()) }
            }
            ConfigRow("Pack Undervoltage Release", s.packUndervoltageReleaseMv, "mV") {
                editDialogItem = Pair("Pack Undervoltage Release", JbdProtocol.REG_RPUVP)
                editUnit = "mV"; editCurrentVal = "${s.packUndervoltageReleaseMv ?: 11000}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_RPUVP, (it / 10).toInt()) }
            }
        }

        ConfigSection(title = "Balancing Parameters") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Balance Enable", fontSize = 14.sp)
                Switch(checked = s.balanceEnabled, onCheckedChange = { viewModel.toggleBalanceEnabled(it) })
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Balance Only When Charging", fontSize = 14.sp)
                Switch(checked = s.chargeBalanceOnly, onCheckedChange = { viewModel.toggleChargeBalanceOnly(it) })
            }
            ConfigRow("Balance Start Voltage", s.balanceStartMv, "mV") {
                editDialogItem = Pair("Balance Start Voltage", JbdProtocol.REG_BALVOL)
                editUnit = "mV"; editCurrentVal = "${s.balanceStartMv ?: 3400}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_BALVOL, it.toInt()) }
            }
            ConfigRow("Balance Precision", s.balancePrecisionMv, "mV") {
                editDialogItem = Pair("Balance Precision", JbdProtocol.REG_BALPREC)
                editUnit = "mV"; editCurrentVal = "${s.balancePrecisionMv ?: 15}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_BALPREC, it.toInt()) }
            }
        }

        ConfigSection(title = "Temperature Protection") {
            ConfigRow("Charge Over Temp", s.chargeOverTempC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Charge Over Temp", JbdProtocol.REG_CHGOT)
                editUnit = "°C"; editCurrentVal = "${s.chargeOverTempC ?: 55.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_CHGOT, it) }
            }
            ConfigRow("Charge Over Temp Release", s.chargeOverTempReleaseC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Charge Over Temp Release", JbdProtocol.REG_RCHGOT)
                editUnit = "°C"; editCurrentVal = "${s.chargeOverTempReleaseC ?: 50.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_RCHGOT, it) }
            }
            ConfigRow("Charge Under Temp", s.chargeUnderTempC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Charge Under Temp", JbdProtocol.REG_CHGUT)
                editUnit = "°C"; editCurrentVal = "${s.chargeUnderTempC ?: 0.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_CHGUT, it) }
            }
            ConfigRow("Charge Under Temp Release", s.chargeUnderTempReleaseC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Charge Under Temp Release", JbdProtocol.REG_RCHGUT)
                editUnit = "°C"; editCurrentVal = "${s.chargeUnderTempReleaseC ?: 5.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_RCHGUT, it) }
            }
            ConfigRow("Discharge Over Temp", s.dischargeOverTempC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Discharge Over Temp", JbdProtocol.REG_DSGOT)
                editUnit = "°C"; editCurrentVal = "${s.dischargeOverTempC ?: 65.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_DSGOT, it) }
            }
            ConfigRow("Discharge Over Temp Release", s.dischargeOverTempReleaseC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Discharge Over Temp Release", JbdProtocol.REG_RDSGOT)
                editUnit = "°C"; editCurrentVal = "${s.dischargeOverTempReleaseC ?: 55.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_RDSGOT, it) }
            }
            ConfigRow("Discharge Under Temp", s.dischargeUnderTempC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Discharge Under Temp", JbdProtocol.REG_DSGUT)
                editUnit = "°C"; editCurrentVal = "${s.dischargeUnderTempC ?: -10.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_DSGUT, it) }
            }
            ConfigRow("Discharge Under Temp Release", s.dischargeUnderTempReleaseC?.let { "%.1f".format(it) }, "°C") {
                editDialogItem = Pair("Discharge Under Temp Release", JbdProtocol.REG_RDSGUT)
                editUnit = "°C"; editCurrentVal = "${s.dischargeUnderTempReleaseC ?: 0.0f}"
                onCommitAction = { viewModel.writeTemp(JbdProtocol.REG_RDSGUT, it) }
            }
        }

        ConfigSection(title = "Current, Capacity & Chemistry") {
            ConfigRow("Cell Count", s.cellCount, "Cells") {
                editDialogItem = Pair("Cell Count", JbdProtocol.REG_CELL_CNT)
                editUnit = "Cells"; editCurrentVal = "${s.cellCount ?: 4}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_CELL_CNT, it.toInt()) }
            }

            val chemLabel = when (chemistry) {
                BatteryChemistry.LFP -> "LiFePO4 (3.20 V)"
                BatteryChemistry.NMC -> "Li-ion / NMC (3.65 V)"
                BatteryChemistry.CUSTOM -> "Custom (${"%.2f".format(customV)} V)"
            }
            ConfigRow("Battery Chemistry", chemLabel, "") {
                showChemDialog = true
            }

            ConfigRow("Design Capacity", s.designCapacityAh?.let { "%.2f".format(it) }, "Ah") {
                editDialogItem = Pair("Design Capacity", JbdProtocol.REG_DCAP)
                editUnit = "Ah"; editCurrentVal = "${s.designCapacityAh ?: 100f}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_DCAP, (it * 100).toInt()) }
            }
            ConfigRow("Cycle Capacity", s.cycleCapacityAh?.let { "%.2f".format(it) }, "Ah") {
                editDialogItem = Pair("Cycle Capacity", JbdProtocol.REG_CCAP)
                editUnit = "Ah"; editCurrentVal = "${s.cycleCapacityAh ?: 100f}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_CCAP, (it * 100).toInt()) }
            }
            ConfigRow("Charge Overcurrent", s.chargeOvercurrentA?.let { "%.2f".format(it) }, "A") {
                editDialogItem = Pair("Charge Overcurrent", JbdProtocol.REG_CHGOC)
                editUnit = "A"; editCurrentVal = "${s.chargeOvercurrentA ?: 50f}"
                onCommitAction = { viewModel.writeU16(JbdProtocol.REG_CHGOC, (it * 100).toInt()) }
            }
            ConfigRow("Discharge Overcurrent", s.dischargeOvercurrentA?.let { "%.2f".format(it) }, "A") {
                editDialogItem = Pair("Discharge Overcurrent", JbdProtocol.REG_DSGOC)
                editUnit = "A"; editCurrentVal = "${s.dischargeOvercurrentA ?: 100f}"
                onCommitAction = { viewModel.writeS16(JbdProtocol.REG_DSGOC, -(it * 100).toInt()) }
            }
            ConfigRow("Charge Overcurrent Delay", s.chargeOvercurrentDelayS, "s") {
                editDialogItem = Pair("Charge Overcurrent Delay", JbdProtocol.REG_CHGOCDELAY)
                editUnit = "s"; editCurrentVal = "${s.chargeOvercurrentDelayS ?: 5}"
                onCommitAction = { viewModel.writeDelays(JbdProtocol.REG_CHGOCDELAY, it.toInt(), 32) }
            }
            ConfigRow("Discharge Overcurrent Delay", s.dischargeOvercurrentDelayS, "s") {
                editDialogItem = Pair("Discharge Overcurrent Delay", JbdProtocol.REG_DSGOCDELAY)
                editUnit = "s"; editCurrentVal = "${s.dischargeOvercurrentDelayS ?: 5}"
                onCommitAction = { viewModel.writeDelays(JbdProtocol.REG_DSGOCDELAY, it.toInt(), 32) }
            }
        }
    }

    if (showNameDialog) {
        var inputName by remember { mutableStateOf(s.bluetoothName.ifBlank { connectedDevice?.name ?: "" }) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Rename Bluetooth BMS") },
            text = {
                Column {
                    Text("Enter the name to be broadcasted over Bluetooth (max 16 characters):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputName,
                        onValueChange = { if (it.length <= 16) inputName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (inputName.isNotBlank()) {
                        viewModel.writeBluetoothName(inputName)
                    }
                    showNameDialog = false
                }) {
                    Text("Save to BMS")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showChemDialog) {
        var selectedOpt by remember { mutableStateOf(chemistry) }
        var customInput by remember { mutableStateOf("$customV") }

        AlertDialog(
            onDismissRequest = { showChemDialog = false },
            title = { Text("Select Battery Chemistry") },
            text = {
                Column {
                    Text("Used to calculate stable pack energy (Wh) without load voltage sag:")
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedOpt = BatteryChemistry.LFP }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedOpt == BatteryChemistry.LFP), onClick = { selectedOpt = BatteryChemistry.LFP })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("LiFePO4 (LFP) — 3.20 V / cell")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedOpt = BatteryChemistry.NMC }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedOpt == BatteryChemistry.NMC), onClick = { selectedOpt = BatteryChemistry.NMC })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Lithium-Ion / NMC — 3.65 V / cell")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedOpt = BatteryChemistry.CUSTOM }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (selectedOpt == BatteryChemistry.CUSTOM), onClick = { selectedOpt = BatteryChemistry.CUSTOM })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Custom Nominal Voltage")
                    }

                    if (selectedOpt == BatteryChemistry.CUSTOM) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            label = { Text("Nominal Volts per cell") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val customVal = customInput.toFloatOrNull() ?: 3.20f
                    viewModel.setBatteryChemistry(selectedOpt, customVal)
                    showChemDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChemDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    editDialogItem?.let { (title, _) ->
        var inputVal by remember { mutableStateOf(editCurrentVal) }
        AlertDialog(
            onDismissRequest = { editDialogItem = null },
            title = { Text("Edit $title") },
            text = {
                Column {
                    Text("Enter new value in $editUnit:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputVal,
                        onValueChange = { inputVal = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val num = inputVal.toFloatOrNull()
                    if (num != null) {
                        onCommitAction(num)
                    }
                    editDialogItem = null
                }) {
                    Text("Save to BMS")
                }
            },
            dismissButton = {
                TextButton(onClick = { editDialogItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ConfigSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun ConfigRow(label: String, value: Any?, unit: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val text = if (value != null && value.toString().isNotBlank()) {
                if (unit.isNotBlank()) "$value $unit" else "$value"
            } else "--"
            Text(text = text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = Color.Gray)
        }
    }
}

@Composable
fun SettingsScreen(viewModel: BmsViewModel) {
    val currentTheme by viewModel.theme.collectAsState()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val calibration by viewModel.calibration.collectAsState()

    var showGuideDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Preferences & Tools", fontWeight = FontWeight.Bold, fontSize = 20.sp)

        // App Theme Selector
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("App Theme", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))

                AppTheme.values().forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setTheme(t) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (currentTheme == t), onClick = { viewModel.setTheme(t) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (t) {
                                AppTheme.SYSTEM -> "System"
                                AppTheme.LIGHT -> "Light"
                                AppTheme.BLACK -> "Black"
                            }
                        )
                    }
                }
            }
        }

        // Speed & Consumption Unit Selector
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Speed & Efficiency Tracker", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Requires GPS location permission while active", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))

                SpeedUnit.values().forEach { u ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setSpeedUnit(u) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (speedUnit == u), onClick = { viewModel.setSpeedUnit(u) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when (u) {
                                SpeedUnit.OFF -> "Disabled"
                                SpeedUnit.KM_H -> "Metric (km/h & Wh/km)"
                                SpeedUnit.NMI_H -> "Nautical (nmi/h & Wh/nmi)"
                            }
                        )
                    }
                }
            }
        }

        // Capacity Calibration Assistant
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Capacity Calibration", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    IconButton(onClick = { showGuideDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Guide", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("Measures usable Ah & Wh delivered during discharge", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Drawn Capacity", color = Color.Gray, fontSize = 12.sp)
                        Text("${"%.2f".format(calibration.drawnAh)} Ah", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("Delivered Energy", color = Color.Gray, fontSize = 12.sp)
                        Text("${"%.1f".format(calibration.deliveredWh)} Wh", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!calibration.isRunning) {
                        Button(
                            onClick = { viewModel.startCalibration() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start Run")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.stopCalibration() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stop Run")
                        }
                    }

                    if (calibration.drawnAh > 0.5f) {
                        OutlinedButton(
                            onClick = { viewModel.applyCalibratedCapacity(calibration.drawnAh) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Write to BMS")
                        }
                    }
                }
            }
        }

        // About & Legal Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("About & Legal", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("App Version", fontSize = 14.sp)
                    Text("1.0.0", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Hardware Protocol", fontSize = 14.sp)
                    Text("JBD / Jiabaida", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLicenseDialog = true }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("License", fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BSD 3-Clause", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Gray)
                    }
                }
            }
        }
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("Capacity Calibration Guide") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "1. Fully Charge: Charge the battery completely until the BMS automatically snaps to 100% and current drops to near zero.\n\n" +
                        "2. Start Run: Tap 'Start Run' in this assistant before starting your discharge.\n\n" +
                        "3. Continuous Discharge: Drain the battery with a steady load (no charging sources connected) until the low-voltage cutoff triggers.\n\n" +
                        "4. Save Result: Tap 'Stop Run' and click 'Write to BMS'. This permanently programs your true measured capacity into Design & Cycle capacity registers.\n\n" +
                        "5. Recharge: Charge the battery back to 100%. The gauge is now fully adapted."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuideDialog = false }) {
                    Text("Understood")
                }
            }
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("BSD 3-Clause License") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Copyright (c) 2026, OpenBMS Contributors\n\n" +
                        "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:\n\n" +
                        "1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.\n\n" +
                        "2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.\n\n" +
                        "3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.\n\n" +
                        "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
