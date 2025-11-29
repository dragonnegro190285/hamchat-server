package com.hamtaro.hamchat.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.hamtaro.hamchat.security.SecureLogger

/**
 * Gestor de inactividad global para Ham-Chat
 * Cierra la aplicación automáticamente cuando no hay interacción del usuario
 * para ahorrar batería en dispositivos como Sharp Keitai 4
 */
object InactivityManager : Application.ActivityLifecycleCallbacks {

    // Tiempo de inactividad antes de cerrar (5 minutos por defecto)
    private const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
    
    private var timeoutMs: Long = DEFAULT_TIMEOUT_MS
    private val handler = Handler(Looper.getMainLooper())
    private var isInitialized = false
    private var currentActivity: Activity? = null
    private var isPaused = false
    
    private val timeoutRunnable = Runnable {
        onInactivityTimeout()
    }

    /**
     * Inicializar el gestor de inactividad
     * Llamar desde Application.onCreate()
     */
    fun init(application: Application, timeoutMinutes: Int = 5) {
        if (isInitialized) return
        
        timeoutMs = timeoutMinutes * 60 * 1000L
        application.registerActivityLifecycleCallbacks(this)
        isInitialized = true
        
        SecureLogger.i("InactivityManager initialized with ${timeoutMinutes}min timeout")
    }

    /**
     * Llamar cuando el usuario interactúa con la app
     * (click, tecla, scroll, etc.)
     */
    fun onUserInteraction() {
        resetTimer()
    }

    /**
     * Reiniciar el temporizador de inactividad
     */
    fun resetTimer() {
        handler.removeCallbacks(timeoutRunnable)
        if (!isPaused) {
            handler.postDelayed(timeoutRunnable, timeoutMs)
        }
    }

    /**
     * Pausar el temporizador (cuando la app está en segundo plano)
     */
    fun pauseTimer() {
        isPaused = true
        handler.removeCallbacks(timeoutRunnable)
    }

    /**
     * Reanudar el temporizador (cuando la app vuelve al primer plano)
     */
    fun resumeTimer() {
        isPaused = false
        resetTimer()
    }

    /**
     * Cambiar el tiempo de inactividad
     */
    fun setTimeoutMinutes(minutes: Int) {
        timeoutMs = minutes * 60 * 1000L
        resetTimer()
    }

    /**
     * Obtener el tiempo de inactividad actual en minutos
     */
    fun getTimeoutMinutes(): Int {
        return (timeoutMs / 60000).toInt()
    }

    /**
     * Acción cuando se detecta inactividad
     */
    private fun onInactivityTimeout() {
        SecureLogger.i("Inactivity timeout - closing app to save battery")
        
        currentActivity?.let { activity ->
            try {
                Toast.makeText(
                    activity,
                    "🔋 Cerrando Ham-Chat por inactividad (ahorro de batería)",
                    Toast.LENGTH_LONG
                ).show()
                
                // Pequeño delay para que se vea el toast
                handler.postDelayed({
                    activity.finishAffinity()
                }, 1500)
            } catch (e: Exception) {
                SecureLogger.e("Error closing app: ${e.message}")
                activity.finishAffinity()
            }
        }
    }

    // ========== ActivityLifecycleCallbacks ==========

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // No hacer nada
    }

    override fun onActivityStarted(activity: Activity) {
        // No hacer nada
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        resumeTimer()
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity == activity) {
            pauseTimer()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        // No hacer nada
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // No hacer nada
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) {
            currentActivity = null
        }
    }
}
