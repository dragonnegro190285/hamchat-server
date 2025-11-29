package com.hamtaro.hamchat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.game.GameWatchActivity
import com.hamtaro.hamchat.workers.HamChatSyncManager
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "hamchat_settings"
private const val KEY_CHAT_PREFIX = "chat_"
private const val KEY_PRIVATE_CHAT = "private_chat_hamtaro"

data class ChatMessage(
    val sender: String, 
    val content: String, 
    val timestamp: Long,
    val serverId: Int = 0,           // ID del servidor (0 si no está en servidor)
    val localId: String = "",        // ID local único para evitar duplicados
    val isSentToServer: Boolean = false  // Si ya se envió al servidor
)

class ChatActivity : AppCompatActivity() {

    private lateinit var messagesScrollView: ScrollView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var chatTitle: TextView
    private lateinit var clearPrivateButton: Button

    private lateinit var contactId: String
    private lateinit var contactName: String
    private var isPrivateChat = false
    private var remoteUserId: Int? = null

    private val messages = mutableListOf<ChatMessage>()
    private val deletedMessageKeys = mutableSetOf<String>() // Mensajes borrados localmente
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable { onIdleTimeout() }

    // Sondeo periodico de mensajes para chats remotos
    private val messagePollingHandler = Handler(Looper.getMainLooper())
    private val messagePollingRunnable = object : Runnable {
        override fun run() {
            if (remoteUserId != null) {
                loadMessagesFromServer()
            }
            messagePollingHandler.postDelayed(this, 5_000L) // cada 5 segundos
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_chat)

            contactId = intent.getStringExtra("contact_id") ?: "unknown"
            contactName = intent.getStringExtra("contact_name") ?: "Contacto"
            isPrivateChat = contactId == "contact_hamtaro"
            if (!isPrivateChat && contactId.startsWith("remote_")) {
                remoteUserId = contactId.removePrefix("remote_").toIntOrNull()
            }

            title = if (isPrivateChat) "📝 Mis Notas" else "Chat con $contactName"

            messagesScrollView = findViewById(R.id.scroll_messages)
            messagesContainer = findViewById(R.id.layout_messages)
            messageEditText = findViewById(R.id.et_message)
            sendButton = findViewById(R.id.btn_send)
            chatTitle = findViewById(R.id.tv_chat_title)
            clearPrivateButton = findViewById(R.id.btn_clear_private)

            // Configure UI based on chat type
            if (isPrivateChat) {
                chatTitle.text = "📝 Mis Notas Privadas"
                messageEditText.hint = "Escribe una nota para ti mismo..."
                sendButton.text = "💾 Guardar"
                clearPrivateButton.visibility = View.VISIBLE
                clearPrivateButton.setOnClickListener {
                    confirmClearPrivateNotes()
                }
            } else {
                clearPrivateButton.visibility = View.GONE

                if (remoteUserId != null) {
                    // Chat remoto entre usuarios Ham-Chat
                    chatTitle.text = "Chat con $contactName (Ham-Chat)"
                    messageEditText.hint = "Escribe un mensaje para $contactName (se enviará por Ham-Chat cuando haya conexión)"
                    sendButton.text = "Enviar"
                } else {
                    // Chat solo local en este dispositivo
                    chatTitle.text = "Chat con $contactName (solo en este dispositivo)"
                    messageEditText.hint = "Escribe un mensaje para $contactName (solo se guarda en este dispositivo)"
                    sendButton.text = "💾 Guardar mensaje"
                }
            }

            if (remoteUserId != null) {
                // Cargar lista de mensajes borrados localmente
                loadDeletedKeys()
                // Cargar historial desde servidor (si hay conexión) y también
                // intentar enviar cualquier mensaje pendiente guardado en este dispositivo.
                loadMessagesFromServer()
                HamChatSyncManager.flushPendingMessages(
                    context = this,
                    contactId = contactId,
                    remoteUserId = remoteUserId!!
                )
            } else {
                loadMessages()
                renderMessages()
            }

            sendButton.setOnClickListener {
                val rawText = messageEditText.text.toString()
                val text = rawText.trim()
                if (text.isEmpty()) return@setOnClickListener

                // Código temporal: en el chat de Hamtaro, la letra 'x' abre el minijuego
                if (isPrivateChat && text.equals("x", ignoreCase = true)) {
                    messageEditText.setText("")
                    try {
                        val intent = Intent(this, GameWatchActivity::class.java)
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(this, "No se pudo abrir el minijuego", Toast.LENGTH_SHORT).show()
                    }
                    return@setOnClickListener
                }

                // Generar ID local único para el mensaje
                val localId = generateLocalId()
                val now = System.currentTimeMillis()
                
                val message = ChatMessage(
                    sender = "Yo",
                    content = text,
                    timestamp = now,
                    serverId = 0,  // Aún no está en servidor
                    localId = localId,
                    isSentToServer = false
                )
                messages.add(message)
                addMessageToContainer(message)
                saveMessages()

                messageEditText.setText("")
                scrollToBottom()

                val toastText = when {
                    isPrivateChat -> "Nota guardada"
                    remoteUserId != null -> "Enviando..."
                    else -> "Mensaje guardado solo en este dispositivo"
                }
                Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()

                if (remoteUserId != null) {
                    HamChatSyncManager.addPendingMessage(
                        context = this,
                        contactId = contactId,
                        content = text,
                        timestamp = now,
                        localId = localId
                    )
                    HamChatSyncManager.flushPendingMessages(
                        context = this,
                        contactId = contactId,
                        remoteUserId = remoteUserId!!
                    ) {
                        // Tras enviar pendientes, recargar desde servidor para alinear el historial
                        loadMessagesFromServer()
                    }
                }
            }
        } catch (e: Exception) {
            val errorMessage = "Error al iniciar chat: ${e.message}\n\nStack trace:\n${e.stackTraceToString()}"
            showErrorDialog("Error al iniciar ChatActivity", errorMessage)
            finish() // Close activity if it fails to load
        }
    }

    override fun onResume() {
        super.onResume()
        startIdleTimer()
        startMessagePolling()
    }

    override fun onPause() {
        super.onPause()
        stopIdleTimer()
        stopMessagePolling()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimer()
    }
    
    private fun showErrorDialog(title: String, message: String) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Copiar error") { _, _ ->
                copyToClipboard(message)
                Toast.makeText(this, "Error copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar") { dialog, _ -> 
                dialog.dismiss()
                finish()
            }
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

    /**
     * Genera un ID local único para cada mensaje
     */
    private fun generateLocalId(): String {
        return "local_${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}"
    }

    private fun loadMessages() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = if (isPrivateChat) KEY_PRIVATE_CHAT else KEY_CHAT_PREFIX + contactId
        val json = prefs.getString(key, null) ?: return

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val sender = obj.optString("sender")
                val content = obj.optString("content")
                val timestamp = obj.optLong("timestamp")
                val serverId = obj.optInt("serverId", 0)
                val localId = obj.optString("localId", "")
                val isSentToServer = obj.optBoolean("isSentToServer", serverId > 0)
                
                messages.add(ChatMessage(
                    sender = sender,
                    content = content,
                    timestamp = timestamp,
                    serverId = serverId,
                    localId = localId,
                    isSentToServer = isSentToServer
                ))
            }
        } catch (e: Exception) {
            // Ignore corrupt history
        }
    }

    private var isFirstLoad = true
    private var hasLoadedFromServer = false

    private fun loadMessagesFromServer() {
        val remoteId = remoteUserId
        if (remoteId == null) {
            loadMessages()
            renderMessages()
            return
        }

        // Primera carga: mostrar mensajes locales mientras se conecta
        if (isFirstLoad && !hasLoadedFromServer) {
            loadMessages()
            renderMessages()
        }

        HamChatSyncManager.loadMessagesFromServer(
            context = this,
            remoteUserId = remoteId,
            onSuccess = { result ->
                hasLoadedFromServer = true
                isFirstLoad = false
                
                // Crear mapa de mensajes del servidor por ID
                val serverMessagesById = mutableMapOf<Int, ChatMessage>()
                for (m in result.messages) {
                    val senderLabel = if (m.sender_id == result.currentUserId) "Yo" else contactName
                    val msgKey = "${m.sender_id}_${m.content}_${m.id}"
                    
                    // Filtrar mensajes borrados localmente
                    if (deletedMessageKeys.contains(msgKey)) continue
                    
                    serverMessagesById[m.id] = ChatMessage(
                        sender = senderLabel,
                        content = m.content,
                        timestamp = m.id.toLong(),
                        serverId = m.id,
                        localId = "",
                        isSentToServer = true
                    )
                }
                
                // Obtener mensajes locales pendientes (no enviados al servidor)
                val pendingLocalMessages = messages.filter { 
                    !it.isSentToServer && it.serverId == 0 && it.localId.isNotEmpty()
                }
                
                // Crear lista final: mensajes del servidor + pendientes locales
                messages.clear()
                messages.addAll(serverMessagesById.values)
                
                // Agregar mensajes pendientes que no están en el servidor
                for (pending in pendingLocalMessages) {
                    // Verificar que no exista ya (por contenido similar)
                    val alreadyExists = messages.any { 
                        it.content == pending.content && 
                        it.sender == pending.sender &&
                        Math.abs(it.timestamp - pending.timestamp) < 60000 // 1 minuto de tolerancia
                    }
                    if (!alreadyExists) {
                        messages.add(pending)
                    }
                }
                
                // Ordenar por timestamp
                messages.sortBy { it.timestamp }
                
                saveMessages()
                renderMessages()
            },
            onHttpError = { code ->
                // Error HTTP: NO recargar mensajes locales si ya se cargaron
                if (isFirstLoad && !hasLoadedFromServer) {
                    isFirstLoad = false
                    // Ya se mostraron los mensajes locales arriba
                }
                val msg = if (code == 401) {
                    "Sesión no válida (401)"
                } else {
                    "Error del servidor ($code)"
                }
                Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
            },
            onNetworkError = {
                // Error de red: NO recargar mensajes locales si ya se cargaron
                if (isFirstLoad && !hasLoadedFromServer) {
                    isFirstLoad = false
                    // Ya se mostraron los mensajes locales arriba
                }
                // No mostrar toast en cada polling fallido, solo la primera vez
                if (!hasLoadedFromServer) {
                    Toast.makeText(this@ChatActivity, "Sin conexión al servidor", Toast.LENGTH_SHORT).show()
                }
            },
            onAuthMissing = {
                if (isFirstLoad && !hasLoadedFromServer) {
                    isFirstLoad = false
                }
                if (!hasLoadedFromServer) {
                    Toast.makeText(this@ChatActivity, "Sesión no disponible", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun saveMessages() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = if (isPrivateChat) KEY_PRIVATE_CHAT else KEY_CHAT_PREFIX + contactId
        val array = JSONArray()
        for (m in messages) {
            val obj = JSONObject()
            obj.put("sender", m.sender)
            obj.put("content", m.content)
            obj.put("timestamp", m.timestamp)
            obj.put("serverId", m.serverId)
            obj.put("localId", m.localId)
            obj.put("isSentToServer", m.isSentToServer)
            array.put(obj)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }


    private fun renderMessages() {
        messagesContainer.removeAllViews()
        for (m in messages) {
            addMessageToContainer(m)
        }
        scrollToBottom()
    }

    private fun addMessageToContainer(message: ChatMessage) {
        val messageLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        val senderView = TextView(this).apply {
            text = if (message.sender == "Yo") "Yo" else message.sender
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 4)
        }

        val contentView = TextView(this).apply {
            text = message.content
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            setPadding(12, 8, 12, 8)
            
            // Style based on sender
            if (message.sender == "Yo") {
                setBackgroundColor(0xFFE3F2FD.toInt()) // Light blue for my messages
            } else {
                setBackgroundColor(0xFFF5F5F5.toInt()) // Light gray for others
            }
        }

        val timeView = TextView(this).apply {
            val time = android.text.format.DateFormat.format("HH:mm", message.timestamp)
            text = time
            textSize = 10f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 4, 0, 0)
        }

        messageLayout.addView(senderView)
        messageLayout.addView(contentView)
        messageLayout.addView(timeView)

        if (isPrivateChat) {
            messageLayout.setOnLongClickListener {
                confirmDeleteSingleNote(message)
                true
            }
        } else {
            // Para cualquier chat no privado (local o remoto), permitir borrar mensajes
            // de forma local mediante pulsación larga.
            messageLayout.setOnLongClickListener {
                confirmDeleteSingleRemoteMessage(message)
                true
            }
        }

        messagesContainer.addView(messageLayout)
    }

    private fun scrollToBottom() {
        messagesScrollView.post {
            messagesScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun startIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
        idleHandler.postDelayed(idleTimeoutRunnable, 5 * 60 * 1000L)
    }

    private fun stopIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
    }

    private fun resetIdleTimer() {
        startIdleTimer()
    }

    private fun startMessagePolling() {
        if (remoteUserId != null) {
            messagePollingHandler.removeCallbacks(messagePollingRunnable)
            messagePollingHandler.postDelayed(messagePollingRunnable, 5_000L)
        }
    }

    private fun stopMessagePolling() {
        messagePollingHandler.removeCallbacks(messagePollingRunnable)
    }

    private fun onIdleTimeout() {
        try {
            Toast.makeText(this, "Cerrando Ham-Chat por inactividad", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
        finishAffinity()
    }

    private fun confirmClearPrivateNotes() {
        if (!isPrivateChat) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Borrar notas privadas")
            .setMessage("¿Seguro que quieres borrar todas tus notas privadas? Esta acción no se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                clearPrivateNotes()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun clearPrivateNotes() {
        if (!isPrivateChat) return
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().remove(KEY_PRIVATE_CHAT).apply()
            messages.clear()
            renderMessages()
            Toast.makeText(this, "Notas privadas borradas", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudieron borrar las notas", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSingleNote(message: ChatMessage) {
        if (!isPrivateChat) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Borrar nota")
            .setMessage("¿Quieres borrar solo esta nota?")
            .setPositiveButton("Borrar") { _, _ ->
                deleteSingleNote(message)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSingleNote(message: ChatMessage) {
        if (!isPrivateChat) return
        try {
            val index = messages.indexOfFirst { it.timestamp == message.timestamp && it.content == message.content && it.sender == message.sender }
            if (index >= 0) {
                messages.removeAt(index)
                saveMessages()
                renderMessages()
                Toast.makeText(this, "Nota borrada", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo borrar la nota", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteSingleRemoteMessage(message: ChatMessage) {
        // Se usa tanto para chats remotos como para chats locales no privados.
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Borrar mensaje")
            .setMessage("¿Quieres borrar solo este mensaje en este dispositivo? El mensaje seguirá existiendo en el servidor.")
            .setPositiveButton("Borrar") { _, _ ->
                deleteSingleRemoteMessage(message)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSingleRemoteMessage(message: ChatMessage) {
        // Borrado solo local del mensaje, tanto para chats remotos como locales.
        try {
            val index = messages.indexOfFirst { it.timestamp == message.timestamp && it.content == message.content && it.sender == message.sender }
            if (index >= 0) {
                // Agregar a lista de borrados para que no reaparezca con el sondeo
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val currentUserId = prefs.getInt("auth_user_id", -1)
                val senderId = if (message.sender == "Yo") currentUserId else remoteUserId ?: -1
                val msgKey = "${senderId}_${message.content}_${message.timestamp}"
                deletedMessageKeys.add(msgKey)
                saveDeletedKeys()

                messages.removeAt(index)
                saveMessages()
                renderMessages()
                Toast.makeText(this, "Mensaje borrado en este dispositivo", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo borrar el mensaje", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveDeletedKeys() {
        if (!isPrivateChat && remoteUserId != null) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val key = "deleted_msgs_$contactId"
            prefs.edit().putStringSet(key, deletedMessageKeys).apply()
        }
    }

    private fun loadDeletedKeys() {
        if (!isPrivateChat && remoteUserId != null) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val key = "deleted_msgs_$contactId"
            val saved = prefs.getStringSet(key, emptySet()) ?: emptySet()
            deletedMessageKeys.clear()
            deletedMessageKeys.addAll(saved)
        }
    }
}
