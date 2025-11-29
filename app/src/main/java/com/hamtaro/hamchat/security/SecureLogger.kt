package com.hamtaro.hamchat.security

import android.util.Log

/**
 * 🔒 Secure Logger para Ham-Chat
 * Previene exposición de datos sensibles en logs
 */
object SecureLogger {
    
    private const val TAG = "HamChat"
    
    /**
     * 🔒 Debug logging seguro
     */
    fun d(message: String, sensitiveData: String? = null) {
        val safeMessage = if (sensitiveData != null) {
            "$message [REDACTED]"
        } else {
            message
        }
        Log.d(TAG, safeMessage)
    }
    
    /**
     * 🔒 Logging de datos sensibles
     */
    fun sensitive(operation: String, data: String) {
        val safeData = if (data.length > 6) {
            "${'$'}{data.take(3)}...${'$'}{data.takeLast(3)}"
        } else {
            "***"
        }
        Log.d(TAG, "$operation: $safeData")
    }
    
    /**
     * 🔒 Warning logging seguro
     */
    fun w(message: String, details: String? = null) {
        val safeMessage = if (details != null) {
            "$message - ${'$'}{details.take(20)}..."
        } else {
            message
        }
        Log.w(TAG, safeMessage)
    }
    
    /**
     * 🔒 Error logging sin stack traces
     */
    fun e(message: String, error: Throwable? = null) {
        val safeMessage = if (error != null) {
            "$message - ${'$'}{error.javaClass.simpleName}"
        } else {
            message
        }
        Log.e(TAG, safeMessage)
    }
    
    /**
     * 🔒 Info logging seguro
     */
    fun i(message: String) {
        Log.i(TAG, message)
    }
    
    /**
     * 🔒 Verbose logging deshabilitado en producción
     */
    fun v(message: String) {
        // Nunca log verbose en producción
        Log.v(TAG, message)
    }
}
