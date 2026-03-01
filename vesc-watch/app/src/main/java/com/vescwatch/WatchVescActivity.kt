package com.vescwatch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/**
 * VESC HUD for Galaxy Watch (Wear OS).
 *
 * Tap: reconnect
 * Long-press: forget trusted boards + rescan
 */
class WatchVescActivity : Activity(), BleManager.Listener {

    private lateinit var ble: BleManager

    private lateinit var dutyCircle: DutyCircleView
    private lateinit var speedValue: TextView
    private lateinit var battValue: TextView
    private lateinit var voltageValue: TextView
    private lateinit var tempMosValue: TextView
    private lateinit var tempMotorValue: TextView
    private lateinit var tempBattValue: TextView
    private lateinit var statusText: TextView

    companion object {
        private const val PERMISSION_REQUEST = 1

        private val COLOR_OK     = Color.WHITE
        private val COLOR_YELLOW = Color.parseColor("#FFEB3B")
        private val COLOR_RED    = Color.parseColor("#FF1744")
        private val COLOR_DIM    = Color.parseColor("#BDBDBD")
        private val COLOR_CYAN   = Color.parseColor("#00E5FF")

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_watch)

        dutyCircle = findViewById(R.id.duty_circle)
        speedValue = findViewById(R.id.speed_value)
        battValue = findViewById(R.id.batt_value)
        voltageValue = findViewById(R.id.voltage_value)
        tempMosValue = findViewById(R.id.temp_mos_value)
        tempMotorValue = findViewById(R.id.temp_motor_value)
        tempBattValue = findViewById(R.id.temp_batt_value)
        statusText = findViewById(R.id.status_text)

        // Board config
        VescProtocol.motorPoles     = 30
        VescProtocol.wheelDiameterM = 0.280
        VescProtocol.gearRatio      = 1.0
        VescProtocol.cellCountS     = 15
        VescProtocol.cellFull       = 4.2
        VescProtocol.cellEmpty      = 3.0

        ble = BleManager(this)
        ble.listener = this

        // Tap to reconnect
        dutyCircle.setOnClickListener {
            ble.stop()
            ble.start()
        }

        // Long-press to forget trusted boards
        dutyCircle.isLongClickable = true
        dutyCircle.setOnLongClickListener {
            ble.clearTrustedDevices()
            statusText.text = "BOARDS FORGOTTEN"
            ble.stop()
            ble.start()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) {
            ble.start()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSION_REQUEST)
        }
    }

    override fun onPause() {
        super.onPause()
        ble.stop()
    }

    // ---- Permissions ----

    private fun hasPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && hasPermissions()) {
            ble.start()
        } else {
            statusText.text = "NEED PERMISSIONS"
        }
    }

    // ---- BLE callbacks ----

    override fun onStatusChanged(status: String) {
        statusText.text = status
    }

    override fun onDataReceived(data: VescData) {
        val duty = data.dutyPct()
        dutyCircle.setDuty(duty.toFloat(), dutyColor(duty))

        speedValue.text = String.format("%.0f", data.speedMph)

        battValue.text = String.format("%.0f%%", data.batteryPct)
        battValue.setTextColor(battColor(data.batteryPct))

        voltageValue.text = String.format("%.1fV", data.voltage)

        tempMosValue.text = String.format("C:%.0f°", cToF(data.tempMos))
        tempMosValue.setTextColor(tempColor(data.tempMos))

        tempMotorValue.text = String.format("M:%.0f°", cToF(data.tempMotor))
        tempMotorValue.setTextColor(tempColor(data.tempMotor))

        if (data.tempBatt >= 0) {
            tempBattValue.text = String.format("B:%.0f°", cToF(data.tempBatt))
            tempBattValue.setTextColor(tempColor(data.tempBatt))
        } else {
            tempBattValue.text = "B:--°"
            tempBattValue.setTextColor(COLOR_DIM)
        }

        statusText.text = "CONNECTED"
    }

    override fun onDevicesFound(devices: List<BleManager.FoundDevice>) {
        showPicker(devices)
    }

    // ---- Device picker (AlertDialog) ----

    private fun showPicker(devices: List<BleManager.FoundDevice>) {
        val names = devices.map { "${it.name} (${it.mac.takeLast(5)})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select board")
            .setItems(names) { _, which ->
                ble.connectToDevice(devices[which])
            }
            .setCancelable(false)
            .show()
    }

    // ---- Color helpers ----

    private fun dutyColor(pct: Double): Int = when {
        pct > 85 -> COLOR_RED
        pct > 70 -> COLOR_YELLOW
        else -> COLOR_CYAN
    }

    private fun battColor(pct: Double): Int = when {
        pct < 15 -> COLOR_RED
        pct < 30 -> COLOR_YELLOW
        else -> COLOR_OK
    }

    private fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32

    private fun tempColor(celsius: Double): Int = when {
        celsius > 80 -> COLOR_RED
        celsius > 60 -> COLOR_YELLOW
        else -> COLOR_DIM
    }
}
