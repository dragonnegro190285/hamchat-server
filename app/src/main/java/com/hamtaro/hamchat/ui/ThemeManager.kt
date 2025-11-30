package com.hamtaro.hamchat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.palette.graphics.Palette
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Gestor de temas y personalización de la aplicación
 */
class ThemeManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hamchat_themes"
        private const val KEY_CURRENT_THEME = "current_theme"
        private const val KEY_CHAT_BACKGROUNDS = "chat_backgrounds"
        private const val KEY_CONTACT_PHOTOS = "contact_photos"
        private const val KEY_APP_COLORS = "app_colors"
        private const val KEY_CUSTOM_EMOJIS = "custom_emojis"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // ========== Colores del tema ==========
    
    data class ThemeColors(
        val primary: Int = 0xFFFF8C00.toInt(),      // Naranja Ham-Chat
        val primaryDark: Int = 0xFFE67E00.toInt(),
        val accent: Int = 0xFFFFAB40.toInt(),
        val background: Int = 0xFFFFF8E1.toInt(),
        val surface: Int = 0xFFFFFFFF.toInt(),
        val textPrimary: Int = 0xFF1A1A1A.toInt(),
        val textSecondary: Int = 0xFF666666.toInt(),
        val bubbleSent: Int = 0xFFDCF8C6.toInt(),   // Verde claro
        val bubbleReceived: Int = 0xFFFFFFFF.toInt()
    )
    
    /**
     * Obtener colores actuales del tema
     */
    fun getThemeColors(): ThemeColors {
        val json = prefs.getString(KEY_APP_COLORS, null)
        return if (json != null) {
            try {
                val obj = JSONObject(json)
                ThemeColors(
                    primary = obj.optInt("primary", 0xFFFF8C00.toInt()),
                    primaryDark = obj.optInt("primaryDark", 0xFFE67E00.toInt()),
                    accent = obj.optInt("accent", 0xFFFFAB40.toInt()),
                    background = obj.optInt("background", 0xFFFFF8E1.toInt()),
                    surface = obj.optInt("surface", 0xFFFFFFFF.toInt()),
                    textPrimary = obj.optInt("textPrimary", 0xFF1A1A1A.toInt()),
                    textSecondary = obj.optInt("textSecondary", 0xFF666666.toInt()),
                    bubbleSent = obj.optInt("bubbleSent", 0xFFDCF8C6.toInt()),
                    bubbleReceived = obj.optInt("bubbleReceived", 0xFFFFFFFF.toInt())
                )
            } catch (e: Exception) {
                ThemeColors()
            }
        } else {
            ThemeColors()
        }
    }
    
    /**
     * Guardar colores del tema
     */
    fun saveThemeColors(colors: ThemeColors) {
        val obj = JSONObject().apply {
            put("primary", colors.primary)
            put("primaryDark", colors.primaryDark)
            put("accent", colors.accent)
            put("background", colors.background)
            put("surface", colors.surface)
            put("textPrimary", colors.textPrimary)
            put("textSecondary", colors.textSecondary)
            put("bubbleSent", colors.bubbleSent)
            put("bubbleReceived", colors.bubbleReceived)
        }
        prefs.edit().putString(KEY_APP_COLORS, obj.toString()).apply()
    }
    
    /**
     * Extraer paleta de colores de una imagen
     */
    fun extractColorsFromImage(bitmap: Bitmap): ThemeColors {
        val palette = Palette.from(bitmap).generate()
        
        val vibrant = palette.vibrantSwatch
        val darkVibrant = palette.darkVibrantSwatch
        val lightVibrant = palette.lightVibrantSwatch
        val muted = palette.mutedSwatch
        val darkMuted = palette.darkMutedSwatch
        val lightMuted = palette.lightMutedSwatch
        
        return ThemeColors(
            primary = vibrant?.rgb ?: muted?.rgb ?: 0xFFFF8C00.toInt(),
            primaryDark = darkVibrant?.rgb ?: darkMuted?.rgb ?: 0xFFE67E00.toInt(),
            accent = lightVibrant?.rgb ?: lightMuted?.rgb ?: 0xFFFFAB40.toInt(),
            background = lightMuted?.rgb ?: 0xFFFFF8E1.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            textPrimary = darkMuted?.bodyTextColor ?: 0xFF1A1A1A.toInt(),
            textSecondary = muted?.bodyTextColor ?: 0xFF666666.toInt(),
            bubbleSent = adjustAlpha(vibrant?.rgb ?: 0xFFDCF8C6.toInt(), 0.3f),
            bubbleReceived = 0xFFFFFFFF.toInt()
        )
    }
    
    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(255, 
            Color.red(color) + ((255 - Color.red(color)) * (1 - factor)).toInt(),
            Color.green(color) + ((255 - Color.green(color)) * (1 - factor)).toInt(),
            Color.blue(color) + ((255 - Color.blue(color)) * (1 - factor)).toInt()
        )
    }
    
    // ========== Fondos de chat ==========
    
    /**
     * Guardar fondo de chat para un contacto
     */
    fun setChatBackground(contactId: String, imageBase64: String?) {
        val backgrounds = getChatBackgrounds().toMutableMap()
        if (imageBase64 != null) {
            backgrounds[contactId] = imageBase64
        } else {
            backgrounds.remove(contactId)
        }
        saveChatBackgrounds(backgrounds)
    }
    
    /**
     * Obtener fondo de chat para un contacto
     */
    fun getChatBackground(contactId: String): Bitmap? {
        val backgrounds = getChatBackgrounds()
        val base64 = backgrounds[contactId] ?: backgrounds["default"]
        return if (base64 != null) {
            try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Establecer fondo por defecto para todos los chats
     */
    fun setDefaultBackground(imageBase64: String?) {
        setChatBackground("default", imageBase64)
    }
    
    private fun getChatBackgrounds(): Map<String, String> {
        val json = prefs.getString(KEY_CHAT_BACKGROUNDS, "{}")
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun saveChatBackgrounds(backgrounds: Map<String, String>) {
        val obj = JSONObject()
        backgrounds.forEach { (key, value) ->
            obj.put(key, value)
        }
        prefs.edit().putString(KEY_CHAT_BACKGROUNDS, obj.toString()).apply()
    }
    
    // ========== Fotos de contactos ==========
    
    /**
     * Guardar foto de perfil de un contacto
     */
    fun setContactPhoto(contactId: String, imageBase64: String?) {
        val photos = getContactPhotos().toMutableMap()
        if (imageBase64 != null) {
            photos[contactId] = imageBase64
        } else {
            photos.remove(contactId)
        }
        saveContactPhotos(photos)
    }
    
    /**
     * Obtener foto de perfil de un contacto
     */
    fun getContactPhoto(contactId: String): Bitmap? {
        val photos = getContactPhotos()
        val base64 = photos[contactId]
        return if (base64 != null) {
            try {
                val bytes = Base64.decode(base64, Base64.NO_WRAP)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Obtener foto de perfil como Base64
     */
    fun getContactPhotoBase64(contactId: String): String? {
        return getContactPhotos()[contactId]
    }
    
    private fun getContactPhotos(): Map<String, String> {
        val json = prefs.getString(KEY_CONTACT_PHOTOS, "{}")
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun saveContactPhotos(photos: Map<String, String>) {
        val obj = JSONObject()
        photos.forEach { (key, value) ->
            obj.put(key, value)
        }
        prefs.edit().putString(KEY_CONTACT_PHOTOS, obj.toString()).apply()
    }
    
    // ========== Emojis personalizados ==========
    
    data class CustomEmoji(
        val id: String,
        val name: String,
        val imageBase64: String,
        val isAnimated: Boolean = false
    )
    
    /**
     * Agregar emoji personalizado
     */
    fun addCustomEmoji(emoji: CustomEmoji) {
        val emojis = getCustomEmojis().toMutableList()
        emojis.removeAll { it.id == emoji.id }
        emojis.add(emoji)
        saveCustomEmojis(emojis)
    }
    
    /**
     * Eliminar emoji personalizado
     */
    fun removeCustomEmoji(emojiId: String) {
        val emojis = getCustomEmojis().toMutableList()
        emojis.removeAll { it.id == emojiId }
        saveCustomEmojis(emojis)
    }
    
    /**
     * Obtener todos los emojis personalizados
     */
    fun getCustomEmojis(): List<CustomEmoji> {
        val json = prefs.getString(KEY_CUSTOM_EMOJIS, "[]")
        return try {
            val array = org.json.JSONArray(json)
            val list = mutableListOf<CustomEmoji>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(CustomEmoji(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    imageBase64 = obj.getString("imageBase64"),
                    isAnimated = obj.optBoolean("isAnimated", false)
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun saveCustomEmojis(emojis: List<CustomEmoji>) {
        val array = org.json.JSONArray()
        emojis.forEach { emoji ->
            val obj = JSONObject().apply {
                put("id", emoji.id)
                put("name", emoji.name)
                put("imageBase64", emoji.imageBase64)
                put("isAnimated", emoji.isAnimated)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CUSTOM_EMOJIS, array.toString()).apply()
    }
    
    // ========== Utilidades ==========
    
    /**
     * Convertir bitmap a Base64
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    /**
     * Cargar imagen desde URI
     */
    fun loadBitmapFromUri(uri: Uri, maxSize: Int = 512): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (original != null) {
                val scale = minOf(maxSize.toFloat() / original.width, maxSize.toFloat() / original.height, 1f)
                if (scale < 1f) {
                    val newWidth = (original.width * scale).toInt()
                    val newHeight = (original.height * scale).toInt()
                    Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
                } else {
                    original
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Resetear tema a valores por defecto
     */
    fun resetToDefaults() {
        prefs.edit()
            .remove(KEY_APP_COLORS)
            .remove(KEY_CHAT_BACKGROUNDS)
            .apply()
    }
}
