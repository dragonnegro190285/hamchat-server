package com.hamtaro.hamchat.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.security.SecureLogger

/**
 * 🌟 Splash Activity con Alice in Wonderland Font
 * Pantalla de presentación mágica con fuente decorativa
 */
class SplashActivity : AppCompatActivity() {
    
    private lateinit var titleTextView: TextView
    private lateinit var subtitleTextView: TextView
    private lateinit var loadingTextView: TextView
    private lateinit var progressBar: ProgressBar
    
    private val splashDelay = 3000L // 3 segundos
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        // Inicializar componentes UI
        initializeUI()
        
        // Aplicar animaciones
        applyAnimations()
        
        // Iniciar splash con delay
        startSplashSequence()
    }
    
    /**
     * 🎨 Inicializar componentes UI
     */
    private fun initializeUI() {
        titleTextView = findViewById(R.id.tv_splash_title)
        subtitleTextView = findViewById(R.id.tv_splash_subtitle)
        loadingTextView = findViewById(R.id.tv_splash_loading)
        progressBar = findViewById(R.id.progress_splash)
        
        SecureLogger.i("SplashActivity initialized with Alice in Wonderland font")
    }
    
    /**
     * ✨ Aplicar animaciones mágicas
     */
    private fun applyAnimations() {
        // Animación de fade-in para el logo
        // Animación de fade-in para el título (con delay)
        val titleAnimation = AlphaAnimation(0f, 1f).apply {
            duration = 1000
            startOffset = 500
            fillAfter = true
        }
        titleTextView.startAnimation(titleAnimation)
        
        // Animación de fade-in para el subtítulo (con delay)
        val subtitleAnimation = AlphaAnimation(0f, 1f).apply {
            duration = 1000
            startOffset = 1000
            fillAfter = true
        }
        subtitleTextView.startAnimation(subtitleAnimation)
        
        // Animación de fade-in para el loading (con delay)
        val loadingAnimation = AlphaAnimation(0f, 1f).apply {
            duration = 1000
            startOffset = 1500
            fillAfter = true
        }
        loadingTextView.startAnimation(loadingAnimation)
        progressBar.startAnimation(loadingAnimation)
    }
    
    /**
     * 🚀 Iniciar secuencia de splash
     */
    private fun startSplashSequence() {
        // Mensajes de carga mágicos
        val loadingMessages = arrayOf(
            "Preparando la magia Hamtaro...",
            "Abriendo el mundo de las maravillas...",
            "Cargando fuentes encantadas...",
            "Iniciando la aventura...",
            "Casi listo para la magia..."
        )
        
        var messageIndex = 0
        val handler = Handler(Looper.getMainLooper())
        
        // Cambiar mensajes de carga cada 600ms
        val messageRunnable = object : Runnable {
            override fun run() {
                if (messageIndex < loadingMessages.size) {
                    loadingTextView.text = loadingMessages[messageIndex]
                    messageIndex++
                    handler.postDelayed(this, 600)
                }
            }
        }
        handler.post(messageRunnable)
        
        // Navegar a la actividad principal después del splash
        handler.postDelayed({
            navigateToMain()
        }, splashDelay)
    }
    
    /**
     * 📱 Navegar a la actividad principal
     */
    private fun navigateToMain() {
        try {
            val intent = Intent(this, com.hamtaro.hamchat.MainActivity::class.java)
            startActivity(intent)
            finish()
            
            // Animación de transición suave
            overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            
            SecureLogger.i("Navigation to MainActivity completed")
        } catch (e: Exception) {
            SecureLogger.e("Error navigating to MainActivity", e)
            // En caso de error, cerrar la app
            finish()
        }
    }
    
    /**
     * 🔄 Manejar el botón de atrás durante el splash
     */
    override fun onBackPressed() {
        // Prevenir que el usuario salga durante el splash
        // Opcional: mostrar mensaje "Por favor espera..."
        super.onBackPressed()
    }
    
    /**
     * 🧹 Cleanup
     */
    override fun onDestroy() {
        super.onDestroy()
        // Limpiar animaciones y handlers
        titleTextView.clearAnimation()
        subtitleTextView.clearAnimation()
        loadingTextView.clearAnimation()
        progressBar.clearAnimation()
        
        SecureLogger.i("SplashActivity destroyed")
    }
}
