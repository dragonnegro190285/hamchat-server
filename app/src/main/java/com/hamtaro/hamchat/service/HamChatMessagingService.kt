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
        
        // Guardar token localmente
        saveTokenLocally(token)
        
        // Enviar token al servidor
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
            Log.d(TAG, "Datos del mensaje: ${remoteMessage.data}")
            handleDataMessage(remoteMessage.data)
        }
        
        // Verificar si hay notificación
        remoteMessage.notification?.let { notification ->
            Log.d(TAG, "Notificación: ${notification.body}")
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
        val senderId = data["sender_id"] ?: ""
        val senderName = data["sender_name"] ?: "Usuario"
        val message = data["message"] ?: ""
        val chatId = data["chat_id"] ?: ""
        
        when (type) {
            "message" -> {
                showNotification(
                    title = "💬 $senderName",
                    body = message,
                    data = data
                )
            }
            "call" -> {
                showNotification(
                    title = "📞 Llamada entrante",
                    body = "$senderName te está llamando",
                    data = data
                )
            }
            "status" -> {
                showNotification(
                    title = "📱 Nuevo estado",
                    body = "$senderName publicó un estado",
                    data = data
                )
            }
        }
    }
    
    /**
     * Mostrar notificación al usuario
     */
    private fun showNotification(title: String, body: String, data: Map<String, String>) {
        // Crear intent para abrir la app
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Pasar datos del chat si existen
            data["chat_id"]?.let { putExtra("open_chat_id", it) }
            data["sender_id"]?.let { putExtra("sender_id", it) }
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Sonido de notificación
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        // Construir notificación
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Crear canal de notificación (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de mensajes de Ham-Chat"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // Mostrar notificación con ID único
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
    
    /**
     * Guardar token FCM localmente
     */
    private fun saveTokenLocally(token: String) {
        val prefs = getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("fcm_token", token)
            .putLong("fcm_token_timestamp", System.currentTimeMillis())
            .apply()
        
        Log.d(TAG, "Token FCM guardado localmente")
    }
    
    /**
     * Enviar token al servidor Ham-Chat
     */
    private fun sendTokenToServer(token: String) {
        val prefs = getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        val userId = prefs.getString("auth_user_id", null)
        
        if (userId != null) {
            // Aquí se enviaría el token al servidor
            // Por ahora solo lo guardamos localmente
            Log.d(TAG, "Token listo para enviar al servidor para usuario: $userId")
            
            // TODO: Implementar llamada API para registrar token
            // api.registerFcmToken(userId, token)
        } else {
            Log.d(TAG, "Usuario no autenticado, token pendiente de envío")
        }
    }
}
