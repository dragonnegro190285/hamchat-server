package com.hamtaro.hamchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hamtaro.hamchat.MainActivity
import com.hamtaro.hamchat.R

/**
 * Servicio de Firebase Cloud Messaging para notificaciones push
 * Versión Lite - Optimizada para bajo consumo
 */
class HamChatMessagingService : FirebaseMessagingService() {
    
    companion object {
        private const val TAG = "HamChatFCM"
        private const val CHANNEL_ID = "hamchat_messages"
        private const val CHANNEL_NAME = "Mensajes Ham-Chat"
    }
    
    /**
     * Llamado cuando se recibe un nuevo token FCM
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Nuevo token FCM: $token")
        saveTokenLocally(token)
        sendTokenToServer(token)
    }
    
    /**
     * Llamado cuando se recibe un mensaje push
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d(TAG, "Mensaje recibido de: ${remoteMessage.from}")
        
        // Verificar si hay datos en el mensaje
        if (remoteMessage.data.isNotEmpty()) {
            handleDataMessage(remoteMessage.data)
        }
        
        // Verificar si hay notificación
        remoteMessage.notification?.let { notification ->
            showNotification(
                title = notification.title ?: "Ham-Chat",
                body = notification.body ?: "Nuevo mensaje",
                data = remoteMessage.data
            )
        }
    }
    
    /**
     * Manejar mensaje con datos personalizados
     */
    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"] ?: "message"
        val senderName = data["sender_name"] ?: "Usuario"
        val message = data["message"] ?: ""
        
        when (type) {
            "message" -> {
                showNotification(
                    title = "💬 $senderName",
                    body = message,
                    data = data
                )
            }
            else -> {
                showNotification(
                    title = "Ham-Chat",
                    body = message,
                    data = data
                )
            }
        }
    }
    
    /**
     * Mostrar notificación al usuario
     */
    private fun showNotification(title: String, body: String, data: Map<String, String>) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            data["chat_id"]?.let { putExtra("open_chat_id", it) }
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
    
    private fun saveTokenLocally(token: String) {
        val prefs = getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
    }
    
    private fun sendTokenToServer(token: String) {
        val prefs = getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        val userId = prefs.getString("auth_user_id", null)
        if (userId != null) {
            Log.d(TAG, "Token listo para enviar al servidor")
        }
    }
}
