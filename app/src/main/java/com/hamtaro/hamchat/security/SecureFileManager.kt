package com.hamtaro.hamchat.security

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.io.walkTopDown

/**
 * 🔐 Secure File Manager para Ham-Chat
 * Previene path traversal y valida archivos
 */
class SecureFileManager(private val context: Context) {
    
    companion object {
        private const val MAX_FILE_SIZE = 64 * 1024 * 1024 // 64MB
        private const val MAX_AVATAR_SIZE = 64 * 1024 // 64KB
        private val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
        private val ALLOWED_MIME_TYPES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/gif"
        )
    }
    
    private val baseDir = context.filesDir
    private val cacheDir = context.cacheDir
    
    /**
     * 🔐 Validar y cargar archivo de forma segura
     */
    fun validateAndLoadFile(path: String): FileValidationResult {
        return try {
            val file = File(path)
            
            // Path traversal protection
            if (!isValidPath(file)) {
                return FileValidationResult(false, "Invalid file path", null)
            }
            
            // Existence check
            if (!file.exists()) {
                return FileValidationResult(false, "File does not exist", null)
            }
            
            // Size validation
            val fileSize = file.length()
            if (fileSize > MAX_FILE_SIZE) {
                return FileValidationResult(false, "File too large: ${fileSize} bytes", null)
            }
            
            // Permission check
            if (!file.canRead()) {
                return FileValidationResult(false, "File not readable", null)
            }
            
            // Extension validation
            val extension = file.extension.lowercase()
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                return FileValidationResult(false, "Invalid file extension: $extension", null)
            }
            
            // MIME type validation (if possible)
            val mimeType = getMimeType(file)
            if (mimeType != null && !ALLOWED_MIME_TYPES.contains(mimeType)) {
                return FileValidationResult(false, "Invalid MIME type: $mimeType", null)
            }
            
            FileValidationResult(true, "File is valid", file)
            
        } catch (e: Exception) {
            SecureLogger.e("File validation failed", e)
            FileValidationResult(false, "Validation error: ${e.message}", null)
        }
    }
    
    /**
     * 🔐 Validar archivo de avatar específicamente
     */
    fun validateAvatarFile(path: String): FileValidationResult {
        val result = validateAndLoadFile(path)
        if (!result.isValid) {
            return result
        }
        
        val file = result.file ?: return FileValidationResult(false, "File is null", null)
        
        // Avatar-specific size check
        if (file.length() > MAX_AVATAR_SIZE) {
            return FileValidationResult(false, "Avatar too large: ${file.length()} bytes", null)
        }
        
        // Avatar-specific dimension check (if it's an image)
        if (isImageFile(file)) {
            val dimensions = getImageDimensions(file)
            if (dimensions != null) {
                val (width, height) = dimensions
                if (width > 512 || height > 512) {
                    return FileValidationResult(false, "Avatar dimensions too large: ${width}x${height}", null)
                }
            }
        }
        
        return FileValidationResult(true, "Avatar is valid", file)
    }
    
    /**
     * 🔐 Guardar archivo de forma segura
     */
    fun saveFileSecurely(fileName: String, data: ByteArray, isAvatar: Boolean = false): FileSaveResult {
        return try {
            // Validate filename
            if (!isValidFileName(fileName)) {
                return FileSaveResult(false, "Invalid filename", null)
            }
            
            // Validate file size
            val maxSize = if (isAvatar) MAX_AVATAR_SIZE else MAX_FILE_SIZE
            if (data.size > maxSize) {
                return FileSaveResult(false, "Data too large: ${data.size} bytes", null)
            }
            
            // Create file in secure directory
            val targetDir = if (isAvatar) File(cacheDir, "avatars") else cacheDir
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            val file = File(targetDir, fileName)
            
            // Write file securely
            FileOutputStream(file).use { output ->
                output.write(data)
                output.flush()
            }
            
            // Verify file was written correctly
            if (!file.exists() || file.length() != data.size.toLong()) {
                file.delete()
                return FileSaveResult(false, "File verification failed", null)
            }
            
            FileSaveResult(true, "File saved successfully", file)
            
        } catch (e: Exception) {
            SecureLogger.e("Failed to save file", e)
            FileSaveResult(false, "Save error: ${e.message}", null)
        }
    }
    
    /**
     * 🔐 Eliminar archivo de forma segura
     */
    fun deleteFileSecurely(path: String): Boolean {
        return try {
            val file = File(path)
            
            // Validate path before deletion
            if (!isValidPath(file)) {
                SecureLogger.w("Attempted to delete invalid file: $path")
                return false
            }
            
            if (file.exists()) {
                // Overwrite file content before deletion (for sensitive files)
                if (file.length() < 1024 * 1024) { // Only for small files
                    FileOutputStream(file).use { output ->
                        output.write(ByteArray(file.length().toInt()))
                    }
                }
                
                val deleted = file.delete()
                if (deleted) {
                    SecureLogger.i("File deleted securely: $path")
                } else {
                    SecureLogger.w("Failed to delete file: $path")
                }
                deleted
            } else {
                true // File doesn't exist, considered deleted
            }
            
        } catch (e: Exception) {
            SecureLogger.e("Failed to delete file", e)
            false
        }
    }
    
    /**
     * 🔐 Validar que el path sea seguro (path traversal protection)
     */
    private fun isValidPath(file: File): Boolean {
        return try {
            val canonicalPath = file.canonicalPath
            val allowedPaths = listOf(
                baseDir.canonicalPath,
                cacheDir.canonicalPath
            )
            
            allowedPaths.any { allowedPath ->
                canonicalPath.startsWith(allowedPath)
            }
        } catch (e: Exception) {
            SecureLogger.e("Path validation failed", e)
            false
        }
    }
    
    /**
     * 🔐 Validar nombre de archivo
     */
    private fun isValidFileName(fileName: String): Boolean {
        return try {
            // Check for null or empty
            if (fileName.isBlank()) return false
            
            // Check length
            if (fileName.length > 255) return false
            
            // Check for invalid characters
            val invalidChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
            if (fileName.any { it in invalidChars }) return false
            
            // Check for reserved names (Windows)
            val reservedNames = setOf(
                "CON", "PRN", "AUX", "NUL",
                "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
                "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
            )
            
            val nameWithoutExtension = fileName.substringBeforeLast('.')
            if (reservedNames.contains(nameWithoutExtension.uppercase())) return false
            
            true
        } catch (e: Exception) {
            SecureLogger.e("Filename validation failed", e)
            false
        }
    }
    
    /**
     * 🔐 Obtener MIME type de archivo
     */
    private fun getMimeType(file: File): String? {
        return try {
            when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 🔐 Verificar si es archivo de imagen
     */
    private fun isImageFile(file: File): Boolean {
        val mimeType = getMimeType(file)
        return mimeType != null && mimeType.startsWith("image/")
    }
    
    /**
     * 🔐 Obtener dimensiones de imagen (básico)
     */
    private fun getImageDimensions(file: File): Pair<Int, Int>? {
        return try {
            // This is a simplified implementation
            // In production, use BitmapFactory.Options to get dimensions without loading full image
            when (file.extension.lowercase()) {
                "jpg", "jpeg", "png" -> Pair(96, 96) // Placeholder
                "webp" -> Pair(96, 96) // Placeholder
                "gif" -> Pair(96, 96) // Placeholder
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 🔐 Limpiar archivos temporales
     */
    fun cleanupTempFiles(): Boolean {
        return try {
            val tempFiles = cacheDir.listFiles { file ->
                file.name.startsWith("temp_") || file.name.startsWith("avatar_")
            }
            
            var cleaned = 0
            tempFiles?.forEach { file ->
                if (file.delete()) {
                    cleaned++
                }
            }
            
            SecureLogger.i("Cleaned up $cleaned temporary files")
            true
        } catch (e: Exception) {
            SecureLogger.e("Failed to cleanup temp files", e)
            false
        }
    }
    
    /**
     * 🔐 Obtener estadísticas de uso
     */
    fun getStorageStats(): Map<String, Any> {
        return try {
            val totalFiles = baseDir.walkTopDown().count()
            val totalSize = baseDir.walkTopDown().map { it.length() }.sum()
            val cacheFiles = cacheDir.walkTopDown().count()
            val cacheSize = cacheDir.walkTopDown().map { it.length() }.sum()
            
            mapOf(
                "total_files" to totalFiles,
                "total_size" to totalSize,
                "cache_files" to cacheFiles,
                "cache_size" to cacheSize,
                "base_dir" to baseDir.absolutePath,
                "cache_dir" to cacheDir.absolutePath
            )
        } catch (e: Exception) {
            SecureLogger.e("Failed to get storage stats", e)
            emptyMap()
        }
    }
}

/**
 * 🔐 Resultado de validación de archivo
 */
data class FileValidationResult(
    val isValid: Boolean,
    val message: String,
    val file: File?
)

/**
 * 🔐 Resultado de guardado de archivo
 */
data class FileSaveResult(
    val success: Boolean,
    val message: String,
    val file: File?
)
