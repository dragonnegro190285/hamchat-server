package com.hamtaro.hamchat.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.hamchat.utils.InactivityManager

/**
 * Activity base para Ham-Chat
 * Detecta interacción del usuario y reinicia el temporizador de inactividad
 * Todas las actividades deben extender esta clase para el ahorro de batería
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // El InactivityManager ya está inicializado en Application
    }

    /**
     * Detecta cualquier interacción del usuario (touch, click)
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        InactivityManager.onUserInteraction()
    }

    /**
     * Detecta eventos de teclado/keypad
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        InactivityManager.onUserInteraction()
        return super.dispatchKeyEvent(event)
    }

    /**
     * Detecta eventos táctiles
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        InactivityManager.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }
}
