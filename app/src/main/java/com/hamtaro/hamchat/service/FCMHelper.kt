package com.hamtaro.hamchat.service

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Helper para manejar Firebase Cloud Messaging
 */
object FCMHelper {
    
    private const val TAG = "FCMHelper"
    
    /**
     * Obtener el token FCM actual
     */
    fun getToken(context: Context, callback: (String?) -> Unit) {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Error al obtener token FCM", task.exception)
                    callback(null)
                    return@addOnCompleteListener
                }
                
                val token = task.result
                Log.d(TAG, "Token FCM obtenido: $token")
                
                // Guardar token localmente
                saveToken(context, token)
                
                callback(token)
            }
    }
    
    /**
     * Guardar token en SharedPreferences
     */
    private fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("fcm_token", token)
            .putLong("fcm_token_timestamp", System.currentTimeMillis())
            .apply()
    }
    
    /**
     * Obtener token guardado localmente
     */
    fun getSavedToken(context: Context): String? {
        val prefs = context.getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        return prefs.getString("fcm_token", null)
    }
    
    /**
     * Suscribirse a un tema (para notificaciones grupales)
     */
    fun subscribeToTopic(topic: String, callback: ((Boolean) -> Unit)? = null) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Suscrito al tema: $topic")
                    callback?.invoke(true)
                } else {
                    Log.w(TAG, "Error al suscribirse al tema: $topic", task.exception)
                    callback?.invoke(false)
                }
            }
    }
    
    /**
     * Desuscribirse de un tema
     */
    fun unsubscribeFromTopic(topic: String, callback: ((Boolean) -> Unit)? = null) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Desuscrito del tema: $topic")
                    callback?.invoke(true)
                } else {
                    Log.w(TAG, "Error al desuscribirse del tema: $topic", task.exception)
                    callback?.invoke(false)
                }
            }
    }
    
    /**
     * Suscribirse a notificaciones generales de Ham-Chat
     */
    fun subscribeToGeneralNotifications() {
        subscribeToTopic("hamchat_general")
        subscribeToTopic("hamchat_updates")
    }
    
    /**
     * Suscribirse a notificaciones de un chat específico
     */
    fun subscribeToChatNotifications(chatId: String) {
        subscribeToTopic("chat_$chatId")
    }
    
    /**
     * Desactivar notificaciones para un chat
     */
    fun muteChatNotifications(chatId: String) {
        unsubscribeFromTopic("chat_$chatId")
    }
}
