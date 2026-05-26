package com.loremote.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
  import com.loremote.app.protocol.DeliveryQueue
import com.loremote.app.protocol.Protocol
import com.loremote.app.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log

class BleService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "loremote_ble"
        const val NOTIF_ID = 1
        const val ACTION_PACKET = "com.loremote.PACKET_RECEIVED"
        const val EXTRA_BYTES = "bytes"
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    private val binder = LocalBinder()

    lateinit var bleManager: LoRemoteBleManager
    lateinit var scanner: BleScanner
    val deliveryQueue = DeliveryQueue(
        sendFn = { packet ->
            val bytes = com.loremote.app.protocol.Protocol.encode(packet)
            kotlinx.coroutines.GlobalScope.launch {
                bleManager.sendLoRemote(bytes)
            }
        },
        onFailed = { devId ->
            Log.w("BleService", "Delivery failed for $devId")
        },
        context = this
    )

    private var pingJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()

        bleManager = LoRemoteBleManager(this) { bytes ->
            val intent = Intent(ACTION_PACKET).apply {
                putExtra(EXTRA_BYTES, bytes)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        scanner = BleScanner(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Ожидание подключения..."))

        lifecycleScope.launch {
            bleManager.state.collect { state ->
                val text = when (state) {
                    is BleState.Ready        -> "● Подключено к ${getLastDeviceName()}"
                    is BleState.Connecting   -> "Подключение..."
                    is BleState.Handshake    -> "Инициализация..."
                    is BleState.Disconnected -> "○ Не подключено"
                    is BleState.Error        -> "Ошибка: ${state.message}"
                }
                updateNotification(text)

                if (state is BleState.Ready) {
                    startPingLoop()
                    lifecycleScope.launch {
                        delay(1000)
                        try {
                            val bytes = Protocol.encode(Protocol.requestAll())
                            bleManager.sendLoRemote(bytes)
                            Log.i("BleService", "Requested all device states")
                        } catch (e: Exception) {
                            Log.e("BleService", "requestAll failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pingJob?.cancel()
        bleManager.disconnect().enqueue()
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = lifecycleScope.launch {
            while (true) {
                delay(60_000)
                if (bleManager.state.value is BleState.Ready) {
                    try {
                        val ping = com.loremote.app.protocol.Protocol.ping()
                        val bytes = com.loremote.app.protocol.Protocol.encode(ping)
                        bleManager.sendLoRemote(bytes)
                    } catch (e: Exception) {
                        android.util.Log.e("BleService", "Ping failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun showAlarmNotification(deviceName: String, message: String) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Тревога: $deviceName")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun getLastDeviceName(): String {
        return getSharedPreferences("loremote", MODE_PRIVATE)
            .getString("last_device_name", "устройство") ?: "устройство"
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LoRemote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LoRemote BLE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Поддержание BLE соединения"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
