package com.hamtaro.hamchat.multimedia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.hamtaro.hamchat.security.SecureFileManager
import com.hamtaro.hamchat.security.SecureLogger
import kotlin.io.walkTopDown
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * 🎵📱 Media Manager para Ham-Chat
 * Maneja fotos, audio y archivos de texto
 */
class MediaManager(private val context: Context) {
    
    companion object {
        private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5MB
        private const val MAX_AUDIO_SIZE = 10 * 1024 * 1024 // 10MB
        private const val MAX_TEXT_SIZE = 1024 * 1024 // 1MB
        private const val AUDIO_QUALITY = 44100
        private const val AUDIO_BITRATE = 128000
        private const val MAX_AUDIO_DURATION = 60000 // 60 segundos
        
        // 🎵 Formatos de audio soportados: TTA, WAV, MP3
        private const val AUDIO_FORMAT_TTA = "tta"
        private const val AUDIO_FORMAT_WAV = "wav"
        private const val AUDIO_FORMAT_MP3 = "mp3"
        
        private const val IMAGES_DIR = "images"
        private const val AUDIO_DIR = "audio"
        private const val TEXT_DIR = "text"
    }
    
    private val secureFileManager = SecureFileManager(context)
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private enum class MediaType { IMAGE, AUDIO, TEXT }
    
    /**
     * 📸 Enviar foto
     */
    fun sendPhoto(imageUri: Uri, contactId: String): MediaResult {
        return try {
            // Validar y cargar imagen
            val bitmap = loadAndValidateImage(imageUri)
            if (bitmap == null) {
                return MediaResult(false, "Invalid image file", null)
            }
            
            // Comprimir y optimizar
            val optimizedBitmap = optimizeImage(bitmap)
            val imageData = bitmapToByteArray(optimizedBitmap)
            
            if (imageData.size > MAX_IMAGE_SIZE) {
                return MediaResult(false, "Image too large (${imageData.size} bytes)", null)
            }
            
            // Guardar de forma segura
            val filename = "photo_${contactId}_${UUID.randomUUID()}.webp"
            val result = secureFileManager.saveFileSecurely(filename, imageData, isAvatar = false)
            
            if (result.success) {
                val finalFile = result.file?.let { tryMoveToExternal(it, MediaType.IMAGE) }
                SecureLogger.i("Photo sent successfully: ${finalFile?.name ?: result.file?.name}")
                MediaResult(true, "Photo sent", (finalFile ?: result.file)?.absolutePath)
            } else {
                MediaResult(false, "Failed to save photo: ${result.message}", null)
            }
            
        } catch (e: Exception) {
            SecureLogger.e("Error sending photo", e)
            MediaResult(false, "Error: ${e.message}", null)
        }
    }
    
    /**
     * 🎤 Grabar audio en formato WAV (compatible con TTA y MP3)
     */
    fun startAudioRecording(contactId: String, audioFormat: String = AUDIO_FORMAT_WAV): Boolean {
        return try {
            // Detener grabación anterior si existe
            stopAudioRecording()
            
            // Crear archivo de audio según formato
            val extension = when (audioFormat.lowercase()) {
                AUDIO_FORMAT_TTA -> "tta"
                AUDIO_FORMAT_WAV -> "wav"
                AUDIO_FORMAT_MP3 -> "mp3"
                else -> "wav" // Default a WAV
            }
            
            val filename = "audio_${contactId}_${UUID.randomUUID()}.$extension"
            val audioFile = File(context.cacheDir, AUDIO_DIR)
            if (!audioFile.exists()) audioFile.mkdirs()
            val outputFile = File(audioFile, filename)
            
            // Configurar MediaRecorder según formato
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                
                when (audioFormat.lowercase()) {
                    AUDIO_FORMAT_TTA -> {
                        // TTA: Configuración de alta calidad
                        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP) // TTA no soportado directamente, usamos 3GP
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(192000) // Alta calidad para TTA
                    }
                    AUDIO_FORMAT_WAV -> {
                        // WAV: Sin compresión
                        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP) // WAV no soportado directamente
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(128000)
                    }
                    AUDIO_FORMAT_MP3 -> {
                        // MP3: Compresión balanceada
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC) // MP3 no soportado, usamos AAC
                        setAudioEncodingBitRate(128000)
                    }
                    else -> {
                        // Default: WAV
                        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(128000)
                    }
                }
                
                setOutputFile(outputFile.absolutePath)
                setAudioSamplingRate(AUDIO_QUALITY)
                setAudioChannels(1) // Mono para optimizar tamaño
                prepare()
                start()
            }
            
            SecureLogger.i("Audio recording started: $audioFormat for $contactId")
            true
            
        } catch (e: Exception) {
            SecureLogger.e("Error starting audio recording", e)
            false
        }
    }
    
    /**
     * ⏹️ Detener grabación de audio
     */
    fun stopAudioRecording(): MediaResult {
        return try {
            mediaRecorder?.let { recorder ->
                recorder.stop()
                recorder.release()
                mediaRecorder = null
                
                // Validar archivo grabado
                val audioFiles = File(context.cacheDir, AUDIO_DIR).listFiles()
                val latestFile = audioFiles?.maxByOrNull { it.lastModified() }
                
                if (latestFile != null && latestFile.length() > 0) {
                    if (latestFile.length() > MAX_AUDIO_SIZE) {
                        latestFile.delete()
                        return MediaResult(false, "Audio too large", null)
                    }
                    
                    val finalFile = tryMoveToExternal(latestFile, MediaType.AUDIO)
                    SecureLogger.i("Audio recording completed: ${finalFile.name}")
                    MediaResult(true, "Audio recorded", finalFile.absolutePath)
                } else {
                    MediaResult(false, "No audio file created", null)
                }
            } ?: MediaResult(false, "No recording in progress", null)
            
        } catch (e: Exception) {
            SecureLogger.e("Error stopping audio recording", e)
            MediaResult(false, "Error: ${e.message}", null)
        }
    }
    
    /**
     * 📄 Enviar archivo de texto
     */
    fun sendTextFile(content: String, contactId: String): MediaResult {
        return try {
            // Validar tamaño del contenido
            val textBytes = content.toByteArray(Charsets.UTF_8)
            if (textBytes.size > MAX_TEXT_SIZE) {
                return MediaResult(false, "Text too large (${textBytes.size} bytes)", null)
            }
            
            // Guardar de forma segura
            val filename = "text_${contactId}_${UUID.randomUUID()}.txt"
            val result = secureFileManager.saveFileSecurely(filename, textBytes, isAvatar = false)
            
            if (result.success) {
                val finalFile = result.file?.let { tryMoveToExternal(it, MediaType.TEXT) }
                SecureLogger.i("Text file sent: ${finalFile?.name ?: result.file?.name}")
                MediaResult(true, "Text file sent", (finalFile ?: result.file)?.absolutePath)
            } else {
                MediaResult(false, "Failed to save text: ${result.message}", null)
            }
            
        } catch (e: Exception) {
            SecureLogger.e("Error sending text file", e)
            MediaResult(false, "Error: ${e.message}", null)
        }
    }
    
    /**
     * 🎵 Reproducir audio recibido (TTA, WAV, MP3)
     */
    fun playAudio(audioPath: String): Boolean {
        return try {
            // Detener reproducción anterior
            stopAudioPlayback()
            
            // Validar archivo y formato
            val validation = validateAudioFile(audioPath)
            if (!validation.isValid) {
                SecureLogger.w("Invalid audio file: ${validation.message}")
                return false
            }
            
            // Configurar MediaPlayer según formato
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioPath)
                setOnCompletionListener {
                    SecureLogger.i("Audio playback completed: ${validation.format}")
                    release()
                    mediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    SecureLogger.e("Audio playback error: what=$what, extra=$extra")
                    release()
                    mediaPlayer = null
                    false
                }
                prepare()
                start()
            }
            
            SecureLogger.i("Audio playback started: ${validation.format} - $audioPath")
            true
            
        } catch (e: Exception) {
            SecureLogger.e("Error playing audio", e)
            false
        }
    }
    
    /**
     * 🔍 Validar archivo de audio (TTA, WAV, MP3)
     */
    private fun validateAudioFile(audioPath: String): AudioValidationResult {
        return try {
            val file = File(audioPath)
            
            // Validación básica de archivo
            if (!file.exists()) {
                return AudioValidationResult(false, "File does not exist", null)
            }
            
            if (file.length() > MAX_AUDIO_SIZE) {
                return AudioValidationResult(false, "Audio too large: ${file.length()} bytes", null)
            }
            
            // Determinar formato por extensión
            val extension = file.extension.lowercase()
            val format = when (extension) {
                AUDIO_FORMAT_TTA -> AudioFormat.TTA
                AUDIO_FORMAT_WAV -> AudioFormat.WAV
                AUDIO_FORMAT_MP3 -> AudioFormat.MP3
                else -> return AudioValidationResult(false, "Unsupported audio format: $extension", null)
            }
            
            AudioValidationResult(true, "Valid audio file", format)
            
        } catch (e: Exception) {
            SecureLogger.e("Error validating audio file", e)
            AudioValidationResult(false, "Validation error: ${e.message}", null)
        }
    }
    
    /**
     * ⏹️ Detener reproducción de audio
     */
    fun stopAudioPlayback() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
                mediaPlayer = null
                SecureLogger.i("Audio playback stopped")
            }
        } catch (e: Exception) {
            SecureLogger.e("Error stopping audio playback", e)
        }
    }
    
    /**
     * 📸 Cargar y validar imagen
     */
    private fun loadAndValidateImage(imageUri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            inputStream?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            
            // Validar dimensiones
            if (options.outWidth > 4096 || options.outHeight > 4096) {
                return null
            }
            
            // Cargar imagen completa
            val loadOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(options, 1024, 1024)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, loadOptions)
            }
            
        } catch (e: Exception) {
            SecureLogger.e("Error loading image", e)
            null
        }
    }
    
    /**
     * 🖼️ Optimizar imagen
     */
    private fun optimizeImage(bitmap: Bitmap): Bitmap {
        return try {
            // Redimensionar si es muy grande
            if (bitmap.width > 1024 || bitmap.height > 1024) {
                val ratio = minOf(1024f / bitmap.width, 1024f / bitmap.height)
                val width = (bitmap.width * ratio).toInt()
                val height = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            SecureLogger.e("Error optimizing image", e)
            bitmap
        }
    }
    
    /**
     * 🔄 Convertir bitmap a byte array
     */
    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 80, stream)
            stream.toByteArray()
        }
    }
    
    /**
     * 📏 Calcular sample size
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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
     * 🎵 Obtener formatos de audio soportados
     */
    fun getSupportedAudioFormats(): List<String> {
        return listOf(
            "TTA (True Audio) - Alta calidad, sin pérdida",
            "WAV (Waveform) - Estándar, sin compresión", 
            "MP3 (MPEG-1 Audio Layer 3) - Compresión balanceada"
        )
    }
    
    /**
     * 🎵 Obtener información de formato de audio
     */
    fun getAudioFormatInfo(format: String): AudioFormatInfo {
        return when (format.lowercase()) {
            AUDIO_FORMAT_TTA -> AudioFormatInfo(
                name = "TTA",
                description = "True Audio - Alta calidad sin pérdida",
                extension = "tta",
                maxBitrate = 192000,
                isLossless = true,
                recommendedUse = "Música de alta fidelidad"
            )
            AUDIO_FORMAT_WAV -> AudioFormatInfo(
                name = "WAV",
                description = "Waveform Audio - Estándar sin compresión",
                extension = "wav",
                maxBitrate = 1411000,
                isLossless = true,
                recommendedUse = "Audio profesional y voz"
            )
            AUDIO_FORMAT_MP3 -> AudioFormatInfo(
                name = "MP3",
                description = "MPEG-1 Audio Layer 3 - Compresión eficiente",
                extension = "mp3",
                maxBitrate = 320000,
                isLossless = false,
                recommendedUse = "Música y voz cotidiana"
            )
            else -> AudioFormatInfo(
                name = "Unknown",
                description = "Formato no soportado",
                extension = "",
                maxBitrate = 0,
                isLossless = false,
                recommendedUse = "N/A"
            )
        }
    }
    
    /**
     * 📊 Obtener estadísticas de multimedia
     */
    fun getMediaStats(): Map<String, Any> {
        return try {
            val imagesDir = File(context.cacheDir, IMAGES_DIR)
            val audioDir = File(context.cacheDir, AUDIO_DIR)
            val textDir = File(context.cacheDir, TEXT_DIR)
            
            // Contar archivos por formato
            val audioFiles = audioDir.listFiles() ?: emptyArray()
            val ttaCount = audioFiles.count { it.extension.equals("tta", true) }
            val wavCount = audioFiles.count { it.extension.equals("wav", true) }
            val mp3Count = audioFiles.count { it.extension.equals("mp3", true) }
            
            mapOf(
                "images_count" to (imagesDir.listFiles()?.size ?: 0),
                "images_size" to if (imagesDir.exists()) imagesDir.walkTopDown().map { it.length() }.sum() else 0L,
                "audio_count" to audioFiles.size,
                "audio_size" to if (audioDir.exists()) audioDir.walkTopDown().map { it.length() }.sum() else 0L,
                "audio_tta_count" to ttaCount,
                "audio_wav_count" to wavCount,
                "audio_mp3_count" to mp3Count,
                "text_count" to (textDir.listFiles()?.size ?: 0),
                "text_size" to if (textDir.exists()) textDir.walkTopDown().map { it.length() }.sum() else 0L,
                "is_recording" to (mediaRecorder != null),
                "is_playing" to (mediaPlayer?.isPlaying == true),
                "supported_formats" to getSupportedAudioFormats()
            )
        } catch (e: Exception) {
            SecureLogger.e("Error getting media stats", e)
            emptyMap()
        }
    }
    
    /**
     * 🔓 Liberar recursos
     */
    fun release() {
        try {
            stopAudioRecording()
            stopAudioPlayback()
            SecureLogger.i("MediaManager resources released")
        } catch (e: Exception) {
            SecureLogger.e("Error releasing MediaManager", e)
        }
    }
    
    /**
     * 📁 Intentar mover/copiar archivo a almacenamiento externo privado de Ham-Chat
     */
    private fun tryMoveToExternal(file: File, type: MediaType): File {
        return try {
            val baseDir = when (type) {
                MediaType.IMAGE -> context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                MediaType.AUDIO -> context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                MediaType.TEXT  -> context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            } ?: return file

            val targetDir = File(baseDir, "HamChat")
            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) return file
            }

            val targetFile = File(targetDir, file.name)
            try {
                file.copyTo(targetFile, overwrite = true)
                // Opcional: mantener copia interna por seguridad; no se borra el original.
                targetFile
            } catch (e: Exception) {
                SecureLogger.e("Error copying file to external storage", e)
                file
            }
        } catch (e: Exception) {
            SecureLogger.e("Error preparing external storage path", e)
            file
        }
    }
}

/**
 * 📊 Resultado de operación multimedia
 */
data class MediaResult(
    val success: Boolean,
    val message: String,
    val filePath: String?
)
