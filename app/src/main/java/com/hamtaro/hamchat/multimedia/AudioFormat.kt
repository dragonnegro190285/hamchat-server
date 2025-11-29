package com.hamtaro.hamchat.multimedia

/**
 * Enumeración de formatos de audio soportados
 */
enum class AudioFormat {
    OPUS,
    TTA,
    WAV,
    MP3
}

/**
 * Resultado de validación de audio
 */
data class AudioValidationResult(
    val isValid: Boolean,
    val message: String,
    val format: AudioFormat?
)

/**
 * 📊 Información de formato de audio
 */
data class AudioFormatInfo(
    val name: String,
    val description: String,
    val extension: String,
    val maxBitrate: Int,
    val isLossless: Boolean,
    val recommendedUse: String
)
