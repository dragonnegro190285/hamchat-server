package com.hamtaro.hamchat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.game.GameWatchActivity
import com.hamtaro.hamchat.workers.HamChatSyncManager
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "hamchat_settings"
private const val KEY_CHAT_PREFIX = "chat_"
private const val KEY_PRIVATE_CHAT = "private_chat_hamtaro"
private const val KEY_DRAFT_PREFIX = "draft_"  // Borradores de mensajes

/**
 * Modelo de mensaje con campos para sincronización robusta:
 * - sentAt: Fecha/hora exacta de envío local
 * - receivedAt: Fecha/hora de recepción (null si no recibido)
 * - isSentToServer: true si ya se envió al servidor (no en cola)
 * - isDelivered: true si el destinatario lo recibió
 */
data class ChatMessage(
    val sender: String, 
    val content: String, 
    val timestamp: Long,
    val serverId: Int = 0,              // ID del servidor (0 si no está en servidor)
    val localId: String = "",           // ID local único para evitar duplicados
    val isSentToServer: Boolean = false, // Si ya se envió al servidor (no en cola)
    val isDelivered: Boolean = false,   // Si el destinatario lo recibió
    val sentAt: Long = System.currentTimeMillis(),    // Timestamp exacto de envío
    val receivedAt: Long? = null        // Timestamp de recepción (null si no recibido)
)

class ChatActivity : BaseActivity() {

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
    
    // Sistema de borradores
    private var currentDraft: String = ""
    private val draftSaveHandler = Handler(Looper.getMainLooper())
    private var draftSaveRunnable: Runnable? = null

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
                clearDraft()  // Limpiar borrador al enviar
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
            
            // Configurar sistema de borradores
            setupDraftSystem()
            
        } catch (e: Exception) {
            val errorMessage = "Error al iniciar chat: ${e.message}\n\nStack trace:\n${e.stackTraceToString()}"
            showErrorDialog("Error al iniciar ChatActivity", errorMessage)
            finish() // Close activity if it fails to load
        }
    }

    override fun onResume() {
        super.onResume()
        startMessagePolling()
        // Cargar borrador guardado
        loadDraft()
    }

    override fun onPause() {
        super.onPause()
        stopMessagePolling()
        // Guardar borrador al pausar
        saveDraftImmediately()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Guardar borrador al destruir
        saveDraftImmediately()
        draftSaveRunnable?.let { draftSaveHandler.removeCallbacks(it) }
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
                
                // Usar Set para evitar duplicados SOLO por serverId (el ID único del servidor)
                val seenServerIds = mutableSetOf<Int>()
                val newMessages = mutableListOf<ChatMessage>()
                val receivedMessageIds = mutableListOf<Int>()  // IDs de mensajes recibidos para marcar como entregados
                
                for (m in result.messages) {
                    // Evitar duplicados SOLO por serverId (permite mensajes con mismo contenido)
                    if (seenServerIds.contains(m.id)) continue
                    seenServerIds.add(m.id)
                    
                    val senderLabel = if (m.sender_id == result.currentUserId) "Yo" else contactName
                    val msgKey = "${m.sender_id}_${m.content}_${m.id}"
                    
                    // Filtrar mensajes borrados localmente
                    if (deletedMessageKeys.contains(msgKey)) continue
                    
                    // Si soy el receptor y el mensaje no está marcado como entregado, agregarlo a la lista
                    if (m.sender_id != result.currentUserId && !m.is_delivered) {
                        receivedMessageIds.add(m.id)
                    }
                    
                    newMessages.add(ChatMessage(
                        sender = senderLabel,
                        content = m.content,
                        timestamp = m.id.toLong(),
                        serverId = m.id,
                        localId = m.local_id ?: "",
                        isSentToServer = true,
                        isDelivered = m.is_delivered,
                        sentAt = System.currentTimeMillis(),
                        receivedAt = if (m.received_at != null) System.currentTimeMillis() else null
                    ))
                }
                
                // Marcar mensajes recibidos como entregados en el servidor
                if (receivedMessageIds.isNotEmpty()) {
                    HamChatSyncManager.markMessagesAsDelivered(this@ChatActivity, receivedMessageIds)
                }
                
                // Obtener mensajes locales pendientes (no enviados al servidor)
                val pendingLocalMessages = messages.filter { 
                    !it.isSentToServer && it.serverId == 0 && it.localId.isNotEmpty()
                }
                
                // Crear lista final
                messages.clear()
                messages.addAll(newMessages)
                
                // Agregar mensajes pendientes que no están en el servidor
                // Usar localId para evitar duplicados de pendientes
                val seenLocalIds = mutableSetOf<String>()
                for (pending in pendingLocalMessages) {
                    if (pending.localId.isNotEmpty() && !seenLocalIds.contains(pending.localId)) {
                        // Verificar que no exista en servidor por contenido+timestamp cercano
                        val existsInServer = newMessages.any { 
                            it.content == pending.content && 
                            it.sender == pending.sender 
                        }
                        if (!existsInServer) {
                            messages.add(pending)
                            seenLocalIds.add(pending.localId)
                        }
                    }
                }
                
                // Ordenar por serverId (que es el orden real del servidor)
                messages.sortBy { it.serverId }
                
                saveMessages()
                renderMessages()
            },
            onHttpError = { code ->
                // Error HTTP: NO recargar mensajes locales si ya se cargaron
                if (isFirstLoad && !hasLoadedFromServer) {
                    isFirstLoad = false
                    // Ya se mostraron los mensajes locales arriba
                }
                
                if (code == 401) {
                    // Intentar auto-relogin
                    HamChatSyncManager.tryAutoRelogin(
                        context = this@ChatActivity,
                        onSuccess = {
                            runOnUiThread {
                                Toast.makeText(this@ChatActivity, "Sesión renovada", Toast.LENGTH_SHORT).show()
                                // Reintentar cargar mensajes
                                loadMessagesFromServer()
                            }
                        },
                        onError = { error ->
                            runOnUiThread {
                                Toast.makeText(this@ChatActivity, "Sesión expirada: $error", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    Toast.makeText(this@ChatActivity, "Error del servidor ($code)", Toast.LENGTH_SHORT).show()
                }
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

    private fun startMessagePolling() {
        if (remoteUserId != null) {
            messagePollingHandler.removeCallbacks(messagePollingRunnable)
            messagePollingHandler.postDelayed(messagePollingRunnable, 5_000L)
        }
    }

    private fun stopMessagePolling() {
        messagePollingHandler.removeCallbacks(messagePollingRunnable)
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
    
    // ========== Sistema de Borradores ==========
    
    /**
     * Configura el sistema de borradores con auto-guardado mientras se escribe
     */
    private fun setupDraftSystem() {
        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentDraft = s?.toString() ?: ""
                // Auto-guardar después de 1 segundo de inactividad
                scheduleDraftSave()
            }
        })
        
        // Configurar long-press en el campo de texto para mostrar opciones de borrador
        messageEditText.setOnLongClickListener {
            if (currentDraft.isNotEmpty()) {
                showDraftOptionsDialog()
                true
            } else {
                false
            }
        }
    }
    
    /**
     * Programa el guardado del borrador después de un delay
     */
    private fun scheduleDraftSave() {
        draftSaveRunnable?.let { draftSaveHandler.removeCallbacks(it) }
        draftSaveRunnable = Runnable {
            saveDraftImmediately()
        }
        draftSaveHandler.postDelayed(draftSaveRunnable!!, 1000) // 1 segundo
    }
    
    /**
     * Guarda el borrador inmediatamente
     */
    private fun saveDraftImmediately() {
        val text = messageEditText.text?.toString() ?: ""
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = KEY_DRAFT_PREFIX + contactId
        
        if (text.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            val draftData = JSONObject().apply {
                put("text", text)
                put("timestamp", System.currentTimeMillis())
                put("contactName", contactName)
            }
            prefs.edit().putString(key, draftData.toString()).apply()
        }
    }
    
    /**
     * Carga el borrador guardado
     */
    private fun loadDraft() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = KEY_DRAFT_PREFIX + contactId
        val draftJson = prefs.getString(key, null)
        
        if (!draftJson.isNullOrEmpty()) {
            try {
                val draftData = JSONObject(draftJson)
                val text = draftData.optString("text", "")
                if (text.isNotEmpty() && messageEditText.text.isNullOrEmpty()) {
                    messageEditText.setText(text)
                    messageEditText.setSelection(text.length) // Cursor al final
                    currentDraft = text
                    
                    // Mostrar indicador de borrador recuperado
                    Toast.makeText(this, "📝 Borrador recuperado", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                // Ignorar errores de parsing
            }
        }
    }
    
    /**
     * Elimina el borrador guardado
     */
    private fun clearDraft() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val key = KEY_DRAFT_PREFIX + contactId
        prefs.edit().remove(key).apply()
        currentDraft = ""
    }
    
    /**
     * Muestra diálogo con opciones para el borrador
     */
    private fun showDraftOptionsDialog() {
        val options = arrayOf(
            "📤 Enviar borrador",
            "🗑️ Eliminar borrador",
            "📋 Copiar borrador",
            "❌ Cancelar"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📝 Opciones de borrador")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Enviar
                        sendButton.performClick()
                    }
                    1 -> { // Eliminar
                        messageEditText.setText("")
                        clearDraft()
                        Toast.makeText(this, "Borrador eliminado", Toast.LENGTH_SHORT).show()
                    }
                    2 -> { // Copiar
                        copyToClipboard(currentDraft)
                        Toast.makeText(this, "Borrador copiado", Toast.LENGTH_SHORT).show()
                    }
                    // 3 -> Cancelar, no hacer nada
                }
            }
            .show()
    }
    
    companion object {
        /**
         * Obtiene todos los borradores guardados para mostrar en la lista de chats
         */
        fun getAllDrafts(context: Context): Map<String, DraftInfo> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val drafts = mutableMapOf<String, DraftInfo>()
            
            prefs.all.forEach { (key, value) ->
                if (key.startsWith(KEY_DRAFT_PREFIX) && value is String) {
                    try {
                        val contactId = key.removePrefix(KEY_DRAFT_PREFIX)
                        val draftData = JSONObject(value)
                        val text = draftData.optString("text", "")
                        val timestamp = draftData.optLong("timestamp", 0)
                        val contactName = draftData.optString("contactName", "")
                        
                        if (text.isNotEmpty()) {
                            drafts[contactId] = DraftInfo(text, timestamp, contactName)
                        }
                    } catch (_: Exception) {
                        // Ignorar errores
                    }
                }
            }
            
            return drafts
        }
        
        /**
         * Elimina un borrador específico
         */
        fun deleteDraft(context: Context, contactId: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_DRAFT_PREFIX + contactId).apply()
        }
    }
}

/**
 * Información de un borrador
 */
data class DraftInfo(
    val text: String,
    val timestamp: Long,
    val contactName: String
)
