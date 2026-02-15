package com.watchlinuxinput

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TcpInputService : Service() {

    companion object {
        private const val TAG = "TcpInputService"
        private const val CHANNEL_ID = "watch_linux_input"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_BACKOFF_MS = 30_000L
        const val EXTRA_HOST = "host"
    }

    inner class LocalBinder : Binder() {
        val service: TcpInputService get() = this@TcpInputService
    }

    private val binder = LocalBinder()
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var running = false
    @Volatile private var currentOut: OutputStream? = null
    private val writeLock = Any()
    private var connectionThread: Thread? = null
    private var host: String? = null
    private var wifiLock: WifiManager.WifiLock? = null

    var onStatusChanged: ((String) -> Unit)? = null

    val isConnected: Boolean
        get() = currentOut != null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newHost = intent?.getStringExtra(EXTRA_HOST)
        if (newHost != null) host = newHost

        if (host.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (running) {
            // Already running — restart connection with new host
            running = false
            connectionThread?.interrupt()
            connectionThread?.join(2000)
        }

        createNotificationChannel()
        val notification = buildNotification("Connecting to $host...")
        startForeground(NOTIFICATION_ID, notification)
        acquireWifiLock()

        running = true
        queue.clear()
        connectionThread = Thread(::connectionLoop, "TcpInput").also { it.start() }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        connectionThread?.interrupt()
        releaseWifiLock()
        onStatusChanged?.invoke("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun send(type: Byte, value: Byte, action: Byte = InputProtocol.ACTION_PRESS) {
        queue.offer(InputProtocol.packet(type, value, action))
    }

    private fun connectionLoop() {
        var backoff = 1000L

        while (running) {
            var socket: Socket? = null
            try {
                postStatus("Connecting to $host...")
                socket = Socket().apply { tcpNoDelay = true }
                socket.connect(InetSocketAddress(host, InputProtocol.PORT), CONNECT_TIMEOUT_MS)

                backoff = 1000L
                postStatus("Connected to $host")
                updateNotification("Connected to $host")

                val out = socket.getOutputStream()
                currentOut = out
                var lastSend = System.currentTimeMillis()

                while (running && !socket.isClosed) {
                    val bytes = queue.poll(1, TimeUnit.SECONDS)
                    if (bytes != null) {
                        synchronized(writeLock) {
                            out.write(bytes)
                            out.flush()
                        }
                        lastSend = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastSend > HEARTBEAT_INTERVAL_MS) {
                        synchronized(writeLock) {
                            out.write(InputProtocol.heartbeat())
                            out.flush()
                        }
                        lastSend = System.currentTimeMillis()
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: IOException) {
                Log.w(TAG, "Connection error: ${e.message}")
            } finally {
                currentOut = null
                try { socket?.close() } catch (_: IOException) {}
            }

            if (!running) break

            postStatus("Reconnecting in ${backoff / 1000}s...")
            updateNotification("Disconnected — reconnecting...")
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun postStatus(status: String) {
        val cb = onStatusChanged ?: return
        android.os.Handler(mainLooper).post { cb(status) }
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG).apply {
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Linux Input Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "TCP input bridge to Linux" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Linux Input")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
