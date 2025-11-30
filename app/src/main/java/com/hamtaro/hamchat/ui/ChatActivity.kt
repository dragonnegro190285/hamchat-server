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
 * Estado del mensaje para mostrar indicadores visuales
 */
enum class MessageStatus {
    SENDING,    // ⏳ Enviando (en cola)
    SENT,       // ✓ Enviado al servidor
    DELIVERED,  // ✓✓ Entregado al destinatario
    READ,       // ✓✓ Leído por el destinatario (azul)
    FAILED      // ❌ Error al enviar
}

/**
 * Modelo de mensaje con campos para sincronización robusta y funciones avanzadas
 */
data class ChatMessage(
    val sender: String, 
    val content: String, 
    val timestamp: Long,
    val serverId: Int = 0,              // ID del servidor (0 si no está en servidor)
    val localId: String = "",           // ID local único para evitar duplicados
    val isSentToServer: Boolean = false, // Si ya se envió al servidor (no en cola)
    val isDelivered: Boolean = false,   // Si el destinatario lo recibió
    val isRead: Boolean = false,        // Si el destinatario lo leyó
    val sentAt: Long = System.currentTimeMillis(),    // Timestamp exacto de envío
    val receivedAt: Long? = null,       // Timestamp de recepción (null si no recibido)
    val replyToId: String? = null,      // ID del mensaje al que responde (null si no es respuesta)
    val replyToContent: String? = null, // Contenido del mensaje al que responde (preview)
    val isForwarded: Boolean = false,   // Si es un mensaje reenviado
    val isStarred: Boolean = false,     // Si está marcado como favorito/importante
    val messageType: String = "text",   // "text", "voice" o "image"
    val audioData: String? = null,      // Base64 encoded audio
    val audioDuration: Int = 0,         // Duración en segundos
    val imageData: String? = null       // Base64 encoded image
) {
    /**
     * Obtiene el estado actual del mensaje para mostrar indicador visual
     */
    fun getStatus(): MessageStatus {
        return when {
            sender != "Yo" -> MessageStatus.READ  // Mensajes recibidos siempre "leídos"
            isRead -> MessageStatus.READ
            isDelivered -> MessageStatus.DELIVERED
            isSentToServer -> MessageStatus.SENT
            serverId > 0 -> MessageStatus.SENT
            else -> MessageStatus.SENDING
        }
    }
    
    /**
     * Obtiene el icono de estado del mensaje (estilo Ham-Chat)
     */
    fun getStatusIcon(): String {
        return when (getStatus()) {
            MessageStatus.SENDING -> "🕐"    // Reloj - enviando
            MessageStatus.SENT -> "📤"       // Enviado
            MessageStatus.DELIVERED -> "📬"  // Buzón con carta - entregado
            MessageStatus.READ -> "👀"       // Ojos - leído
            MessageStatus.FAILED -> "⚠️"    // Advertencia - error
        }
    }
}

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
    private var isGroupChat = false      // Si es un chat grupal
    private var groupId: Int? = null     // ID del grupo (si es chat grupal)

    private val messages = mutableListOf<ChatMessage>()
    private val deletedMessageKeys = mutableSetOf<String>() // Mensajes borrados localmente
    
    // Sistema de borradores
    private var currentDraft: String = ""
    private val draftSaveHandler = Handler(Looper.getMainLooper())
    private var draftSaveRunnable: Runnable? = null
    
    // Sistema de respuestas
    private var replyingToMessage: ChatMessage? = null
    private var replyPreviewContainer: LinearLayout? = null
    
    // Mensajes favoritos/destacados
    private val starredMessages = mutableSetOf<String>()
    
    // Control de renderizado para evitar parpadeo
    private var lastRenderedMessageCount = 0
    private var lastRenderedMessageHash = 0
    
    // Sistema de mensajes de voz
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var currentAudioFile: java.io.File? = null
    private var voiceButton: Button? = null
    private var recordingIndicator: TextView? = null
    
    // Sistema de fotos
    private var photoButton: Button? = null
    private var currentPhotoUri: android.net.Uri? = null
    private val REQUEST_CAMERA = 101
    private val REQUEST_GALLERY = 102

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
            
            // Detectar tipo de chat
            if (!isPrivateChat && contactId.startsWith("remote_")) {
                remoteUserId = contactId.removePrefix("remote_").toIntOrNull()
            } else if (!isPrivateChat && contactId.startsWith("group_")) {
                isGroupChat = true
                groupId = contactId.removePrefix("group_").toIntOrNull()
            }

            title = when {
                isPrivateChat -> "📝 Mis Notas"
                isGroupChat -> "👥 $contactName"
                else -> "Chat con $contactName"
            }

            messagesScrollView = findViewById(R.id.scroll_messages)
            messagesContainer = findViewById(R.id.layout_messages)
            messageEditText = findViewById(R.id.et_message)
            sendButton = findViewById(R.id.btn_send)
            chatTitle = findViewById(R.id.tv_chat_title)
            clearPrivateButton = findViewById(R.id.btn_clear_private)
            voiceButton = findViewById(R.id.btn_voice)
            recordingIndicator = findViewById(R.id.tv_recording_indicator)
            photoButton = findViewById(R.id.btn_photo)
            
            // Configurar botón de voz (mantener presionado para grabar)
            setupVoiceButton()
            
            // Configurar botón de foto
            setupPhotoButton()

            // Configure UI based on chat type
            if (isPrivateChat) {
                chatTitle.text = "📝 Mis Notas"
                messageEditText.hint = "Escribe una nota..."
                sendButton.text = "💾"
                clearPrivateButton.visibility = View.VISIBLE
                clearPrivateButton.setOnClickListener {
                    confirmClearPrivateNotes()
                }
            } else if (isGroupChat) {
                // Chat grupal - solo nombre del grupo
                chatTitle.text = contactName
                messageEditText.hint = "Mensaje al grupo..."
                sendButton.text = "📤"
                clearPrivateButton.visibility = View.GONE
                
                // Mantener presionado el título para ver miembros
                chatTitle.setOnLongClickListener {
                    showGroupMembersDialog()
                    true
                }
                chatTitle.setOnClickListener {
                    Toast.makeText(this, "Mantén presionado para ver miembros", Toast.LENGTH_SHORT).show()
                }
            } else {
                clearPrivateButton.visibility = View.GONE

                if (remoteUserId != null) {
                    // Chat remoto - solo nombre del contacto
                    chatTitle.text = contactName
                    messageEditText.hint = "Mensaje..."
                    sendButton.text = "📤"
                } else {
                    // Chat solo local
                    chatTitle.text = contactName
                    messageEditText.hint = "Mensaje..."
                    sendButton.text = "💾"
                }
            }

            // Cargar mensajes destacados
            loadStarredMessages()
            
            // Verificar si hay mensaje para reenviar
            checkForForwardedMessage()
            
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
                
                // Verificar si es respuesta a otro mensaje
                val replyTo = replyingToMessage
                val replyToId = replyTo?.localId?.ifEmpty { replyTo.serverId.toString() }
                val replyToContent = replyTo?.content?.take(100)
                
                // Verificar si hay mensaje para reenviar
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val isForwarding = prefs.getBoolean("forward_message_pending", false)
                if (isForwarding) {
                    prefs.edit().remove("forward_message_pending").remove("forward_message_content").apply()
                }
                
                val message = ChatMessage(
                    sender = "Yo",
                    content = text,
                    timestamp = now,
                    serverId = 0,  // Aún no está en servidor
                    localId = localId,
                    isSentToServer = false,
                    replyToId = replyToId,
                    replyToContent = replyToContent,
                    isForwarded = isForwarding
                )
                messages.add(message)
                addMessageToContainer(message)
                saveMessages()

                messageEditText.setText("")
                clearDraft()  // Limpiar borrador al enviar
                cancelReply() // Limpiar modo respuesta
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
            renderMessages(forceRender = true)
            return
        }

        // Primera carga: mostrar mensajes locales mientras se conecta
        if (isFirstLoad && !hasLoadedFromServer) {
            loadMessages()
            renderMessages(forceRender = true)
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


    private fun renderMessages(forceRender: Boolean = false) {
        // Calcular hash de mensajes actuales para detectar cambios
        val currentHash = messages.hashCode()
        val currentCount = messages.size
        
        // Solo re-renderizar si hay cambios reales o es forzado
        if (!forceRender && currentHash == lastRenderedMessageHash && currentCount == lastRenderedMessageCount) {
            return // No hay cambios, evitar parpadeo
        }
        
        // Guardar estado del campo de texto
        val hasFocus = messageEditText.hasFocus()
        val cursorPosition = messageEditText.selectionStart
        val currentText = messageEditText.text.toString()
        
        // Renderizar mensajes
        messagesContainer.removeAllViews()
        for (m in messages) {
            addMessageToContainer(m)
        }
        
        // Solo hacer scroll si hay mensajes nuevos
        val hadNewMessages = currentCount > lastRenderedMessageCount
        
        // Actualizar tracking
        lastRenderedMessageHash = currentHash
        lastRenderedMessageCount = currentCount
        
        // Restaurar estado del campo de texto
        if (hasFocus) {
            messageEditText.requestFocus()
            messageEditText.setSelection(minOf(cursorPosition, currentText.length))
        }
        
        // Scroll al final si hay mensajes nuevos o es render forzado
        if (hadNewMessages || forceRender) {
            scrollToBottom()
        }
    }

    private fun addMessageToContainer(message: ChatMessage) {
        val isMyMessage = message.sender == "Yo"
        val isStarred = starredMessages.contains(message.localId)
        
        // Contenedor principal con alineación según remitente
        val outerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 6, 12, 6)
            gravity = if (isMyMessage) android.view.Gravity.END else android.view.Gravity.START
        }
        
        // Calcular máximo ancho (75% de pantalla)
        val maxBubbleWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()
        
        // Burbuja del mensaje con bordes redondeados
        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            
            // Usar drawable con bordes redondeados
            val bubbleDrawable = if (isMyMessage) {
                R.drawable.bubble_sent
            } else {
                R.drawable.bubble_received
            }
            setBackgroundResource(bubbleDrawable)
            
            // Configurar tamaño máximo
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (isMyMessage) {
                    marginStart = 48 // Espacio a la izquierda para mensajes enviados
                } else {
                    marginEnd = 48 // Espacio a la derecha para mensajes recibidos
                }
            }
        }
        
        // Si es mensaje reenviado, mostrar indicador
        if (message.isForwarded) {
            val forwardedView = TextView(this).apply {
                text = "↪️ Reenviado"
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, 4)
            }
            bubbleLayout.addView(forwardedView)
        }
        
        // Si es respuesta a otro mensaje, mostrar preview
        if (!message.replyToContent.isNullOrEmpty()) {
            val replyContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 4, 8, 4)
                setBackgroundColor(0x20000000) // Fondo semi-transparente
            }
            
            val replyLabel = TextView(this).apply {
                text = "↩️ Respuesta"
                textSize = 10f
                setTextColor(0xFF666666.toInt())
            }
            
            val replyPreview = TextView(this).apply {
                text = message.replyToContent.take(50) + if (message.replyToContent.length > 50) "..." else ""
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                maxLines = 2
            }
            
            replyContainer.addView(replyLabel)
            replyContainer.addView(replyPreview)
            bubbleLayout.addView(replyContainer)
            
            // Espacio entre reply y contenido
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 6
                )
            }
            bubbleLayout.addView(spacer)
        }
        
        // Nombre del remitente (solo para chats grupales, no en chats 1 a 1)
        // En chats uno a uno no es necesario mostrar quién envía
        if (!isMyMessage && this.isGroupChat) {
            val senderView = TextView(this).apply {
                text = message.sender
                textSize = 12f
                setTextColor(0xFFFF8C00.toInt()) // Naranja Ham-Chat
                setPadding(0, 0, 0, 2)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            bubbleLayout.addView(senderView)
        }

        // Contenido del mensaje (texto, voz o imagen)
        when {
            message.messageType == "image" -> {
                // Mensaje de imagen - cargar desde memoria o almacenamiento local
                val imageData = message.imageData ?: loadMediaLocally(message.localId, "image")
                
                if (!imageData.isNullOrEmpty()) {
                    try {
                        val imageBytes = android.util.Base64.decode(imageData, android.util.Base64.NO_WRAP)
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        
                        val imageView = android.widget.ImageView(this).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            maxWidth = (resources.displayMetrics.widthPixels * 0.6).toInt()
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            setPadding(0, 4, 0, 4)
                            
                            setOnClickListener {
                                showFullImage(imageData)
                            }
                        }
                        bubbleLayout.addView(imageView)
                    } catch (e: Exception) {
                        val errorView = TextView(this).apply {
                            text = "📷 [Error al cargar imagen]"
                            textSize = 14f
                            setTextColor(0xFF888888.toInt())
                        }
                        bubbleLayout.addView(errorView)
                    }
                } else {
                    // Imagen no disponible localmente
                    val pendingView = TextView(this).apply {
                        text = "📷 [Imagen pendiente de recibir]"
                        textSize = 14f
                        setTextColor(0xFF888888.toInt())
                    }
                    bubbleLayout.addView(pendingView)
                }
            }
            message.messageType == "voice" -> {
                // Mensaje de voz - cargar desde memoria o almacenamiento local
                val audioData = message.audioData ?: loadMediaLocally(message.localId, "voice")
                
                val voiceContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                
                if (!audioData.isNullOrEmpty()) {
                    val playButton = Button(this).apply {
                        text = "▶️"
                        textSize = 18f
                        setPadding(8, 4, 8, 4)
                        setOnClickListener {
                            text = "⏸️"
                            playVoiceMessage(audioData)
                            postDelayed({ text = "▶️" }, (message.audioDuration * 1000 + 500).toLong())
                        }
                    }
                    voiceContainer.addView(playButton)
                } else {
                    val pendingButton = Button(this).apply {
                        text = "⏳"
                        textSize = 18f
                        setPadding(8, 4, 8, 4)
                        isEnabled = false
                    }
                    voiceContainer.addView(pendingButton)
                }
                
                val durationText = TextView(this).apply {
                    text = "🎤 ${message.audioDuration}s"
                    textSize = 14f
                    setTextColor(0xFF1A1A1A.toInt())
                    setPadding(8, 0, 0, 0)
                }
                
                voiceContainer.addView(durationText)
                bubbleLayout.addView(voiceContainer)
            }
            else -> {
                // Mensaje de texto normal
                val contentView = TextView(this).apply {
                    text = message.content
                    textSize = 15f
                    setTextColor(0xFF1A1A1A.toInt())
                    setTextIsSelectable(true)
                }
                bubbleLayout.addView(contentView)
            }
        }
        
        // Fila inferior: hora + estado + estrella
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, 4, 0, 0)
        }
        
        // Estrella si está marcado
        if (isStarred) {
            val starView = TextView(this).apply {
                text = "⭐"
                textSize = 10f
                setPadding(0, 0, 4, 0)
            }
            bottomRow.addView(starView)
        }
        
        // Hora del mensaje
        val timeView = TextView(this).apply {
            val time = android.text.format.DateFormat.format("HH:mm", message.sentAt)
            text = time.toString()
            textSize = 10f
            setTextColor(0xFF888888.toInt())
        }
        bottomRow.addView(timeView)
        
        // Estado del mensaje (solo para mis mensajes)
        if (isMyMessage && remoteUserId != null) {
            val statusView = TextView(this).apply {
                text = " ${message.getStatusIcon()}"
                textSize = 10f
            }
            bottomRow.addView(statusView)
        }
        
        bubbleLayout.addView(bottomRow)
        outerLayout.addView(bubbleLayout)
        
        // Menú de opciones al mantener presionado
        outerLayout.setOnLongClickListener {
            showMessageOptionsDialog(message)
            true
        }
        
        // Click simple para responder rápido
        outerLayout.setOnClickListener {
            // Doble click para responder (implementar con handler)
        }

        messagesContainer.addView(outerLayout)
    }
    
    /**
     * Muestra diálogo con opciones para el mensaje (estilo Ham-Chat)
     */
    private fun showMessageOptionsDialog(message: ChatMessage) {
        val isMyMessage = message.sender == "Yo"
        val isStarred = starredMessages.contains(message.localId)
        
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        
        // Responder
        options.add("↩️ Responder")
        actions.add { startReplyToMessage(message) }
        
        // Copiar
        options.add("📋 Copiar texto")
        actions.add { 
            copyToClipboard(message.content)
            Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show()
        }
        
        // Reenviar
        options.add("↪️ Reenviar")
        actions.add { forwardMessage(message) }
        
        // Destacar/Quitar destacado
        if (isStarred) {
            options.add("⭐ Quitar de destacados")
            actions.add { toggleStarMessage(message) }
        } else {
            options.add("⭐ Destacar mensaje")
            actions.add { toggleStarMessage(message) }
        }
        
        // Info del mensaje
        options.add("ℹ️ Info del mensaje")
        actions.add { showMessageInfo(message) }
        
        // Eliminar
        if (isPrivateChat) {
            options.add("🗑️ Eliminar nota")
            actions.add { confirmDeleteSingleNote(message) }
        } else {
            options.add("🗑️ Eliminar (solo aquí)")
            actions.add { confirmDeleteSingleRemoteMessage(message) }
        }
        
        // Cancelar
        options.add("❌ Cancelar")
        actions.add { /* No hacer nada */ }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📨 Opciones del mensaje")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < actions.size) {
                    actions[which]()
                }
            }
            .show()
    }
    
    /**
     * Inicia el modo de respuesta a un mensaje
     */
    private fun startReplyToMessage(message: ChatMessage) {
        replyingToMessage = message
        
        // Mostrar preview de respuesta arriba del campo de texto
        showReplyPreview(message)
        
        // Enfocar el campo de texto
        messageEditText.requestFocus()
        
        Toast.makeText(this, "Respondiendo a: ${message.content.take(30)}...", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Muestra el preview del mensaje al que se está respondiendo
     */
    private fun showReplyPreview(message: ChatMessage) {
        // Remover preview anterior si existe
        replyPreviewContainer?.let { messagesContainer.parent?.let { parent -> 
            if (parent is LinearLayout) {
                parent.removeView(replyPreviewContainer)
            }
        }}
        
        replyPreviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFE8E8E8.toInt())
            setPadding(12, 8, 12, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        
        val previewText = TextView(this).apply {
            text = "↩️ ${message.sender}: ${message.content.take(40)}${if (message.content.length > 40) "..." else ""}"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val cancelButton = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xFF888888.toInt())
            setPadding(16, 0, 0, 0)
            setOnClickListener { cancelReply() }
        }
        
        replyPreviewContainer?.addView(previewText)
        replyPreviewContainer?.addView(cancelButton)
        
        // Insertar antes del campo de texto (buscar el padre)
        val inputContainer = messageEditText.parent as? LinearLayout
        inputContainer?.let {
            val index = it.indexOfChild(messageEditText)
            if (index >= 0) {
                it.addView(replyPreviewContainer, index)
            }
        }
    }
    
    /**
     * Cancela el modo de respuesta
     */
    private fun cancelReply() {
        replyingToMessage = null
        replyPreviewContainer?.let { preview ->
            (preview.parent as? LinearLayout)?.removeView(preview)
        }
        replyPreviewContainer = null
    }
    
    /**
     * Reenvía un mensaje a otro contacto
     */
    private fun forwardMessage(message: ChatMessage) {
        // Guardar mensaje para reenviar en preferencias temporales
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString("forward_message_content", message.content)
            .putBoolean("forward_message_pending", true)
            .apply()
        
        Toast.makeText(this, "Selecciona un contacto para reenviar", Toast.LENGTH_LONG).show()
        
        // Volver a la lista de contactos
        finish()
    }
    
    /**
     * Alterna el estado de destacado de un mensaje
     */
    private fun toggleStarMessage(message: ChatMessage) {
        val key = message.localId.ifEmpty { "${message.serverId}" }
        
        if (starredMessages.contains(key)) {
            starredMessages.remove(key)
            Toast.makeText(this, "Mensaje quitado de destacados", Toast.LENGTH_SHORT).show()
        } else {
            starredMessages.add(key)
            Toast.makeText(this, "⭐ Mensaje destacado", Toast.LENGTH_SHORT).show()
        }
        
        // Guardar en preferencias
        saveStarredMessages()
        
        // Refrescar vista
        renderMessages()
    }
    
    /**
     * Muestra información detallada del mensaje
     */
    private fun showMessageInfo(message: ChatMessage) {
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault())
        
        val info = StringBuilder()
        info.append("📨 Información del mensaje\n\n")
        info.append("👤 De: ${message.sender}\n")
        info.append("🕐 Enviado: ${sdf.format(java.util.Date(message.sentAt))}\n")
        
        if (message.receivedAt != null) {
            info.append("📬 Recibido: ${sdf.format(java.util.Date(message.receivedAt))}\n")
        }
        
        info.append("📊 Estado: ${message.getStatusIcon()} ${message.getStatus().name}\n")
        
        if (message.serverId > 0) {
            info.append("🔢 ID servidor: ${message.serverId}\n")
        }
        
        if (message.localId.isNotEmpty()) {
            info.append("🏷️ ID local: ${message.localId.take(8)}...\n")
        }
        
        if (message.isForwarded) {
            info.append("↪️ Mensaje reenviado\n")
        }
        
        if (message.replyToContent != null) {
            info.append("↩️ Respuesta a: ${message.replyToContent.take(30)}...\n")
        }
        
        info.append("\n📝 Contenido:\n${message.content}")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ℹ️ Info del mensaje")
            .setMessage(info.toString())
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Copiar info") { _, _ ->
                copyToClipboard(info.toString())
                Toast.makeText(this, "Info copiada", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    
    /**
     * Guarda los mensajes destacados
     */
    private fun saveStarredMessages() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putStringSet("starred_$contactId", starredMessages).apply()
    }
    
    /**
     * Carga los mensajes destacados
     */
    private fun loadStarredMessages() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getStringSet("starred_$contactId", emptySet()) ?: emptySet()
        starredMessages.clear()
        starredMessages.addAll(saved)
    }
    
    /**
     * Verifica si hay un mensaje pendiente para reenviar
     */
    private fun checkForForwardedMessage() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isPending = prefs.getBoolean("forward_message_pending", false)
        val content = prefs.getString("forward_message_content", null)
        
        if (isPending && !content.isNullOrEmpty()) {
            // Poner el contenido en el campo de texto
            messageEditText.setText(content)
            messageEditText.setSelection(content.length)
            
            Toast.makeText(this, "↪️ Mensaje listo para reenviar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scrollToBottom() {
        messagesScrollView.post {
            messagesScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ========== Funciones de Grupos ==========
    
    /**
     * Muestra diálogo con los miembros del grupo y opciones de gestión
     */
    private fun showGroupMembersDialog() {
        if (!isGroupChat || groupId == null) return
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "No hay sesión activa", Toast.LENGTH_SHORT).show()
            return
        }
        
        val authHeader = "Bearer $token"
        
        // Mostrar diálogo de carga
        val loadingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("👥 $contactName")
            .setMessage("Cargando miembros...")
            .setCancelable(true)
            .create()
        loadingDialog.show()
        
        // Obtener miembros del servidor
        com.hamtaro.hamchat.network.HamChatApiClient.api.getGroupMembers(authHeader, groupId!!)
            .enqueue(object : retrofit2.Callback<List<com.hamtaro.hamchat.network.GroupMemberDto>> {
                override fun onResponse(
                    call: retrofit2.Call<List<com.hamtaro.hamchat.network.GroupMemberDto>>,
                    response: retrofit2.Response<List<com.hamtaro.hamchat.network.GroupMemberDto>>
                ) {
                    loadingDialog.dismiss()
                    
                    if (response.isSuccessful && response.body() != null) {
                        val members = response.body()!!
                        showMembersListDialog(members)
                    } else {
                        Toast.makeText(this@ChatActivity, "Error al cargar miembros", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onFailure(call: retrofit2.Call<List<com.hamtaro.hamchat.network.GroupMemberDto>>, t: Throwable) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@ChatActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Muestra la lista de miembros con opciones
     */
    private fun showMembersListDialog(members: List<com.hamtaro.hamchat.network.GroupMemberDto>) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentUserId = prefs.getInt("auth_user_id", -1)
        
        // Verificar si el usuario actual es admin
        val currentUserMember = members.find { it.user_id == currentUserId }
        val isAdmin = currentUserMember?.role == "admin"
        
        // Construir lista de miembros
        val membersList = StringBuilder()
        membersList.append("👥 Miembros (${members.size}):\n\n")
        
        for (member in members) {
            val roleIcon = if (member.role == "admin") "👑" else "👤"
            val youLabel = if (member.user_id == currentUserId) " (Tú)" else ""
            membersList.append("$roleIcon ${member.username}$youLabel\n")
            membersList.append("    📱 ${member.phone_e164}\n\n")
        }
        
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        
        // Opción para agregar miembro (solo admin)
        if (isAdmin) {
            options.add("➕ Agregar miembro")
            actions.add { showAddMemberDialog() }
        }
        
        // Opción para eliminar miembro (solo admin)
        if (isAdmin && members.size > 1) {
            options.add("➖ Eliminar miembro")
            actions.add { showRemoveMemberDialog(members.filter { it.user_id != currentUserId }) }
        }
        
        // Opción para salir del grupo
        options.add("🚪 Salir del grupo")
        actions.add { confirmLeaveGroup() }
        
        // Cerrar
        options.add("❌ Cerrar")
        actions.add { /* No hacer nada */ }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("👥 $contactName")
            .setMessage(membersList.toString())
            .setItems(options.toTypedArray()) { _, which ->
                if (which < actions.size) {
                    actions[which]()
                }
            }
            .show()
    }
    
    /**
     * Diálogo para agregar un nuevo miembro al grupo
     */
    private fun showAddMemberDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "ID del usuario a agregar"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(50, 30, 50, 30)
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➕ Agregar miembro")
            .setMessage("Ingresa el ID del usuario que deseas agregar al grupo:")
            .setView(input)
            .setPositiveButton("Agregar") { _, _ ->
                val userId = input.text.toString().toIntOrNull()
                if (userId != null) {
                    addMemberToGroup(userId)
                } else {
                    Toast.makeText(this, "ID inválido", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Agrega un miembro al grupo
     */
    private fun addMemberToGroup(userId: Int) {
        if (groupId == null) return
        
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) return
        
        val authHeader = "Bearer $token"
        val request = com.hamtaro.hamchat.network.AddGroupMemberRequest(user_id = userId)
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.addGroupMember(authHeader, groupId!!, request)
            .enqueue(object : retrofit2.Callback<Map<String, Any>> {
                override fun onResponse(
                    call: retrofit2.Call<Map<String, Any>>,
                    response: retrofit2.Response<Map<String, Any>>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ChatActivity, "✅ Miembro agregado", Toast.LENGTH_SHORT).show()
                    } else {
                        val error = when (response.code()) {
                            403 -> "Solo los admins pueden agregar miembros"
                            404 -> "Usuario no encontrado"
                            400 -> "El usuario ya está en el grupo"
                            else -> "Error al agregar miembro"
                        }
                        Toast.makeText(this@ChatActivity, error, Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onFailure(call: retrofit2.Call<Map<String, Any>>, t: Throwable) {
                    Toast.makeText(this@ChatActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Diálogo para seleccionar qué miembro eliminar
     */
    private fun showRemoveMemberDialog(members: List<com.hamtaro.hamchat.network.GroupMemberDto>) {
        if (members.isEmpty()) {
            Toast.makeText(this, "No hay miembros para eliminar", Toast.LENGTH_SHORT).show()
            return
        }
        
        val memberNames = members.map { "${it.username} (${it.phone_e164})" }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➖ Eliminar miembro")
            .setItems(memberNames) { _, which ->
                val member = members[which]
                confirmRemoveMember(member)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Confirma la eliminación de un miembro
     */
    private fun confirmRemoveMember(member: com.hamtaro.hamchat.network.GroupMemberDto) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Eliminar miembro")
            .setMessage("¿Seguro que quieres eliminar a ${member.username} del grupo?")
            .setPositiveButton("Eliminar") { _, _ ->
                removeMemberFromGroup(member.user_id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Elimina un miembro del grupo
     */
    private fun removeMemberFromGroup(userId: Int) {
        if (groupId == null) return
        
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        if (token.isNullOrEmpty()) return
        
        val authHeader = "Bearer $token"
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.removeGroupMember(authHeader, groupId!!, userId)
            .enqueue(object : retrofit2.Callback<Map<String, Any>> {
                override fun onResponse(
                    call: retrofit2.Call<Map<String, Any>>,
                    response: retrofit2.Response<Map<String, Any>>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ChatActivity, "✅ Miembro eliminado", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ChatActivity, "Error al eliminar miembro", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onFailure(call: retrofit2.Call<Map<String, Any>>, t: Throwable) {
                    Toast.makeText(this@ChatActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    /**
     * Confirma salir del grupo
     */
    private fun confirmLeaveGroup() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🚪 Salir del grupo")
            .setMessage("¿Seguro que quieres salir de \"$contactName\"?\n\nYa no podrás ver los mensajes del grupo.")
            .setPositiveButton("Salir") { _, _ ->
                leaveGroup()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Sale del grupo actual
     */
    private fun leaveGroup() {
        if (groupId == null) return
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentUserId = prefs.getInt("auth_user_id", -1)
        val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken()
        
        if (token.isNullOrEmpty() || currentUserId == -1) return
        
        val authHeader = "Bearer $token"
        
        com.hamtaro.hamchat.network.HamChatApiClient.api.removeGroupMember(authHeader, groupId!!, currentUserId)
            .enqueue(object : retrofit2.Callback<Map<String, Any>> {
                override fun onResponse(
                    call: retrofit2.Call<Map<String, Any>>,
                    response: retrofit2.Response<Map<String, Any>>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@ChatActivity, "Has salido del grupo", Toast.LENGTH_SHORT).show()
                        finish() // Cerrar el chat
                    } else {
                        Toast.makeText(this@ChatActivity, "Error al salir del grupo", Toast.LENGTH_SHORT).show()
                    }
                }
                
                override fun onFailure(call: retrofit2.Call<Map<String, Any>>, t: Throwable) {
                    Toast.makeText(this@ChatActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            })
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
    
    // ========== Sistema de Mensajes de Voz ==========
    
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceButton() {
        voiceButton?.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    stopRecordingAndSend()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun startRecording() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
                return
            }
        }
        
        try {
            val audioDir = java.io.File(cacheDir, "voice_messages")
            if (!audioDir.exists()) audioDir.mkdirs()
            currentAudioFile = java.io.File(audioDir, "voice_${System.currentTimeMillis()}.m4a")
            
            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }
            
            mediaRecorder?.apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(currentAudioFile?.absolutePath)
                prepare()
                start()
            }
            
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            recordingIndicator?.visibility = View.VISIBLE
            voiceButton?.text = "🔴"
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error al grabar", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }
    
    private fun stopRecordingAndSend() {
        if (!isRecording) return
        
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            recordingIndicator?.visibility = View.GONE
            voiceButton?.text = "🎤"
            
            val duration = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
            if (duration < 1) {
                currentAudioFile?.delete()
                return
            }
            
            val audioFile = currentAudioFile
            if (audioFile != null && audioFile.exists()) {
                val audioBase64 = android.util.Base64.encodeToString(audioFile.readBytes(), android.util.Base64.NO_WRAP)
                sendVoiceMessage(audioBase64, duration)
                audioFile.delete()
            }
        } catch (e: Exception) {
            recordingIndicator?.visibility = View.GONE
            voiceButton?.text = "🎤"
        }
    }
    
    private fun sendVoiceMessage(audioBase64: String, duration: Int) {
        val localId = java.util.UUID.randomUUID().toString()
        
        // Guardar audio localmente
        saveMediaLocally(localId, "voice", audioBase64)
        
        val voiceMessage = ChatMessage(
            sender = "Yo",
            content = "🎤 Mensaje de voz (${duration}s)",
            timestamp = System.currentTimeMillis(),
            localId = localId,
            messageType = "voice",
            audioData = audioBase64,  // Se guarda localmente
            audioDuration = duration
        )
        
        messages.add(voiceMessage)
        saveMessages()
        renderMessages(forceRender = true)
        
        // Solo enviar notificación al servidor (sin datos multimedia)
        if (remoteUserId != null) {
            val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken() ?: return
            val request = com.hamtaro.hamchat.network.MessageRequest(
                recipient_id = remoteUserId!!,
                content = voiceMessage.content,
                local_id = localId,
                message_type = "voice",
                audio_data = null,  // NO enviar audio al servidor
                audio_duration = duration
            )
            com.hamtaro.hamchat.network.HamChatApiClient.api.sendMessage("Bearer $token", request)
                .enqueue(object : retrofit2.Callback<com.hamtaro.hamchat.network.MessageDto> {
                    override fun onResponse(call: retrofit2.Call<com.hamtaro.hamchat.network.MessageDto>, response: retrofit2.Response<com.hamtaro.hamchat.network.MessageDto>) {}
                    override fun onFailure(call: retrofit2.Call<com.hamtaro.hamchat.network.MessageDto>, t: Throwable) {}
                })
        }
    }
    
    private fun playVoiceMessage(audioData: String) {
        try {
            val audioBytes = android.util.Base64.decode(audioData, android.util.Base64.NO_WRAP)
            val tempFile = java.io.File(cacheDir, "temp_voice.m4a")
            tempFile.writeBytes(audioBytes)
            
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener { tempFile.delete() }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al reproducir", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ========== Sistema de Fotos ==========
    
    private fun setupPhotoButton() {
        photoButton?.setOnClickListener {
            showPhotoOptions()
        }
    }
    
    private fun showPhotoOptions() {
        val options = arrayOf("📷 Tomar foto", "🖼️ Elegir de galería")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enviar imagen")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> openGallery()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun openCamera() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA)
                return
            }
        }
        
        try {
            val photoFile = java.io.File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            currentPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            
            val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, currentPhotoUri)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CAMERA)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir cámara", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openGallery() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_PICK)
            intent.type = "image/*"
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_GALLERY)
        } catch (e: Exception) {
            Toast.makeText(this, "Error al abrir galería", Toast.LENGTH_SHORT).show()
        }
    }
    
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode != android.app.Activity.RESULT_OK) return
        
        when (requestCode) {
            REQUEST_CAMERA -> {
                currentPhotoUri?.let { uri ->
                    processAndSendImage(uri)
                }
            }
            REQUEST_GALLERY -> {
                data?.data?.let { uri ->
                    processAndSendImage(uri)
                }
            }
        }
    }
    
    private fun processAndSendImage(uri: android.net.Uri) {
        try {
            // Cargar y comprimir imagen
            val inputStream = contentResolver.openInputStream(uri)
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (originalBitmap == null) {
                Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Redimensionar si es muy grande (max 800px)
            val maxSize = 800
            val scale = minOf(maxSize.toFloat() / originalBitmap.width, maxSize.toFloat() / originalBitmap.height, 1f)
            val newWidth = (originalBitmap.width * scale).toInt()
            val newHeight = (originalBitmap.height * scale).toInt()
            
            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
            
            // Comprimir a JPEG
            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
            val imageBytes = outputStream.toByteArray()
            
            // Convertir a Base64
            val imageBase64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
            
            // Enviar mensaje de imagen
            sendImageMessage(imageBase64)
            
            // Limpiar
            if (scaledBitmap != originalBitmap) scaledBitmap.recycle()
            originalBitmap.recycle()
            
        } catch (e: Exception) {
            Toast.makeText(this, "Error al procesar imagen", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun sendImageMessage(imageBase64: String) {
        val localId = java.util.UUID.randomUUID().toString()
        
        // Guardar imagen localmente
        saveMediaLocally(localId, "image", imageBase64)
        
        val imageMessage = ChatMessage(
            sender = "Yo",
            content = "📷 Imagen",
            timestamp = System.currentTimeMillis(),
            localId = localId,
            messageType = "image",
            imageData = imageBase64  // Se guarda localmente
        )
        
        messages.add(imageMessage)
        saveMessages()
        renderMessages(forceRender = true)
        
        // Solo enviar notificación al servidor (sin datos multimedia)
        if (remoteUserId != null) {
            val token = com.hamtaro.hamchat.security.SecurePreferences(this).getAuthToken() ?: return
            val request = com.hamtaro.hamchat.network.MessageRequest(
                recipient_id = remoteUserId!!,
                content = imageMessage.content,
                local_id = localId,
                message_type = "image",
                image_data = null  // NO enviar imagen al servidor
            )
            com.hamtaro.hamchat.network.HamChatApiClient.api.sendMessage("Bearer $token", request)
                .enqueue(object : retrofit2.Callback<com.hamtaro.hamchat.network.MessageDto> {
                    override fun onResponse(call: retrofit2.Call<com.hamtaro.hamchat.network.MessageDto>, response: retrofit2.Response<com.hamtaro.hamchat.network.MessageDto>) {}
                    override fun onFailure(call: retrofit2.Call<com.hamtaro.hamchat.network.MessageDto>, t: Throwable) {}
                })
        }
        
        Toast.makeText(this, "📷 Imagen guardada localmente", Toast.LENGTH_SHORT).show()
    }
    
    private fun showFullImage(imageData: String) {
        try {
            val imageBytes = android.util.Base64.decode(imageData, android.util.Base64.NO_WRAP)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            val imageView = android.widget.ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(imageView)
                .setPositiveButton("Cerrar", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al mostrar imagen", Toast.LENGTH_SHORT).show()
        }
    }
    
    // ========== Almacenamiento Local de Multimedia ==========
    
    /**
     * Guarda multimedia (audio/imagen) localmente en el dispositivo
     */
    private fun saveMediaLocally(localId: String, type: String, data: String) {
        try {
            val mediaDir = java.io.File(filesDir, "media_$contactId")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            
            val extension = if (type == "voice") "m4a" else "jpg"
            val file = java.io.File(mediaDir, "${localId}.$extension")
            
            val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            file.writeBytes(bytes)
        } catch (e: Exception) {
            // Silently fail - data is still in memory
        }
    }
    
    /**
     * Carga multimedia desde almacenamiento local
     */
    private fun loadMediaLocally(localId: String, type: String): String? {
        try {
            val mediaDir = java.io.File(filesDir, "media_$contactId")
            val extension = if (type == "voice") "m4a" else "jpg"
            val file = java.io.File(mediaDir, "${localId}.$extension")
            
            if (file.exists()) {
                val bytes = file.readBytes()
                return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            // File not found or error reading
        }
        return null
    }
    
    /**
     * Verifica si existe multimedia local para un mensaje
     */
    private fun hasLocalMedia(localId: String, type: String): Boolean {
        val mediaDir = java.io.File(filesDir, "media_$contactId")
        val extension = if (type == "voice") "m4a" else "jpg"
        val file = java.io.File(mediaDir, "${localId}.$extension")
        return file.exists()
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
