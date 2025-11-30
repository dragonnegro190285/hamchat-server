package com.hamtaro.hamchat.service

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Helper para manejar Firebase Cloud Messaging
 * Versión Lite - Simplificada
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
                Log.d(TAG, "Token FCM obtenido")
                saveToken(context, token)
                callback(token)
            }
    }
    
    private fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
    }
    
    fun getSavedToken(context: Context): String? {
        val prefs = context.getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        return prefs.getString("fcm_token", null)
    }
    
    fun subscribeToTopic(topic: String) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
    }
    
    fun unsubscribeFromTopic(topic: String) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
    }
}
