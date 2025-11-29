package com.hamtaro.hamchat.security

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.util.regex.Pattern

/**
 * 🔒 IntentValidator for Ham-Chat
 * Prevents intent injection and spoofing attacks
 */
class IntentValidator {
    
    companion object {
        private const val TAG = "IntentValidator"
        
        // Allowed intent actions
        private val ALLOWED_ACTIONS = setOf(
            Intent.ACTION_MAIN,
            Intent.ACTION_VIEW,
            "com.hamtaro.hamchat.ACTION_SECURE_MESSAGE"
        )
        
        // Blocked URI patterns
        private val BLOCKED_URI_PATTERNS = listOf(
            Pattern.compile("file://.*"),
            Pattern.compile("content://.*"),
            Pattern.compile("http://.*"),
            Pattern.compile("ftp://.*")
        )
        
        // Allowed schemes (solo https ahora, tox deshabilitado)
        private val ALLOWED_SCHEMES = setOf("https")
        
        // Max bundle size to prevent DoS
        private const val MAX_BUNDLE_SIZE = 1024 * 1024 // 1MB
    }
    
    /**
     * 🔒 Validate incoming intent
     */
    fun validateIntent(intent: Intent?): ValidationResult {
        if (intent == null) {
            Log.w(TAG, "Null intent received")
            return ValidationResult(false, "Null intent")
        }
        
        // Check action
        if (!isValidAction(intent.action)) {
            Log.w(TAG, "Invalid action: ${intent.action}")
            return ValidationResult(false, "Invalid action")
        }
        
        // Check component
        if (!isValidComponent(intent.component)) {
            Log.w(TAG, "Invalid component: ${intent.component}")
            return ValidationResult(false, "Invalid component")
        }
        
        // Check data URI
        if (!isValidDataUri(intent.data)) {
            Log.w(TAG, "Invalid data URI: ${intent.data}")
            return ValidationResult(false, "Invalid data URI")
        }
        
        // Check extras
        val extrasResult = validateExtras(intent.extras)
        if (!extrasResult.isValid) {
            Log.w(TAG, "Invalid extras: ${extrasResult.error}")
            return extrasResult
        }
        
        return ValidationResult(true, "Intent is valid")
    }
    
    /**
     * 🔒 Validate intent action
     *
     * Permite acciones nulas (intents explícitos internos como el lanzamiento desde SplashActivity)
     * y restringe únicamente las acciones no nulas a la lista permitida.
     */
    private fun isValidAction(action: String?): Boolean {
        if (action == null) return true
        return ALLOWED_ACTIONS.contains(action)
    }
    
    /**
     * 🔒 Validate component
     */
    private fun isValidComponent(component: ComponentName?): Boolean {
        if (component == null) return true
        
        return component.packageName == "com.hamtaro.hamchat" &&
               component.className.startsWith("com.hamtaro.hamchat")
    }
    
    /**
     * 🔒 Validate data URI
     */
    private fun isValidDataUri(uri: Uri?): Boolean {
        if (uri == null) return true
        
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && !ALLOWED_SCHEMES.contains(scheme)) {
            return false
        }
        
        // Check against blocked patterns
        val uriString = uri.toString()
        for (pattern in BLOCKED_URI_PATTERNS) {
            if (pattern.matcher(uriString).matches()) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * 🔒 Validate bundle extras
     */
    private fun validateExtras(extras: Bundle?): ValidationResult {
        if (extras == null) return ValidationResult(true, "No extras")
        
        // Check bundle size
        val bundleSize = calculateBundleSize(extras)
        if (bundleSize > MAX_BUNDLE_SIZE) {
            return ValidationResult(false, "Bundle too large: $bundleSize bytes")
        }
        
        // Validate each extra
        for (key in extras.keySet()) {
            val value = extras.get(key)
            if (!isValidExtraValue(key, value)) {
                return ValidationResult(false, "Invalid extra: $key")
            }
        }
        
        return ValidationResult(true, "Extras are valid")
    }
    
    /**
     * 🔒 Calculate bundle size
     */
    private fun calculateBundleSize(bundle: Bundle): Int {
        var size = 0
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            size += key.toByteArray().size
            size += getValueSize(value)
        }
        return size
    }
    
    /**
     * 🔒 Get value size
     */
    private fun getValueSize(value: Any?): Int {
        return when (value) {
            null -> 0
            is String -> value.toByteArray().size
            is ByteArray -> value.size
            is Int -> 4
            is Long -> 8
            is Float -> 4
            is Double -> 8
            is Boolean -> 1
            else -> value.toString().toByteArray().size
        }
    }
    
    /**
     * 🔒 Validate extra value
     */
    private fun isValidExtraValue(key: String, value: Any?): Boolean {
        if (value == null) return true
        
        // Check key length
        if (key.length > 100) return false
        
        // Check value based on type
        return when (value) {
            is String -> isValidStringValue(value)
            is ByteArray -> isValidByteArray(value)
            is Int -> isValidInt(value)
            is Long -> isValidLong(value)
            is Float -> isValidFloat(value)
            is Double -> isValidDouble(value)
            is Boolean -> true
            else -> false
        }
    }
    
    /**
     * 🔒 Validate string value
     */
    private fun isValidStringValue(value: String): Boolean {
        // Check length
        if (value.length > 10000) return false
        
        // Check for dangerous characters
        if (value.contains(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"))) {
            return false
        }
        
        // Check for script injection
        if (value.contains(Regex("(?i)<script|javascript:|vbscript:|onload=|onerror="))) {
            return false
        }
        
        return true
    }
    
    /**
     * 🔒 Validate byte array
     */
    private fun isValidByteArray(value: ByteArray): Boolean {
        // Check size
        if (value.size > 1024 * 1024) return false // 1MB max
        
        // Check for null bytes in inappropriate places
        return !value.contains(0.toByte())
    }
    
    /**
     * 🔒 Validate int value
     */
    private fun isValidInt(value: Int): Boolean {
        return value in Int.MIN_VALUE..Int.MAX_VALUE
    }
    
    /**
     * 🔒 Validate long value
     */
    private fun isValidLong(value: Long): Boolean {
        return value in Long.MIN_VALUE..Long.MAX_VALUE
    }
    
    /**
     * 🔒 Validate float value
     */
    private fun isValidFloat(value: Float): Boolean {
        return !value.isNaN() && !value.isInfinite()
    }
    
    /**
     * 🔒 Validate double value
     */
    private fun isValidDouble(value: Double): Boolean {
        return !value.isNaN() && !value.isInfinite()
    }
    
    /**
     * 🔒 Sanitize intent
     */
    fun sanitizeIntent(intent: Intent): Intent {
        val sanitized = Intent(intent)
        
        // Remove dangerous extras
        sanitized.extras?.keySet()?.forEach { key ->
            val value = sanitized.extras?.get(key)
            if (!isValidExtraValue(key, value)) {
                sanitized.removeExtra(key)
            }
        }
        
        // Sanitize action
        if (!isValidAction(intent.action)) {
            sanitized.action = Intent.ACTION_MAIN
        }
        
        // Sanitize data
        if (!isValidDataUri(intent.data)) {
            sanitized.data = null
        }
        
        return sanitized
    }
    
    /**
     * 🔒 ValidationResult
     */
    data class ValidationResult(
        val isValid: Boolean,
        val error: String = ""
    )
}
