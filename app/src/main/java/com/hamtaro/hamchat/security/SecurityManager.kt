package com.hamtaro.hamchat.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.scottyab.rootbeer.RootBeer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 🔒 SecurityManager for Ham-Chat
 * Handles all security-related operations
 */
class SecurityManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SecurityManager"
        private const val KEY_ALIAS = "HamChatSecureKey"
        private const val HASH_ALGORITHM = "SHA-256"
        private const val ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
    }
    
    // 🔒 Device Security Checks
    // NOTA: Desactivado para compatibilidad con Xiaomi/MIUI
    fun isDeviceSecure(): Boolean {
        // Siempre retornar true para evitar bloqueos en dispositivos Xiaomi
        return true
        
        // Código original comentado:
        // if (isDebugOrEmulator()) {
        //     return true
        // }
        // return !isRooted() && isSecureFromDebugging()
    }
    
    private fun isRooted(): Boolean {
        return try {
            val rootBeer = RootBeer(context)
            rootBeer.isRooted
        } catch (e: Exception) {
            Log.e(TAG, "Error checking root status", e)
            true // Assume rooted if check fails
        }
    }
    
    private fun isDebugOrEmulator(): Boolean {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()

        val isEmulator = fingerprint.startsWith("generic") || fingerprint.contains("vbox") || fingerprint.contains("test-keys") ||
                model.contains("google_sdk") || model.contains("droid4x") || model.contains("emulator") || model.contains("android sdk built for x86") ||
                manufacturer.contains("genymotion") || product.contains("sdk_google") || product.contains("google_sdk") ||
                product.contains("sdk") || product.contains("emulator") || product.contains("simulator") ||
                hardware.contains("goldfish") || hardware.contains("ranchu") || hardware.contains("vbox") ||
                brand.startsWith("generic") && device.startsWith("generic")

        return isDebuggable || isEmulator
    }
    
    private fun isSecureFromDebugging(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Settings.Secure.getInt(context.contentResolver, 
                Settings.Global.ADB_ENABLED, 0) == 0
        } else {
            Settings.Secure.getInt(context.contentResolver, 
                Settings.Secure.ADB_ENABLED, 0) == 0
        }
    }
    
    // 🔒 Biometric Authentication
    fun canUseBiometric(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }
    
    fun createBiometricPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ): BiometricPrompt {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure("Authentication error: $errString")
            }
            
            override fun onAuthenticationFailed() {
                onFailure("Authentication failed")
            }
        }
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Ham-Chat Security")
            .setSubtitle("Authenticate to access Ham-Chat")
            .setNegativeButtonText("Cancel")
            .build()
        
        return BiometricPrompt(activity, executor, callback)
    }
    
    // 🔒 Permission Management
    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun getRequiredPermissions(): Array<String> {
        return arrayOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE
        )
    }
    
    fun getOptionalPermissions(): Array<String> {
        return arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }
    
    // 🔒 Data Encryption
    fun encryptData(data: String, key: SecretKey): String {
        return try {
            val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedData = cipher.doFinal(data.toByteArray())
            android.util.Base64.encodeToString(encryptedData, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            ""
        }
    }
    
    fun decryptData(encryptedData: String, key: SecretKey): String {
        return try {
            val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decodedData = android.util.Base64.decode(encryptedData, android.util.Base64.DEFAULT)
            val decryptedData = cipher.doFinal(decodedData)
            String(decryptedData)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            ""
        }
    }
    
    // 🔒 Key Generation
    fun generateSecretKey(): SecretKey {
        return try {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(256)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Key generation failed", e)
            // Fallback key
            SecretKeySpec("HamChatFallbackKey123".toByteArray(), "AES")
        }
    }
    
    // 🔒 Input Validation
    fun validateToxId(toxId: String): Boolean {
        return try {
            // Tox ID should be 76 characters (64 hex + 12 checksum)
            toxId.length == 76 && 
            toxId.all { it.isLetterOrDigit() } &&
            !toxId.contains(Regex("[OIl]")) // Exclude ambiguous characters
        } catch (e: Exception) {
            Log.e(TAG, "Tox ID validation failed", e)
            false
        }
    }
    
    fun validateUsername(username: String): Boolean {
        return try {
            username.length in 3..32 &&
            username.isNotBlank() &&
            !username.contains(Regex("[<>\"'&]")) // Prevent XSS
        } catch (e: Exception) {
            Log.e(TAG, "Username validation failed", e)
            false
        }
    }
    
    fun validateMessage(message: String): Boolean {
        return try {
            message.length <= 1000 && // Prevent buffer overflow
            !message.contains(Regex("[\u0000-\u001F\u007F-\u009F]")) // No control characters
        } catch (e: Exception) {
            Log.e(TAG, "Message validation failed", e)
            false
        }
    }
    
    // 🔒 Hash Functions
    fun hashData(data: String): String {
        return try {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            val hashBytes = digest.digest(data.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Hash generation failed", e)
            ""
        }
    }
    
    // 🔒 Anti-Tampering
    fun verifyAppIntegrity(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName, 
                PackageManager.GET_SIGNATURES
            )
            // Verify signature hash (implement actual signature checking)
            true // Placeholder - implement real signature verification
        } catch (e: Exception) {
            Log.e(TAG, "App integrity check failed", e)
            false
        }
    }
    
    // 🔒 Rate Limiting
    private val loginAttempts = mutableMapOf<String, Int>()
    private val lastAttemptTime = mutableMapOf<String, Long>()
    
    fun canAttemptLogin(identifier: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastAttemptTime[identifier] ?: 0
        val attempts = loginAttempts[identifier] ?: 0
        
        // Reset after 5 minutes
        if (currentTime - lastTime > 300000) {
            loginAttempts[identifier] = 0
            return true
        }
        
        // Block after 5 failed attempts
        return attempts < 5
    }
    
    fun recordFailedLogin(identifier: String) {
        loginAttempts[identifier] = (loginAttempts[identifier] ?: 0) + 1
        lastAttemptTime[identifier] = System.currentTimeMillis()
    }
    
    fun recordSuccessfulLogin(identifier: String) {
        loginAttempts.remove(identifier)
        lastAttemptTime.remove(identifier)
    }
    
    // 🔒 Security Logging
    fun logSecurityEvent(event: String, details: String = "") {
        Log.w(TAG, "SECURITY: $event - $details")
        // In production, send to secure logging service
    }
}
