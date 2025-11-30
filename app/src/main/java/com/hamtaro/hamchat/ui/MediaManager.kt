package com.hamtaro.hamchat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestor de archivos multimedia y documentos
 */
class MediaManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hamchat_media"
        private const val KEY_STICKER_PACKS = "sticker_packs"
        private const val KEY_RECENT_STICKERS = "recent_stickers"
        private const val KEY_RECENT_GIFS = "recent_gifs"
        private const val KEY_LINK_PREVIEWS = "link_previews"
        
        // Tipos de documentos soportados
        val SUPPORTED_DOCUMENTS = listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "application/zip",
            "application/x-rar-compressed"
        )
        
        // Extensiones de documentos
        val DOCUMENT_EXTENSIONS = mapOf(
            "pdf" to "📄",
            "doc" to "📝",
            "docx" to "📝",
            "xls" to "📊",
            "xlsx" to "📊",
            "ppt" to "📽️",
            "pptx" to "📽️",
            "txt" to "📃",
            "zip" to "📦",
            "rar" to "📦"
        )
        
        // Stickers tipográficos (compartidos con Lite)
        val TEXT_STICKERS = listOf(
            // Caritas tipográficas - Felices
            "n.n", "^_^", "^-^", ":)", ":D", "=)", "=D", "c:",
            // Caritas tipográficas - Tristes/Cansadas
            "x.x", "X.X", "x_x", "-.-", "T.T", "T_T", ";-;", ":'(",
            // Caritas tipográficas - Sorpresa/Enojo
            ">.<", ">_<", "O.O", "o.o", "O_O", "D:", ":O", ":/",
            // Caritas tipográficas - Amor/Cariño
            "<3", "♥", "uwu", "UwU", ":3", "=3", "^w^", "owo",
            // Caritas tipográficas - Otras
            ":P", ":p", "xD", "XD", ";)", ";D", "B)", ":B",
            // Kaomojis
            "(╯°□°)╯", "(ノಠ益ಠ)ノ", "¯\\_(ツ)_/¯", "(づ｡◕‿‿◕｡)づ",
            "ಠ_ಠ", "(◕‿◕)", "(*^_^*)", "(≧◡≦)"
        )
        
        // Stickers de emojis (solo Pro)
        val EMOJI_STICKERS = listOf(
            // Hamtaro
            "🐹", "🐹💕", "🐹✨", "🐹🎉", "🐹😊",
            // Emociones
            "😀", "😍", "🥺", "😭", "😡", "🤔", "😴", "🤩",
            // Gestos
            "👍", "👎", "👏", "🙏", "💪", "🤝", "✌️", "🤟",
            // Corazones
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💕",
            // Celebración
            "🎉", "🎊", "🎁", "🎂", "🍰", "🥳", "🎈", "🎆",
            // Animales
            "🐱", "🐶", "🐰", "🦊", "🐻", "🐼", "🐨", "🦁"
        )
        
        // Todos los stickers predefinidos (Pro)
        val DEFAULT_STICKERS = TEXT_STICKERS + EMOJI_STICKERS
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // ========== Documentos ==========
    
    data class Document(
        val id: String = java.util.UUID.randomUUID().toString(),
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val base64Data: String,
        val thumbnail: String? = null
    ) {
        fun getIcon(): String {
            val ext = fileName.substringAfterLast(".", "").lowercase()
            return DOCUMENT_EXTENSIONS[ext] ?: "📎"
        }
        
        fun getFormattedSize(): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            }
        }
    }
    
    /**
     * Procesar documento desde URI
     */
    fun processDocument(uri: Uri): Document? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = getFileName(uri) ?: "documento"
            
            // Limitar tamaño a 25MB
            if (bytes.size > 25 * 1024 * 1024) {
                return null
            }
            
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            
            Document(
                fileName = fileName,
                mimeType = mimeType,
                size = bytes.size.toLong(),
                base64Data = base64
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
    
    // ========== Stickers ==========
    
    data class StickerPack(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val author: String = "Ham-Chat",
        val stickers: List<Sticker>,
        val isDefault: Boolean = false
    )
    
    data class Sticker(
        val id: String = java.util.UUID.randomUUID().toString(),
        val emoji: String? = null,          // Si es emoji
        val imageBase64: String? = null,    // Si es imagen
        val isAnimated: Boolean = false
    )
    
    /**
     * Obtener paquetes de stickers
     */
    fun getStickerPacks(): List<StickerPack> {
        val packs = mutableListOf<StickerPack>()
        
        // Pack por defecto
        packs.add(StickerPack(
            id = "default",
            name = "Ham-Chat Stickers",
            stickers = DEFAULT_STICKERS.map { Sticker(emoji = it) },
            isDefault = true
        ))
        
        // Packs personalizados
        val json = prefs.getString(KEY_STICKER_PACKS, "[]")
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                val stickersArray = p.getJSONArray("stickers")
                val stickers = mutableListOf<Sticker>()
                for (j in 0 until stickersArray.length()) {
                    val s = stickersArray.getJSONObject(j)
                    stickers.add(Sticker(
                        id = s.getString("id"),
                        emoji = s.optString("emoji", null),
                        imageBase64 = s.optString("imageBase64", null),
                        isAnimated = s.optBoolean("isAnimated", false)
                    ))
                }
                packs.add(StickerPack(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    author = p.optString("author", "Usuario"),
                    stickers = stickers
                ))
            }
        } catch (e: Exception) {}
        
        return packs
    }
    
    /**
     * Crear paquete de stickers personalizado
     */
    fun createStickerPack(name: String, stickers: List<Sticker>): StickerPack {
        val pack = StickerPack(name = name, stickers = stickers)
        
        val json = prefs.getString(KEY_STICKER_PACKS, "[]")
        val array = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
        
        val stickersArray = JSONArray()
        stickers.forEach { s ->
            stickersArray.put(JSONObject().apply {
                put("id", s.id)
                put("emoji", s.emoji)
                put("imageBase64", s.imageBase64)
                put("isAnimated", s.isAnimated)
            })
        }
        
        array.put(JSONObject().apply {
            put("id", pack.id)
            put("name", pack.name)
            put("author", pack.author)
            put("stickers", stickersArray)
        })
        
        prefs.edit().putString(KEY_STICKER_PACKS, array.toString()).apply()
        return pack
    }
    
    /**
     * Agregar sticker a recientes
     */
    fun addRecentSticker(sticker: Sticker) {
        val recent = getRecentStickers().toMutableList()
        recent.removeAll { it.id == sticker.id }
        recent.add(0, sticker)
        if (recent.size > 20) recent.subList(20, recent.size).clear()
        
        val array = JSONArray()
        recent.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id)
                put("emoji", s.emoji)
                put("imageBase64", s.imageBase64)
            })
        }
        prefs.edit().putString(KEY_RECENT_STICKERS, array.toString()).apply()
    }
    
    /**
     * Obtener stickers recientes
     */
    fun getRecentStickers(): List<Sticker> {
        val json = prefs.getString(KEY_RECENT_STICKERS, "[]")
        return try {
            val array = JSONArray(json)
            val stickers = mutableListOf<Sticker>()
            for (i in 0 until array.length()) {
                val s = array.getJSONObject(i)
                stickers.add(Sticker(
                    id = s.getString("id"),
                    emoji = s.optString("emoji", null),
                    imageBase64 = s.optString("imageBase64", null)
                ))
            }
            stickers
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ========== GIFs ==========
    
    data class GifResult(
        val id: String,
        val url: String,
        val previewUrl: String,
        val width: Int,
        val height: Int
    )
    
    /**
     * Buscar GIFs (usando Tenor API - requiere API key)
     * Por ahora retorna GIFs de ejemplo
     */
    fun searchGifs(query: String, callback: (List<GifResult>) -> Unit) {
        // Simulación de búsqueda de GIFs
        // En producción, usar Tenor o Giphy API
        val results = listOf(
            GifResult("1", "https://media.tenor.com/example1.gif", "https://media.tenor.com/example1_preview.gif", 200, 200),
            GifResult("2", "https://media.tenor.com/example2.gif", "https://media.tenor.com/example2_preview.gif", 200, 200)
        )
        callback(results)
    }
    
    /**
     * Agregar GIF a recientes
     */
    fun addRecentGif(gif: GifResult) {
        val recent = getRecentGifs().toMutableList()
        recent.removeAll { it.id == gif.id }
        recent.add(0, gif)
        if (recent.size > 20) recent.subList(20, recent.size).clear()
        
        val array = JSONArray()
        recent.forEach { g ->
            array.put(JSONObject().apply {
                put("id", g.id)
                put("url", g.url)
                put("previewUrl", g.previewUrl)
                put("width", g.width)
                put("height", g.height)
            })
        }
        prefs.edit().putString(KEY_RECENT_GIFS, array.toString()).apply()
    }
    
    /**
     * Obtener GIFs recientes
     */
    fun getRecentGifs(): List<GifResult> {
        val json = prefs.getString(KEY_RECENT_GIFS, "[]")
        return try {
            val array = JSONArray(json)
            val gifs = mutableListOf<GifResult>()
            for (i in 0 until array.length()) {
                val g = array.getJSONObject(i)
                gifs.add(GifResult(
                    id = g.getString("id"),
                    url = g.getString("url"),
                    previewUrl = g.getString("previewUrl"),
                    width = g.optInt("width", 200),
                    height = g.optInt("height", 200)
                ))
            }
            gifs
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ========== Vista previa de links ==========
    
    data class LinkPreview(
        val url: String,
        val title: String?,
        val description: String?,
        val imageUrl: String?,
        val siteName: String?,
        val fetchedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Obtener vista previa de un link (caché)
     */
    fun getLinkPreview(url: String): LinkPreview? {
        val json = prefs.getString(KEY_LINK_PREVIEWS, "{}")
        return try {
            val obj = JSONObject(json)
            if (!obj.has(url)) return null
            val p = obj.getJSONObject(url)
            
            // Caché válido por 24 horas
            val fetchedAt = p.optLong("fetchedAt", 0)
            if (System.currentTimeMillis() - fetchedAt > 24 * 60 * 60 * 1000) {
                return null
            }
            
            LinkPreview(
                url = url,
                title = p.optString("title", null),
                description = p.optString("description", null),
                imageUrl = p.optString("imageUrl", null),
                siteName = p.optString("siteName", null),
                fetchedAt = fetchedAt
            )
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Guardar vista previa de link
     */
    fun saveLinkPreview(preview: LinkPreview) {
        val json = prefs.getString(KEY_LINK_PREVIEWS, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        
        obj.put(preview.url, JSONObject().apply {
            put("title", preview.title)
            put("description", preview.description)
            put("imageUrl", preview.imageUrl)
            put("siteName", preview.siteName)
            put("fetchedAt", preview.fetchedAt)
        })
        
        prefs.edit().putString(KEY_LINK_PREVIEWS, obj.toString()).apply()
    }
    
    /**
     * Extraer URLs de un texto
     */
    fun extractUrls(text: String): List<String> {
        val urlPattern = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
        return urlPattern.findAll(text).map { it.value }.toList()
    }
    
    /**
     * Fetch link preview (básico)
     */
    fun fetchLinkPreview(url: String, callback: (LinkPreview?) -> Unit) {
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val html = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                
                // Extraer meta tags básicos
                val title = extractMetaContent(html, "og:title") 
                    ?: extractMetaContent(html, "title")
                    ?: extractHtmlTitle(html)
                val description = extractMetaContent(html, "og:description")
                    ?: extractMetaContent(html, "description")
                val imageUrl = extractMetaContent(html, "og:image")
                val siteName = extractMetaContent(html, "og:site_name")
                
                val preview = LinkPreview(
                    url = url,
                    title = title,
                    description = description,
                    imageUrl = imageUrl,
                    siteName = siteName
                )
                
                saveLinkPreview(preview)
                callback(preview)
            } catch (e: Exception) {
                callback(null)
            }
        }.start()
    }
    
    private fun extractMetaContent(html: String, property: String): String? {
        val patterns = listOf(
            Regex("<meta[^>]*property=[\"']$property[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE),
            Regex("<meta[^>]*name=[\"']$property[\"'][^>]*content=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE),
            Regex("<meta[^>]*content=[\"']([^\"']*)[\"'][^>]*property=[\"']$property[\"']", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
    
    private fun extractHtmlTitle(html: String): String? {
        val pattern = Regex("<title>([^<]*)</title>", RegexOption.IGNORE_CASE)
        return pattern.find(html)?.groupValues?.get(1)
    }
}
