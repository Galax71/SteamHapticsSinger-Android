package com.galax71.steamhapticssinger

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

/**
 * Experimental Steam Controller 2026 BLE GATT output transport.
 *
 * The Valve custom service/characteristic IDs come from the published SC2 BLE
 * protocol investigation. Do not write until the characteristic advertises
 * WRITE or WRITE_NO_RESPONSE. This does not use Android's input-only gamepad API.
 * Firmware behavior and report framing must be verified on real hardware.
 */
class SteamBluetoothHaptics(private val context: Context, private val status: (String) -> Unit) {
    private val serviceId = UUID.fromString("100f6c32-1735-4313-b402-38567131e5f3")
    private val outputId = UUID.fromString("100f6c34-1735-4313-b402-38567131e5f3")
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile var ready: Boolean = false
        private set
    @Volatile private var connecting = false
    private var busy = false
    private var lastWrite = 0L
    private val trackpad = intArrayOf(0, 9, 9, 10, 10, 11, 12, 12, 13, 14, 15, 15, 16, 17, 18, 19, 21, 22, 23, 24, 26, 28, 29, 31, 33, 35, 37, 39, 41, 44, 46, 49, 52, 55, 58, 62, 65, 69, 73, 78, 82, 87, 92, 98, 104, 110, 117, 123, 131, 139, 147, 156, 165, 175, 185, 196, 208, 220, 233, 247, 261, 276, 293, 310, 328, 349, 369, 391, 414, 439, 466, 493, 522, 552, 584, 621, 658, 696, 738, 781, 828, 877, 929, 985, 1043, 1105, 1171, 1240, 1314, 1392, 1475, 1562, 1655, 1754, 1858, 1969, 2085, 2209, 2340, 2480, 2627, 2784, 2949, 3124, 3311, 3507, 3716, 3938, 4173, 4422, 4686, 4965, 5261, 5575, 5907, 6259, 6632, 7027, 7446, 7889, 8359, 8857, 9384, 9943, 10535, 11162, 11827, 12531)
    private val grip = intArrayOf(0, 10, 10, 11, 11, 12, 13, 13, 14, 15, 16, 16, 17, 18, 19, 20, 22, 23, 24, 25, 27, 29, 30, 32, 34, 36, 38, 40, 42, 45, 47, 50, 53, 56, 59, 63, 66, 70, 75, 80, 84, 89, 94, 100, 107, 113, 120, 126, 134, 142, 151, 160, 169, 179, 189, 200, 213, 226, 239, 253, 267, 283, 300, 318, 336, 357, 377, 399, 423, 449, 477, 505, 535, 566, 598, 636, 674, 713, 756, 800, 848, 898, 951, 1008, 1068, 1131, 1199, 1270, 1345, 1425, 1510, 1600, 1693, 1792, 1897, 2008, 2125, 2249, 2381, 2521, 2669, 2826, 2992, 3168, 3354, 3552, 3761, 3983, 4218, 4467, 4731, 5010, 5306, 5620, 5952, 6304, 6677, 7072, 7491, 7934, 8404, 8902, 9429, 9988, 10580, 11207, 11872, 12576)

    private fun permitted(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun connect(): String {
        if (!permitted()) return "Bluetooth Nearby devices permission required"
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "Bluetooth adapter unavailable"
        if (!adapter.isEnabled) return "Bluetooth is disabled"
        val target = try {
            adapter.bondedDevices.firstOrNull { it.name?.startsWith("Steam Ctrl (BT)", true) == true }
        } catch (_: SecurityException) { null } ?: return "No paired Steam Controller 2026 found"
        if (ready) return "Bluetooth haptic GATT ready"
        if (connecting) return "Bluetooth haptic connection in progress"
        close()
        return try {
            connecting = true
            gatt = target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            "Connecting to Steam Controller 2026 BLE haptic service..."
        } catch (e: Exception) { connecting = false; "Bluetooth GATT connection failed: ${e.message}" }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
            if (code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                try { if (!g.discoverServices()) report("BLE connected but discovery could not start") }
                catch (e: Exception) { report("BLE discovery failed: ${e.message}") }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) {
                connecting = false
                ready = false
                report("Steam Controller BLE disconnected (GATT status $code)")
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            connecting = false
            val ch = if (code == BluetoothGatt.GATT_SUCCESS) g.getService(serviceId)?.getCharacteristic(outputId) else null
            if (ch == null || ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                ready = false
                report("Valve writable BLE output characteristic unavailable; haptic playback cannot start")
                return
            }
            synchronized(lock) { characteristic = ch; busy = false; queue.clear() }
            ready = true
            report("Steam Controller 2026 BLE output characteristic ready (experimental). Try a short A4 note.")
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, statusCode: Int) {
            synchronized(lock) { busy = false }
            if (statusCode != BluetoothGatt.GATT_SUCCESS) report("Bluetooth haptic write failed: GATT $statusCode")
            main.post { drain() }
        }
    }

    private fun report(message: String) { main.post { status(message) } }

    fun note(channel: Int, pitch: Int?, velocity: Int = 64, gainDb: Int = -3, swap: Boolean = false): Boolean {
        if (!ready || channel !in 0..3 || (pitch != null && pitch !in 0..127)) return false
        val actuator = (if (swap) intArrayOf(1, 0, 4, 3) else intArrayOf(4, 3, 1, 0))[channel]
        val packet = ByteArray(10)
        packet[0] = 0x83.toByte()
        packet[1] = actuator.toByte()
        if (pitch == null) {
            packet[2] = 0x80.toByte()
        } else {
            val hz = if (actuator > 2) grip[pitch] else trackpad[pitch]
            packet[2] = gainDb.coerceIn(-35, 0).toByte()
            packet[3] = (hz and 255).toByte()
            packet[4] = (hz shr 8).toByte()
            packet[5] = 0xff.toByte()
            packet[6] = 0x7f.toByte()
        }
        synchronized(lock) {
            // Bound queue memory and prevent old MIDI notes from playing seconds late.
            if (queue.size >= 32) queue.removeFirst()
            queue.addLast(packet)
        }
        main.post { drain() }
        return true // queued, not confirmation of physical vibration
    }

    private fun drain() {
        if (!ready || !permitted()) return
        val g = gatt ?: return
        val ch = characteristic ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastWrite < 40) {
            main.postDelayed({ drain() }, 40 - (now - lastWrite))
            return
        }
        val packet = synchronized(lock) {
            if (busy || queue.isEmpty()) null else {
                busy = true
                queue.removeFirst()
            }
        } ?: return
        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = packet
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            lastWrite = now
            if (!result) {
                synchronized(lock) { busy = false }
                report("BLE output report rejected by Android")
            }
        } catch (e: Exception) {
            synchronized(lock) { busy = false }
            report("BLE haptic output failed: ${e.message}")
        }
    }

    fun stop() {
        if (!ready) return
        synchronized(lock) { queue.clear() }
        for (i in 0..3) note(i, null)
    }

    fun close() {
        ready = false
        connecting = false
        synchronized(lock) { queue.clear(); characteristic = null; busy = false }
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }
}
