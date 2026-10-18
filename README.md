# OpenBMS

A clean, modern, open-source Android app for monitoring and configuring **JBD / Jiabaida** Battery Management Systems over Bluetooth.

![Release](https://img.shields.io/badge/version-1.0.0-blue.svg)
![License](https://img.shields.io/badge/license-BSD--3--Clause-green.svg)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)

## Features
- **Real-Time Telemetry:** Pack voltage, charge/discharge current, instantaneous power, and capacity (Ah).
- **Accurate Energy Calculations:** Selectable battery chemistry (LiFePO4, Li-Ion/NMC, or custom nominal voltage) prevents Wh fluctuation from load voltage sag.
- **Individual Cell Monitoring:** Millivolt cell grid with delta ($\Delta\text{ mV}$) tracking.
- **Hardware Controls:** Independent Charge and Discharge MOSFET switching and physical SW switch activation.
- **EEPROM Configuration:** Read and modify cell and pack cutoffs, balancing start/precision, delays, and temperature cutoffs.
- **Bluetooth Renaming:** Direct BLE dongle module renaming.
- **GPS Speed & Efficiency Tracker:** Displays speed in km/h or nmi/h and energy consumption in Wh/km or Wh/nmi.
- **Capacity Calibration Assistant:** Integrated Coulomb counter for measuring true battery capacity during an active discharge run.
- **OLED Dark Mode:** True `#000000` AMOLED pitch black theme.

## ⚠️ Safety Warning & Disclaimer
Modifying battery management parameters can lead to cell damage, thermal runaway, fire, or personal injury if configured incorrectly. Always consult your cell manufacturer's safe operating limits before editing protection thresholds. This software is provided "AS IS" under the BSD 3-Clause License without warranty of any kind. Use entirely at your own risk.

## License
BSD 3-Clause License. See [LICENSE](LICENSE) for details.
