package com.hamtaro.toxmessenger

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.toxmessenger.utils.KonamiCodeDetector

class LoginActivity : AppCompatActivity() {
    
    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var konamiCodeDetector: KonamiCodeDetector
    private var inputSequence = mutableListOf<String>()
    private var isUsernameFocused = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        
        konamiCodeDetector = KonamiCodeDetector()
        
        initViews()
        setupListeners()
    }
    
    private fun initViews() {
        usernameEditText = findViewById(R.id.username_edittext)
        passwordEditText = findViewById(R.id.password_edittext)
        loginButton = findViewById(R.id.login_button)
    }
    
    private fun setupListeners() {
        usernameEditText.setOnFocusChangeListener { _, hasFocus ->
            isUsernameFocused = hasFocus
        }
        
        loginButton.setOnClickListener {
            performLogin()
        }
        
        // Handle key events for Konami code
        usernameEditText.setOnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }
        
        passwordEditText.setOnKeyListener { _, keyCode, event ->
            handleKeyEvent(keyCode, event)
        }
    }
    
    private fun handleKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) {
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "UP"
                KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
                KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
                KeyEvent.KEYCODE_2 -> "2"
                KeyEvent.KEYCODE_B -> "B"
                KeyEvent.KEYCODE_A -> "A"
                else -> null
            }
            
            direction?.let {
                inputSequence.add(it)
                
                // Check for Konami code
                if (konamiCodeDetector.checkSequence(inputSequence)) {
                    startSecretGame()
                    inputSequence.clear()
                    return true
                }
                
                // Keep only last 8 inputs
                if (inputSequence.size > 8) {
                    inputSequence.removeAt(0)
                }
            }
        }
        
        return false
    }
    
    private fun performLogin() {
        val username = usernameEditText.text.toString()
        val password = passwordEditText.text.toString()
        
        if (username.isNotEmpty() && password.isNotEmpty()) {
            // Start main activity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
    
    private fun startSecretGame() {
        val intent = Intent(this, SecretGameActivity::class.java)
        startActivity(intent)
    }
}
