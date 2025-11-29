package com.hamtaro.hamchat.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hamtaro.hamchat.network.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Repositorio central para manejo de chat
 * Implementa carga incremental con since_id para evitar duplicados
 */
class ChatRepository(private val context: Context) {

    companion object {
        private const val TAG = "ChatRepository"
        private const val PREFS_NAME = "hamchat_chat_prefs"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LAST_MSG_PREFIX = "last_msg_"
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, using regular", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // Cache de conversaciones para evitar duplicados
    private val conversationCache = mutableMapOf<Int, ConversationState>()

    // ========== Session Management ==========

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: Int
        get() = prefs.getInt(KEY_USER_ID, -1)
        set(value) = prefs.edit().putInt(KEY_USER_ID, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
            if (value.isNotEmpty()) {
                HamChatApiClient.setServerUrl(value)
            }
        }

    val isLoggedIn: Boolean
        get() = token != null && userId > 0

    private val authHeader: String
        get() = "Bearer ${token ?: ""}"

    // ========== Last Message ID Tracking ==========

    /**
     * Guarda el último ID de mensaje para una conversación
     * Esto es clave para evitar duplicados al reconectarse
     */
    fun saveLastMessageId(withUserId: Int, messageId: Int) {
        prefs.edit().putInt("$KEY_LAST_MSG_PREFIX$withUserId", messageId).apply()
        conversationCache[withUserId]?.lastMessageId = messageId
    }

    /**
     * Obtiene el último ID de mensaje conocido para una conversación
     */
    fun getLastMessageId(withUserId: Int): Int {
        return conversationCache[withUserId]?.lastMessageId
            ?: prefs.getInt("$KEY_LAST_MSG_PREFIX$withUserId", 0)
    }

    // ========== Message Operations ==========

    /**
     * Carga inicial de mensajes para una conversación
     * Solo se usa la primera vez que se abre un chat
     */
    fun loadInitialMessages(
        withUserId: Int,
        limit: Int = 50,
        onSuccess: (List<MessageDto>) -> Unit,
        onError: (String) -> Unit
    ) {
        HamChatApiClient.api.getMessages(authHeader, withUserId, limit)
            .enqueue(object : Callback<List<MessageDto>> {
                override fun onResponse(call: Call<List<MessageDto>>, response: Response<List<MessageDto>>) {
                    if (response.isSuccessful) {
                        val messages = response.body() ?: emptyList()
                        
                        // Inicializar cache de conversación
                        val state = ConversationState(withUserId).apply {
                            this.messages.clear()
                            this.messages.addAll(messages)
                            this.lastMessageId = messages.maxOfOrNull { it.id } ?: 0
                        }
                        conversationCache[withUserId] = state
                        
                        // Guardar último ID en persistencia
                        if (state.lastMessageId > 0) {
                            saveLastMessageId(withUserId, state.lastMessageId)
                        }
                        
                        onSuccess(messages)
                    } else {
                        onError("Error: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<List<MessageDto>>, t: Throwable) {
                    Log.e(TAG, "Load messages failed", t)
                    onError(t.message ?: "Error de conexión")
                }
            })
    }

    /**
     * Carga incremental de mensajes - SOLO mensajes nuevos
     * Esta es la función clave para evitar duplicados en reconexión
     */
    fun loadNewMessages(
        withUserId: Int,
        onSuccess: (List<MessageDto>) -> Unit,
        onError: (String) -> Unit
    ) {
        val sinceId = getLastMessageId(withUserId)
        Log.d(TAG, "Loading messages since ID: $sinceId for user: $withUserId")

        HamChatApiClient.api.getMessagesSince(authHeader, withUserId, sinceId)
            .enqueue(object : Callback<List<MessageDto>> {
                override fun onResponse(call: Call<List<MessageDto>>, response: Response<List<MessageDto>>) {
                    if (response.isSuccessful) {
                        val newMessages = response.body() ?: emptyList()
                        
                        if (newMessages.isNotEmpty()) {
                            // Agregar solo mensajes nuevos al cache
                            val state = conversationCache.getOrPut(withUserId) { 
                                ConversationState(withUserId, sinceId) 
                            }
                            
                            // Filtrar duplicados por si acaso
                            val existingIds = state.messages.map { it.id }.toSet()
                            val trulyNew = newMessages.filter { it.id !in existingIds }
                            
                            state.messages.addAll(trulyNew)
                            
                            // Actualizar último ID
                            val maxId = newMessages.maxOf { it.id }
                            state.lastMessageId = maxId
                            saveLastMessageId(withUserId, maxId)
                            
                            Log.d(TAG, "Loaded ${trulyNew.size} new messages")
                        }
                        
                        onSuccess(newMessages)
                    } else {
                        onError("Error: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<List<MessageDto>>, t: Throwable) {
                    Log.e(TAG, "Load new messages failed", t)
                    onError(t.message ?: "Error de conexión")
                }
            })
    }

    /**
     * Envía un mensaje
     */
    fun sendMessage(
        recipientId: Int,
        content: String,
        onSuccess: (MessageDto) -> Unit,
        onError: (String) -> Unit
    ) {
        HamChatApiClient.api.sendMessage(authHeader, MessageRequest(recipientId, content))
            .enqueue(object : Callback<MessageDto> {
                override fun onResponse(call: Call<MessageDto>, response: Response<MessageDto>) {
                    if (response.isSuccessful) {
                        val message = response.body()!!
                        
                        // Agregar al cache local
                        val state = conversationCache.getOrPut(recipientId) { 
                            ConversationState(recipientId) 
                        }
                        state.messages.add(message)
                        state.lastMessageId = message.id
                        saveLastMessageId(recipientId, message.id)
                        
                        onSuccess(message)
                    } else {
                        onError("Error: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<MessageDto>, t: Throwable) {
                    Log.e(TAG, "Send message failed", t)
                    onError(t.message ?: "Error de conexión")
                }
            })
    }

    /**
     * Obtiene mensajes del cache local
     */
    fun getCachedMessages(withUserId: Int): List<MessageDto> {
        return conversationCache[withUserId]?.messages?.toList() ?: emptyList()
    }

    // ========== Auth Operations ==========

    fun login(
        username: String,
        password: String,
        onSuccess: (LoginResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        HamChatApiClient.api.login(LoginRequest(username, password))
            .enqueue(object : Callback<LoginResponse> {
                override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                    if (response.isSuccessful) {
                        val body = response.body()!!
                        token = body.token
                        userId = body.user_id
                        this@ChatRepository.username = username
                        onSuccess(body)
                    } else {
                        onError("Credenciales inválidas")
                    }
                }

                override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                    Log.e(TAG, "Login failed", t)
                    onError(t.message ?: "Error de conexión")
                }
            })
    }

    fun register(
        username: String,
        password: String,
        phoneCountryCode: String,
        phoneNational: String,
        onSuccess: (RegisterResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        HamChatApiClient.api.register(RegisterRequest(username, password, phoneCountryCode, phoneNational))
            .enqueue(object : Callback<RegisterResponse> {
                override fun onResponse(call: Call<RegisterResponse>, response: Response<RegisterResponse>) {
                    if (response.isSuccessful) {
                        onSuccess(response.body()!!)
                    } else {
                        onError("Error en registro: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<RegisterResponse>, t: Throwable) {
                    Log.e(TAG, "Register failed", t)
                    onError(t.message ?: "Error de conexión")
                }
            })
    }

    fun logout() {
        token = null
        userId = -1
        username = null
        conversationCache.clear()
    }

    // ========== Inbox ==========

    fun getInbox(
        onSuccess: (List<InboxItemDto>) -> Unit,
        onError: (String) -> Unit
    ) {
        HamChatApiClient.api.getInbox(authHeader)
            .enqueue(object : Callback<List<InboxItemDto>> {
                override fun onResponse(call: Call<List<InboxItemDto>>, response: Response<List<InboxItemDto>>) {
                    if (response.isSuccessful) {
                        onSuccess(response.body() ?: emptyList())
                    } else {
                        onError("Error: ${response.code()}")
                    }
                }

                override fun onFailure(call: Call<List<InboxItemDto>>, t: Throwable) {
                    Log.e(TAG, "Get inbox failed", t)
                    onError(t.message ?: "Error de conexión")
                }
            })
    }
}

/**
 * Estado de una conversación en cache
 */
data class ConversationState(
    val withUserId: Int,
    var lastMessageId: Int = 0,
    val messages: MutableList<MessageDto> = mutableListOf()
)
