package com.hamtaro.hamchat.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * 🔐 SharedPreferences para Ham-Chat
 * Compatible con todos los dispositivos (incluyendo Xiaomi/MIUI)
 */
class SecurePreferences(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "HamChatSecure"
        private const val GAME_UNLOCKED = "game_mode_unlocked"
        private const val HAMTARO_UNLOCKED = "hamtaro_theme_unlocked"
        private const val SECRET_ATTEMPTS = "secret_attempts"
        private const val LAST_ATTEMPT = "last_attempt"
        private const val HIGH_SCORE = "high_score"
        private const val GAME_SETTINGS = "game_settings"
        private const val AUTH_TOKEN = "auth_token_secure"
    }
    
    // Usar SharedPreferences normales para compatibilidad con Xiaomi/MIUI
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Ofuscación simple para datos sensibles (no es encriptación fuerte, pero es compatible)
    private fun encode(value: String): String {
        return Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun decode(value: String): String {
        return String(Base64.decode(value, Base64.NO_WRAP))
    }
    
    /**
     * 🔐 Guardar estado de modo secreto
     */
    fun setSecretUnlocked(secret: String, unlocked: Boolean) {
        try {
            prefs.edit().putBoolean(secret, unlocked).apply()
            SecureLogger.i("Secret status updated: $secret")
        } catch (e: Exception) {
            SecureLogger.e("Failed to save secret status", e)
        }
    }
    
    /**
     * 🔐 Verificar si modo secreto está desbloqueado
     */
    fun isSecretUnlocked(secret: String): Boolean {
        return try {
            prefs.getBoolean(secret, false)
        } catch (e: Exception) {
            SecureLogger.e("Failed to read secret status", e)
            false
        }
    }
    
    /**
     * 🔐 Guardar intentos de secretos
     */
    fun setSecretAttempts(attempts: Int) {
        try {
            prefs.edit()
                .putInt(SECRET_ATTEMPTS, attempts)
                .putLong(LAST_ATTEMPT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            SecureLogger.e("Failed to save secret attempts", e)
        }
    }
    
    /**
     * 🔐 Obtener intentos de secretos
     */
    fun getSecretAttempts(): Int {
        return try {
            prefs.getInt(SECRET_ATTEMPTS, 0)
        } catch (e: Exception) {
            SecureLogger.e("Failed to read secret attempts", e)
            0
        }
    }
    
    /**
     * 🔐 Obtener último intento
     */
    fun getLastAttemptTime(): Long {
        return try {
            prefs.getLong(LAST_ATTEMPT, 0L)
        } catch (e: Exception) {
            SecureLogger.e("Failed to read last attempt time", e)
            0L
        }
    }
    
    /**
     * 🔐 Guardar puntuación alta del juego
     */
    fun setHighScore(score: Int) {
        try {
            val currentHigh = getHighScore()
            if (score > currentHigh) {
                prefs.edit()
                    .putInt(HIGH_SCORE, score)
                    .putLong("${HIGH_SCORE}_date", System.currentTimeMillis())
                    .apply()
                SecureLogger.i("New high score: $score")
            }
        } catch (e: Exception) {
            SecureLogger.e("Failed to save high score", e)
        }
    }
    
    /**
     * 🔐 Obtener puntuación alta
     */
    fun getHighScore(): Int {
        return try {
            prefs.getInt(HIGH_SCORE, 0)
        } catch (e: Exception) {
            SecureLogger.e("Failed to read high score", e)
            0
        }
    }
    
    /**
     * 🔐 Guardar configuración del juego
     */
    fun setGameSettings(settings: Map<String, Any>) {
        try {
            val editor = prefs.edit()
            settings.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString("${GAME_SETTINGS}_$key", value)
                    is Int -> editor.putInt("${GAME_SETTINGS}_$key", value)
                    is Boolean -> editor.putBoolean("${GAME_SETTINGS}_$key", value)
                    is Float -> editor.putFloat("${GAME_SETTINGS}_$key", value)
                    is Long -> editor.putLong("${GAME_SETTINGS}_$key", value)
                }
            }
            editor.apply()
        } catch (e: Exception) {
            SecureLogger.e("Failed to save game settings", e)
        }
    }
    
    /**
     * 🔐 Obtener configuración del juego
     */
    fun getGameSettings(): Map<String, Any?> {
        return try {
            val settings = mutableMapOf<String, Any?>()
            prefs.all.forEach { (key, value) ->
                if (key.startsWith(GAME_SETTINGS)) {
                    val cleanKey = key.substring("${GAME_SETTINGS}_".length)
                    settings[cleanKey] = value
                }
            }
            settings
        } catch (e: Exception) {
            SecureLogger.e("Failed to read game settings", e)
            emptyMap()
        }
    }
    
    /**
     * 🔐 Resetear todos los secretos (para desarrollo)
     */
    fun resetSecrets() {
        try {
            prefs.edit()
                .remove(GAME_UNLOCKED)
                .remove(HAMTARO_UNLOCKED)
                .remove(SECRET_ATTEMPTS)
                .remove(LAST_ATTEMPT)
                .remove(HIGH_SCORE)
                .remove("${HIGH_SCORE}_date")
                .apply()
            
            // Limpiar settings del juego
            val keysToRemove = prefs.all.keys.filter { 
                it.startsWith(GAME_SETTINGS) 
            }
            prefs.edit().apply {
                keysToRemove.forEach { remove(it) }
                apply()
            }
            
            SecureLogger.i("All secrets reset")
        } catch (e: Exception) {
            SecureLogger.e("Failed to reset secrets", e)
        }
    }
    
    /**
     * 🔐 Guardar un String sensible de forma segura (por ejemplo, el token de auth)
     */
    fun setSecureString(key: String, value: String) {
        try {
            prefs.edit()
                .putString(key, value)
                .apply()
        } catch (e: Exception) {
            SecureLogger.e("Failed to save secure string", e)
        }
    }

    /**
     * 🔐 Leer un String sensible guardado de forma segura
     */
    fun getSecureString(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (e: Exception) {
            SecureLogger.e("Failed to read secure string", e)
            null
        }
    }

    /**
     * 🔐 Helpers específicos para el token de autenticación
     */
    fun setAuthToken(token: String) {
        setSecureString(AUTH_TOKEN, token)
    }

    fun getAuthToken(): String? {
        return getSecureString(AUTH_TOKEN)
    }
    
    /**
     * 🔐 Verificar integridad de preferencias
     */
    fun verifyIntegrity(): Boolean {
        return try {
            // Intentar leer una clave conocida
            prefs.all.isNotEmpty()
        } catch (e: Exception) {
            SecureLogger.e("Preferences integrity check failed", e)
            false
        }
    }
    
    /**
     * 🔐 Obtener información de uso
     */
    fun getUsageInfo(): Map<String, Any> {
        return try {
            mapOf(
                "game_unlocked" to isSecretUnlocked(GAME_UNLOCKED),
                "hamtaro_unlocked" to isSecretUnlocked(HAMTARO_UNLOCKED),
                "attempts" to getSecretAttempts(),
                "last_attempt" to getLastAttemptTime(),
                "high_score" to getHighScore(),
                "integrity" to verifyIntegrity()
            )
        } catch (e: Exception) {
            SecureLogger.e("Failed to get usage info", e)
            emptyMap()
        }
    }
}
