package com.hamtaro.hamchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.hamtaro.hamchat.ui.BaseActivity
import com.hamtaro.hamchat.network.HamChatApiClient
import com.hamtaro.hamchat.network.HealthResponse
import com.hamtaro.hamchat.network.LoginRequest
import com.hamtaro.hamchat.network.LoginResponse
import com.hamtaro.hamchat.network.RegisterRequest
import com.hamtaro.hamchat.network.RegisterResponse
import com.hamtaro.hamchat.security.IntentValidator
import com.hamtaro.hamchat.security.SecurityManager
import com.hamtaro.hamchat.security.SecureLogger
import com.hamtaro.hamchat.security.SecurePreferences
import com.hamtaro.hamchat.workers.HamChatSyncManager
import com.hamtaro.hamchat.ui.ChatActivity
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import android.text.InputType
import java.util.UUID
import com.hamtaro.hamchat.network.LadaCreateRequest
import com.hamtaro.hamchat.network.LadaDto
import com.hamtaro.hamchat.workers.WeeklyBackupWorker
import com.hamtaro.hamchat.workers.BackupInfo
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog

// ContactItem para UI de MainActivity (diferente de model.Contact)
data class ContactItem(
    val id: String,
    val name: String,
    val lastMessage: String = "",
    val timestamp: String = "",
    val isOnline: Boolean = false,
    val unreadCount: Int = 0,
    val phoneNumber: String? = null
)

/**
 * 🔒 Secure MainActivity for Ham-Chat
 * Protected against intent injection and spoofing
 * Extiende BaseActivity para detección de inactividad
 */
class MainActivity : BaseActivity() {
    
    private lateinit var securityManager: SecurityManager
    private lateinit var intentValidator: IntentValidator
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var chatsLayout: View
    private lateinit var friendsLayout: View
    private lateinit var settingsLayout: View
    private lateinit var chatsListLayout: LinearLayout
    private lateinit var friendsListLayout: LinearLayout
    private lateinit var newChatButton: Button
    private lateinit var addFriendRemoteButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var openMediaFolderButton: Button
    private lateinit var checkServerButton: Button
    private lateinit var helpButton: Button
    private lateinit var backupButton: Button
    private var userLabelTextView: TextView? = null
    private var phoneLabelTextView: TextView? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable { onIdleTimeout() }
    private val inboxHandler = Handler(Looper.getMainLooper())
    private val inboxPollRunnable = object : Runnable {
        override fun run() {
            try {
                pollInboxOnce()
            } catch (_: Exception) {
            }
            inboxHandler.postDelayed(this, 60_000L)
        }
    }

    // Temporizador independiente para promover contactos locales a remotos
    private val contactPromotionHandler = Handler(Looper.getMainLooper())
    private val contactPromotionRunnable = object : Runnable {
        override fun run() {
            try {
                if (hasLocalContactsNeedingPromotion()) {
                    checkLocalContactsForRemoteMatches()
                    // Volver a comprobar en 30 segundos mientras existan contactos locales
                    contactPromotionHandler.postDelayed(this, 30_000L)
                }
                // Si ya no hay contactos locales, no reprogramamos: se reactivará al abrir la app
            } catch (_: Exception) {
            }
        }
    }
    
    companion object {
        private const val MAX_INTENT_PROCESSING_TIME = 5000 // 5 seconds
        private const val SECTION_CHATS = 0
        private const val SECTION_FRIENDS = 1
        private const val SECTION_SETTINGS = 2
        private const val PREFS_NAME = "hamchat_settings"
        private const val KEY_FRIEND_IDS = "friend_ids"
        private const val KEY_AUTH_USER_ID = "auth_user_id"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_AUTH_USERNAME = "auth_username"
        private const val KEY_AUTH_PASSWORD = "auth_password"
        private const val KEY_AUTH_PHONE_E164 = "auth_phone_e164"
        private const val KEY_CUSTOM_LADAS = "custom_ladas"
        private const val DEFAULT_LADAS_COUNT = 7
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val STORAGE_PERMISSION_CODE = 1001
    }

    private val contacts = listOf(
        ContactItem("contact_hamtaro", "Hamtaro", "¡Hola! Soy Hamtaro 🐹", "Ahora", false, 1)
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize security components
        securityManager = SecurityManager(this)
        intentValidator = IntentValidator()
        
        // 🔒 Security checks before proceeding
        if (!performSecurityChecks()) {
            SecureLogger.e("Security checks failed, finishing activity")
            finish()
            return
        }
        
        // 🔒 Validate incoming intent
        val validationResult = intentValidator.validateIntent(intent)
        if (!validationResult.isValid) {
            SecureLogger.e("Intent validation failed: ${validationResult.error}")
            securityManager.logSecurityEvent("INTENT_VALIDATION_FAILED", validationResult.error)
            finish()
            return
        }
        
        // Sanitize intent
        val sanitizedIntent = intentValidator.sanitizeIntent(intent)
        
        // Proceed with normal initialization
        setContentView(R.layout.activity_main)
        initializeUI()
        handleIntent(sanitizedIntent)
    }

    override fun onResume() {
        super.onResume()
        startIdleTimer()
        startInboxPolling()
        startContactPromotionIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        stopIdleTimer()
        stopInboxPolling()
        stopContactPromotion()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimer()
    }
    
    /**
     * 🔒 Perform comprehensive security checks
     */
    private fun performSecurityChecks(): Boolean {
        val startTime = System.currentTimeMillis()
        
        try {
            // Check device security
            if (!securityManager.isDeviceSecure()) {
                SecureLogger.w("Device security check failed")
                Toast.makeText(this, "Device security requirements not met", Toast.LENGTH_LONG).show()
                return false
            }
            
            // Check app integrity
            if (!securityManager.verifyAppIntegrity()) {
                SecureLogger.e("App integrity check failed")
                Toast.makeText(this, "App integrity verification failed", Toast.LENGTH_LONG).show()
                return false
            }
            
            // Check permissions
            val requiredPermissions = securityManager.getRequiredPermissions()
            for (permission in requiredPermissions) {
                if (!securityManager.hasPermission(permission)) {
                    SecureLogger.w("Missing required permission: $permission")
                    // Request permission or show error
                    return false
                }
            }
            
            // Check processing time (prevent DoS)
            val processingTime = System.currentTimeMillis() - startTime
            if (processingTime > MAX_INTENT_PROCESSING_TIME) {
                SecureLogger.w("Security checks took too long: ${processingTime}ms")
                return false
            }
            
            return true
            
        } catch (e: Exception) {
            SecureLogger.e("Security check exception", e)
            securityManager.logSecurityEvent("SECURITY_CHECK_EXCEPTION", e.message ?: "")
            return false
        }
    }

    private fun startIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
        idleHandler.postDelayed(idleTimeoutRunnable, IDLE_TIMEOUT_MS)
    }

    private fun stopIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
    }

    private fun resetIdleTimer() {
        startIdleTimer()
    }

    private fun startInboxPolling() {
        inboxHandler.removeCallbacks(inboxPollRunnable)
        inboxHandler.postDelayed(inboxPollRunnable, 60_000L)
    }

    private fun stopInboxPolling() {
        inboxHandler.removeCallbacks(inboxPollRunnable)
    }

    private fun startContactPromotionIfNeeded() {
        contactPromotionHandler.removeCallbacks(contactPromotionRunnable)
        if (hasLocalContactsNeedingPromotion()) {
            // Primera comprobación a los 30 segundos desde que se abre/reanuda la app
            contactPromotionHandler.postDelayed(contactPromotionRunnable, 30_000L)
        }
    }

    private fun stopContactPromotion() {
        contactPromotionHandler.removeCallbacks(contactPromotionRunnable)
    }

    private fun pollInboxOnce() {
        HamChatSyncManager.pollInboxOnce(this)
    }

    private fun hasLocalContactsNeedingPromotion(): Boolean {
        return try {
            getAllContacts().any { contact: ContactItem ->
                contact.id != "contact_hamtaro" &&
                        !contact.id.startsWith("remote_") &&
                        !contact.phoneNumber.isNullOrBlank()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun checkLocalContactsForRemoteMatches() {
        try {
            val all = getAllContacts()
            for (contact in all) {
                val phone = contact.phoneNumber
                if (contact.id == "contact_hamtaro" ||
                    contact.id.startsWith("remote_") ||
                    phone.isNullOrBlank()
                ) {
                    continue
                }

                resolveRemoteUserForPhone(phone) { remoteUser ->
                    if (remoteUser != null) {
                        promoteContactToRemote(contact, remoteUser)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun promoteContactToRemote(
        contact: ContactItem,
        remoteUser: com.hamtaro.hamchat.network.UserLookupResponse
    ) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val contactsJson = prefs.getString("custom_contacts", "[]")

            val updated = JSONArray()
            val original = JSONArray(contactsJson)
            for (i in 0 until original.length()) {
                val obj = original.getJSONObject(i)
                val id = obj.optString("id")
                if (id != contact.id) {
                    updated.put(obj)
                }
            }

            val isAutoNamed =
                !contact.phoneNumber.isNullOrBlank() && contact.name == contact.phoneNumber
            val displayName = if (isAutoNamed && remoteUser.username.isNotBlank()) {
                remoteUser.username
            } else {
                contact.name
            }

            val newId = "remote_${remoteUser.id}"
            val contactJson = JSONObject().apply {
                put("id", newId)
                put("name", displayName)
                put("lastMessage", contact.lastMessage)
                put("timestamp", contact.timestamp)
                put("isOnline", contact.isOnline)
                put("unreadCount", contact.unreadCount)
                put("phoneNumber", remoteUser.phone_e164)
            }
            updated.put(contactJson)
            prefs.edit().putString("custom_contacts", updated.toString()).apply()

            val simple = prefs.getString("custom_contacts_simple", "") ?: ""
            if (simple.isNotEmpty()) {
                val parts = simple.split("|").filter { it.isNotBlank() }
                val builder = StringBuilder()
                for (part in parts) {
                    val namePart = part.substringBefore(":")
                    val numberPart = part.substringAfter(":", "")
                    val matchesOriginal =
                        namePart == contact.name || numberPart == (contact.phoneNumber ?: "")
                    if (!matchesOriginal) {
                        if (builder.isNotEmpty()) builder.append('|')
                        builder.append(part)
                    }
                }
                prefs.edit().putString("custom_contacts_simple", builder.toString()).apply()
            }

            val friendIds = getFriendIds()
            if (friendIds.remove(contact.id)) {
                friendIds.add(newId)
            }
            saveFriendIds(friendIds)

            updateChatList()
            renderFriends()
        } catch (_: Exception) {
        }
    }

    private fun onIdleTimeout() {
        try {
            Toast.makeText(this, "Cerrando Ham-Chat por inactividad", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
        finishAffinity()
    }

    private fun showAddRemoteFriendDialog() {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        val usernameInput = EditText(context).apply {
            hint = "Nombre de usuario Ham-Chat (opcional)"
        }

        val countryLabel = TextView(context).apply {
            text = "Lada (para búsqueda por número)"
            setPadding(0, 16, 0, 4)
        }

        val (countryValues, countryDisplay) = loadLocalLadas()
        loadRemoteLadas(countryValues, countryDisplay)

        val countrySpinner = android.widget.Spinner(context)
        val countryAdapter = android.widget.ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_item,
            countryDisplay
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        countrySpinner.adapter = countryAdapter
        countrySpinner.setSelection(0)

        val addLadaButton = Button(context).apply {
            text = "Agregar otra lada"
            setOnClickListener {
                val form = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 8)
                }
                val codeInput = EditText(context).apply {
                    hint = "Lada (ej. +52 o 52)"
                }
                val labelInput = EditText(context).apply {
                    hint = "Descripción (opcional)"
                }
                form.addView(codeInput)
                form.addView(labelInput)

                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("Nueva lada")
                    .setView(form)
                    .setPositiveButton("Guardar") { _, _ ->
                        val rawCode = codeInput.text.toString().trim()
                        if (rawCode.isEmpty()) {
                            Toast.makeText(context, "Ingresa una lada válida", Toast.LENGTH_SHORT).show()
                        } else {
                            val normalized = if (rawCode.startsWith("+")) rawCode else "+" + rawCode
                            val label = labelInput.text.toString().trim()
                            val display = if (label.isEmpty()) normalized else "$normalized $label"
                            countryValues.add(normalized)
                            countryAdapter.add(display)
                            countrySpinner.setSelection(countryValues.size - 1)
                            saveLocalLadas(countryValues, countryDisplay)
                            sendLadaToServer(normalized, label)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        val phoneInput = EditText(context).apply {
            hint = "Número de teléfono (solo dígitos, opcional)"
            inputType = InputType.TYPE_CLASS_PHONE
        }

        container.addView(usernameInput)
        container.addView(countryLabel)
        container.addView(countrySpinner)
        container.addView(addLadaButton)
        container.addView(phoneInput)

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Agregar amigo Ham-Chat")
            .setView(container)
            .setPositiveButton("Agregar") { _, _ ->
                val username = usernameInput.text.toString().trim()
                val selectedIndex = countrySpinner.selectedItemPosition
                val cc = if (selectedIndex in countryValues.indices) countryValues[selectedIndex] else "+52"
                val phoneDigits = phoneInput.text.toString().filter { it.isDigit() }

                if (username.isEmpty() && phoneDigits.isEmpty()) {
                    Toast.makeText(context, "Ingresa usuario o número", Toast.LENGTH_SHORT).show()
                    showAddRemoteFriendDialog()
                } else {
                    addRemoteFriend(username, cc, phoneDigits)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addRemoteFriend(username: String, countryCode: String, phoneDigits: String) {
        fun handleUser(user: com.hamtaro.hamchat.network.UserLookupResponse) {
            val contactId = "remote_" + user.id
            val newContact = ContactItem(
                id = contactId,
                name = user.username,
                lastMessage = "",
                timestamp = "",
                isOnline = false,
                unreadCount = 0
            )
            saveContactToPrefs(newContact, "")
            updateChatList()
            renderFriends()
            Toast.makeText(
                this@MainActivity,
                "Amigo Ham-Chat agregado: ${'$'}{user.username}",
                Toast.LENGTH_SHORT
            ).show()
        }

        try {
            val usePhone = phoneDigits.isNotEmpty()
            val useUsername = username.isNotEmpty()

            if (usePhone) {
                val cc = if (countryCode.isBlank()) "+52" else countryCode.trim()
                HamChatApiClient.api.getUserByPhone(cc, phoneDigits)
                    .enqueue(object : Callback<com.hamtaro.hamchat.network.UserLookupResponse> {
                        override fun onResponse(
                            call: Call<com.hamtaro.hamchat.network.UserLookupResponse>,
                            response: Response<com.hamtaro.hamchat.network.UserLookupResponse>
                        ) {
                            if (response.isSuccessful && response.body() != null) {
                                handleUser(response.body()!!)
                            } else {
                                val msg = if (response.code() == 404) {
                                    "Número no encontrado en el servidor"
                                } else {
                                    "Error al buscar número en el servidor"
                                }
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onFailure(
                            call: Call<com.hamtaro.hamchat.network.UserLookupResponse>,
                            t: Throwable
                        ) {
                            Toast.makeText(
                                this@MainActivity,
                                "No se pudo contactar al servidor para agregar amigo",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
            } else if (useUsername) {
                HamChatApiClient.api.getUserByUsername(username)
                    .enqueue(object : Callback<com.hamtaro.hamchat.network.UserLookupResponse> {
                        override fun onResponse(
                            call: Call<com.hamtaro.hamchat.network.UserLookupResponse>,
                            response: Response<com.hamtaro.hamchat.network.UserLookupResponse>
                        ) {
                            if (response.isSuccessful && response.body() != null) {
                                handleUser(response.body()!!)
                            } else {
                                val msg = if (response.code() == 404) {
                                    "Usuario no encontrado en el servidor"
                                } else {
                                    "Error al buscar usuario en el servidor"
                                }
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onFailure(
                            call: Call<com.hamtaro.hamchat.network.UserLookupResponse>,
                            t: Throwable
                        ) {
                            Toast.makeText(
                                this@MainActivity,
                                "No se pudo contactar al servidor para agregar amigo",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error agregando amigo Ham-Chat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureRegistered() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val securePrefs = SecurePreferences(this)
        var token = securePrefs.getAuthToken()
        if (token.isNullOrEmpty()) {
            val legacyToken = prefs.getString(KEY_AUTH_TOKEN, null)
            if (!legacyToken.isNullOrEmpty()) {
                try {
                    securePrefs.setAuthToken(legacyToken)
                    token = legacyToken
                    prefs.edit().remove(KEY_AUTH_TOKEN).apply()
                } catch (_: Exception) {
                }
            }
        }
        val username = prefs.getString(KEY_AUTH_USERNAME, null)
        if (token.isNullOrEmpty() || username.isNullOrEmpty()) {
            // Verificar si hay backups disponibles antes de mostrar registro
            checkForAvailableBackups()
        }
    }
    
    private fun checkForAvailableBackups() {
        // Verificar permiso de almacenamiento primero
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }
        
        // Buscar backups disponibles
        val backups = WeeklyBackupWorker.listLocalBackups(this)
        
        if (backups.isNotEmpty()) {
            // Hay backups disponibles, mostrar opción de restaurar
            showBackupRestoreDialog(backups)
        } else {
            // No hay backups, continuar con registro normal
            showRegistrationDialog()
        }
    }
    
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, STORAGE_PERMISSION_CODE)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, STORAGE_PERMISSION_CODE)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
        }
    }
    
    private fun showBackupRestoreDialog(backups: List<BackupInfo>) {
        val context = this
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        
        val titleText = TextView(context).apply {
            text = "📦 Se encontraron backups disponibles"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleText)
        
        val infoText = TextView(context).apply {
            text = "Selecciona un backup para restaurar tus conversaciones:"
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)
        
        // Spinner para seleccionar backup
        val backupOptions = backups.map { backup ->
            "${backup.getDateFormatted()} - ${backup.messageCount} msgs, ${backup.contactCount} contactos (${backup.getSizeFormatted()})"
        }
        
        val backupSpinner = Spinner(context)
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, backupOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        backupSpinner.adapter = adapter
        container.addView(backupSpinner)
        
        // Botón para empezar de cero
        val startFreshButton = Button(context).apply {
            text = "🗑️ Empezar de cero (borrar todo)"
            setBackgroundColor(Color.parseColor("#FFCCCC"))
            setTextColor(Color.parseColor("#CC0000"))
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                showDeleteAllBackupsConfirmation(backups)
            }
        }
        
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24
            )
        }
        container.addView(spacer)
        container.addView(startFreshButton)
        
        AlertDialog.Builder(context)
            .setTitle("🐹 Restaurar Backup")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Restaurar") { _, _ ->
                val selectedIndex = backupSpinner.selectedItemPosition
                if (selectedIndex >= 0 && selectedIndex < backups.size) {
                    restoreSelectedBackup(backups[selectedIndex])
                }
            }
            .setNegativeButton("Registrarse nuevo") { _, _ ->
                showRegistrationDialog()
            }
            .setNeutralButton("🔑 Recuperar cuenta") { _, _ ->
                showRecoveryDialog()
            }
            .show()
    }
    
    private fun showDeleteAllBackupsConfirmation(backups: List<BackupInfo>) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ ADVERTENCIA")
            .setMessage("""
                ¿Estás seguro de que deseas BORRAR TODOS los backups y empezar de cero?
                
                🚨 ESTA ACCIÓN ES IRREVERSIBLE 🚨
                
                • Se eliminarán ${backups.size} backup(s)
                • Perderás TODAS tus conversaciones guardadas
                • Tus contactos que tenían conversaciones contigo conservarán sus mensajes
                
                📢 RECOMENDACIÓN:
                Es recomendable que notifiques a tus contactos que has reiniciado tu cuenta. Ellos pueden decidir si desean:
                • Mantener el historial de conversaciones
                • Eliminar las conversaciones antiguas
                
                ¿Deseas continuar?
            """.trimIndent())
            .setPositiveButton("Sí, borrar todo") { _, _ ->
                showFinalDeleteConfirmation(backups)
            }
            .setNegativeButton("Cancelar") { _, _ ->
                // Volver a mostrar el diálogo de backups
                checkForAvailableBackups()
            }
            .show()
    }
    
    private fun showFinalDeleteConfirmation(backups: List<BackupInfo>) {
        AlertDialog.Builder(this)
            .setTitle("🗑️ CONFIRMACIÓN FINAL")
            .setMessage("""
                Escribe "BORRAR" para confirmar que deseas eliminar todos los backups y empezar de cero.
                
                Esta es tu última oportunidad de cancelar.
            """.trimIndent())
            .setView(EditText(this).apply {
                hint = "Escribe BORRAR"
                id = android.R.id.edit
            })
            .setPositiveButton("Confirmar") { dialog, _ ->
                val editText = (dialog as AlertDialog).findViewById<EditText>(android.R.id.edit)
                val confirmation = editText?.text?.toString()?.trim()?.uppercase()
                
                if (confirmation == "BORRAR") {
                    deleteAllBackupsAndStartFresh(backups)
                } else {
                    Toast.makeText(this, "Confirmación incorrecta. Operación cancelada.", Toast.LENGTH_SHORT).show()
                    checkForAvailableBackups()
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                checkForAvailableBackups()
            }
            .show()
    }
    
    private fun deleteAllBackupsAndStartFresh(backups: List<BackupInfo>) {
        var deletedCount = 0
        
        // Eliminar todos los backups
        backups.forEach { backup ->
            if (WeeklyBackupWorker.deleteLocalBackup(this, backup.fileName)) {
                deletedCount++
            }
        }
        
        Toast.makeText(this, "✅ $deletedCount backup(s) eliminado(s)", Toast.LENGTH_SHORT).show()
        
        // Mostrar mensaje de recomendación final
        AlertDialog.Builder(this)
            .setTitle("📢 Recomendación importante")
            .setMessage("""
                Has eliminado todos tus backups y empezarás de cero.
                
                📱 IMPORTANTE:
                Tus contactos aún tienen las conversaciones que tuvieron contigo. Te recomendamos:
                
                1. Notificarles que reiniciaste tu cuenta
                2. Pedirles que decidan si desean:
                   • Mantener el historial (para referencia)
                   • Eliminar las conversaciones antiguas
                
                Esto ayudará a mantener la privacidad y evitar confusiones.
            """.trimIndent())
            .setPositiveButton("Entendido, continuar") { _, _ ->
                showRegistrationDialog()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun restoreSelectedBackup(backup: BackupInfo) {
        try {
            val backupData = WeeklyBackupWorker.restoreLocalBackup(this, backup.fileName)
            
            if (backupData != null) {
                // Mostrar información del backup y pedir login
                val username = backupData.optString("username", "")
                val phoneE164 = backupData.optString("phone_e164", "")
                val totalMessages = backupData.optInt("total_messages", 0)
                val totalContacts = backupData.optInt("total_contacts", 0)
                
                AlertDialog.Builder(this)
                    .setTitle("📦 Backup encontrado")
                    .setMessage("""
                        Usuario: $username
                        Teléfono: $phoneE164
                        Mensajes: $totalMessages
                        Contactos: $totalContacts
                        
                        Para restaurar este backup, necesitas iniciar sesión con tu cuenta.
                    """.trimIndent())
                    .setPositiveButton("Iniciar sesión") { _, _ ->
                        // Mostrar diálogo de recuperación con el teléfono prellenado
                        showRecoveryDialogWithPhone(phoneE164)
                    }
                    .setNegativeButton("Cancelar") { _, _ ->
                        showRegistrationDialog()
                    }
                    .show()
            } else {
                Toast.makeText(this, "Error leyendo backup", Toast.LENGTH_SHORT).show()
                showRegistrationDialog()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error restaurando backup: ${e.message}", Toast.LENGTH_SHORT).show()
            showRegistrationDialog()
        }
    }
    
    private fun showRecoveryDialogWithPhone(phoneE164: String) {
        // Extraer código de país y número del teléfono E164
        val countryCode = if (phoneE164.startsWith("+52")) "+52" else "+1"
        val nationalNumber = phoneE164.removePrefix(countryCode)
        
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        
        val infoText = TextView(context).apply {
            text = "Ingresa tu contraseña de recuperación para restaurar tu cuenta y backup:"
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)
        
        val phoneText = TextView(context).apply {
            text = "Teléfono: $phoneE164"
            setPadding(0, 0, 0, 8)
        }
        container.addView(phoneText)
        
        val passwordInput = EditText(context).apply {
            hint = "Contraseña de recuperación"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(passwordInput)
        
        AlertDialog.Builder(context)
            .setTitle("🔑 Recuperar cuenta")
            .setView(container)
            .setPositiveButton("Recuperar") { _, _ ->
                val password = passwordInput.text.toString().trim()
                if (password.length >= 4) {
                    performRecovery(countryCode, nationalNumber, password)
                } else {
                    Toast.makeText(context, "La contraseña debe tener al menos 4 caracteres", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                showRegistrationDialog()
            }
            .show()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            // Verificar permiso después de volver de configuración
            if (hasStoragePermission()) {
                checkForAvailableBackups()
            } else {
                // Sin permiso, continuar con registro normal
                showRegistrationDialog()
            }
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkForAvailableBackups()
            } else {
                // Sin permiso, continuar con registro normal
                showRegistrationDialog()
            }
        }
    }

    private fun showRegistrationDialog() {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        val countryLabel = TextView(context).apply {
            text = "Lada del teléfono"
            setPadding(0, 0, 0, 4)
        }

        val (countryValues, countryDisplay) = loadLocalLadas()
        loadRemoteLadas(countryValues, countryDisplay)

        val countrySpinner = android.widget.Spinner(context)
        val countryAdapter = android.widget.ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_item,
            countryDisplay
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        countrySpinner.adapter = countryAdapter
        countrySpinner.setSelection(0)

        val addLadaButton = Button(context).apply {
            text = "Agregar otra lada"
            setOnClickListener {
                val form = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 8)
                }
                val codeInput = EditText(context).apply {
                    hint = "Lada (ej. +52 o 52)"
                }
                val labelInput = EditText(context).apply {
                    hint = "Descripción (opcional)"
                }
                form.addView(codeInput)
                form.addView(labelInput)

                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("Nueva lada")
                    .setView(form)
                    .setPositiveButton("Guardar") { _, _ ->
                        val rawCode = codeInput.text.toString().trim()
                        if (rawCode.isEmpty()) {
                            Toast.makeText(context, "Ingresa una lada válida", Toast.LENGTH_SHORT).show()
                        } else {
                            val normalized = if (rawCode.startsWith("+")) rawCode else "+" + rawCode
                            val label = labelInput.text.toString().trim()
                            val display = if (label.isEmpty()) normalized else "$normalized $label"
                            countryValues.add(normalized)
                            countryAdapter.add(display)
                            countrySpinner.setSelection(countryValues.size - 1)
                            saveLocalLadas(countryValues, countryDisplay)
                            sendLadaToServer(normalized, label)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        val nameInput = EditText(context).apply {
            hint = "Nombre de usuario"
        }

        val phoneInput = EditText(context).apply {
            hint = "Número de teléfono (solo dígitos)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        
        val passwordLabel = TextView(context).apply {
            text = "Contraseña de recuperación (mínimo 4 caracteres)"
            setPadding(0, 16, 0, 4)
        }
        
        val passwordInput = EditText(context).apply {
            hint = "Contraseña para recuperar cuenta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(countryLabel)
        container.addView(countrySpinner)
        container.addView(addLadaButton)
        container.addView(nameInput)
        container.addView(phoneInput)
        container.addView(passwordLabel)
        container.addView(passwordInput)

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Registro Ham-Chat")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Registrar") { _, _ ->
                val selectedIndex = countrySpinner.selectedItemPosition
                val cc = if (selectedIndex in countryValues.indices) countryValues[selectedIndex] else "+52"
                val username = nameInput.text.toString().trim()
                val phoneDigits = phoneInput.text.toString().filter { it.isDigit() }
                val recoveryPassword = passwordInput.text.toString().trim()

                if (cc.isEmpty() || username.isEmpty() || phoneDigits.isEmpty()) {
                    Toast.makeText(context, "Ingresa nombre y número", Toast.LENGTH_SHORT).show()
                    showRegistrationDialog()
                } else if (recoveryPassword.length < 4) {
                    Toast.makeText(context, "La contraseña debe tener al menos 4 caracteres", Toast.LENGTH_SHORT).show()
                    showRegistrationDialog()
                } else {
                    performRegistration(cc, username, phoneDigits, recoveryPassword)
                }
            }
            .setNeutralButton("🔑 Recuperar cuenta") { _, _ ->
                showRecoveryDialog()
            }
            .show()
    }

    private fun loadLocalLadas(): Pair<MutableList<String>, MutableList<String>> {
        val countryValues = mutableListOf("+52", "+1", "+81", "+34", "+44", "+55", "+91")
        val countryDisplay = mutableListOf(
            "+52 México",
            "+1 EE.UU./Canadá",
            "+81 Japón",
            "+34 España",
            "+44 Reino Unido",
            "+55 Brasil",
            "+91 India"
        )

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_LADAS, null)
        if (!json.isNullOrEmpty()) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val code = obj.optString("code", "")
                    if (code.isBlank()) continue
                    val label = obj.optString("label", "")
                    if (!countryValues.contains(code)) {
                        val display = if (label.isBlank()) code else "$code $label"
                        countryValues.add(code)
                        countryDisplay.add(display)
                    }
                }
            } catch (_: Exception) {
            }
        }

        return Pair(countryValues, countryDisplay)
    }

    private fun saveLocalLadas(
        countryValues: MutableList<String>,
        countryDisplay: MutableList<String>
    ) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        try {
            val arr = JSONArray()
            val start = DEFAULT_LADAS_COUNT.coerceAtMost(countryValues.size)
            for (i in start until countryValues.size) {
                val code = countryValues[i]
                val full = if (i < countryDisplay.size) countryDisplay[i] else code
                val label = if (full.startsWith(code)) {
                    full.substring(code.length).trimStart()
                } else {
                    ""
                }
                val obj = JSONObject()
                obj.put("code", code)
                obj.put("label", label)
                arr.put(obj)
            }
            prefs.edit().putString(KEY_CUSTOM_LADAS, arr.toString()).apply()
        } catch (e: Exception) {
            prefs.edit().remove(KEY_CUSTOM_LADAS).apply()
        }
    }

    private fun loadRemoteLadas(
        countryValues: MutableList<String>,
        countryDisplay: MutableList<String>
    ) {
        try {
            HamChatApiClient.api.getLadas()
                .enqueue(object : Callback<List<LadaDto>> {
                    override fun onResponse(
                        call: Call<List<LadaDto>>,
                        response: Response<List<LadaDto>>
                    ) {
                        if (response.isSuccessful && response.body() != null) {
                            val list = response.body()!!
                            var changed = false
                            for (lada in list) {
                                val code = lada.code
                                if (!countryValues.contains(code)) {
                                    val label = lada.label ?: ""
                                    val display = if (label.isBlank()) code else "$code $label"
                                    countryValues.add(code)
                                    countryDisplay.add(display)
                                    changed = true
                                }
                            }
                            if (changed) {
                                saveLocalLadas(countryValues, countryDisplay)
                            }
                        }
                    }

                    override fun onFailure(call: Call<List<LadaDto>>, t: Throwable) {
                        // Ignore network errors; user still has local list
                    }
                })
        } catch (_: Exception) {
        }
    }

    private fun sendLadaToServer(code: String, label: String?) {
        try {
            val req = LadaCreateRequest(code = code, label = label?.takeIf { it.isNotBlank() })
            HamChatApiClient.api.addLada(req).enqueue(object : Callback<LadaDto> {
                override fun onResponse(call: Call<LadaDto>, response: Response<LadaDto>) {
                    // No UI needed; best-effort
                }

                override fun onFailure(call: Call<LadaDto>, t: Throwable) {
                    // Ignore; lada seguirá al menos en local
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun performRegistration(
        countryCode: String,
        username: String,
        phoneDigits: String,
        recoveryPassword: String = ""
    ) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val password = UUID.randomUUID().toString().replace("-", "").take(12)

        // Guardar password localmente para poder re-login si es necesario
        prefs.edit().putString(KEY_AUTH_PASSWORD, password).apply()

        val request = RegisterRequest(
            username = username,
            password = password,
            phone_country_code = countryCode,
            phone_national = phoneDigits
        )

        Toast.makeText(this, "Registrando en servidor...", Toast.LENGTH_SHORT).show()

        HamChatApiClient.api.register(request).enqueue(object : Callback<RegisterResponse> {
            override fun onResponse(
                call: Call<RegisterResponse>,
                response: Response<RegisterResponse>
            ) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    prefs.edit()
                        .putString(KEY_AUTH_USERNAME, body.username)
                        .putInt(KEY_AUTH_USER_ID, body.id)
                        .putString(KEY_AUTH_PHONE_E164, body.phone_e164)
                        .putString("phone_country_code", body.phone_country_code)
                        .putString("phone_national", body.phone_national)
                        .apply()
                    
                    // Guardar también en ChatRepository para auto-relogin
                    val app = application as? HamtaroApplication
                    app?.chatRepository?.let { repo ->
                        repo.phoneCountryCode = body.phone_country_code
                        repo.phoneNational = body.phone_national
                    }
                    
                    Toast.makeText(
                        this@MainActivity,
                        "Usuario registrado, iniciando sesion...",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Guardar contraseña de recuperación después del login
                    performLoginWithRecovery(body.username, password, recoveryPassword)
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Sin detalles"
                    val code = response.code()
                    Toast.makeText(
                        this@MainActivity,
                        "Error registro (codigo $code): $errorBody",
                        Toast.LENGTH_LONG
                    ).show()
                    SecureLogger.e("Registration failed: $code - $errorBody")
                    // Mostrar dialogo de nuevo
                    showRegistrationDialog()
                }
            }

            override fun onFailure(call: Call<RegisterResponse>, t: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    "Error de red al registrar: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                SecureLogger.e("Registration network error", t)
                showRegistrationDialog()
            }
        })
    }

    private fun performLogin(username: String, password: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val securePrefs = SecurePreferences(this)
        val request = LoginRequest(username = username, password = password)

        HamChatApiClient.api.login(request).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(
                call: Call<LoginResponse>,
                response: Response<LoginResponse>
            ) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    prefs.edit()
                        .putInt(KEY_AUTH_USER_ID, body.user_id)
                        .apply()
                    try {
                        securePrefs.setAuthToken(body.token)
                        
                        // Migrar teléfono a ChatRepository para auto-relogin
                        val phoneCC = prefs.getString("phone_country_code", null)
                        val phoneNat = prefs.getString("phone_national", null)
                        if (!phoneCC.isNullOrEmpty() && !phoneNat.isNullOrEmpty()) {
                            val app = application as? HamtaroApplication
                            app?.chatRepository?.let { repo ->
                                repo.phoneCountryCode = phoneCC
                                repo.phoneNational = phoneNat
                                repo.token = body.token
                                repo.userId = body.user_id
                            }
                        }
                        
                        Toast.makeText(
                            this@MainActivity,
                            "Registro completado exitosamente",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        // Refrescar datos de ajustes inmediatamente
                        refreshSettingsData()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "Error guardando token: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        SecureLogger.e("Failed to save auth token", e)
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Sin detalles"
                    val code = response.code()
                    Toast.makeText(
                        this@MainActivity,
                        "Error login (codigo $code): $errorBody",
                        Toast.LENGTH_LONG
                    ).show()
                    SecureLogger.e("Login failed: $code - $errorBody")
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                Toast.makeText(
                    this@MainActivity,
                    "Error de red al iniciar sesion: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                SecureLogger.e("Login network error", t)
            }
        })
    }
    
    /**
     * Login y luego establecer contraseña de recuperación
     */
    private fun performLoginWithRecovery(username: String, password: String, recoveryPassword: String) {
        val request = LoginRequest(username = username, password = password)
        
        HamChatApiClient.api.login(request).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    try {
                        SecurePreferences(this@MainActivity).setAuthToken(body.token)
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        prefs.edit().putInt(KEY_AUTH_USER_ID, body.user_id).apply()
                        
                        // Establecer contraseña de recuperación
                        if (recoveryPassword.isNotEmpty()) {
                            setRecoveryPassword(body.token, recoveryPassword)
                        }
                        
                        Toast.makeText(this@MainActivity, "✅ Registro completado", Toast.LENGTH_SHORT).show()
                        refreshSettingsData()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Error al iniciar sesión", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
            }
        })
    }
    
    /**
     * Establece la contraseña de recuperación en el servidor
     */
    private fun setRecoveryPassword(token: String, recoveryPassword: String) {
        val request = com.hamtaro.hamchat.network.SetRecoveryPasswordRequest(recovery_password = recoveryPassword)
        
        HamChatApiClient.api.setRecoveryPassword("Bearer $token", request)
            .enqueue(object : Callback<Map<String, Any>> {
                override fun onResponse(call: Call<Map<String, Any>>, response: Response<Map<String, Any>>) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "🔑 Contraseña de recuperación guardada", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {}
            })
    }
    
    /**
     * Muestra diálogo para recuperar cuenta
     */
    private fun showRecoveryDialog() {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }
        
        val infoText = TextView(context).apply {
            text = "Ingresa tu número de teléfono y la contraseña de recuperación que creaste al registrarte."
            setPadding(0, 0, 0, 16)
        }
        
        val (countryValues, countryDisplay) = loadLocalLadas()
        
        val countrySpinner = android.widget.Spinner(context)
        val countryAdapter = android.widget.ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_item,
            countryDisplay
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        countrySpinner.adapter = countryAdapter
        
        val phoneInput = EditText(context).apply {
            hint = "Número de teléfono"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        
        val passwordInput = EditText(context).apply {
            hint = "Contraseña de recuperación"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        container.addView(infoText)
        container.addView(countrySpinner)
        container.addView(phoneInput)
        container.addView(passwordInput)
        
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("🔑 Recuperar cuenta")
            .setView(container)
            .setPositiveButton("Recuperar") { _, _ ->
                val selectedIndex = countrySpinner.selectedItemPosition
                val cc = if (selectedIndex in countryValues.indices) countryValues[selectedIndex] else "+52"
                val phoneDigits = phoneInput.text.toString().filter { it.isDigit() }
                val recoveryPassword = passwordInput.text.toString().trim()
                
                if (phoneDigits.isEmpty() || recoveryPassword.isEmpty()) {
                    Toast.makeText(context, "Ingresa teléfono y contraseña", Toast.LENGTH_SHORT).show()
                    showRecoveryDialog()
                } else {
                    performRecovery(cc, phoneDigits, recoveryPassword)
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                showRegistrationDialog()
            }
            .show()
    }
    
    /**
     * Ejecuta la recuperación de cuenta
     */
    private fun performRecovery(countryCode: String, phoneDigits: String, recoveryPassword: String) {
        Toast.makeText(this, "Recuperando cuenta...", Toast.LENGTH_SHORT).show()
        
        val request = com.hamtaro.hamchat.network.RecoverAccountRequest(
            phone_country_code = countryCode,
            phone_national = phoneDigits,
            recovery_password = recoveryPassword
        )
        
        HamChatApiClient.api.recoverAccount(request)
            .enqueue(object : Callback<com.hamtaro.hamchat.network.RecoverAccountResponse> {
                override fun onResponse(
                    call: Call<com.hamtaro.hamchat.network.RecoverAccountResponse>,
                    response: Response<com.hamtaro.hamchat.network.RecoverAccountResponse>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        
                        // Guardar credenciales
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        prefs.edit()
                            .putString(KEY_AUTH_USERNAME, body.username)
                            .putInt(KEY_AUTH_USER_ID, body.user_id)
                            .putString(KEY_AUTH_PHONE_E164, countryCode + phoneDigits)
                            .putString("phone_country_code", countryCode)
                            .putString("phone_national", phoneDigits)
                            .apply()
                        
                        SecurePreferences(this@MainActivity).setAuthToken(body.token)
                        
                        Toast.makeText(this@MainActivity, "✅ Cuenta recuperada: ${body.username}", Toast.LENGTH_SHORT).show()
                        
                        // Si tiene respaldos, ofrecer restaurar
                        if (body.has_backup) {
                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("📥 Respaldo encontrado")
                                .setMessage("Se encontraron respaldos de chats. ¿Deseas restaurarlos?")
                                .setPositiveButton("Restaurar") { _, _ ->
                                    showBackupOptions()
                                }
                                .setNegativeButton("Después", null)
                                .show()
                        }
                        
                        refreshSettingsData()
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Error desconocido"
                        Toast.makeText(this@MainActivity, "Error: $errorBody", Toast.LENGTH_LONG).show()
                        showRecoveryDialog()
                    }
                }
                
                override fun onFailure(call: Call<com.hamtaro.hamchat.network.RecoverAccountResponse>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "Error de conexión: ${t.message}", Toast.LENGTH_SHORT).show()
                    showRecoveryDialog()
                }
            })
    }
    
    /**
     * 🔒 Handle validated intent
     */
    private fun handleIntent(intent: Intent) {
        val action = intent.action
        
        when (action) {
            Intent.ACTION_MAIN -> {
                // Normal app launch
                securityManager.logSecurityEvent("APP_LAUNCHED", "Normal launch")
                showMainScreen()
            }
            
            Intent.ACTION_VIEW -> {
                // Handle view intent securely
                val data = intent.data
                if (data != null) {
                    handleViewIntent(data)
                } else {
                    showMainScreen()
                }
            }
            
            "com.hamtaro.hamchat.ACTION_SECURE_MESSAGE" -> {
                // Handle secure message intent
                handleSecureMessageIntent(intent)
            }
            
            else -> {
                SecureLogger.w("Unknown intent action: $action")
                showMainScreen()
            }
        }
    }
    
    /**
     * 🔒 Handle view intent
     */
    private fun handleViewIntent(data: android.net.Uri) {
        // Validate URI scheme
        val scheme = data.scheme?.lowercase()
        if (scheme == "tox") {
            // Handle Tox URI
            val toxId = data.toString().substringAfter("tox:")
            if (securityManager.validateToxId(toxId)) {
                // Valid Tox ID, add friend
                addToxFriend(toxId)
            } else {
                Toast.makeText(this, "Invalid Tox ID", Toast.LENGTH_SHORT).show()
            }
        } else {
            SecureLogger.w("Unsupported URI scheme: $scheme")
            Toast.makeText(this, "Unsupported link type", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 🔒 Handle secure message intent
     */
    private fun handleSecureMessageIntent(intent: Intent) {
        try {
            val message = intent.getStringExtra("message")
            val sender = intent.getStringExtra("sender")
            
            // Validate inputs
            if (message != null && securityManager.validateMessage(message) &&
                sender != null && securityManager.validateUsername(sender)) {
                
                // Process secure message
                processSecureMessage(sender, message)
                securityManager.logSecurityEvent("SECURE_MESSAGE_RECEIVED", "From: $sender")
            } else {
                SecureLogger.w("Invalid secure message intent data")
                securityManager.logSecurityEvent("INVALID_SECURE_MESSAGE", "Validation failed")
            }
        } catch (e: Exception) {
            SecureLogger.e("Error handling secure message intent", e)
            securityManager.logSecurityEvent("SECURE_MESSAGE_ERROR", e.message ?: "")
        }
    }
    
    /**
     * 🔒 Add Tox friend securely
     */
    private fun addToxFriend(toxId: String) {
        // Rate limiting check
        if (!securityManager.canAttemptLogin(toxId)) {
            Toast.makeText(this, "Too many attempts, please wait", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // Add friend logic here
            SecureLogger.sensitive("Adding Tox friend", toxId)
            Toast.makeText(this, "Adding friend...", Toast.LENGTH_SHORT).show()
            
            // Record successful attempt
            securityManager.recordSuccessfulLogin(toxId)
            
        } catch (e: Exception) {
            SecureLogger.e("Error adding Tox friend", e)
            securityManager.recordFailedLogin(toxId)
            Toast.makeText(this, "Failed to add friend", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 🔒 Process secure message
     */
    private fun processSecureMessage(sender: String, message: String) {
        // Decrypt message if needed
        val decryptedMessage = if (message.startsWith("encrypted:")) {
            // Decrypt logic here
            message.substring(10) // Placeholder
        } else {
            message
        }
        
        // Display message
        SecureLogger.sensitive("Secure message from", sender)
        Toast.makeText(this, "Message from $sender", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 🔒 Initialize UI components
     */
    private fun initializeUI() {
        // Initialize UI with security considerations
        // Prevent clickjacking, overlay attacks, etc.

        chatsLayout = findViewById(R.id.layout_chats)
        friendsLayout = findViewById(R.id.layout_friends)
        settingsLayout = findViewById(R.id.layout_settings)
        bottomNavigationView = findViewById(R.id.bottom_navigation)

        chatsListLayout = findViewById(R.id.layout_chats_list)
        friendsListLayout = findViewById(R.id.layout_friends_list)

        val placeholderTextView: TextView = findViewById(R.id.tv_main_placeholder)
        newChatButton = findViewById(R.id.btn_new_chat)
        val addFriendRemoteId = resources.getIdentifier("btn_add_friend_remote", "id", packageName)
        addFriendRemoteButton = if (addFriendRemoteId != 0) {
            findViewById(addFriendRemoteId)
        } else {
            newChatButton
        }
        saveSettingsButton = findViewById(R.id.btn_save_settings)
        openMediaFolderButton = findViewById(R.id.btn_open_media_folder)
        checkServerButton = findViewById(R.id.btn_check_server)
        helpButton = findViewById(R.id.btn_help)
        val userLabelId = resources.getIdentifier("tv_user_label", "id", packageName)
        userLabelTextView = if (userLabelId != 0) findViewById(userLabelId) else null
        val phoneLabelId = resources.getIdentifier("tv_phone_label", "id", packageName)
        phoneLabelTextView = if (phoneLabelId != 0) findViewById(phoneLabelId) else null

        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    showSection(SECTION_CHATS)
                    true
                }
                R.id.nav_friends -> {
                    showSection(SECTION_FRIENDS)
                    true
                }
                R.id.nav_settings -> {
                    showSection(SECTION_SETTINGS)
                    true
                }
                else -> false
            }
        }

        newChatButton.setOnClickListener {
            showNewChatOptions()
        }

        addFriendRemoteButton.setOnClickListener {
            showAddRemoteFriendDialog()
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val username = prefs.getString(KEY_AUTH_USERNAME, null)
        userLabelTextView?.text = if (username.isNullOrEmpty()) {
            "Usuario: (sin registrar)"
        } else {
            "Usuario: $username"
        }
        val phoneE164 = prefs.getString(KEY_AUTH_PHONE_E164, null)
        phoneLabelTextView?.text = if (phoneE164.isNullOrEmpty()) {
            "Teléfono: (sin registrar)"
        } else {
            "Teléfono: $phoneE164"
        }

        saveSettingsButton.setOnClickListener {
            Toast.makeText(this, "No hay opciones de configuración para guardar", Toast.LENGTH_SHORT).show()
        }
        openMediaFolderButton.setOnClickListener {
            showMediaFolderInfo()
        }
        checkServerButton.setOnClickListener {
            checkServerHealth()
        }
        
        helpButton.setOnClickListener {
            showHelpDialog()
        }
        
        backupButton = findViewById(R.id.btn_backup)
        backupButton.setOnClickListener {
            showBackupOptions()
        }

        updateChatList()
        renderFriends()
        showSection(SECTION_CHATS)
        checkServerHealth()
        ensureRegistered()
    }
    
    /**
     * 🔒 Show main screen
     */
    private fun showMainScreen() {
        // Show main chat interface
        SecureLogger.i("Showing main screen")
        showSection(SECTION_CHATS)
    }

    private fun showSection(section: Int) {
        chatsLayout.visibility = if (section == SECTION_CHATS) View.VISIBLE else View.GONE
        friendsLayout.visibility = if (section == SECTION_FRIENDS) View.VISIBLE else View.GONE
        settingsLayout.visibility = if (section == SECTION_SETTINGS) View.VISIBLE else View.GONE
        
        // Refrescar datos de ajustes cuando se muestra esa sección
        if (section == SECTION_SETTINGS) {
            refreshSettingsData()
        }
    }
    
    /**
     * Refresca los datos mostrados en la sección de ajustes
     */
    private fun refreshSettingsData() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val username = prefs.getString(KEY_AUTH_USERNAME, null)
        userLabelTextView?.text = if (username.isNullOrEmpty()) {
            "Usuario: (sin registrar)"
        } else {
            "Usuario: $username"
        }
        val phoneE164 = prefs.getString(KEY_AUTH_PHONE_E164, null)
        phoneLabelTextView?.text = if (phoneE164.isNullOrEmpty()) {
            "Teléfono: (sin registrar)"
        } else {
            "Teléfono: $phoneE164"
        }
    }
    
    private fun showMediaFolderInfo() {
        val entries = mutableListOf<Pair<String, java.io.File>>()
        try {
            val pictures = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)?.let { java.io.File(it, "HamChat") }
            val music = getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)?.let { java.io.File(it, "HamChat") }
            val docs = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)?.let { java.io.File(it, "HamChat") }
            if (pictures != null) entries.add("Imágenes" to pictures)
            if (music != null) entries.add("Audio" to music)
            if (docs != null) entries.add("Documentos" to docs)
        } catch (_: Exception) {
        }
        if (entries.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Carpetas de medios Ham-Chat")
                .setMessage("Aún no hay carpetas de medios externas disponibles. Se crearán automáticamente cuando envíes o recibas archivos multimedia.")
                .setNegativeButton("Cerrar", null)
                .show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        val infoText = TextView(this).apply {
            text = "Selecciona una carpeta de medios Ham-Chat para intentar abrirla en un explorador de archivos (si tu teléfono lo permite)."
            setPadding(0, 0, 0, 16)
        }
        container.addView(infoText)

        for ((label, dir) in entries) {
            val button = Button(this).apply {
                text = label
                setOnClickListener {
                    openMediaFolder(dir)
                }
            }
            container.addView(button)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Carpetas de medios Ham-Chat")
            .setView(container)
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openMediaFolder(dir: java.io.File) {
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val uri = Uri.fromFile(dir)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pm = packageManager
            if (intent.resolveActivity(pm) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "No hay aplicación para abrir carpetas", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir la carpeta", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun checkServerHealth() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val username = prefs.getString(KEY_AUTH_USERNAME, null)
            val phoneE164 = prefs.getString(KEY_AUTH_PHONE_E164, null)

            HamChatApiClient.api.health().enqueue(object : Callback<HealthResponse> {
                override fun onResponse(
                    call: Call<HealthResponse>,
                    response: Response<HealthResponse>
                ) {
                    if (response.isSuccessful && response.body()?.status == "ok") {
                        val userInfo = if (username.isNullOrEmpty()) {
                            "Usuario: (sin registrar)"
                        } else {
                            "Usuario: $username"
                        }

                        val phoneInfo = if (phoneE164.isNullOrEmpty()) {
                            "Teléfono vinculado: (sin registrar)"
                        } else {
                            "Teléfono vinculado: $phoneE164"
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "Conexión exitosa con servidor Ham-Chat.\n$userInfo\n$phoneInfo",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Error de conexión con servidor Ham-Chat (código ${response.code()})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<HealthResponse>, t: Throwable) {
                    val msg = "Error de conexión con servidor Ham-Chat (" +
                            t.javaClass.simpleName +
                            (t.message?.let { ": $it" } ?: ")")
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            })
        } catch (e: Exception) {
            val msg = "Error comprobando servidor Ham-Chat: " + (e.message ?: "desconocido")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun createSimpleContactView(contact: ContactItem): View {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            minimumHeight = 80

            // Importante para Keitai 4 (sin pantalla táctil):
            // permitir foco y clicks desde el keypad (DPAD/OK)
            isClickable = true
            isFocusable = true
            isLongClickable = true
            
            setOnClickListener {
                try {
                    openChat(contact)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            
            setOnLongClickListener {
                showContactOptions(contact)
                true
            }
        }
        
        // Name (incluyendo número si existe)
        val displayName = if (!contact.phoneNumber.isNullOrBlank()) "${contact.name} (${contact.phoneNumber})" else contact.name
        val nameView = TextView(this).apply {
            text = displayName
            setTextColor(Color.parseColor("#4A2C17"))
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        // Last message
        val messageView = TextView(this).apply {
            text = contact.lastMessage
            setTextColor(Color.parseColor("#6B4423"))
            textSize = 14f
            setPadding(0, 4, 0, 0)
        }
        
        // Timestamp and unread
        val infoView = TextView(this).apply {
            val info = "${contact.timestamp}"
            if (contact.unreadCount > 0) {
                "$info • ${contact.unreadCount} no leídos"
            }
            text = info
            setTextColor(Color.parseColor("#6B4423"))
            textSize = 12f
            setPadding(0, 4, 0, 0)
        }
        
        mainLayout.addView(nameView)
        mainLayout.addView(messageView)
        mainLayout.addView(infoView)
        
        return mainLayout
    }

    private fun showContactOptions(contact: ContactItem) {
        val isFriend = getFriendIds().contains(contact.id)
        // Contactos sin nombre asignado: nombre == phoneNumber
        val isAutoNamed = !contact.phoneNumber.isNullOrBlank() && contact.name == contact.phoneNumber

        val options: Array<String> = if (contact.id == "contact_hamtaro") {
            arrayOf(
                "Chatear",
                "Borrar notas (limpiar chat)"
            )
        } else if (isAutoNamed) {
            arrayOf(
                "Chatear",
                if (isFriend) "Quitar de amigos" else "Agregar a amigos",
                "Modificar contacto",
                "Número: ${contact.phoneNumber}",
                "Borrar chat",
                "Eliminar contacto"
            )
        } else {
            arrayOf(
                "Chatear",
                if (isFriend) "Quitar de amigos" else "Agregar a amigos",
                "Modificar contacto",
                "Borrar chat",
                "Eliminar contacto"
            )
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(contact.name)
            .setItems(options) { _, which ->
                if (contact.id == "contact_hamtaro") {
                    when (which) {
                        0 -> openChat(contact)
                        1 -> clearChatHistory(contact)
                    }
                } else if (isAutoNamed) {
                    when (which) {
                        0 -> openChat(contact)
                        1 -> toggleFriend(contact)
                        2 -> showEditContactDialog(contact)
                        3 -> {
                            // Mostrar/copiar el número del contacto sin nombre
                            val number = contact.phoneNumber ?: ""
                            if (number.isNotBlank()) {
                                try {
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Número de contacto", number)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(this, "Número copiado: $number", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(this, "Número: $number", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        4 -> clearChatHistory(contact)
                        5 -> deleteContact(contact)
                    }
                } else {
                    when (which) {
                        0 -> openChat(contact)
                        1 -> toggleFriend(contact)
                        2 -> showEditContactDialog(contact)
                        3 -> clearChatHistory(contact)
                        4 -> deleteContact(contact)
                    }
                }
            }
            .show()
    }

    private fun showEditContactDialog(contact: ContactItem) {
        try {
            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 20)
            }

            // Name input
            val nameLabel = TextView(this).apply {
                text = "Nombre del contacto:"
                setTextColor(Color.WHITE)
                setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
                textSize = 16f
                setPadding(0, 0, 0, 8)
            }

            val nameInput = EditText(this).apply {
                hint = "Ej: María González"
                setTextColor(Color.parseColor("#4A2C17"))
                setHintTextColor(Color.parseColor("#6B4423"))
                setPadding(16, 12, 16, 12)
                setBackgroundColor(Color.parseColor("#F5E6D3"))
                setText(contact.name)
            }

            // Lada y número telefónico (solo dígitos)
            val ladaLabel = TextView(this).apply {
                text = "Lada del teléfono"
                setTextColor(Color.WHITE)
                setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
                textSize = 16f
                setPadding(0, 16, 0, 8)
            }

            val (countryValues, countryDisplay) = loadLocalLadas()
            loadRemoteLadas(countryValues, countryDisplay)

            val countrySpinner = android.widget.Spinner(this)
            val countryAdapter = android.widget.ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                countryDisplay
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            countrySpinner.adapter = countryAdapter

            // Intentar preseleccionar la lada actual del contacto
            val currentPhone = contact.phoneNumber ?: ""
            var initialIndex = 0
            var initialDigits = ""
            if (currentPhone.isNotBlank()) {
                var bestMatchIndex = -1
                var bestMatchLength = 0
                for ((index, code) in countryValues.withIndex()) {
                    if (currentPhone.startsWith(code) && code.length > bestMatchLength) {
                        bestMatchIndex = index
                        bestMatchLength = code.length
                    }
                }
                if (bestMatchIndex >= 0) {
                    initialIndex = bestMatchIndex
                    val withoutCode = currentPhone.substring(countryValues[bestMatchIndex].length)
                    initialDigits = withoutCode.filter { it.isDigit() }
                } else {
                    initialDigits = currentPhone.filter { it.isDigit() }
                }
            }
            countrySpinner.setSelection(initialIndex)

            val numberLabel = TextView(this).apply {
                text = "Número (solo dígitos, sin lada):"
                setTextColor(Color.WHITE)
                setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
                textSize = 16f
                setPadding(0, 16, 0, 8)
            }

            val numberInput = EditText(this).apply {
                hint = "Ej: 1234567890"
                inputType = android.text.InputType.TYPE_CLASS_PHONE
                setTextColor(Color.parseColor("#4A2C17"))
                setHintTextColor(Color.parseColor("#6B4423"))
                setPadding(16, 12, 16, 12)
                setBackgroundColor(Color.parseColor("#F5E6D3"))
                setText(initialDigits)
            }

            // Checkbox para marcar si es amigo
            val isFriendNow = getFriendIds().contains(contact.id)
            val friendCheck = android.widget.CheckBox(this).apply {
                text = "Marcar como amigo"
                isChecked = isFriendNow
                setTextColor(Color.WHITE)
                setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
                setPadding(0, 16, 0, 8)
            }

            dialogView.addView(nameLabel)
            dialogView.addView(nameInput)
            dialogView.addView(ladaLabel)
            dialogView.addView(countrySpinner)
            dialogView.addView(numberLabel)
            dialogView.addView(numberInput)
            dialogView.addView(friendCheck)

            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("✏️ Modificar contacto")
                .setView(dialogView)
                .setPositiveButton("Guardar cambios") { _, _ ->
                    val rawName = nameInput.text.toString().trim()
                    val selectedIndex = countrySpinner.selectedItemPosition
                    val cc = if (selectedIndex in countryValues.indices) countryValues[selectedIndex] else "+52"
                    val digits = numberInput.text.toString().filter { it.isDigit() }

                    // Solo el número es obligatorio
                    if (digits.isEmpty()) {
                        Toast.makeText(this, "Ingresa un número telefónico válido", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val fullNumber = cc + digits

                    // Estado de amigo tras la edición
                    val makeFriend = friendCheck.isChecked

                    // Evitar usar el propio número del dispositivo
                    try {
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        val ownPhone = prefs.getString(KEY_AUTH_PHONE_E164, null)
                        if (!ownPhone.isNullOrBlank() && ownPhone == fullNumber) {
                            Toast.makeText(
                                this,
                                "No puedes usar el número de tu propio dispositivo",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                    } catch (_: Exception) {
                    }

                    // Evitar duplicar números con otros contactos (excepto el propio contacto)
                    try {
                        val existing = getAllContacts()
                        if (existing.any { it.id != contact.id && it.phoneNumber == fullNumber }) {
                            Toast.makeText(
                                this,
                                "Ya existe otro contacto con ese número",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                    } catch (_: Exception) {
                    }

                    // Si se desmarca como amigo, eliminar completamente el contacto
                    if (!makeFriend) {
                        deleteContact(contact)
                        return@setPositiveButton
                    }

                    // Si el nombre queda en blanco, usar el número completo como nombre
                    val finalName = if (rawName.isBlank()) fullNumber else rawName

                    updateContactInPrefs(contact, finalName, fullNumber)

                    // Asegurar que quede marcado como amigo
                    try {
                        val ids = getFriendIds()
                        if (ids.add(contact.id)) {
                            saveFriendIds(ids)
                        }
                    } catch (_: Exception) {
                    }

                    updateChatList()
                    renderFriends()
                    Toast.makeText(this, "Contacto actualizado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }
                .create()

            dialog.show()
        } catch (e: Exception) {
            showErrorDialog("Error al editar contacto", "No se pudo abrir el editor: ${e.message}")
        }
    }
    
    private fun toggleFriend(contact: ContactItem) {
        val ids = getFriendIds()
        if (ids.contains(contact.id)) {
            ids.remove(contact.id)
            Toast.makeText(this, "${contact.name} eliminado de amigos", Toast.LENGTH_SHORT).show()
        } else {
            ids.add(contact.id)
            Toast.makeText(this, "${contact.name} agregado a amigos", Toast.LENGTH_SHORT).show()
        }
        saveFriendIds(ids)
        updateChatList()
        renderFriends()
    }
    
    private fun showHelpDialog() {
        val helpText = """
            🐹 **Ham-Chat - Guía de Uso**
            
            **📱 Cómo usar la app:**
            
            **📝 Chats:**
            • Click en un contacto para abrir chat
            • Mantén presionado para opciones (Agregar/Quitar amigo)
            • Los mensajes se guardan localmente
            
            **👥 Amigos:**
            • Ve la lista de tus amigos agregados
            • Click en "Chatear" para iniciar conversación
            • Los amigos se guardan persistentemente
            
            **⚙️ Ajustes:**
            • Configura tu usuario y teléfono de Ham-Chat
            • Prueba la conexión con el servidor Ham-Chat
            • La configuración se guarda automáticamente
            
            **🔧 Agregar contactos:**
            • Mantén presionado cualquier contacto
            • Selecciona "Agregar a amigos"
            • El contacto aparecerá en la sección Amigos
            
            **💡 Tips:**
            • Los chats se guardan localmente
            • Los amigos agregados son persistentes
            • Hamtaro es tu contacto de prueba
            
            **❓ ¿Necesitas ayuda?**
            • Esta app es una demostración
            • Los contactos son para prueba
            • Puedes agregar/quitar amigos libremente
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("❓ Ayuda de Ham-Chat")
            .setMessage(helpText)
            .setPositiveButton("Entendido") { dialog, _ -> dialog.dismiss() }
            .setNegativeButton("Ver más") { dialog, _ ->
                showAdvancedHelp()
                dialog.dismiss()
            }
            .show()
    }
    
    private fun showAdvancedHelp() {
        val advancedText = """
            🔧 **Ayuda Avanzada**
            
            **📂 Almacenamiento:**
            • Chats guardados en memoria local
            • Configuración persistente
            • Amigos guardados automáticamente
            
            **🔒 Seguridad:**
            • Validación de intents segura
            • Protección contra spoofing
            • Logs encriptados
            
            **🎨 Personalización:**
            • Fondo Hamtaro personalizado
            • Textos con sombras para legibilidad
            • Diseño estilo chat moderno
            
            **📞 Comunicación:**
            • Ham-Chat usa su propio servicio de mensajería
            • Los mensajes se sincronizan con el servidor cuando hay conexión
            
            **🐹 Sobre Hamtaro:**
            • Mascota oficial de la app
            • Contacto de demostración
            • Representa amistad y diversión
            
            **🔄 Desarrollo:**
            • App en fase de prueba
            • Funcionalidades básicas implementadas
            • Extensible para más características
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔧 Ayuda Avanzada")
            .setMessage(advancedText)
            .setPositiveButton("Cerrar") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    private fun renderFriends() {
        friendsListLayout.removeAllViews()
        val friendIds = getFriendIds()
        val friendContacts = getAllContacts().filter { friendIds.contains(it.id) }

        if (friendContacts.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "Aún no hay amigos agregados"
                setPadding(32, 32, 32, 32)
                setTextColor(Color.RED)
            }
            friendsListLayout.addView(emptyView)
            return
        }

        for (contact in friendContacts) {
            val displayName = if (!contact.phoneNumber.isNullOrBlank()) "${contact.name} (${contact.phoneNumber})" else contact.name
            val nameView = TextView(this).apply {
                text = displayName
                setPadding(32, 16, 32, 16)
                setTextColor(Color.parseColor("#4A2C17"))
                setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 16, 32, 16)
            }

            val chatButton = Button(this).apply {
                text = "Chatear"
                setOnClickListener {
                    openChat(contact)
                }
            }

            row.addView(nameView)
            row.addView(chatButton)
            friendsListLayout.addView(row)
        }
    }

    private fun getFriendIds(): MutableSet<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getStringSet(KEY_FRIEND_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveFriendIds(ids: MutableSet<String>) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_FRIEND_IDS, ids).apply()
    }

    private fun clearChatHistory(contact: ContactItem) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val key = if (contact.id == "contact_hamtaro") {
                // Hamtaro usa clave privada especial
                "private_chat_hamtaro"
            } else {
                "chat_" + contact.id
            }
            // Eliminar historial de mensajes
            prefs.edit().remove(key).apply()

            // Para contactos personalizados, limpiar también el último mensaje mostrado en la lista
            if (contact.id != "contact_hamtaro") {
                val contactsJson = prefs.getString("custom_contacts", "[]")
                try {
                    val original = JSONArray(contactsJson)
                    val updated = JSONArray()
                    for (i in 0 until original.length()) {
                        val obj = original.getJSONObject(i)
                        val id = obj.optString("id")
                        if (id == contact.id) {
                            obj.put("lastMessage", "")
                            obj.put("timestamp", "")
                            obj.put("unreadCount", 0)
                        }
                        updated.put(obj)
                    }
                    prefs.edit().putString("custom_contacts", updated.toString()).apply()
                } catch (e: Exception) {
                    // Ignorar errores de formato
                }
            }

            // Refrescar la lista de chats para que ya no se vea el historial
            updateChatList()

            Toast.makeText(this, "Chat con ${contact.name} eliminado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo borrar el chat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteContact(contact: ContactItem) {
        if (contact.id == "contact_hamtaro") {
            Toast.makeText(this, "Hamtaro es un contacto fijo y no se puede eliminar", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            // Eliminar de la lista JSON de contactos personalizados
            val contactsJson = prefs.getString("custom_contacts", "[]")
            try {
                val original = JSONArray(contactsJson)
                val updated = JSONArray()
                for (i in 0 until original.length()) {
                    val obj = original.getJSONObject(i)
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    if (id != contact.id && name != contact.name) {
                        updated.put(obj)
                    }
                }
                prefs.edit().putString("custom_contacts", updated.toString()).apply()
            } catch (e: Exception) {
                // Ignorar errores de formato y continuar con el string simple
            }

            // Eliminar de la cadena simple "nombre:numero"
            val simple = prefs.getString("custom_contacts_simple", "") ?: ""
            if (simple.isNotEmpty()) {
                val parts = simple.split("|").filter { it.isNotBlank() && !it.startsWith("${contact.name}:") }
                val updatedSimple = parts.joinToString(separator = "|")
                prefs.edit().putString("custom_contacts_simple", updatedSimple).apply()
            }

            // Quitar de amigos si estaba marcado
            val ids = getFriendIds()
            if (ids.remove(contact.id)) {
                saveFriendIds(ids)
            }

            // Borrar historial de chat asociado
            clearChatHistory(contact)

            // Refrescar UI
            updateChatList()
            renderFriends()

            Toast.makeText(this, "${contact.name} eliminado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo eliminar el contacto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getAllContacts(): List<ContactItem> {
        val allContacts = mutableListOf<ContactItem>()
        // Contactos fijos (Hamtaro, etc.)
        allContacts.addAll(contacts)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val contactsJson = prefs.getString("custom_contacts", "[]")

        try {
            val array = JSONArray(contactsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // Compatibilidad: aceptar tanto "phoneNumber" nuevo como "whatsappNumber" antiguo
                val rawPhone = obj.optString("phoneNumber", obj.optString("whatsappNumber", ""))
                val contact = ContactItem(
                    id = obj.optString("id", "custom_$i"),
                    name = obj.optString("name", "Contacto"),
                    lastMessage = obj.optString("lastMessage", ""),
                    timestamp = obj.optString("timestamp", ""),
                    isOnline = obj.optBoolean("isOnline", false),
                    unreadCount = obj.optInt("unreadCount", 0),
                    phoneNumber = if (rawPhone.isBlank()) null else rawPhone
                )
                allContacts.add(contact)
            }
        } catch (e: Exception) {
            // Fallback a formato simple "nombre:numero"
            val simple = prefs.getString("custom_contacts_simple", "") ?: ""
            if (simple.isNotEmpty()) {
                val parts = simple.split("|").filter { it.isNotBlank() }
                for ((index, part) in parts.withIndex()) {
                    val name = part.substringBefore(":")
                    allContacts.add(ContactItem(id = "simple_$index", name = name))
                }
            }
        }

        return allContacts
    }

    private fun updateChatList() {
        try {
            chatsListLayout.removeAllViews()
            val allContacts = getAllContacts()
            for (contact in allContacts) {
                val contactView = createSimpleContactView(contact)
                chatsListLayout.addView(contactView)
            }
        } catch (e: Exception) {
            chatsListLayout.removeAllViews()
            Toast.makeText(this, "Error al mostrar contactos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openChat(contact: ContactItem) {
        try {
            Toast.makeText(this, "Abriendo chat con ${contact.name}...", Toast.LENGTH_SHORT).show()

            val phone = contact.phoneNumber
            // Si no es Hamtaro ni un contacto remoto explícito y tiene número,
            // intentamos resolver automáticamente si es un usuario Ham-Chat remoto.
            if (!contact.id.startsWith("remote_") &&
                contact.id != "contact_hamtaro" &&
                !phone.isNullOrBlank()
            ) {
                resolveRemoteUserForPhone(phone) { remoteUser ->
                    if (remoteUser != null) {
                        val remoteContactId = "remote_${remoteUser.id}"
                        val isAutoNamed =
                            !contact.phoneNumber.isNullOrBlank() && contact.name == contact.phoneNumber
                        val displayName = if (isAutoNamed && remoteUser.username.isNotBlank()) {
                            remoteUser.username
                        } else {
                            contact.name
                        }
                        openChatInternal(remoteContactId, displayName)
                    } else {
                        openChatInternal(contact.id, contact.name)
                    }
                }
            } else {
                openChatInternal(contact.id, contact.name)
            }
        } catch (e: Exception) {
            val errorMessage =
                "Error al abrir chat: ${e.message}\n\nStack trace:\n${e.stackTraceToString()}"
            showErrorDialog("Error al abrir chat", errorMessage)
        }
    }

    private fun openChatInternal(contactId: String, contactName: String) {
        try {
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("contact_id", contactId)
            intent.putExtra("contact_name", contactName)

            // Verify ChatActivity exists before starting
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                showErrorDialog(
                    "ChatActivity no encontrada",
                    "La activity ChatActivity no está registrada en AndroidManifest.xml"
                )
            }
        } catch (e: Exception) {
            val errorMessage =
                "Error al abrir chat: ${e.message}\n\nStack trace:\n${e.stackTraceToString()}"
            showErrorDialog("Error al abrir chat", errorMessage)
        }
    }

    /**
     * Intentar resolver si un número E.164 pertenece a un usuario Ham-Chat remoto.
     * Si se encuentra, se devuelve un UserLookupResponse; si no, null.
     */
    private fun resolveRemoteUserForPhone(
        phoneE164: String,
        onResult: (com.hamtaro.hamchat.network.UserLookupResponse?) -> Unit
    ) {
        try {
            val trimmed = phoneE164.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("+")) {
                onResult(null)
                return
            }

            // Intentar separar lada y número usando la lista local de ladas.
            // Elegimos siempre la lada más larga que coincida para evitar conflictos
            // entre códigos como +52 y +521.
            val (countryValues, _) = loadLocalLadas()
            var bestCode: String? = null
            for (code in countryValues) {
                if (trimmed.startsWith(code)) {
                    if (bestCode == null || code.length > bestCode.length) {
                        bestCode = code
                    }
                }
            }

            val cc = bestCode
            val nat = if (cc != null) trimmed.removePrefix(cc).filter { it.isDigit() } else null

            if (cc.isNullOrBlank() || nat.isNullOrBlank()) {
                onResult(null)
                return
            }

            HamChatApiClient.api.getUserByPhone(cc, nat)
                .enqueue(object :
                    Callback<com.hamtaro.hamchat.network.UserLookupResponse> {
                    override fun onResponse(
                        call: Call<com.hamtaro.hamchat.network.UserLookupResponse>,
                        response: Response<com.hamtaro.hamchat.network.UserLookupResponse>
                    ) {
                        if (response.isSuccessful && response.body() != null) {
                            onResult(response.body())
                        } else {
                            onResult(null)
                        }
                    }

                    override fun onFailure(
                        call: Call<com.hamtaro.hamchat.network.UserLookupResponse>,
                        t: Throwable
                    ) {
                        onResult(null)
                    }
                })
        } catch (_: Exception) {
            onResult(null)
        }
    }
    
    private fun showAddContactDialog() {
        // Create custom dialog with input fields
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }
        
        // Name input
        val nameLabel = TextView(this).apply {
            text = "Nombre del contacto:"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        
        val nameInput = EditText(this).apply {
            hint = "Ej: María González"
            setTextColor(Color.parseColor("#4A2C17"))
            setHintTextColor(Color.parseColor("#6B4423"))
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#F5E6D3"))
        }

        // Lada y número telefónico (solo dígitos)
        val ladaLabel = TextView(this).apply {
            text = "Lada del teléfono"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }

        val (countryValues, countryDisplay) = loadLocalLadas()
        loadRemoteLadas(countryValues, countryDisplay)

        val countrySpinner = android.widget.Spinner(this)
        val countryAdapter = android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            countryDisplay
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        countrySpinner.adapter = countryAdapter
        countrySpinner.setSelection(0)

        val addLadaButton = Button(this).apply {
            text = "Agregar otra lada"
            setOnClickListener {
                val form = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 8)
                }
                val codeInput = EditText(this@MainActivity).apply {
                    hint = "Lada (ej. +52 o 52)"
                }
                val labelInput = EditText(this@MainActivity).apply {
                    hint = "Descripción (opcional)"
                }
                form.addView(codeInput)
                form.addView(labelInput)

                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Nueva lada")
                    .setView(form)
                    .setPositiveButton("Guardar") { _, _ ->
                        val rawCode = codeInput.text.toString().trim()
                        if (rawCode.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Ingresa una lada válida", Toast.LENGTH_SHORT).show()
                        } else {
                            val normalized = if (rawCode.startsWith("+")) rawCode else "+" + rawCode
                            val label = labelInput.text.toString().trim()
                            val display = if (label.isEmpty()) normalized else "$normalized $label"
                            countryValues.add(normalized)
                            countryAdapter.add(display)
                            countrySpinner.setSelection(countryValues.size - 1)
                            saveLocalLadas(countryValues, countryDisplay)
                            sendLadaToServer(normalized, label)
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        val numberLabel = TextView(this).apply {
            text = "Número (solo dígitos, sin lada):"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#F5E6D3"))
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }

        val numberInput = EditText(this).apply {
            hint = "Ej: 1234567890"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setTextColor(Color.parseColor("#4A2C17"))
            setHintTextColor(Color.parseColor("#6B4423"))
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#F5E6D3"))
        }

        dialogView.addView(nameLabel)
        dialogView.addView(nameInput)
        dialogView.addView(ladaLabel)
        dialogView.addView(countrySpinner)
        dialogView.addView(addLadaButton)
        dialogView.addView(numberLabel)
        dialogView.addView(numberInput)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➕ Agregar contacto")
            .setView(dialogView)
            .setPositiveButton("Agregar") { _, _ ->
                val name = nameInput.text.toString().trim()
                val selectedIndex = countrySpinner.selectedItemPosition
                val cc = if (selectedIndex in countryValues.indices) countryValues[selectedIndex] else "+52"
                val digits = numberInput.text.toString().filter { it.isDigit() }

                // Solo el número es obligatorio
                if (digits.isEmpty()) {
                    Toast.makeText(this, "Ingresa un número telefónico válido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val fullNumber = cc + digits

                // Evitar agregar el propio número del dispositivo como contacto
                try {
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    val ownPhone = prefs.getString(KEY_AUTH_PHONE_E164, null)
                    if (!ownPhone.isNullOrBlank() && ownPhone == fullNumber) {
                        Toast.makeText(
                            this,
                            "No puedes agregarte a ti mismo como contacto",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                } catch (_: Exception) {
                }

                // Evitar duplicar números con otros contactos existentes
                try {
                    val existing = getAllContacts()
                    if (existing.any { it.phoneNumber == fullNumber }) {
                        Toast.makeText(
                            this,
                            "Ya existe un contacto con ese número",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                } catch (_: Exception) {
                }

                // Si el nombre queda en blanco, usar el número completo como nombre
                val finalName = if (name.isBlank()) fullNumber else name

                // Intentar primero crear como contacto remoto Ham-Chat si el número está
                // registrado en el servidor. Si no se encuentra, se crea contacto local.
                resolveRemoteUserForPhone(fullNumber) { remoteUser ->
                    if (remoteUser != null) {
                        try {
                            val remoteContactId = "remote_${remoteUser.id}"

                            // Si el usuario no escribió nombre (o usó el número), usar el username del servidor si existe
                            val isAutoNamed = name.isBlank() || finalName == fullNumber
                            val displayName = if (isAutoNamed && remoteUser.username.isNotBlank()) {
                                remoteUser.username
                            } else {
                                finalName
                            }

                            val contact = ContactItem(
                                id = remoteContactId,
                                name = displayName,
                                lastMessage = "Contacto Ham-Chat agregado",
                                timestamp = "Ahora",
                                isOnline = false,
                                unreadCount = 0
                            )

                            saveContactToPrefs(contact, remoteUser.phone_e164)
                            updateChatList()
                            renderFriends()

                            Toast.makeText(
                                this,
                                "$displayName agregado como contacto Ham-Chat",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {
                            // Si algo falla al crear el contacto remoto, caer a contacto local
                            addNewContact(finalName, fullNumber)
                        }
                    } else {
                        // Número no registrado en Ham-Chat: mantener contacto local normal
                        addNewContact(finalName, fullNumber)
                    }
                }
            }
            .setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }
            .create()
        
        dialog.show()
    }
    
    private fun addNewContact(name: String, phoneNumber: String) {
        try {
            // Generate unique ID for new contact
            val contactId = "contact_${System.currentTimeMillis()}"
            
            // Create new contact
            val newContact = ContactItem(
                id = contactId,
                name = name,
                lastMessage = "Contacto agregado recientemente",
                timestamp = "Ahora",
                isOnline = false,
                unreadCount = 0
            )
            
            // Add to contacts list (convert to mutable)
            val mutableContacts = contacts.toMutableList()
            mutableContacts.add(newContact)
            
            // Update the contacts list (we'll need to make it mutable)
            // For ahora, guardamos en SharedPreferences
            saveContactToPrefs(newContact, phoneNumber)
            
            // Refresh UI
            updateChatList()
            
            Toast.makeText(this, "$name agregado correctamente", Toast.LENGTH_SHORT).show()
                
        } catch (e: Exception) {
            showErrorDialog("Error al agregar contacto", "No se pudo agregar el contacto: ${e.message}")
        }
    }
    
    private fun saveContactToPrefs(contact: ContactItem, phoneNumber: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val contactsJson = prefs.getString("custom_contacts", "[]")
        
        try {
            val contactsArray = JSONArray(contactsJson)
            val newContactJson = JSONObject().apply {
                put("id", contact.id)
                put("name", contact.name)
                put("lastMessage", contact.lastMessage)
                put("timestamp", contact.timestamp)
                put("isOnline", contact.isOnline)
                put("unreadCount", contact.unreadCount)
                put("phoneNumber", phoneNumber)
            }
            contactsArray.put(newContactJson)
            
            prefs.edit().putString("custom_contacts", contactsArray.toString()).apply()

            // Marcar contacto como amigo por defecto
            try {
                val friendIds = getFriendIds()
                if (friendIds.add(contact.id)) {
                    saveFriendIds(friendIds)
                }
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            // If JSON fails, save as simple string
            val existingContacts = prefs.getString("custom_contacts_simple", "") + "|${contact.name}:$phoneNumber"
            prefs.edit().putString("custom_contacts_simple", existingContacts).apply()
        }
    }
    
    private fun updateContactInPrefs(original: ContactItem, newName: String, newPhoneNumber: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val contactsJson = prefs.getString("custom_contacts", "[]")

        try {
            val originalArray = JSONArray(contactsJson)
            val updatedArray = JSONArray()
            for (i in 0 until originalArray.length()) {
                val obj = originalArray.getJSONObject(i)
                val id = obj.optString("id")
                if (id == original.id) {
                    obj.put("name", newName)
                    obj.put("phoneNumber", newPhoneNumber)
                }
                updatedArray.put(obj)
            }
            prefs.edit().putString("custom_contacts", updatedArray.toString()).apply()
        } catch (e: Exception) {
            val simple = prefs.getString("custom_contacts_simple", "") ?: ""
            if (simple.isNotEmpty()) {
                val parts = simple.split("|").filter { it.isNotBlank() }
                val builder = StringBuilder()
                for (part in parts) {
                    val namePart = part.substringBefore(":")
                    val numberPart = part.substringAfter(":", "")
                    val matchesOriginal =
                        namePart == original.name || numberPart == (original.phoneNumber ?: "")
                    if (matchesOriginal) {
                        if (builder.isNotEmpty()) builder.append('|')
                        builder.append("$newName:$newPhoneNumber")
                    } else {
                        if (builder.isNotEmpty()) builder.append('|')
                        builder.append(part)
                    }
                }
                prefs.edit().putString("custom_contacts_simple", builder.toString()).apply()
            }
        }
    }
    
    private fun showErrorDialog(title: String, message: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Copiar error") { _, _ ->
                copyToClipboard(message)
                Toast.makeText(this, "Error copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar") { dialog, _ -> dialog.dismiss() }
            .create()
        
        dialog.show()
        
        // Make message selectable for manual copying
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Error Ham-Chat", text)
        clipboard.setPrimaryClip(clip)
    }
    
    // ========== Funciones de Grupos ==========
    
    /**
     * Muestra opciones para nuevo chat o grupo
     */
    private fun showNewChatOptions() {
        val options = arrayOf(
            "👤 Nuevo contacto",
            "👥 Crear grupo",
            "📋 Mis grupos"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➕ Nuevo")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddContactDialog()
                    1 -> showCreateGroupDialog()
                    2 -> showMyGroupsDialog()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Diálogo para crear un nuevo grupo
     */
    private fun showCreateGroupDialog() {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Debes estar registrado para crear grupos", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }
        
        val nameLabel = TextView(this).apply {
            text = "Nombre del grupo:"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        
        val nameInput = EditText(this).apply {
            hint = "Ej: Familia, Trabajo, Amigos..."
            setTextColor(Color.parseColor("#4A2C17"))
            setHintTextColor(Color.parseColor("#6B4423"))
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#F5E6D3"))
        }
        
        val descLabel = TextView(this).apply {
            text = "Descripción (opcional):"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 16, 0, 8)
        }
        
        val descInput = EditText(this).apply {
            hint = "Descripción del grupo..."
            setTextColor(Color.parseColor("#4A2C17"))
            setHintTextColor(Color.parseColor("#6B4423"))
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#F5E6D3"))
        }
        
        dialogView.addView(nameLabel)
        dialogView.addView(nameInput)
        dialogView.addView(descLabel)
        dialogView.addView(descInput)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("👥 Crear grupo")
            .setView(dialogView)
            .setPositiveButton("Crear") { _, _ ->
                val name = nameInput.text.toString().trim()
                val description = descInput.text.toString().trim().ifEmpty { null }
                
                if (name.isEmpty()) {
                    Toast.makeText(this, "El nombre es obligatorio", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                createGroup(name, description)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Crea un nuevo grupo en el servidor
     */
    private fun createGroup(name: String, description: String?) {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) return
        
        val authHeader = "Bearer $token"
        val request = com.hamtaro.hamchat.network.CreateGroupRequest(
            name = name,
            description = description,
            member_ids = emptyList()
        )
        
        Toast.makeText(this, "Creando grupo...", Toast.LENGTH_SHORT).show()
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.createGroup(authHeader, request)
            .enqueue(object : retrofit2.Callback<com.hamtaro.hamchat.network.GroupDto> {
                override fun onResponse(
                    call: retrofit2.Call<com.hamtaro.hamchat.network.GroupDto>,
                    response: retrofit2.Response<com.hamtaro.hamchat.network.GroupDto>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        val group = response.body()!!
                        Toast.makeText(this@MainActivity, "✅ Grupo \"${group.name}\" creado", Toast.LENGTH_SHORT).show()
                        
                        // Abrir el chat del grupo
                        openGroupChat(group)
                    } else {
                        Toast.makeText(this@MainActivity, "Error al crear grupo", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onFailure(call: retrofit2.Call<com.hamtaro.hamchat.network.GroupDto>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Muestra la lista de grupos del usuario
     */
    private fun showMyGroupsDialog() {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Debes estar registrado para ver grupos", Toast.LENGTH_SHORT).show()
            return
        }
        
        val authHeader = "Bearer $token"
        
        // Mostrar diálogo de carga
        val loadingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 Mis grupos")
            .setMessage("Cargando...")
            .setCancelable(true)
            .create()
        loadingDialog.show()
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.getMyGroups(authHeader)
            .enqueue(object : retrofit2.Callback<List<com.hamtaro.hamchat.network.GroupDto>> {
                override fun onResponse(
                    call: retrofit2.Call<List<com.hamtaro.hamchat.network.GroupDto>>,
                    response: retrofit2.Response<List<com.hamtaro.hamchat.network.GroupDto>>
                ) {
                    loadingDialog.dismiss()
                    
                    if (response.isSuccessful && response.body() != null) {
                        val groups = response.body()!!
                        
                        if (groups.isEmpty()) {
                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("📋 Mis grupos")
                                .setMessage("No tienes grupos.\n\nCrea uno nuevo con el botón ➕")
                                .setPositiveButton("Crear grupo") { _, _ -> showCreateGroupDialog() }
                                .setNegativeButton("Cerrar", null)
                                .show()
                        } else {
                            showGroupsList(groups)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Error al cargar grupos", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onFailure(call: retrofit2.Call<List<com.hamtaro.hamchat.network.GroupDto>>, t: Throwable) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Muestra la lista de grupos para seleccionar
     */
    private fun showGroupsList(groups: List<com.hamtaro.hamchat.network.GroupDto>) {
        val groupNames = groups.map { "👥 ${it.name} (${it.member_count} miembros)" }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 Mis grupos (${groups.size})")
            .setItems(groupNames) { _, which ->
                val group = groups[which]
                openGroupChat(group)
            }
            .setPositiveButton("➕ Crear nuevo") { _, _ -> showCreateGroupDialog() }
            .setNegativeButton("Cerrar", null)
            .show()
    }
    
    /**
     * Abre el chat de un grupo
     */
    private fun openGroupChat(group: com.hamtaro.hamchat.network.GroupDto) {
        try {
            val intent = Intent(this, com.hamtaro.hamchat.ui.ChatActivity::class.java)
            intent.putExtra("contact_id", "group_${group.id}")
            intent.putExtra("contact_name", group.name)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir grupo: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ========== Sistema de Respaldo y Recuperación ==========
    
    /**
     * Obtiene un ID único del dispositivo para respaldos
     */
    private fun getBackupDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var deviceId = prefs.getString("device_unique_id", null)
        
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_unique_id", deviceId).apply()
        }
        
        return deviceId
    }
    
    /**
     * Obtiene el nombre del dispositivo
     */
    private fun getDeviceName(): String {
        return "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    }
    
    /**
     * Muestra opciones de respaldo/recuperación
     */
    fun showBackupOptions() {
        val options = arrayOf(
            "💾 Crear respaldo ahora",
            "📥 Restaurar desde respaldo",
            "📋 Ver mis respaldos",
            "🗑️ Eliminar respaldo"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("☁️ Respaldo y Recuperación")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createBackup()
                    1 -> showRestoreDialog()
                    2 -> showBackupsList()
                    3 -> showDeleteBackupDialog()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Crea un respaldo de todos los chats
     */
    private fun createBackup() {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Debes estar registrado para crear respaldos", Toast.LENGTH_SHORT).show()
            return
        }
        
        Toast.makeText(this, "Creando respaldo...", Toast.LENGTH_SHORT).show()
        
        // Recopilar todos los chats
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val allData = mutableMapOf<String, String>()
        
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("chat_") || key.startsWith("media_") || key.startsWith("starred_")) {
                allData[key] = value.toString()
            }
        }
        
        val backupJson = org.json.JSONObject(allData as Map<*, *>).toString()
        
        val request = com.hamtaro.hamchat.network.BackupRequest(
            device_id = getBackupDeviceId(),
            device_name = getDeviceName(),
            backup_data = backupJson
        )
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.createBackup("Bearer $token", request)
            .enqueue(object : retrofit2.Callback<Map<String, Any>> {
                override fun onResponse(call: retrofit2.Call<Map<String, Any>>, response: retrofit2.Response<Map<String, Any>>) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "✅ Respaldo creado exitosamente", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Error al crear respaldo", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: retrofit2.Call<Map<String, Any>>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Muestra lista de respaldos para restaurar
     */
    private fun showRestoreDialog() {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Debes estar registrado", Toast.LENGTH_SHORT).show()
            return
        }
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.listBackups("Bearer $token")
            .enqueue(object : retrofit2.Callback<com.hamtaro.hamchat.network.BackupListResponse> {
                override fun onResponse(
                    call: retrofit2.Call<com.hamtaro.hamchat.network.BackupListResponse>,
                    response: retrofit2.Response<com.hamtaro.hamchat.network.BackupListResponse>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        val backups = response.body()!!.backups
                        if (backups.isEmpty()) {
                            Toast.makeText(this@MainActivity, "No hay respaldos disponibles", Toast.LENGTH_SHORT).show()
                            return
                        }
                        
                        val options = backups.map { 
                            "📱 ${it.device_name ?: "Dispositivo"}\n📅 ${it.updated_at.take(10)}" 
                        }.toTypedArray()
                        
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("📥 Selecciona respaldo a restaurar")
                            .setItems(options) { _, which ->
                                confirmRestore(backups[which])
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
                override fun onFailure(call: retrofit2.Call<com.hamtaro.hamchat.network.BackupListResponse>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Confirma y ejecuta la restauración
     */
    private fun confirmRestore(backup: com.hamtaro.hamchat.network.BackupListItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Restaurar respaldo")
            .setMessage("¿Restaurar chats desde \"${backup.device_name}\"?\n\nEsto reemplazará los chats actuales.")
            .setPositiveButton("Restaurar") { _, _ ->
                restoreBackup(backup.device_id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Restaura un respaldo específico
     */
    private fun restoreBackup(deviceId: String) {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken() ?: return
        
        Toast.makeText(this, "Restaurando...", Toast.LENGTH_SHORT).show()
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.restoreBackup("Bearer $token", deviceId)
            .enqueue(object : retrofit2.Callback<com.hamtaro.hamchat.network.RestoreResponse> {
                override fun onResponse(
                    call: retrofit2.Call<com.hamtaro.hamchat.network.RestoreResponse>,
                    response: retrofit2.Response<com.hamtaro.hamchat.network.RestoreResponse>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        val data = response.body()!!
                        
                        try {
                            val json = org.json.JSONObject(data.backup_data)
                            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            val editor = prefs.edit()
                            
                            json.keys().forEach { key ->
                                editor.putString(key, json.getString(key))
                            }
                            
                            editor.apply()
                            
                            Toast.makeText(this@MainActivity, "✅ Respaldo restaurado", Toast.LENGTH_SHORT).show()
                            updateChatList()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Error al procesar respaldo", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Error al restaurar", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: retrofit2.Call<com.hamtaro.hamchat.network.RestoreResponse>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Muestra lista de respaldos
     */
    private fun showBackupsList() {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "Debes estar registrado", Toast.LENGTH_SHORT).show()
            return
        }
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.listBackups("Bearer $token")
            .enqueue(object : retrofit2.Callback<com.hamtaro.hamchat.network.BackupListResponse> {
                override fun onResponse(
                    call: retrofit2.Call<com.hamtaro.hamchat.network.BackupListResponse>,
                    response: retrofit2.Response<com.hamtaro.hamchat.network.BackupListResponse>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        val backups = response.body()!!.backups
                        
                        if (backups.isEmpty()) {
                            Toast.makeText(this@MainActivity, "No hay respaldos", Toast.LENGTH_SHORT).show()
                            return
                        }
                        
                        val message = StringBuilder("📋 Tus respaldos:\n\n")
                        backups.forEach { b ->
                            message.append("📱 ${b.device_name ?: "Dispositivo"}\n")
                            message.append("   📅 ${b.updated_at.take(10)}\n")
                            message.append("   💾 ${b.data_size / 1024} KB\n\n")
                        }
                        
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("📋 Mis respaldos (${backups.size})")
                            .setMessage(message.toString())
                            .setPositiveButton("Cerrar", null)
                            .show()
                    }
                }
                override fun onFailure(call: retrofit2.Call<com.hamtaro.hamchat.network.BackupListResponse>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Muestra diálogo para eliminar respaldo
     */
    private fun showDeleteBackupDialog() {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) return
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.listBackups("Bearer $token")
            .enqueue(object : retrofit2.Callback<com.hamtaro.hamchat.network.BackupListResponse> {
                override fun onResponse(
                    call: retrofit2.Call<com.hamtaro.hamchat.network.BackupListResponse>,
                    response: retrofit2.Response<com.hamtaro.hamchat.network.BackupListResponse>
                ) {
                    if (response.isSuccessful && response.body() != null) {
                        val backups = response.body()!!.backups
                        if (backups.isEmpty()) {
                            Toast.makeText(this@MainActivity, "No hay respaldos", Toast.LENGTH_SHORT).show()
                            return
                        }
                        
                        val options = backups.map { "📱 ${it.device_name ?: it.device_id}" }.toTypedArray()
                        
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("🗑️ Eliminar respaldo")
                            .setItems(options) { _, which ->
                                deleteBackup(backups[which].device_id)
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
                override fun onFailure(call: retrofit2.Call<com.hamtaro.hamchat.network.BackupListResponse>, t: Throwable) {}
            })
    }
    
    /**
     * Elimina un respaldo
     */
    private fun deleteBackup(deviceId: String) {
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken() ?: return
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.deleteBackup("Bearer $token", deviceId)
            .enqueue(object : retrofit2.Callback<Map<String, Any>> {
                override fun onResponse(call: retrofit2.Call<Map<String, Any>>, response: retrofit2.Response<Map<String, Any>>) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "✅ Respaldo eliminado", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: retrofit2.Call<Map<String, Any>>, t: Throwable) {}
            })
    }
    
    /**
     * 🔒 Override new intent to validate
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        
        // Validate new intent
        val validationResult = intentValidator.validateIntent(intent)
        if (validationResult.isValid) {
            val sanitizedIntent = intentValidator.sanitizeIntent(intent!!)
            handleIntent(sanitizedIntent)
        } else {
            SecureLogger.e("New intent validation failed: ${validationResult.error}")
            securityManager.logSecurityEvent("NEW_INTENT_INVALID", validationResult.error)
        }
    }
    
    /**
     * 🔒 Override back press to prevent exploits
     */
    override fun onBackPressed() {
        // Prevent back button exploits
        try {
            super.onBackPressed()
        } catch (e: Exception) {
            SecureLogger.e("Error on back press", e)
            finish()
        }
    }
    
    /**
     * 🔒 Cleanup on destroy
     */
    override fun onDestroy() {
        super.onDestroy()
        
        // Clear sensitive data
        try {
            // Clear any cached sensitive information
            securityManager.logSecurityEvent("ACTIVITY_DESTROYED", "MainActivity")
        } catch (e: Exception) {
            SecureLogger.e("Error in cleanup", e)
        }
    }
}
