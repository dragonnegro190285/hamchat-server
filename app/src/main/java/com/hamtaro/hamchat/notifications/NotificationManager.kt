package com.hamtaro.hamchat.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.security.SecureLogger
import com.hamtaro.hamchat.security.SecurePreferences
import com.hamtaro.hamchat.ui.NewRemoteChatActivity

/**
 * 🔔 Notification Manager para Ham-Chat
 * Maneja notificaciones personalizadas y tonos
 */
class HamChatNotificationManager(private val context: Context) {
    
    private val notificationManager = NotificationManagerCompat.from(context)
    private val securePrefs = SecurePreferences(context)
    
    companion object {
        private const val CHANNEL_MESSAGES = "hamtaro_messages"
        private const val CHANNEL_CALLS = "hamtaro_calls"
        private const val CHANNEL_MEDIA = "hamtaro_media"
        
        // Tonos por defecto
        private const val DEFAULT_MESSAGE_TONE = "hamtaro_message"
        private const val DEFAULT_CALL_TONE = "hamtaro_call"
        private const val DEFAULT_MEDIA_TONE = "hamtaro_media"
        
        // Claves para preferencias
        private const val PREF_MESSAGE_TONE = "notification_message_tone"
        private const val PREF_CALL_TONE = "notification_call_tone"
        private const val PREF_MEDIA_TONE = "notification_media_tone"
        private const val PREF_VIBRATION = "notification_vibration"
        private const val PREF_LED = "notification_led"
    }

    /**
     * 📥 Intent para abrir actividad de nuevo chat remoto
     */
    private fun createNewChatPendingIntent(
        remoteUserId: Int,
        username: String,
        phoneE164: String,
        lastMessage: String
    ): PendingIntent {
        val intent = Intent(context, NewRemoteChatActivity::class.java).apply {
            putExtra("remote_user_id", remoteUserId)
            putExtra("remote_username", username)
            putExtra("remote_phone_e164", phoneE164)
            putExtra("last_message", lastMessage)
        }
        return PendingIntent.getActivity(
            context,
            ("newchat_$remoteUserId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    init {
        createNotificationChannels()
    }
    
    /**
     * 🔔 Crear canales de notificación
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal de mensajes
            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Mensajes Hamtaro",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de mensajes nuevos"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                setSound(getMessageTone(), getAudioAttributes())
            }
            
            // Canal de llamadas
            val callChannel = NotificationChannel(
                CHANNEL_CALLS,
                "Llamadas Hamtaro",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de llamadas entrantes"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                setSound(getCallTone(), getAudioAttributes())
            }
            
            // Canal de multimedia
            val mediaChannel = NotificationChannel(
                CHANNEL_MEDIA,
                "Multimedia Hamtaro",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificaciones de archivos multimedia"
                enableLights(true)
                enableVibration(false)
                setShowBadge(true)
                setSound(getMediaTone(), getAudioAttributes())
            }
            
            notificationManager.createNotificationChannels(listOf(
                messageChannel, callChannel, mediaChannel
            ))
        }
    }
    
    /**
     * 💬 Mostrar notificación de mensaje
     */
    fun showMessageNotification(
        contactName: String,
        message: String,
        contactId: String
    ) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("Mensaje de $contactName")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setSound(getMessageTone())
                .setVibrate(getVibrationPattern())
                .setLights(0xFFFF9500.toInt(), 500, 500)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addAction(
                    R.drawable.ic_add,
                    "Responder",
                    createReplyPendingIntent(contactId)
                )
                .build()
            
            notificationManager.notify(contactName.hashCode(), notification)
            SecureLogger.sensitive("Message notification shown", contactName)
            
        } catch (e: Exception) {
            SecureLogger.e("Error showing message notification", e)
        }
    }

    /**
     * 💬 Notificación especial de nuevo chat (contacto remoto aún no agregado)
     */
    fun showNewChatNotification(
        contactName: String,
        message: String,
        contactId: String,
        remoteUserId: Int,
        phoneE164: String
    ) {
        try {
            val intent = createNewChatPendingIntent(remoteUserId, contactName, phoneE164, message)
            val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("Nuevo chat de $contactName")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setSound(getMessageTone())
                .setVibrate(getVibrationPattern())
                .setLights(0xFFFF9500.toInt(), 500, 500)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(intent)
                .build()

            notificationManager.notify(contactId.hashCode(), notification)
            SecureLogger.sensitive("New chat notification shown", contactName)

        } catch (e: Exception) {
            SecureLogger.e("Error showing new chat notification", e)
        }
    }
    
    /**
     * 📞 Mostrar notificación de llamada
     */
    fun showCallNotification(
        contactName: String,
        isIncoming: Boolean,
        contactId: String
    ) {
        try {
            val title = if (isIncoming) "Llamada entrante de $contactName" 
                       else "Llamando a $contactName"
            
            val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle(title)
                .setContentText("Toca para responder")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(false)
                .setSound(getCallTone())
                .setVibrate(getCallVibrationPattern())
                .setLights(0xFF00FF00.toInt(), 200, 200)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .addAction(
                    R.drawable.ic_add,
                    "Aceptar",
                    createAcceptCallPendingIntent(contactId)
                )
                .addAction(
                    R.drawable.ic_settings,
                    "Rechazar",
                    createDeclineCallPendingIntent(contactId)
                )
                .setOngoing(isIncoming)
                .build()
            
            notificationManager.notify("call_$contactId".hashCode(), notification)
            SecureLogger.sensitive("Call notification shown", contactName)
            
        } catch (e: Exception) {
            SecureLogger.e("Error showing call notification", e)
        }
    }
    
    /**
     * 📸 Mostrar notificación de multimedia
     */
    fun showMediaNotification(
        contactName: String,
        mediaType: String,
        fileName: String,
        contactId: String
    ) {
        try {
            val notification = NotificationCompat.Builder(context, CHANNEL_MEDIA)
                .setSmallIcon(R.drawable.ic_chat)
                .setContentTitle("$mediaType de $contactName")
                .setContentText(fileName)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setSound(getMediaTone())
                .setLights(0xFF0099FF.toInt(), 300, 300)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addAction(
                    R.drawable.ic_add,
                    "Descargar",
                    createDownloadPendingIntent(contactId, fileName)
                )
                .build()
            
            notificationManager.notify("media_$contactId".hashCode(), notification)
            SecureLogger.sensitive("Media notification shown", "$contactName - $mediaType")
            
        } catch (e: Exception) {
            SecureLogger.e("Error showing media notification", e)
        }
    }
    
    /**
     * 🎵 Establecer tono de mensaje
     */
    fun setMessageTone(toneUri: Uri?): Boolean {
        return try {
            val toneString = toneUri?.toString() ?: DEFAULT_MESSAGE_TONE
            securePrefs.setSecretUnlocked(PREF_MESSAGE_TONE, true)
            // Guardar URI en preferencias seguras
            true
        } catch (e: Exception) {
            SecureLogger.e("Error setting message tone", e)
            false
        }
    }
    
    /**
     * 📞 Establecer tono de llamada
     */
    fun setCallTone(toneUri: Uri?): Boolean {
        return try {
            val toneString = toneUri?.toString() ?: DEFAULT_CALL_TONE
            securePrefs.setSecretUnlocked(PREF_CALL_TONE, true)
            // Guardar URI en preferencias seguras
            true
        } catch (e: Exception) {
            SecureLogger.e("Error setting call tone", e)
            false
        }
    }
    
    /**
     * 🎶 Establecer tono de multimedia
     */
    fun setMediaTone(toneUri: Uri?): Boolean {
        return try {
            val toneString = toneUri?.toString() ?: DEFAULT_MEDIA_TONE
            securePrefs.setSecretUnlocked(PREF_MEDIA_TONE, true)
            // Guardar URI en preferencias seguras
            true
        } catch (e: Exception) {
            SecureLogger.e("Error setting media tone", e)
            false
        }
    }
    
    /**
     * 🔔 Obtener tono de mensaje
     */
    private fun getMessageTone(): Uri {
        return try {
            // Intentar obtener tono personalizado
            if (securePrefs.isSecretUnlocked(PREF_MESSAGE_TONE)) {
                // Obtener URI guardado
                getDefaultHamtaroTone()
            } else {
                getDefaultHamtaroTone()
            }
        } catch (e: Exception) {
            getDefaultHamtaroTone()
        }
    }
    
    /**
     * 📞 Obtener tono de llamada
     */
    private fun getCallTone(): Uri {
        return try {
            if (securePrefs.isSecretUnlocked(PREF_CALL_TONE)) {
                getDefaultHamtaroCallTone()
            } else {
                getDefaultHamtaroCallTone()
            }
        } catch (e: Exception) {
            getDefaultHamtaroCallTone()
        }
    }
    
    /**
     * 🎶 Obtener tono de multimedia
     */
    private fun getMediaTone(): Uri {
        return try {
            if (securePrefs.isSecretUnlocked(PREF_MEDIA_TONE)) {
                getDefaultHamtaroMediaTone()
            } else {
                getDefaultHamtaroMediaTone()
            }
        } catch (e: Exception) {
            getDefaultHamtaroMediaTone()
        }
    }
    
    /**
     * 🐹 Tonos por defecto de Hamtaro
     */
    private fun getDefaultHamtaroTone(): Uri {
        // Tonos personalizados de Hamtaro
        return Uri.parse("android.resource://${context.packageName}/raw/hamtaro_message")
    }
    
    private fun getDefaultHamtaroCallTone(): Uri {
        return Uri.parse("android.resource://${context.packageName}/raw/hamtaro_call")
    }
    
    private fun getDefaultHamtaroMediaTone(): Uri {
        return Uri.parse("android.resource://${context.packageName}/raw/hamtaro_media")
    }
    
    /**
     * 🔊 Atributos de audio
     */
    private fun getAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
    }
    
    /**
     * 📳 Patrón de vibración
     */
    private fun getVibrationPattern(): LongArray {
        return longArrayOf(0, 300, 200, 300) // Patrón Hamtaro
    }
    
    /**
     * 📞 Patrón de vibración para llamadas
     */
    private fun getCallVibrationPattern(): LongArray {
        return longArrayOf(0, 1000, 500, 1000) // Vibración de llamada
    }
    
    /**
     * 📝 Intent para responder mensaje
     */
    private fun createReplyPendingIntent(contactId: String): android.app.PendingIntent {
        // Implementar intent para responder
        return android.app.PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            android.content.Intent(context, com.hamtaro.hamchat.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * 📞 Intent para aceptar llamada
     */
    private fun createAcceptCallPendingIntent(contactId: String): android.app.PendingIntent {
        // Implementar intent para aceptar llamada
        return android.app.PendingIntent.getActivity(
            context,
            "accept_$contactId".hashCode(),
            android.content.Intent(context, com.hamtaro.hamchat.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * 📵 Intent para rechazar llamada
     */
    private fun createDeclineCallPendingIntent(contactId: String): android.app.PendingIntent {
        // Implementar intent para rechazar llamada
        return android.app.PendingIntent.getActivity(
            context,
            "decline_$contactId".hashCode(),
            android.content.Intent(context, com.hamtaro.hamchat.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * 📥 Intent para descargar multimedia
     */
    private fun createDownloadPendingIntent(contactId: String, fileName: String): android.app.PendingIntent {
        // Implementar intent para descargar
        return android.app.PendingIntent.getActivity(
            context,
            "download_$contactId".hashCode(),
            android.content.Intent(context, com.hamtaro.hamchat.MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * 🧹 Limpiar notificaciones
     */
    fun clearNotifications(contactId: String? = null) {
        try {
            if (contactId != null) {
                // Limpiar notificaciones de contacto específico
                notificationManager.cancel(contactId.hashCode())
                notificationManager.cancel("call_$contactId".hashCode())
                notificationManager.cancel("media_$contactId".hashCode())
            } else {
                // Limpiar todas las notificaciones
                notificationManager.cancelAll()
            }
            SecureLogger.i("Notifications cleared")
        } catch (e: Exception) {
            SecureLogger.e("Error clearing notifications", e)
        }
    }
    
    /**
     * 📊 Obtener configuración de notificaciones
     */
    fun getNotificationSettings(): Map<String, Any> {
        return try {
            mapOf(
                "message_tone_enabled" to securePrefs.isSecretUnlocked(PREF_MESSAGE_TONE),
                "call_tone_enabled" to securePrefs.isSecretUnlocked(PREF_CALL_TONE),
                "media_tone_enabled" to securePrefs.isSecretUnlocked(PREF_MEDIA_TONE),
                "vibration_enabled" to securePrefs.isSecretUnlocked(PREF_VIBRATION),
                "led_enabled" to securePrefs.isSecretUnlocked(PREF_LED),
                "channels_created" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            )
        } catch (e: Exception) {
            SecureLogger.e("Error getting notification settings", e)
            emptyMap()
        }
    }
    
    /**
     * 🔔 Verificar permisos de notificación
     */
    fun areNotificationsEnabled(): Boolean {
        return notificationManager.areNotificationsEnabled()
    }
    
    /**
     * 📱 Solicitar permisos de notificación
     */
    fun requestNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Para Android 13+, solicitar permiso en runtime
            true // Implementar en Activity
        } else {
            true
        }
    }
}
