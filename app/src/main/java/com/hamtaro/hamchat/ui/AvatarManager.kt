package com.hamtaro.hamchat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.hamtaro.hamchat.model.Contact
import com.hamtaro.hamchat.security.SecureFileManager
import com.hamtaro.hamchat.security.SecureLogger
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 🖼️ Avatar Manager optimizado para batería y memoria
 * Para Ham-Chat en Sharp Keitai 4
 */
class AvatarManager(private val context: Context) {
    
    companion object {
        private const val AVATAR_SIZE = 96
        private const val MAX_CACHE_SIZE = 20 * 1024 * 1024 // 20MB
        private const val MAX_CACHE_ITEMS = 100
    }
    
    // 🔋 Cache optimizado para avatares
    private val avatarCache = LruCache<String, Bitmap>(MAX_CACHE_ITEMS)
    private val secureFileManager = SecureFileManager(context)
    
    // 🔋 Paint reutilizable para rendimiento
    private val textPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 48f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    
    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
    }
    
    /**
     * 🖼️ Cargar avatar con optimización de batería
     */
    fun loadAvatar(contact: Contact, callback: (Bitmap) -> Unit) {
        val cacheKey = generateCacheKey(contact)
        
        // 🔋 Revisar cache primero
        avatarCache.get(cacheKey)?.let { cachedBitmap ->
            callback(cachedBitmap)
            return
        }
        
        // 🖼️ Intentar cargar desde archivo local
        contact.avatarPath?.let { path ->
            loadFromFile(path, contact, callback)
            return
        }
        
        // 🌐 Cargar desde URL con Glide optimizado
        contact.avatarUrl?.let { url ->
            loadFromUrl(url, contact, callback)
            return
        }
        
        // 🔋 Generar avatar por defecto
        generateDefaultAvatar(contact, callback)
    }
    
    /**
     * 🔋 Cargar desde archivo local (optimizado)
     */
    private fun loadFromFile(path: String, contact: Contact, callback: (Bitmap) -> Unit) {
        // Validar archivo de forma segura
        val validation = secureFileManager.validateAvatarFile(path)
        if (!validation.isValid) {
            SecureLogger.w("Avatar file validation failed: ${validation.message}")
            generateDefaultAvatar(contact, callback)
            return
        }
        
        val file = validation.file ?: run {
            generateDefaultAvatar(contact, callback)
            return
        }
        
        try {
            // 🔋 Decodificación optimizada para memoria
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            
            // Calcular sample size para optimizar memoria
            options.inSampleSize = calculateInSampleSize(options, AVATAR_SIZE, AVATAR_SIZE)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565 // Menor memoria que ARGB_8888
            
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            if (bitmap != null) {
                val resizedBitmap = resizeBitmap(bitmap, AVATAR_SIZE, AVATAR_SIZE)
                cacheAvatar(generateCacheKey(contact), resizedBitmap)
                callback(resizedBitmap)
            } else {
                generateDefaultAvatar(contact, callback)
            }
        } catch (e: Exception) {
            SecureLogger.e("Error loading avatar from file", e)
            generateDefaultAvatar(contact, callback)
        }
    }
    
    /**
     * 🌐 Cargar desde URL con Glide optimizado
     */
    private fun loadFromUrl(url: String, contact: Contact, callback: (Bitmap) -> Unit) {
        val requestOptions = RequestOptions().apply {
            override(AVATAR_SIZE, AVATAR_SIZE)
            centerCrop()
            encodeFormat(Bitmap.CompressFormat.WEBP) // Más eficiente que PNG/JPEG
            encodeQuality(70) // Balance calidad/tamaño
            skipMemoryCache(false) // Usar cache
            diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
        }
        
        Glide.with(context)
            .asBitmap()
            .load(url)
            .apply(requestOptions)
            .into(object : SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    cacheAvatar(generateCacheKey(contact), resource)
                    callback(resource)
                }
                
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    generateDefaultAvatar(contact, callback)
                }
            })
    }
    
    /**
     * 🔋 Generar avatar por defecto (mínimo consumo de batería)
     */
    private fun generateDefaultAvatar(contact: Contact, callback: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        
        // 🎨 Fondo de color Hamtaro (naranja)
        backgroundPaint.color = 0xFFFF9500.toInt()
        canvas.drawCircle(
            AVATAR_SIZE / 2f,
            AVATAR_SIZE / 2f,
            AVATAR_SIZE / 2f,
            backgroundPaint
        )
        
        // 🔤 Inicial del nombre
        val initial = contact.name.firstOrNull()?.uppercase() ?: "?"
        canvas.drawText(
            initial,
            AVATAR_SIZE / 2f,
            AVATAR_SIZE / 2f + 16f,
            textPaint
        )
        
        cacheAvatar(generateCacheKey(contact), bitmap)
        callback(bitmap)
    }
    
    /**
     * 🔋 Cache optimizado
     */
    private fun cacheAvatar(key: String, bitmap: Bitmap) {
        avatarCache.put(key, bitmap)
    }
    
    /**
     * 🔋 Generar cache key único
     */
    private fun generateCacheKey(contact: Contact): String {
        val input = "${contact.id}_${contact.avatarUrl}_${contact.avatarPath}"
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
    
    /**
     * 🔋 Calcular sample size para optimizar memoria
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * 🔋 Redimensionar bitmap con optimización de memoria
     */
    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        return if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaledBitmap != bitmap) {
                bitmap.recycle() // Liberar memoria
            }
            scaledBitmap
        }
    }
    
    /**
     * 🔋 Limpiar cache para liberar memoria
     */
    fun clearCache() {
        avatarCache.evictAll()
        Glide.get(context).clearMemory()
    }
    
    /**
     * 🖼️ Guardar avatar en almacenamiento local
     */
    fun saveAvatarLocally(contact: Contact, bitmap: Bitmap): String? {
        return try {
            val filename = "avatar_${contact.id.hashCode()}.webp"
            
            // Convertir bitmap a bytes
            val stream = java.io.ByteArrayOutputStream()
            val success = bitmap.compress(
                Bitmap.CompressFormat.WEBP, 
                70, 
                stream
            )
            
            if (!success) {
                SecureLogger.w("Failed to compress bitmap")
                return null
            }
            
            val data = stream.toByteArray()
            
            // Guardar con SecureFileManager
            val result = secureFileManager.saveFileSecurely(filename, data, isAvatar = true)
            
            if (result.success) {
                result.file?.absolutePath
            } else {
                SecureLogger.w("Failed to save avatar: ${result.message}")
                null
            }
        } catch (e: Exception) {
            SecureLogger.e("Error saving avatar locally", e)
            null
        }
    }
    
    /**
     * 🔋 Verificar uso de memoria
     */
    fun getMemoryUsage(): String {
        val cacheSize = avatarCache.size()
        val maxSize = avatarCache.maxSize()
        return "Cache: ${cacheSize / 1024}KB / ${maxSize / 1024}KB"
    }
}
