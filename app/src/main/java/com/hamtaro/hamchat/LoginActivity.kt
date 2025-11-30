package com.hamtaro.hamchat

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.hamtaro.hamchat.game.GameWatchActivity
import com.hamtaro.hamchat.network.NetworkUtils
import com.hamtaro.hamchat.ui.BaseActivity
import com.hamtaro.hamchat.ui.SecretModes
import com.hamtaro.hamchat.ui.SecretInputType

class LoginActivity : BaseActivity() {
    
    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var secretModes: SecretModes
    
    // Contador de errores de conexión
    private var connectionErrorCount = 0
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        
        secretModes = SecretModes(this)
        
        initViews()
        setupListeners()
    }
    
    private fun initViews() {
        usernameEditText = findViewById(R.id.username_edittext)
        passwordEditText = findViewById(R.id.password_edittext)
        loginButton = findViewById(R.id.login_button)
    }
    
    private fun setupListeners() {
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
                        connectionErrorCount++
                        
                        if (connectionErrorCount >= 3) {
                            // Al 3er error, buscar servidor automáticamente
                            Toast.makeText(this, "Buscando servidor automáticamente...", Toast.LENGTH_SHORT).show()
                            autoDiscoverServer()
                        } else {
                            val remaining = 3 - connectionErrorCount
                            Toast.makeText(this, "Error: $error\n($remaining intentos para búsqueda automática)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } else {
            Toast.makeText(this, "Ingresa usuario y contraseña", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Buscar servidor automáticamente en la red local
     */
    private fun autoDiscoverServer() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("🔍 Buscando servidor")
            .setMessage("Escaneando red local...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        val foundServers = mutableListOf<String>()
        
        // Escanear puerto 8000 (el puerto del servidor HamChat)
        NetworkUtils.scanLocalNetwork(
            port = 8000,
            onPeerFound = { serverUrl ->
                runOnUiThread {
                    progressDialog.setMessage("Encontrado: $serverUrl")
                }
                foundServers.add(serverUrl)
            },
            onComplete = { servers ->
                runOnUiThread {
                    progressDialog.dismiss()
                    
                    when {
                        servers.isEmpty() -> {
                            // No se encontró ningún servidor
                            showManualServerDialog()
                        }
                        servers.size == 1 -> {
                            // Un servidor encontrado, usar automáticamente
                            setServerAndRetry(servers[0])
                        }
                        else -> {
                            // Múltiples servidores, mostrar lista
                            showServerSelectionDialog(servers)
                        }
                    }
                }
            }
        )
    }
    
    /**
     * Mostrar diálogo para seleccionar servidor
     */
    private fun showServerSelectionDialog(servers: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("🖥️ Servidores encontrados")
            .setItems(servers.toTypedArray()) { _, which ->
                setServerAndRetry(servers[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Mostrar diálogo para ingresar servidor manualmente
     */
    private fun showManualServerDialog() {
        val input = EditText(this)
        input.hint = "http://192.168.1.X:8000"
        input.setPadding(50, 30, 50, 30)
        
        AlertDialog.Builder(this)
            .setTitle("❌ No se encontró servidor")
            .setMessage("Ingresa la dirección del servidor manualmente:")
            .setView(input)
            .setPositiveButton("Conectar") { _, _ ->
                val serverUrl = input.text.toString().trim()
                if (serverUrl.isNotEmpty()) {
                    setServerAndRetry(serverUrl)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Configurar servidor y reintentar login
     */
    private fun setServerAndRetry(serverUrl: String) {
        val app = application as HamtaroApplication
        app.chatRepository.serverUrl = serverUrl
        connectionErrorCount = 0
        
        Toast.makeText(this, "✅ Servidor configurado: $serverUrl", Toast.LENGTH_SHORT).show()
        
        // Reintentar login automáticamente si hay credenciales
        if (usernameEditText.text.isNotEmpty() && passwordEditText.text.isNotEmpty()) {
            performLogin()
        }
    }
    
    private fun startSecretGame() {
        val intent = Intent(this, GameWatchActivity::class.java)
        startActivity(intent)
    }
}
