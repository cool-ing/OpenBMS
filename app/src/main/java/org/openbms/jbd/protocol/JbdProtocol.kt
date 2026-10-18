package org.openbms.jbd.protocol

import org.openbms.jbd.model.BmsBasicInfo
import org.openbms.jbd.model.BmsCellInfo
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object JbdProtocol {
    const val START_BYTE: Byte = 0xDD.toByte()
    const val STOP_BYTE: Byte = 0x77.toByte()
    const val CMD_READ: Byte = 0xA5.toByte()
    const val CMD_WRITE: Byte = 0x5A.toByte()

    // Real-time telemetry registers
    const val REG_BASIC_INFO: Byte = 0x03.toByte()
    const val REG_CELL_VOLTAGES: Byte = 0x04.toByte()
    const val REG_DEVICE_MODEL_READ: Byte = 0x05.toByte()
    const val REG_MOSFET_CTRL: Byte = 0xE1.toByte()

    // Factory mode handshake
    const val REG_FACTORY_MODE: Byte = 0x00.toByte()
    const val REG_EXIT_FACTORY: Byte = 0x01.toByte()

    // EEPROM configuration registers
    const val REG_DCAP: Byte = 0x10.toByte()
    const val REG_CCAP: Byte = 0x11.toByte()
    const val REG_CHGOT: Byte = 0x18.toByte()
    const val REG_RCHGOT: Byte = 0x19.toByte()
    const val REG_CHGUT: Byte = 0x1A.toByte()
    const val REG_RCHGUT: Byte = 0x1B.toByte()
    const val REG_DSGOT: Byte = 0x1C.toByte()
    const val REG_RDSGOT: Byte = 0x1D.toByte()
    const val REG_DSGUT: Byte = 0x1E.toByte()
    const val REG_RDSGUT: Byte = 0x1F.toByte()
    const val REG_POVP: Byte = 0x20.toByte()
    const val REG_RPOVP: Byte = 0x21.toByte()
    const val REG_PUVP: Byte = 0x22.toByte()
    const val REG_RPUVP: Byte = 0x23.toByte()
    const val REG_COVP: Byte = 0x24.toByte()
    const val REG_RCOVP: Byte = 0x25.toByte()
    const val REG_CUVP: Byte = 0x26.toByte()
    const val REG_RCUVP: Byte = 0x27.toByte()
    const val REG_CHGOC: Byte = 0x28.toByte()
    const val REG_DSGOC: Byte = 0x29.toByte()
    const val REG_BALVOL: Byte = 0x2A.toByte()
    const val REG_BALPREC: Byte = 0x2B.toByte()
    const val REG_FUNC_CONFIG: Byte = 0x2D.toByte()
    const val REG_CELL_CNT: Byte = 0x2F.toByte()
    const val REG_CVDELAY: Byte = 0x3D.toByte()
    const val REG_CHGOCDELAY: Byte = 0x3E.toByte()
    const val REG_DSGOCDELAY: Byte = 0x3F.toByte()
    const val REG_EEPROM_NAME_WRITE: Byte = 0xA1.toByte()

    fun buildReadCommand(register: Byte): ByteArray {
        val payload = byteArrayOf(START_BYTE, CMD_READ, register, 0x00)
        return payload + calculateChecksum(byteArrayOf(register, 0x00)) + byteArrayOf(STOP_BYTE)
    }

    fun buildWriteCommand(register: Byte, data: ByteArray): ByteArray {
        val len = data.size.toByte()
        val chkBytes = byteArrayOf(register, len) + data
        return byteArrayOf(START_BYTE, CMD_WRITE, register, len) + data + calculateChecksum(chkBytes) + byteArrayOf(STOP_BYTE)
    }

    fun buildEnterFactoryMode(): ByteArray =
        buildWriteCommand(REG_FACTORY_MODE, byteArrayOf(0x56, 0x78))

    fun buildExitFactoryModeWithoutSaving(): ByteArray =
        buildWriteCommand(REG_EXIT_FACTORY, byteArrayOf(0x00, 0x00))

    fun buildExitFactoryModeAndSave(): ByteArray =
        buildWriteCommand(REG_EXIT_FACTORY, byteArrayOf(0x28, 0x28))

    fun buildMosfetCommand(chargeOn: Boolean, dischargeOn: Boolean): ByteArray {
        val mode = when {
            chargeOn && dischargeOn -> 0x00
            !chargeOn && dischargeOn -> 0x01
            chargeOn && !dischargeOn -> 0x02
            else -> 0x03
        }.toByte()
        return buildWriteCommand(REG_MOSFET_CTRL, byteArrayOf(0x00, mode))
    }

    /**
     * Builds the direct BLE module command to change the advertised Bluetooth name.
     * Overkill / JBD spec: FF AA 07 [len] [chars] [modulo-256 checksum]
     */
    fun buildBleRenamePacket(name: String): ByteArray {
        val clean = name.trim().take(16).toByteArray(StandardCharsets.US_ASCII)
        val len = clean.size.toByte()
        val cmd = 0x07.toByte()

        var sum = (cmd.toInt() and 0xFF) + (len.toInt() and 0xFF)
        for (b in clean) {
            sum += (b.toInt() and 0xFF)
        }
        val checksum = (sum % 256).toByte()

        return byteArrayOf(0xFF.toByte(), 0xAA.toByte(), cmd, len) + clean + byteArrayOf(checksum)
    }

    fun buildEepromNameCommand(name: String): ByteArray {
        val clean = name.trim().take(16).toByteArray(StandardCharsets.US_ASCII)
        val payload = byteArrayOf(clean.size.toByte()) + clean
        return buildWriteCommand(REG_EEPROM_NAME_WRITE, payload)
    }

    private fun calculateChecksum(bytes: ByteArray): ByteArray {
        val sum = bytes.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        val checksum = (0x10000 - sum) and 0xFFFF
        return byteArrayOf((checksum shr 8).toByte(), (checksum and 0xFF).toByte())
    }

    class FrameAccumulator {
        private val buffer = ByteArrayOutputStream()

        fun feed(data: ByteArray): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            for (byte in data) {
                if (buffer.size() == 0) {
                    if (byte == START_BYTE) {
                        buffer.write(byte.toInt())
                    }
                } else {
                    buffer.write(byte.toInt())
                    val current = buffer.toByteArray()
                    if (current.size >= 4) {
                        val payloadLen = current[3].toInt() and 0xFF
                        val expectedLen = 7 + payloadLen
                        if (current.size == expectedLen) {
                            if (byte == STOP_BYTE) {
                                frames.add(current)
                            }
                            buffer.reset()
                        } else if (current.size > expectedLen) {
                            buffer.reset()
                        }
                    }
                }
            }
            return frames
        }
    }

    fun parseBasicInfo(frame: ByteArray): BmsBasicInfo? {
        if (frame.size < 31 || frame[1] != REG_BASIC_INFO || frame[2] != 0x00.toByte()) return null

        val u16 = { offset: Int -> ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF) }
        val s16 = { offset: Int -> u16(offset).toShort().toInt() }

        val voltageMv = u16(4) * 10
        val currentA = s16(6) / 100.0f
        val remainingCapAh = u16(8) / 100.0f
        val nominalCapAh = u16(10) / 100.0f
        val cycles = u16(12)
        val protectBits = u16(20)
        val soc = frame[23].toInt() and 0xFF
        val fet = frame[24].toInt() and 0xFF
        val chargeFet = (fet and 0x01) != 0
        val dischargeFet = (fet and 0x02) != 0
        val cellCount = frame[25].toInt() and 0xFF
        val ntcCount = frame[26].toInt() and 0xFF

        val temps = mutableListOf<Float>()
        for (i in 0 until ntcCount) {
            val offset = 27 + (i * 2)
            if (offset + 1 < frame.size - 3) {
                val kelvin01 = u16(offset)
                temps.add((kelvin01 - 2731) / 10.0f)
            }
        }

        return BmsBasicInfo(
            voltageMv = voltageMv,
            currentA = currentA,
            remainingCapacityAh = remainingCapAh,
            nominalCapacityAh = nominalCapAh,
            cycles = cycles,
            soc = soc,
            chargeFetActive = chargeFet,
            dischargeFetActive = dischargeFet,
            cellCount = cellCount,
            temperaturesC = temps,
            protectionBits = protectBits
        )
    }

    fun parseCellVoltages(frame: ByteArray): BmsCellInfo? {
        if (frame.size < 7 || frame[1] != REG_CELL_VOLTAGES || frame[2] != 0x00.toByte()) return null
        val payloadLen = frame[3].toInt() and 0xFF
        val cellCount = payloadLen / 2
        val cells = mutableListOf<Int>()
        for (i in 0 until cellCount) {
            val offset = 4 + (i * 2)
            val mv = ((frame[offset].toInt() and 0xFF) shl 8) or (frame[offset + 1].toInt() and 0xFF)
            cells.add(mv)
        }
        return BmsCellInfo(cellVoltagesMv = cells)
    }

    fun parseString(frame: ByteArray): String? {
        if (frame.size < 7 || frame[2] != 0x00.toByte()) return null
        val payloadLen = frame[3].toInt() and 0xFF
        if (payloadLen == 0) return ""
        val stringLen = frame[4].toInt() and 0xFF
        return if (stringLen in 1 until payloadLen) {
            String(frame, 5, stringLen, StandardCharsets.US_ASCII).trim()
        } else {
            String(frame, 4, payloadLen, StandardCharsets.US_ASCII).trim()
        }
    }
}
