package com.vescwatch

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.*

/**
 * BLE manager for VESC NRF51822 dongle communication.
 * Uses modern BluetoothLeScanner API (API 21+).
 *
 * Trusted device flow:
 *   1. Scan finds VESC dongles in range
 *   2. If any are trusted (MAC in SharedPreferences), connect to strongest signal
 *   3. If none trusted, notify listener with list of found devices for user to pick
 *   4. Once connected + confirmed, MAC is auto-trusted
 *   5. Trusted list persists across app restarts (max 10 devices)
 */
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "VescBLE"
        private const val PREFS_NAME = "vesc_ble"
        private const val KEY_TRUSTED = "trusted_macs"

        // Nordic UART Service UUIDs (used by VESC NRF dongle)
        private val NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX      = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX      = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val BLE_CHUNK_SIZE = 20
        private const val POLL_INTERVAL_MS = 500L
        private const val SCAN_COLLECT_MS = 5000L
    }

    interface Listener {
        fun onStatusChanged(status: String)
        fun onDataReceived(data: VescData)
        fun onDevicesFound(devices: List<FoundDevice>)
    }

    data class FoundDevice(
        val device: BluetoothDevice,
        val name: String,
        val mac: String,
        val rssi: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    var listener: Listener? = null

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var connectedMac: String? = null

    private var scanning = false
    private var connected = false

    private val foundDevices = mutableListOf<FoundDevice>()

    // Receive buffer for packet reassembly
    private val rxBuffer = ByteArray(1024)
    private var rxLen = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (connected && txChar != null) {
                rxLen = 0 // Clear stale data before each request
                sendGetValues()
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ---- Trusted device management ----

    private fun getTrustedMacs(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet())

    fun trustDevice(mac: String) {
        val trusted = getTrustedMacs()
        trusted.add(mac)
        prefs.edit().putStringSet(KEY_TRUSTED, trusted).apply()
        Log.i(TAG, "Trusted device: $mac (total: ${trusted.size})")
    }

    fun clearTrustedDevices() {
        prefs.edit().remove(KEY_TRUSTED).apply()
        Log.i(TAG, "Cleared all trusted devices")
    }

    fun isTrusted(mac: String): Boolean = getTrustedMacs().contains(mac)

    fun trustedCount(): Int = getTrustedMacs().size

    fun connectToDevice(fd: FoundDevice) {
        stopScan()
        trustDevice(fd.mac)
        notifyStatus(fd.name)
        connectDevice(fd.device)
    }

    // ---- Lifecycle ----

    fun start() {
        if (btAdapter == null) {
            notifyStatus("NO BT HARDWARE")
            return
        }
        if (!btAdapter.isEnabled) {
            notifyStatus("BT DISABLED")
            return
        }
        startScan()
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
        stopScan()
        gatt?.close()
        gatt = null
        connected = false
        connectedMac = null
    }

    // ---- BLE Scanning (modern API) ----

    private fun startScan() {
        if (scanning) return
        scanner = btAdapter?.bluetoothLeScanner
        if (scanner == null) {
            notifyStatus("NO BLE SCANNER")
            return
        }
        scanning = true
        foundDevices.clear()

        val trusted = getTrustedMacs()
        if (trusted.isEmpty()) {
            notifyStatus("SCANNING (NEW)...")
        } else {
            notifyStatus("SCANNING...")
        }
        Log.i(TAG, "Starting BLE scan, trusted=${trusted.size}")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)

        handler.postDelayed(scanEvaluator, SCAN_COLLECT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanEvaluator)
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Prefer scan record name (no permission needed) over device.name (needs BLUETOOTH_CONNECT)
            val name = result.scanRecord?.deviceName ?: device.name ?: return
            if (!(name.contains("VESC", true) || name.contains("NRF", true))) return

            val mac = device.address
            val rssi = result.rssi
            Log.i(TAG, "Found: $name [$mac] rssi=$rssi")

            // Update or add to found list (keep strongest RSSI)
            val idx = foundDevices.indexOfFirst { it.mac == mac }
            if (idx >= 0) {
                if (rssi > foundDevices[idx].rssi) {
                    foundDevices[idx] = FoundDevice(device, name, mac, rssi)
                }
            } else {
                foundDevices.add(FoundDevice(device, name, mac, rssi))
            }

            // If this is a trusted device, connect immediately
            if (isTrusted(mac)) {
                Log.i(TAG, "Trusted device found, connecting immediately: $name")
                stopScan()
                notifyStatus(name)
                connectDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            notifyStatus("SCAN FAILED")
            handler.postDelayed({ startScan() }, 3000)
        }
    }

    private val scanEvaluator = Runnable {
        if (!scanning || connected) return@Runnable
        stopScan()

        if (foundDevices.isEmpty()) {
            notifyStatus("NO VESC FOUND")
            handler.postDelayed({ startScan() }, 3000)
            return@Runnable
        }

        // Check for trusted devices (strongest RSSI first)
        val bestTrusted = foundDevices.filter { isTrusted(it.mac) }.maxByOrNull { it.rssi }
        if (bestTrusted != null) {
            Log.i(TAG, "Connecting to trusted: ${bestTrusted.name}")
            notifyStatus(bestTrusted.name)
            connectDevice(bestTrusted.device)
            return@Runnable
        }

        // No trusted — if single VESC, auto-connect + trust
        if (foundDevices.size == 1) {
            val fd = foundDevices[0]
            Log.i(TAG, "Single VESC found, auto-trusting: ${fd.name}")
            trustDevice(fd.mac)
            notifyStatus(fd.name)
            connectDevice(fd.device)
            return@Runnable
        }

        // Multiple untrusted — let user pick
        Log.i(TAG, "Multiple VESCs found (${foundDevices.size}), showing picker")
        notifyStatus("SELECT BOARD")
        listener?.onDevicesFound(ArrayList(foundDevices))
    }

    // ---- BLE Connection ----

    private fun connectDevice(device: BluetoothDevice) {
        connectedMac = device.address
        val auto = isTrusted(connectedMac!!)
        gatt = device.connectGatt(context, auto, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT, discovering services...")
                handler.post { notifyStatus("DISCOVERING...") }
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected from GATT (status=$status)")
                connected = false
                txChar = null
                g.close()
                gatt = null
                handler.post {
                    notifyStatus("DISCONNECTED")
                    handler.removeCallbacks(pollRunnable)
                    handler.postDelayed({ startScan() }, 2000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val nus = g.getService(NUS_SERVICE)
            if (nus == null) {
                Log.e(TAG, "NUS service not found on device")
                handler.post { notifyStatus("NOT A VESC") }
                return
            }

            txChar = nus.getCharacteristic(NUS_TX)
            val rxChar = nus.getCharacteristic(NUS_RX)

            if (txChar == null || rxChar == null) {
                Log.e(TAG, "NUS characteristics not found")
                return
            }

            g.setCharacteristicNotification(rxChar, true)
            val desc = rxChar.getDescriptor(CCCD)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            }

            connected = true
            if (connectedMac != null) {
                trustDevice(connectedMac!!)
            }
            Log.i(TAG, "VESC BLE ready — starting telemetry polling")
            handler.post {
                notifyStatus("CONNECTED")
                handler.postDelayed(pollRunnable, 500)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (NUS_RX == c.uuid) {
                val chunk = c.value ?: return
                if (chunk.isEmpty()) return
                onBleDataReceived(chunk)
            }
        }

        // API 33+ callback — value passed directly instead of via characteristic.getValue()
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (NUS_RX == c.uuid) {
                if (value.isEmpty()) return
                onBleDataReceived(value)
            }
        }
    }

    // ---- Send / Receive ----

    @Suppress("DEPRECATION")
    private fun sendGetValues() {
        val g = gatt ?: return
        val tx = txChar ?: return
        val packet = VescProtocol.buildGetValues()
        var i = 0
        while (i < packet.size) {
            val end = minOf(i + BLE_CHUNK_SIZE, packet.size)
            tx.value = packet.copyOfRange(i, end)
            g.writeCharacteristic(tx)
            i = end
        }
    }

    private fun onBleDataReceived(chunk: ByteArray) {
        if (rxLen + chunk.size > rxBuffer.size) {
            rxLen = 0
        }
        System.arraycopy(chunk, 0, rxBuffer, rxLen, chunk.size)
        rxLen += chunk.size

        val bounds = VescProtocol.findPacketBounds(rxBuffer, 0, rxLen)
        if (bounds != null) {
            val payloadStart = bounds[0]
            val payloadLen = bounds[1]
            val packetEnd = bounds[2]

            val payload = rxBuffer.copyOfRange(payloadStart, payloadStart + payloadLen)

            val remaining = rxLen - packetEnd
            if (remaining > 0) {
                System.arraycopy(rxBuffer, packetEnd, rxBuffer, 0, remaining)
            }
            rxLen = remaining

            val data = VescProtocol.parseGetValues(payload)
            if (data != null) {
                handler.post { listener?.onDataReceived(data) }
            }
        }
    }

    private fun notifyStatus(status: String) {
        listener?.onStatusChanged(status)
    }
}
