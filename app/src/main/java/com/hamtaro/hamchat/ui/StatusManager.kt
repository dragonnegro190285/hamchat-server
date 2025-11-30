package com.hamtaro.hamchat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Gestor de estados (stories) de usuarios
 */
class StatusManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hamchat_status"
        private const val KEY_MY_STATUSES = "my_statuses"
        private const val KEY_VIEWED_STATUSES = "viewed_statuses"
        private const val KEY_CONTACTS_STATUSES = "contacts_statuses"
        private const val STATUS_DURATION_HOURS = 24
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Modelo de estado
     */
    data class Status(
        val id: String = java.util.UUID.randomUUID().toString(),
        val userId: String,
        val username: String,
        val type: StatusType,
        val content: String,           // Texto o Base64 de imagen
        val caption: String? = null,   // Texto sobre imagen
        val backgroundColor: Int = 0xFF1A1A2E.toInt(),
        val textColor: Int = 0xFFFFFFFF.toInt(),
        val createdAt: Long = System.currentTimeMillis(),
        val expiresAt: Long = System.currentTimeMillis() + (STATUS_DURATION_HOURS * 60 * 60 * 1000),
        val viewedBy: List<String> = emptyList()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
        
        fun getTimeAgo(): String {
            val diff = System.currentTimeMillis() - createdAt
            val minutes = diff / (1000 * 60)
            val hours = minutes / 60
            
            return when {
                minutes < 1 -> "Ahora"
                minutes < 60 -> "Hace $minutes min"
                hours < 24 -> "Hace $hours h"
                else -> "Hace más de 24h"
            }
        }
    }
    
    enum class StatusType {
        TEXT,       // Solo texto con fondo de color
        IMAGE,      // Imagen (con o sin caption)
        VIDEO       // Video corto (futuro)
    }
    
    // ========== Mis estados ==========
    
    /**
     * Crear nuevo estado de texto
     */
    fun createTextStatus(text: String, backgroundColor: Int, textColor: Int): Status {
        val prefs = context.getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        val username = prefs.getString("auth_username", "Usuario") ?: "Usuario"
        val userId = prefs.getString("auth_user_id", "me") ?: "me"
        
        val status = Status(
            userId = userId,
            username = username,
            type = StatusType.TEXT,
            content = text,
            backgroundColor = backgroundColor,
            textColor = textColor
        )
        
        addMyStatus(status)
        return status
    }
    
    /**
     * Crear nuevo estado con imagen
     */
    fun createImageStatus(imageBase64: String, caption: String? = null): Status {
        val prefs = context.getSharedPreferences("hamchat_settings", Context.MODE_PRIVATE)
        val username = prefs.getString("auth_username", "Usuario") ?: "Usuario"
        val userId = prefs.getString("auth_user_id", "me") ?: "me"
        
        val status = Status(
            userId = userId,
            username = username,
            type = StatusType.IMAGE,
            content = imageBase64,
            caption = caption
        )
        
        addMyStatus(status)
        return status
    }
    
    /**
     * Obtener mis estados activos
     */
    fun getMyStatuses(): List<Status> {
        return loadStatuses(KEY_MY_STATUSES).filter { !it.isExpired() }
    }
    
    /**
     * Eliminar un estado mío
     */
    fun deleteMyStatus(statusId: String) {
        val statuses = loadStatuses(KEY_MY_STATUSES).toMutableList()
        statuses.removeAll { it.id == statusId }
        saveStatuses(KEY_MY_STATUSES, statuses)
    }
    
    private fun addMyStatus(status: Status) {
        val statuses = loadStatuses(KEY_MY_STATUSES).toMutableList()
        statuses.add(0, status) // Agregar al inicio
        // Mantener máximo 30 estados
        if (statuses.size > 30) {
            statuses.subList(30, statuses.size).clear()
        }
        saveStatuses(KEY_MY_STATUSES, statuses)
    }
    
    // ========== Estados de contactos ==========
    
    /**
     * Agregar estado de un contacto (recibido del servidor)
     */
    fun addContactStatus(status: Status) {
        val statuses = loadStatuses(KEY_CONTACTS_STATUSES).toMutableList()
        statuses.removeAll { it.id == status.id }
        statuses.add(0, status)
        saveStatuses(KEY_CONTACTS_STATUSES, statuses)
    }
    
    /**
     * Obtener estados de contactos agrupados por usuario
     */
    fun getContactsStatuses(): Map<String, List<Status>> {
        return loadStatuses(KEY_CONTACTS_STATUSES)
            .filter { !it.isExpired() }
            .groupBy { it.userId }
    }
    
    /**
     * Obtener estados de un contacto específico
     */
    fun getStatusesForContact(userId: String): List<Status> {
        return loadStatuses(KEY_CONTACTS_STATUSES)
            .filter { it.userId == userId && !it.isExpired() }
    }
    
    // ========== Estados vistos ==========
    
    /**
     * Marcar estado como visto
     */
    fun markStatusAsViewed(statusId: String) {
        val viewed = getViewedStatuses().toMutableSet()
        viewed.add(statusId)
        prefs.edit().putStringSet(KEY_VIEWED_STATUSES, viewed).apply()
    }
    
    /**
     * Verificar si un estado fue visto
     */
    fun isStatusViewed(statusId: String): Boolean {
        return getViewedStatuses().contains(statusId)
    }
    
    private fun getViewedStatuses(): Set<String> {
        return prefs.getStringSet(KEY_VIEWED_STATUSES, emptySet()) ?: emptySet()
    }
    
    // ========== Persistencia ==========
    
    private fun loadStatuses(key: String): List<Status> {
        val json = prefs.getString(key, "[]")
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<Status>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(Status(
                    id = obj.getString("id"),
                    userId = obj.getString("userId"),
                    username = obj.getString("username"),
                    type = StatusType.valueOf(obj.optString("type", "TEXT")),
                    content = obj.getString("content"),
                    caption = obj.optString("caption", null),
                    backgroundColor = obj.optInt("backgroundColor", 0xFF1A1A2E.toInt()),
                    textColor = obj.optInt("textColor", 0xFFFFFFFF.toInt()),
                    createdAt = obj.getLong("createdAt"),
                    expiresAt = obj.getLong("expiresAt"),
                    viewedBy = emptyList() // Se maneja por separado
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun saveStatuses(key: String, statuses: List<Status>) {
        val array = JSONArray()
        statuses.forEach { status ->
            val obj = JSONObject().apply {
                put("id", status.id)
                put("userId", status.userId)
                put("username", status.username)
                put("type", status.type.name)
                put("content", status.content)
                put("caption", status.caption)
                put("backgroundColor", status.backgroundColor)
                put("textColor", status.textColor)
                put("createdAt", status.createdAt)
                put("expiresAt", status.expiresAt)
            }
            array.put(obj)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
    
    /**
     * Limpiar estados expirados
     */
    fun cleanExpiredStatuses() {
        val myStatuses = loadStatuses(KEY_MY_STATUSES).filter { !it.isExpired() }
        saveStatuses(KEY_MY_STATUSES, myStatuses)
        
        val contactStatuses = loadStatuses(KEY_CONTACTS_STATUSES).filter { !it.isExpired() }
        saveStatuses(KEY_CONTACTS_STATUSES, contactStatuses)
    }
}
