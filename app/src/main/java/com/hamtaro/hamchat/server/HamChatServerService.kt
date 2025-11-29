package com.hamtaro.hamchat.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.ui.SplashActivity

/**
 * Servicio en primer plano que mantiene el servidor HTTP embebido corriendo
 * Permite que otros dispositivos se conecten a este dispositivo
 */
class HamChatServerService : Service() {

    companion object {
        private const val TAG = "HamChatServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hamchat_server_channel"
        const val DEFAULT_PORT = 8080

        fun start(context: Context, port: Int = DEFAULT_PORT) {
            val intent = Intent(context, HamChatServerService::class.java).apply {
                putExtra("port", port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HamChatServerService::class.java))
        }
    }

    private var server: EmbeddedServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): HamChatServerService = this@HamChatServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT
        
        startForeground(NOTIFICATION_ID, createNotification(port))
        startServer(port)
        
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startServer(port: Int) {
        if (server?.isAlive == true) {
            Log.d(TAG, "Server already running")
            return
        }

        try {
            server = EmbeddedServer(applicationContext, port).apply {
                start()
            }
            Log.i(TAG, "🚀 Server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        Log.i(TAG, "Server stopped")
    }

    fun isRunning(): Boolean = server?.isAlive == true

    fun getPort(): Int = DEFAULT_PORT

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HamChat Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servidor de mensajería HamChat activo"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(port: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SplashActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐹 HamChat Activo")
            .setContentText("Servidor corriendo en puerto $port")
            .setSmallIcon(R.drawable.hamtaro)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HamChat::ServerWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
