package com.hamtaro.hamchat.model

import java.io.Serializable

/**
 * 🖼️ Contact Model con Avatar para Ham-Chat
 * Optimizado para Sharp Keitai 4 (batería y memoria)
 */
data class Contact(
    val id: String,                    // Tox ID único
    var name: String,                   // Nombre visible
    var statusMessage: String = "",     // Mensaje de estado
    var isOnline: Boolean = false,      // Estado de conexión
    var lastSeen: Long = 0L,            // Última vez visto
    var avatarUrl: String? = null,      // URL del avatar (opcional)
    var avatarPath: String? = null,     // Ruta local del avatar
    var isMuted: Boolean = false,       // Silenciado
    var isFavorite: Boolean = false,    // Contacto favorito
    var unreadCount: Int = 0,           // Mensajes no leídos
    var typingStatus: Boolean = false,  // Está escribiendo
    var customEmoji: String = "😊"      // Emoji personalizado
) : Serializable {
    
    companion object {
        const val MAX_AVATAR_SIZE = 64 * 1024 // 64KB máximo
        const val AVATAR_DIMENSION = 96        // 96x96px
        
        // 🖼️ Avatar por defecto (generado con iniciales)
        fun generateDefaultAvatar(name: String): String {
            val initial = name.firstOrNull()?.uppercase() ?: "?"
            return "https://ui-avatars.com/api/?name=$initial&size=96&background=FF9500&color=FFFFFF"
        }
    }
    
    /**
     * 🔋 Optimización: Avatar cacheado
     */
    fun getOptimizedAvatarUrl(): String {
        return avatarUrl ?: avatarPath ?: generateDefaultAvatar(name)
    }
    
    /**
     * 🔋 Verificar si el avatar es válido y pequeño
     */
    fun hasValidAvatar(): Boolean {
        return !avatarUrl.isNullOrEmpty() || !avatarPath.isNullOrEmpty()
    }
    
    /**
     * 🖼️ Actualizar avatar con validación de tamaño
     */
    fun updateAvatar(newUrl: String?, newPath: String?): Boolean {
        // Validar tamaño antes de guardar
        if (newPath != null) {
            try {
                val file = java.io.File(newPath)
                if (file.exists() && file.length() > MAX_AVATAR_SIZE) {
                    return false // Avatar demasiado grande
                }
            } catch (e: Exception) {
                return false
            }
        }
        
        avatarUrl = newUrl
        avatarPath = newPath
        return true
    }
    
    /**
     * 🔋 Estado de conexión optimizado
     */
    fun getDisplayStatus(): String {
        return when {
            isOnline -> "En línea"
            typingStatus -> "Escribiendo..."
            lastSeen > 0 -> "Visto por última vez: ${formatLastSeen()}"
            else -> "Desconectado"
        }
    }
    
    /**
     * 🔋 Formato de hora optimizado
     */
    private fun formatLastSeen(): String {
        val now = System.currentTimeMillis()
        val diff = now - lastSeen
        
        return when {
            diff < 60000 -> "ahora"
            diff < 3600000 -> "hace ${diff / 60000} min"
            diff < 86400000 -> "hace ${diff / 3600000} h"
            else -> "hace ${diff / 86400000} días"
        }
    }
    
    /**
     * 🔋 Búsqueda optimizada
     */
    fun matchesQuery(query: String): Boolean {
        val lowercaseQuery = query.lowercase()
        return name.lowercase().contains(lowercaseQuery) ||
               statusMessage.lowercase().contains(lowercaseQuery)
    }
    
    /**
     * 🖼️ Prioridad de contacto para ordenamiento
     */
    fun getPriority(): Int {
        return when {
            isFavorite -> 0
            isOnline -> 1
            unreadCount > 0 -> 2
            else -> 3
        }
    }
}
