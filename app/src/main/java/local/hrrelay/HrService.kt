package local.hrrelay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/**
 * Subscribes to the BLE Heart Rate Measurement (0x180D / 0x2A37) notifications and relays them as broadcast intents.
 *
 *  local.hrrelay.UPDATE : bpm(String), ts(String, epoch ms)  -- all extras are strings (MacroDroid stores only strings)
 *  local.hrrelay.STATUS : state = connected / disconnected / lost / recovered / no_hr_service / stopped
 */
@SuppressLint("MissingPermission")
class HrService : Service() {

    companion object {
        const val ACTION_UPDATE = "local.hrrelay.UPDATE"
        const val ACTION_STATUS = "local.hrrelay.STATUS"
        const val PREFS = "hrrelay"
        const val KEY_MAC = "mac"
        const val KEY_TIMEOUT = "timeout"
        private const val CHANNEL_ID = "hrrelay"
        private const val NOTIF_ID = 1
        private const val WATCHDOG_MS = 5_000L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val NOTIF_INTERVAL_MS = 5_000L

        private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var mac = ""
    private var timeoutSec = 60
    private var running = false
    private var lastData = 0L          // elapsedRealtime
    private var lostSent = false
    private var retryDelay = RETRY_MIN_MS
    private var lastNotif = 0L

    private val retryRunnable = Runnable { connect() }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (!lostSent && now - lastData > timeoutSec * 1000L) {
                lostSent = true
                sendStatus("lost")
                updateNotification("No heart rate data. Reconnecting...")
                connect() // rebuild the connection
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mac = prefs.getString(KEY_MAC, "")?.trim()?.uppercase() ?: ""
        timeoutSec = prefs.getInt(KEY_TIMEOUT, 60).coerceAtLeast(10)

        startForegroundCompat("Starting...")

        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            lastData = SystemClock.elapsedRealtime()
            lostSent = false
            retryDelay = RETRY_MIN_MS
            connect()
            handler.postDelayed(watchdog, WATCHDOG_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        closeGatt()
        sendStatus("stopped")
        super.onDestroy()
    }

    // ---- connection ----

    private fun connect() {
        if (!running) return
        handler.removeCallbacks(retryRunnable)
        closeGatt()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            scheduleRetry()
            return
        }
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            stopSelf()
            return
        }
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        try {
            g?.disconnect()
        } catch (_: Exception) {
        }
        try {
            g?.close()
        } catch (_: Exception) {
        }
    }

    private fun scheduleRetry() {
        if (!running) return
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) {
                    try { g.close() } catch (_: Exception) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    sendStatus("connected")
                    updateNotification("Connected. Discovering services...")
                    g.discoverServices()
                } else {
                    sendStatus("disconnected")
                    updateNotification("Disconnected. Reconnecting...")
                    closeGatt()
                    scheduleRetry()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                val cccd = ch?.getDescriptor(CCCD)
                if (ch == null || cccd == null) {
                    sendStatus("no_hr_service")
                    updateNotification("Heart Rate service not found")
                    closeGatt()
                    scheduleRetry()
                    return@post
                }
                g.setCharacteristicNotification(ch, true)
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g === gatt) {
                    retryDelay = RETRY_MIN_MS
                    updateNotification("Subscribed. Waiting for heart rate...")
                }
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handler.post { onHeartRate(g, value) }
        }

        // Android 12 and below
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val v = c.value ?: return
                handler.post { onHeartRate(g, v) }
            }
        }
    }

    // ---- heart rate ----

    private fun onHeartRate(g: BluetoothGatt, v: ByteArray) {
        if (g !== gatt || v.size < 2) return
        val flags = v[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 == 0) {
            v[1].toInt() and 0xFF
        } else {
            if (v.size < 3) return
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        }

        val now = SystemClock.elapsedRealtime()
        lastData = now
        if (lostSent) {
            lostSent = false
            sendStatus("recovered")
        }

        sendBroadcast(
            Intent(ACTION_UPDATE)
                .putExtra("bpm", bpm.toString())
                .putExtra("ts", System.currentTimeMillis().toString())
        )

        if (now - lastNotif >= NOTIF_INTERVAL_MS) {
            lastNotif = now
            updateNotification("♥ $bpm bpm")
        }
    }

    private fun sendStatus(state: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .putExtra("state", state)
                .putExtra("ts", System.currentTimeMillis().toString())
        )
    }

    // ---- notification ----

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HR Relay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HR Relay")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
