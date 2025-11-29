package com.hamtaro.hamchat.multimedia

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Environment
import com.hamtaro.hamchat.security.SecureFileManager
import com.hamtaro.hamchat.security.SecureLogger
import java.io.File
import java.util.UUID
import kotlin.io.walkTopDown

/**
 * 📞🎤📄 Simple Media Manager para Ham-Chat
 * Solo: Llamadas, Audio y Texto
 */
class SimpleMediaManager(private val context: Context) {
    
    companion object {
        private const val MAX_AUDIO_SIZE = 10 * 1024 * 1024 // 10MB
        private const val MAX_TEXT_SIZE = 1024 * 1024 // 1MB
        private const val AUDIO_QUALITY = 48000 // 48kHz para Opus
        private const val AUDIO_BITRATE = 48000 // 48kbps Opus
        private const val MAX_AUDIO_DURATION = 60000 // 60 segundos
        
        // 🎵 Formatos de audio soportados: Opus para grabación, TTA para tonos
        private const val AUDIO_FORMAT_OPUS = "opus"
        private const val AUDIO_FORMAT_TTA = "tta"
        private const val AUDIO_FORMAT_WAV = "wav"
        private const val AUDIO_FORMAT_MP3 = "mp3"
        
        // 📞 Formatos para tonos de llamada: Solo TTA
        private const val CALL_TONE_FORMAT = "tta"
        
        private const val AUDIO_DIR = "audio"
        private const val TEXT_DIR = "text"
        private const val CALL_TONES_DIR = "call_tones"
    }
    
    private val secureFileManager = SecureFileManager(context)
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private enum class MediaType { AUDIO, TEXT }
    
    /**
     * 📞 Iniciar llamada de voz
     */
    fun startVoiceCall(contactId: String): Boolean {
        return try {
            // Iniciar grabación para llamada
            startAudioRecording(contactId, AUDIO_FORMAT_WAV)
            SecureLogger.i("Voice call started with: $contactId")
            true
        } catch (e: Exception) {
            SecureLogger.e("Error starting voice call", e)
            false
        }
    }
    
    /**
     * 📞 Finalizar llamada de voz
     */
    fun endVoiceCall(contactId: String): Boolean {
        return try {
            // Detener grabación de llamada
            stopAudioRecording()
            SecureLogger.i("Voice call ended with: $contactId")
            true
        } catch (e: Exception) {
            SecureLogger.e("Error ending voice call", e)
            false
        }
    }
    
    /**
     * 🎤 Grabar audio en formato Opus 48kbps (solo para mensajes de audio)
     */
    fun startAudioRecording(contactId: String, audioFormat: String = AUDIO_FORMAT_OPUS): Boolean {
        return try {
            // Detener grabación anterior si existe
            stopAudioRecording()
            
            // Solo permitir Opus para mensajes de audio
            val extension = if (audioFormat.lowercase() == AUDIO_FORMAT_OPUS) {
                "opus"
            } else {
                // Forzar Opus como único formato para mensajes
                "opus"
            }
            
            val filename = "audio_${contactId}_${UUID.randomUUID()}.$extension"
            val audioFile = File(context.cacheDir, AUDIO_DIR)
            if (!audioFile.exists()) audioFile.mkdirs()
            val outputFile = File(audioFile, filename)
            
            // Configurar MediaRecorder para Opus 48kbps (único formato para mensajes)
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                
                // Solo Opus 48kbps para mensajes de audio
                setOutputFormat(MediaRecorder.OutputFormat.OGG) // Opus en contenedor OGG
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS) // Opus encoder
                setAudioEncodingBitRate(AUDIO_BITRATE) // 48kbps
                setAudioSamplingRate(AUDIO_QUALITY) // 48kHz
                setAudioChannels(1) // Mono para optimizar tamaño
                
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            
            SecureLogger.i("Audio recording started: Opus 48kbps for audio message to $contactId")
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
                try {
                    recorder.stop()
                } catch (e: Exception) {
                    SecureLogger.w("MediaRecorder stop failed, continuing with cleanup", e.message)
                }
                try {
                    recorder.release()
                } catch (e: Exception) {
                    SecureLogger.w("MediaRecorder release failed", e.message)
                }
                mediaRecorder = null
                
                // Validar archivo grabado
                val audioFiles = File(context.cacheDir, AUDIO_DIR).listFiles()
                val latestFile = audioFiles?.maxByOrNull { it.lastModified() }
                
                if (latestFile != null && latestFile.length() > 0) {
                    val finalFile = tryMoveToExternal(latestFile, MediaType.AUDIO)
                    SecureLogger.i("Audio recording completed: ${finalFile.name}")
                    MediaResult(true, "Recording completed", finalFile.absolutePath)
                } else {
                    SecureLogger.w("Audio file not created or empty")
                    MediaResult(false, "Recording failed - no file created", null)
                }
            } ?: MediaResult(false, "No active recording", null)
            
        } catch (e: Exception) {
            SecureLogger.e("Error stopping audio recording", e)
            // Cleanup for safety
            try {
                mediaRecorder?.release()
                mediaRecorder = null
            } catch (cleanupException: Exception) {
                SecureLogger.w("Error during cleanup", cleanupException.message)
            }
            MediaResult(false, "Error stopping recording: ${e.message}", null)
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
     * ⏹️ Detener reproducción de audio
     */
    fun stopAudioPlayback() {
        try {
            mediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                    }
                } catch (e: Exception) {
                    SecureLogger.w("MediaPlayer stop failed", e.message)
                }
                try {
                    player.release()
                } catch (e: Exception) {
                    SecureLogger.w("MediaPlayer release failed", e.message)
                }
                mediaPlayer = null
                SecureLogger.i("Audio playback stopped")
            }
        } catch (e: Exception) {
            SecureLogger.e("Error stopping audio playback", e)
            // Force cleanup
            try {
                mediaPlayer?.release()
                mediaPlayer = null
            } catch (cleanupException: Exception) {
                SecureLogger.w("Force cleanup failed", cleanupException.message)
                SecureLogger.e("Force cleanup failed", cleanupException)
            }
        }
    }
    
    /**
     * 🔍 Validar archivo de audio (Opus para mensajes, TTA para tonos)
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
                AUDIO_FORMAT_OPUS -> AudioFormat.OPUS
                AUDIO_FORMAT_TTA -> AudioFormat.TTA
                else -> return AudioValidationResult(false, "Unsupported audio format: $extension", null)
            }
            
            AudioValidationResult(true, "Valid audio file", format)
            
        } catch (e: Exception) {
            SecureLogger.e("Error validating audio file", e)
            AudioValidationResult(false, "Validation error: ${e.message}", null)
        }
    }
    
    /**
     * 📞 Establecer tono de llamada en formato TTA (único formato permitido)
     */
    fun setCallTone(tonePath: String): Boolean {
        return try {
            // Validar que sea formato TTA (único permitido para tonos)
            val validation = validateAudioFile(tonePath)
            if (!validation.isValid || validation.format != AudioFormat.TTA) {
                SecureLogger.w("Invalid call tone format: Only TTA allowed for call tones")
                return false
            }
            
            // Copiar tono a directorio de tonos de llamada
            val toneFile = File(tonePath)
            val callTonesDir = File(context.cacheDir, CALL_TONES_DIR)
            if (!callTonesDir.exists()) callTonesDir.mkdirs()
            
            val callToneFile = File(callTonesDir, "call_tone_${UUID.randomUUID()}.tta")
            toneFile.copyTo(callToneFile, overwrite = true)
            
            SecureLogger.i("Call tone set (TTA format): ${callToneFile.name}")
            true
            
        } catch (e: Exception) {
            SecureLogger.e("Error setting call tone", e)
            false
        }
    }
    
    /**
     * 📞 Obtener tonos de llamada disponibles (solo TTA)
     */
    fun getAvailableCallTones(): List<String> {
        return try {
            val callTonesDir = File(context.cacheDir, CALL_TONES_DIR)
            if (!callTonesDir.exists()) return emptyList()
            
            callTonesDir.listFiles()
                ?.filter { it.extension.equals("tta", true) }
                ?.map { it.absolutePath }
                ?: emptyList()
        } catch (e: Exception) {
            SecureLogger.e("Error getting available call tones", e)
            emptyList()
        }
    }
    
    /**
     * 📞 Reproducir tono de llamada TTA (solo este formato)
     */
    fun playCallTone(tonePath: String): Boolean {
        return try {
            // Validar formato TTA (único permitido)
            val validation = validateAudioFile(tonePath)
            if (!validation.isValid || validation.format != AudioFormat.TTA) {
                SecureLogger.w("Invalid call tone format: Only TTA allowed for call tones")
                return false
            }
            
            // Reproducir tono
            playAudio(tonePath)
        } catch (e: Exception) {
            SecureLogger.e("Error playing call tone", e)
            false
        }
    }
    
    /**
     * 🎵 Obtener formatos de audio soportados (Opus para mensajes, TTA para tonos)
     */
    fun getSupportedAudioFormats(): List<String> {
        return listOf(
            "Opus - 48kbps (único formato para mensajes de audio)",
            "TTA (True Audio) - Alta calidad, sin pérdida (solo para tonos de llamada)"
        )
    }
    
    /**
     * 🎵 Obtener información de formato de audio
     */
    fun getAudioFormatInfo(format: String): AudioFormatInfo {
        return when (format.lowercase()) {
            AUDIO_FORMAT_OPUS -> AudioFormatInfo(
                name = "Opus",
                description = "Opus - 48kbps optimizado para mensajes de audio",
                extension = "opus",
                maxBitrate = 48000,
                isLossless = false,
                recommendedUse = "Mensajes de audio (único formato permitido)"
            )
            AUDIO_FORMAT_TTA -> AudioFormatInfo(
                name = "TTA",
                description = "True Audio - Alta calidad sin pérdida (solo para tonos de llamada)",
                extension = "tta",
                maxBitrate = 192000,
                isLossless = true,
                recommendedUse = "Tonos de llamada de alta fidelidad (único formato permitido)"
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
     * 📞 Obtener información de formatos de tonos de llamada (solo TTA)
     */
    fun getCallToneFormats(): List<String> {
        return listOf(
            "TTA (True Audio) - Único formato permitido para tonos de llamada",
            "Calidad: Alta, sin pérdida",
            "Bitrate: 192kbps",
            "Uso: Tonos de llamada personalizados exclusivamente"
        )
    }
    
    /**
     * 📊 Obtener estadísticas de multimedia
     */
    fun getMediaStats(): Map<String, Any> {
        return try {
            val audioDir = File(context.cacheDir, AUDIO_DIR)
            val textDir = File(context.cacheDir, TEXT_DIR)
            
            // Contar archivos por formato (solo Opus para mensajes, TTA para tonos)
            val audioFiles = audioDir.listFiles() ?: emptyArray()
            val opusCount = audioFiles.count { it.extension.equals("opus", true) }
            val ttaCount = audioFiles.count { it.extension.equals("tta", true) }
            
            // Contar tonos de llamada TTA
            val callTonesDir = File(context.cacheDir, CALL_TONES_DIR)
            val callToneFiles = callTonesDir.listFiles() ?: emptyArray()
            val callToneCount = callToneFiles.count { it.extension.equals("tta", true) }
            
            mapOf(
                "audio_count" to audioFiles.size,
                "audio_size" to if (audioDir.exists()) audioDir.walkTopDown().map { it.length() }.sum() else 0L,
                "audio_opus_count" to opusCount,
                "audio_tta_count" to ttaCount,
                "call_tone_count" to callToneCount,
                "text_count" to (textDir.listFiles()?.size ?: 0),
                "text_size" to if (textDir.exists()) textDir.walkTopDown().map { it.length() }.sum() else 0L,
                "is_recording" to (mediaRecorder != null),
                "is_playing" to (mediaPlayer?.isPlaying == true),
                "supported_formats" to getSupportedAudioFormats(),
                "call_tone_formats" to getCallToneFormats(),
                "audio_message_format" to "Opus 48kbps (único formato)",
                "call_tone_format" to "TTA (único formato)"
            )
        } catch (e: Exception) {
            SecureLogger.e("Error getting media stats", e)
            emptyMap()
        }
    }
    
    /**
     * 🧹 Limpiar archivos temporales
     */
    fun cleanupTempFiles(): Boolean {
        return try {
            val cacheDirs = listOf(
                File(context.cacheDir, AUDIO_DIR),
                File(context.cacheDir, TEXT_DIR),
                File(context.cacheDir, CALL_TONES_DIR)
            )
            
            var cleaned = 0
            cacheDirs.forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        if (file.delete()) cleaned++
                    }
                }
            }
            
            SecureLogger.i("Cleaned up $cleaned media files")
            true
        } catch (e: Exception) {
            SecureLogger.e("Error cleaning up media files", e)
            false
        }
    }
    
    /**
     * 🔓 Liberar recursos
     */
    fun release() {
        try {
            stopAudioRecording()
            stopAudioPlayback()
            SecureLogger.i("SimpleMediaManager resources released")
        } catch (e: Exception) {
            SecureLogger.e("Error releasing SimpleMediaManager", e)
        }
    }

    /**
     * 📁 Intentar mover/copiar archivo a almacenamiento externo privado de Ham-Chat
     */
    private fun tryMoveToExternal(file: File, type: MediaType): File {
        return try {
            val baseDir = when (type) {
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
                // Mantener copia interna como respaldo; no se borra el original.
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
