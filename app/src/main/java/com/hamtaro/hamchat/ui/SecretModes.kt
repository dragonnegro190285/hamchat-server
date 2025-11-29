package com.hamtaro.hamchat.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import com.hamtaro.hamchat.security.SecurityManager
import com.hamtaro.hamchat.security.SecurePreferences

/**
 * 🎮 Modos Secretos de Ham-Chat
 * Solo el juego Game & Watch es secreto
 * El tema Hamtaro es el tema PRINCIPAL (no secreto)
 */
class SecretModes(private val context: Context) {
    
    private val securityManager = SecurityManager(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("HamChatSecrets", Context.MODE_PRIVATE)
    private val securePrefs = SecurePreferences(context)
    
    companion object {
        // 🎮 Konami Code para Game & Watch (ÚNICO SECRETO)
        private const val KONAMI_CODE = "UP_UP_DOWN_DOWN_LEFT_RIGHT_LEFT_RIGHT_22"
        
        // 🔐 Claves para SharedPreferences
        private const val GAME_UNLOCKED = "game_mode_unlocked"
        private const val SECRET_ATTEMPTS = "secret_attempts"
        
        // 🎮 Configuración Game & Watch
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_TIME = 300000L // 5 minutos
    }
    
    private var konamiSequence = mutableListOf<String>()
    private var hamtaroInput = ""
    private var lastAttemptTime = 0L
    
    /**
     * 🔍 Procesar input para modos secretos
     */
    fun processSecretInput(input: String, type: SecretInputType): SecretResult {
        return when (type) {
            SecretInputType.KEYCODE -> processKeyCode(input)
            SecretInputType.TEXT -> processTextInput(input)
        }
    }
    
    /**
     * 🎮 Procesar códigos de teclado (Konami)
     */
    private fun processKeyCode(keyCode: String): SecretResult {
        val currentTime = System.currentTimeMillis()
        
        // 🔐 Rate limiting
        if (currentTime - lastAttemptTime < LOCKOUT_TIME && !canAttemptSecret()) {
            return SecretResult(false, "Demasiados intentos. Espera 5 minutos.")
        }
        
        // 🎮 Agregar a secuencia
        when (keyCode) {
            "UP" -> konamiSequence.add("UP")
            "DOWN" -> konamiSequence.add("DOWN")
            "LEFT" -> konamiSequence.add("LEFT")
            "RIGHT" -> konamiSequence.add("RIGHT")
            "22" -> konamiSequence.add("22")
        }
        
        // 🎮 Mantener solo últimos 10 elementos
        if (konamiSequence.size > 10) {
            konamiSequence = konamiSequence.takeLast(10).toMutableList()
        }
        
        // 🎮 Verificar Konami Code
        val currentSequence = konamiSequence.joinToString("_")
        if (currentSequence.contains(KONAMI_CODE)) {
            return unlockGameMode()
        }
        
        lastAttemptTime = currentTime
        return SecretResult(false, "Sigue intentando...")
    }
    
    /**
     * 🎨 Procesar input de texto
     * Ya no se usa para desbloquear tema (el tema Hamtaro es el principal)
     */
    private fun processTextInput(text: String): SecretResult {
        // El tema Hamtaro ya no es secreto, es el tema principal
        return SecretResult(false, "")
    }
    
    /**
     * 🎮 Desbloquear modo Game & Watch
     */
    private fun unlockGameMode(): SecretResult {
        if (!canAttemptSecret()) {
            return SecretResult(false, "Demasiados intentos. Espera 5 minutos.")
        }
        
        // 🔐 Verificar si ya está desbloqueado
        if (securePrefs.isSecretUnlocked(GAME_UNLOCKED)) {
            return SecretResult(true, "🎮 ¡Modo Game & Watch ya activado!")
        }
        
        try {
            // 🎮 Activar modo juego
            securePrefs.setSecretUnlocked(GAME_UNLOCKED, true)
            securePrefs.setSecretAttempts(0)
            
            securityManager.logSecurityEvent("SECRET_GAME_UNLOCKED", "Konami Code success")
            
            // 🎮 Limpiar secuencia
            konamiSequence.clear()
            
            return SecretResult(true, "🎮 ¡Modo Game & Watch desbloqueado! Jugando voleibol Hamtaro...")
            
        } catch (e: Exception) {
            securityManager.logSecurityEvent("SECRET_GAME_ERROR", e.message ?: "")
            recordFailedAttempt()
            return SecretResult(false, "Error al desbloquear modo juego")
        }
    }
    
    /**
     * 🎨 El tema Hamtaro es el tema PRINCIPAL
     * No necesita desbloquearse, siempre está disponible
     */
    fun isHamtaroThemeEnabled(): Boolean {
        // El tema Hamtaro siempre está habilitado (es el tema principal)
        return true
    }
    
    /**
     * 🔐 Verificar si puede intentar secretos
     */
    private fun canAttemptSecret(): Boolean {
        val attempts = securePrefs.getSecretAttempts()
        val currentTime = System.currentTimeMillis()
        val lastAttempt = securePrefs.getLastAttemptTime()
        
        // Reset después de 5 minutos
        if (currentTime - lastAttempt > LOCKOUT_TIME) {
            securePrefs.setSecretAttempts(0)
            return true
        }
        
        return attempts < MAX_ATTEMPTS
    }
    
    /**
     * 📝 Registrar intento fallido
     */
    private fun recordFailedAttempt() {
        val attempts = securePrefs.getSecretAttempts() + 1
        securePrefs.setSecretAttempts(attempts)
    }
    
    /**
     * 🎮 Verificar si modo juego está desbloqueado
     */
    fun isGameModeUnlocked(): Boolean {
        return securePrefs.isSecretUnlocked(GAME_UNLOCKED)
    }
    
    /**
     * 🎨 El tema Hamtaro siempre está disponible (es el tema principal)
     */
    fun isHamtaroThemeUnlocked(): Boolean {
        // Siempre true - el tema Hamtaro es el tema principal, no secreto
        return true
    }
    
    /**
     * 🎮 Iniciar modo Game & Watch
     */
    fun startGameMode(): GameModeResult {
        if (!isGameModeUnlocked()) {
            return GameModeResult(false, "Modo no desbloqueado")
        }
        
        try {
            // 🎮 Iniciar juego de voleibol
            securityManager.logSecurityEvent("GAME_MODE_STARTED", "Voleibol Hamtaro")
            return GameModeResult(true, "🏐 ¡Voleibol Hamtaro iniciado!")
        } catch (e: Exception) {
            securityManager.logSecurityEvent("GAME_MODE_ERROR", e.message ?: "")
            return GameModeResult(false, "Error al iniciar juego")
        }
    }
    
    /**
     * 🎨 El tema Hamtaro es el tema principal
     * Siempre está activo, no necesita activación especial
     */
    fun activateHamtaroTheme(): ThemeResult {
        // El tema Hamtaro siempre está disponible
        return ThemeResult(true, "🐹 Tema Hamtaro activo - Es el tema principal de la app")
    }
    
    /**
     * 🔍 Obtener pistas para el juego secreto
     * (El tema Hamtaro no necesita pistas, es el tema principal)
     */
    fun getSecretHints(): SecretHints {
        return SecretHints(
            gameHint = if (isGameModeUnlocked()) {
                "🎮 ¡Ya desbloqueado! Usa ↑↑↓↓←→22"
            } else {
                "🎮 Piensa en los clásicos... ↑↑↓↓←→?"
            },
            themeHint = "🎨 Tema Hamtaro siempre activo 🐹",
            attemptsRemaining = MAX_ATTEMPTS - securePrefs.getSecretAttempts()
        )
    }
    
    /**
     * 🔓 Resetear secretos (para desarrollo)
     */
    fun resetSecrets() {
        securePrefs.resetSecrets()
        
        konamiSequence.clear()
        hamtaroInput = ""
        
        securityManager.logSecurityEvent("SECRETS_RESET", "Developer reset")
    }
}

/**
 * 🎮 Tipos de input secreto
 */
enum class SecretInputType {
    KEYCODE,    // Para Konami Code
    TEXT        // Para tema Hamtaro
}

/**
 * 🔍 Resultado de intento secreto
 */
data class SecretResult(
    val success: Boolean,
    val message: String
)

/**
 * 🎮 Resultado de modo juego
 */
data class GameModeResult(
    val success: Boolean,
    val message: String
)

/**
 * 🎨 Resultado de tema
 */
data class ThemeResult(
    val success: Boolean,
    val message: String
)

/**
 * 🔍 Pistas para secretos
 */
data class SecretHints(
    val gameHint: String,
    val themeHint: String,
    val attemptsRemaining: Int
)
