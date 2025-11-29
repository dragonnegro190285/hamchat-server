package com.hamtaro.hamchat

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.hamchat.game.GameWatchActivity
import com.hamtaro.hamchat.server.LocalDatabase
import com.hamtaro.hamchat.ui.SecretModes
import com.hamtaro.hamchat.ui.SecretInputType

class LoginActivity : AppCompatActivity() {
    
    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var resetDbButton: Button
    private lateinit var secretModes: SecretModes
    private lateinit var localDatabase: LocalDatabase
    
    private var inputSequence = mutableListOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        
        secretModes = SecretModes(this)
        localDatabase = LocalDatabase(this)
        
        initViews()
        setupListeners()
    }
    
    private fun initViews() {
        usernameEditText = findViewById(R.id.username_edittext)
        passwordEditText = findViewById(R.id.password_edittext)
        loginButton = findViewById(R.id.login_button)
        resetDbButton = findViewById(R.id.reset_db_button)
    }
    
    private fun setupListeners() {
        loginButton.setOnClickListener {
            performLogin()
        }
        
        // 🗑️ Botón temporal para limpiar BD
        resetDbButton.setOnClickListener {
            showResetConfirmation()
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
                KeyEvent.KEYCODE_2 -> "22"
                KeyEvent.KEYCODE_B -> "B"
                KeyEvent.KEYCODE_A -> "A"
                else -> null
            }
            
            direction?.let {
                // Procesar con SecretModes
                val result = secretModes.processSecretInput(it, SecretInputType.KEYCODE)
                
                if (result.success) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    startSecretGame()
                    return true
                }
            }
        }
        
        return false
    }
    
    private fun performLogin() {
        val username = usernameEditText.text.toString()
        val password = passwordEditText.text.toString()
        
        if (username.isNotEmpty() && password.isNotEmpty()) {
            // Usar ChatRepository para login
            val app = application as HamtaroApplication
            app.chatRepository.login(
                username = username,
                password = password,
                onSuccess = { response ->
                    runOnUiThread {
                        Toast.makeText(this, "¡Bienvenido $username!", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            Toast.makeText(this, "Ingresa usuario y contraseña", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun startSecretGame() {
        val intent = Intent(this, GameWatchActivity::class.java)
        startActivity(intent)
    }
    
    /**
     * Muestra diálogo de confirmación para limpiar BD
     */
    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Limpiar Base de Datos")
            .setMessage("¿Estás seguro? Esto eliminará:\n\n• Todos los usuarios\n• Todos los mensajes\n• Todos los contactos\n• Todos los tokens\n\nEsta acción NO se puede deshacer.")
            .setPositiveButton("Sí, limpiar") { _, _ ->
                resetDatabase()
            }
            .setNegativeButton("Cancelar", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
    
    /**
     * Limpia la base de datos local
     */
    private fun resetDatabase() {
        try {
            // Obtener estadísticas antes
            val statsBefore = localDatabase.getStats()
            
            // Limpiar BD
            localDatabase.resetDatabase()
            
            // Limpiar sesión del repositorio
            val app = application as HamtaroApplication
            app.chatRepository.logout()
            
            // Obtener estadísticas después
            val statsAfter = localDatabase.getStats()
            
            val message = """
                ✅ Base de datos limpiada
                
                Antes:
                • Usuarios: ${statsBefore["users"]}
                • Mensajes: ${statsBefore["messages"]}
                • Contactos: ${statsBefore["contacts"]}
                
                Después:
                • Usuarios: ${statsAfter["users"]}
                • Mensajes: ${statsAfter["messages"]}
                • Contactos: ${statsAfter["contacts"]}
            """.trimIndent()
            
            AlertDialog.Builder(this)
                .setTitle("✅ Completado")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
                
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
