package com.watchlinuxinput

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView

class InputActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btnVol: Button

    private var service: TcpInputService? = null
    private var bound = false
    private var volumeMode = false
    private var host: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TcpInputService.LocalBinder).service
            bound = true
            service?.onStatusChanged = { status ->
                runOnUiThread { statusText.text = status }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input)

        host = intent.getStringExtra(TcpInputService.EXTRA_HOST)
        if (host.isNullOrEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        statusText = findViewById(R.id.status_text)
        btnVol = findViewById(R.id.btn_vol)

        // D-Pad buttons
        findViewById<Button>(R.id.btn_up).setOnClickListener {
            service?.send(InputProtocol.TYPE_GESTURE, InputProtocol.GESTURE_UP)
        }
        findViewById<Button>(R.id.btn_down).setOnClickListener {
            service?.send(InputProtocol.TYPE_GESTURE, InputProtocol.GESTURE_DOWN)
        }
        findViewById<Button>(R.id.btn_left).apply {
            setOnClickListener {
                service?.send(InputProtocol.TYPE_GESTURE, InputProtocol.GESTURE_LEFT)
            }
            setOnLongClickListener {
                service?.send(InputProtocol.TYPE_KEY, InputProtocol.KEY_PREV)
                true
            }
        }
        findViewById<Button>(R.id.btn_right).apply {
            setOnClickListener {
                service?.send(InputProtocol.TYPE_GESTURE, InputProtocol.GESTURE_RIGHT)
            }
            setOnLongClickListener {
                service?.send(InputProtocol.TYPE_KEY, InputProtocol.KEY_NEXT)
                true
            }
        }
        findViewById<Button>(R.id.btn_ok).setOnClickListener {
            service?.send(InputProtocol.TYPE_GESTURE, InputProtocol.GESTURE_TAP)
        }

        // Bottom row
        findViewById<Button>(R.id.btn_esc).setOnClickListener {
            service?.send(InputProtocol.TYPE_KEY, InputProtocol.KEY_ESC)
        }
        findViewById<Button>(R.id.btn_tab).setOnClickListener {
            service?.send(InputProtocol.TYPE_KEY, InputProtocol.KEY_TAB)
        }
        findViewById<Button>(R.id.btn_play).setOnClickListener {
            service?.send(InputProtocol.TYPE_KEY, InputProtocol.KEY_PLAY_PAUSE)
        }
        btnVol.setOnClickListener {
            volumeMode = !volumeMode
            btnVol.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (volumeMode) 0xFFFF9800.toInt() else 0xFF444444.toInt()
            )
        }

        // Start foreground service
        val serviceIntent = Intent(this, TcpInputService::class.java).apply {
            putExtra(TcpInputService.EXTRA_HOST, host)
        }
        startForegroundService(serviceIntent)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, TcpInputService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        service?.onStatusChanged = null
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
    }

    // Rotary encoder input (Wear OS crown)
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (delta != 0f) {
                if (volumeMode) {
                    val key = if (delta > 0) InputProtocol.KEY_VOL_UP else InputProtocol.KEY_VOL_DOWN
                    service?.send(InputProtocol.TYPE_KEY, key)
                } else {
                    val dir = if (delta > 0) InputProtocol.ROTARY_CCW else InputProtocol.ROTARY_CW
                    service?.send(InputProtocol.TYPE_ROTARY, dir)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // Handle hardware buttons (back = ESC)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) {
                service?.send(InputProtocol.TYPE_KEY, InputProtocol.KEY_ESC)
                return true
            }
            // Long-press back = actually go back
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
