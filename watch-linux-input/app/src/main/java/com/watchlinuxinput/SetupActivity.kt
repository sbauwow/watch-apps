package com.watchlinuxinput

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText

class SetupActivity : Activity() {

    companion object {
        private const val PREFS = "watch_linux_input"
        private const val KEY_HOST = "host"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val ipField = findViewById<EditText>(R.id.ip_field)
        val connectBtn = findViewById<Button>(R.id.btn_connect)

        // Restore saved host
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedHost = prefs.getString(KEY_HOST, "") ?: ""
        ipField.setText(savedHost)

        connectBtn.setOnClickListener {
            val host = ipField.text.toString().trim()
            if (host.isNotEmpty()) {
                prefs.edit().putString(KEY_HOST, host).apply()
                val intent = Intent(this, InputActivity::class.java)
                intent.putExtra(TcpInputService.EXTRA_HOST, host)
                startActivity(intent)
                finish()
            }
        }
    }
}
