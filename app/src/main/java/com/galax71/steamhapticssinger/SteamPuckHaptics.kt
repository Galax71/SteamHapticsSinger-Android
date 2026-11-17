package com.galax71.steamhapticssinger

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Experimental Steam Controller 2 (2026) Puck standard dual rumble.
 * Only writes the documented 0x80 output report to Valve 28DE:1304.
 * No firmware, pairing, or persistent settings are modified.
 */
class SteamPuckHaptics(context: Context) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var face: UsbInterface? = null
    private var output: UsbEndpoint? = null
    private var device: UsbDevice? = null

    // SteamHapticsSinger defaults to gain 0. Do not exceed upstream default.
    @Volatile var directVelocityGain: Boolean = false
    @Volatile var swapTrackpadAndRumble: Boolean = false
    @Volatile var musicGain: Int = -3
        set(value) { field = value.coerceIn(-35, 0) }

    // Calibrated frequencies from SteamHapticsSinger, BSD-3-Clause.
    private val rearFrequencies = intArrayOf(0, 10, 10, 11, 11, 12, 13, 13, 14, 15, 16, 16, 17, 18, 19, 20, 22, 23, 24, 25, 27, 29, 30, 32, 34, 36, 38, 40, 42, 45, 47, 50, 53, 56, 59, 63, 66, 70, 75, 80, 84, 89, 94, 100, 107, 113, 120, 126, 134, 142, 151, 160, 169, 179, 189, 200, 213, 226, 239, 253, 267, 283, 300, 318, 336, 357, 377, 399, 423, 449, 477, 505, 535, 566, 598, 636, 674, 713, 756, 800, 848, 898, 951, 1008, 1068, 1131, 1199, 1270, 1345, 1425, 1510, 1600, 1693, 1792, 1897, 2008, 2125, 2249, 2381, 2521, 2669, 2826, 2992, 3168, 3354, 3552, 3761, 3983, 4218, 4467, 4731, 5010, 5306, 5620, 5952, 6304, 6677, 7072, 7491, 7934, 8404, 8902, 9429, 9988, 10580, 11207, 11872, 12576)
    private val padFrequencies = intArrayOf(0, 9, 9, 10, 10, 11, 12, 12, 13, 14, 15, 15, 16, 17, 18, 19, 21, 22, 23, 24, 26, 28, 29, 31, 33, 35, 37, 39, 41, 44, 46, 49, 52, 55, 58, 62, 65, 69, 73, 78, 82, 87, 92, 98, 104, 110, 117, 123, 131, 139, 147, 156, 165, 175, 185, 196, 208, 220, 233, 247, 261, 276, 293, 310, 328, 349, 369, 391, 414, 439, 466, 493, 522, 552, 584, 621, 658, 696, 738, 781, 828, 877, 929, 985, 1043, 1105, 1171, 1240, 1314, 1392, 1475, 1562, 1655, 1754, 1858, 1969, 2085, 2209, 2340, 2480, 2627, 2784, 2949, 3124, 3311, 3507, 3716, 3938, 4173, 4422, 4686, 4965, 5261, 5575, 5907, 6259, 6632, 7027, 7446, 7889, 8359, 8857, 9384, 9943, 10535, 11162, 11827, 12531)

    // Prefer a directly USB-connected Steam Controller, then the wireless Puck.
    // Product IDs other than the known Puck are discovered but not assumed to
    // support the Puck's private 0x83 report without protocol verification.
    fun directControllers(): List<UsbDevice> = usb.deviceList.values.filter {
        it.vendorId == 0x28de && it.productId != 0x1304
    }
    fun receiver(): UsbDevice? = usb.deviceList.values.firstOrNull {
        it.vendorId == 0x28de && it.productId == 0x1304
    }
    fun connectionSummary(): String = when {
        directControllers().isNotEmpty() -> "Direct Valve USB device detected (haptic protocol not verified)"
        receiver() != null -> "Steam Puck USB receiver detected"
        else -> "No Valve USB device detected"
    }

    fun hasPermission(): Boolean = receiver()?.let(usb::hasPermission) == true

    @Synchronized fun connect(): String {
        close()
        val puck = receiver() ?: return "Steam Controller Puck not detected"
        if (!usb.hasPermission(puck)) return "USB permission required"
        val candidates = (0 until puck.interfaceCount).map { puck.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        for (candidate in candidates) {
            val ep = (0 until candidate.endpointCount).map { candidate.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
                ?: continue
            val handle = usb.openDevice(puck) ?: return "Android could not open the Puck"
            if (handle.claimInterface(candidate, true)) {
                connection = handle; face = candidate; output = ep; device = puck
                return "Connected to Puck HID interface ${candidate.id} (experimental output)"
            }
            handle.close()
        }
        return "No accessible HID interrupt OUT endpoint found on the Puck"
    }

    @Synchronized fun rumble(left: Int, right: Int): Boolean {
        val conn = connection ?: return false
        val ep = output ?: return false
        val l = left.coerceIn(0, 32767)
        val r = right.coerceIn(0, 32767)
        // SDL Triton standard rumble output packet (report ID 0x80).
        val packet = byteArrayOf(
            0x80.toByte(), 0, 0,
            (l and 255).toByte(), ((l shr 8) and 255).toByte(), 0,
            (r and 255).toByte(), ((r shr 8) and 255).toByte(), 0, 0
        )
        return conn.bulkTransfer(ep, packet, packet.size, 100) == packet.size
    }

    /**
     * Experimental Steam Controller 2026 frequency-based haptic note report (0x83).
     * Based on the protocol used by CrazyCritic89/SteamHapticsSinger (BSD-3-Clause).
     * MIDI channels 0..3 map to actuator IDs 4,3,1,0 respectively.
     * Report is 64 bytes, using the existing USB HID interrupt OUT endpoint.
     */
    @Synchronized fun note(channel: Int, pitch: Int?, velocity: Int = 64): Boolean {
        val conn = connection ?: return false
        val ep = output ?: return false
        if (channel !in 0..3) return false
        // SteamHapticsSinger -s toggles the Triton trackpad/rumble pair.
        val actuator = (if (swapTrackpadAndRumble) intArrayOf(1, 0, 4, 3) else intArrayOf(4, 3, 1, 0))[channel]
        val packet = ByteArray(64)
        packet[0] = 0x83.toByte()
        packet[1] = actuator.toByte()
        if (pitch == null) {
            packet[2] = 0x80.toByte()
            packet[6] = 0x80.toByte()
        } else {
            if (pitch !in 0..127) return false
            val hz = if (actuator > 2) rearFrequencies[pitch] else padFrequencies[pitch]
            // Match upstream gain range, capped at SteamHapticsSinger default (0).
            // SteamHapticsSinger -e / _dv: velocity controls note strength.
            // Keep output capped at upstream's normal gain (0).
            val gain = if (directVelocityGain) ((velocity.coerceIn(0, 127) * 255) / 127 - 128).coerceAtMost(0) else musicGain
            packet[2] = gain.toByte()
            // The upstream HID report uses 0xFF as its frequency divisor.
            packet[3] = (hz % 0xff).toByte()
            packet[4] = (hz / 0xff).toByte()
            packet[5] = 0xff.toByte()
            packet[6] = 0x7f.toByte()
        }
        return try { conn.bulkTransfer(ep, packet, packet.size, 100) == packet.size }
        catch (_: Exception) { false }
    }

    @Synchronized fun stopNotes() {
        for (channel in 0..3) note(channel, null)
    }

    @Synchronized fun close() {
        try { stopNotes() } catch (_: Exception) {}
        try { rumble(0, 0) } catch (_: Exception) {}
        try { face?.let { connection?.releaseInterface(it) } } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        connection = null; face = null; output = null; device = null
    }
}
