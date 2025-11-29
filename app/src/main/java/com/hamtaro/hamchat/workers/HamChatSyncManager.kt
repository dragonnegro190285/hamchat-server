package com.hamtaro.hamchat.workers

import android.content.Context
import android.content.SharedPreferences
import com.hamtaro.hamchat.network.HamChatApiClient
import com.hamtaro.hamchat.network.InboxItemDto
import com.hamtaro.hamchat.network.MessageDto
import com.hamtaro.hamchat.network.MessageRequest
import com.hamtaro.hamchat.notifications.HamChatNotificationManager
import com.hamtaro.hamchat.security.SecureLogger
import com.hamtaro.hamchat.security.SecurePreferences
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

private const val PREFS_NAME = "hamchat_settings"
private const val KEY_AUTH_USER_ID = "auth_user_id"
private const val KEY_AUTH_TOKEN = "auth_token"
private const val KEY_PENDING_PREFIX = "pending_"
private const val KEY_LAST_MSG_PREFIX = "last_msg_id_"

/**
 * HamChatSyncManager
 * Centraliza toda la logica cliente-servidor de mensajes:
 * - Envio de mensajes pendientes
 * - Carga de historial remoto
 * - Sondeo de inbox y procesamiento de notificaciones
 */
object HamChatSyncManager {

    data class RemoteMessagesResult(
        val currentUserId: Int,
        val messages: List<MessageDto>
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Obtener token de forma segura desde SecurePreferences, migrando desde
     * SharedPreferences normales si existe un token legacy.
     */
    fun getAuthTokenSecure(context: Context, legacyPrefs: SharedPreferences): String? {
        return try {
            val securePrefs = SecurePreferences(context)
            val secureToken = securePrefs.getAuthToken()
            if (!secureToken.isNullOrEmpty()) {
                secureToken
            } else {
                val legacyToken = legacyPrefs.getString(KEY_AUTH_TOKEN, null)
                if (!legacyToken.isNullOrEmpty()) {
                    securePrefs.setAuthToken(legacyToken)
                    legacyToken
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Obtener el último ID de mensaje guardado para una conversación
     */
    fun getLastMessageId(context: Context, remoteUserId: Int): Int {
        val prefs = getPrefs(context)
        return prefs.getInt("$KEY_LAST_MSG_PREFIX$remoteUserId", 0)
    }

    /**
     * Guardar el último ID de mensaje para una conversación
     */
    fun saveLastMessageId(context: Context, remoteUserId: Int, messageId: Int) {
        val prefs = getPrefs(context)
        prefs.edit().putInt("$KEY_LAST_MSG_PREFIX$remoteUserId", messageId).apply()
    }

    /**
     * Cargar mensajes desde el servidor para un chat remoto.
     * SIEMPRE carga todos los mensajes porque el servidor en internet es la fuente de verdad.
     * El cliente reemplaza su lista local con lo que devuelve el servidor.
     */
    fun loadMessagesFromServer(
        context: Context,
        remoteUserId: Int,
        onSuccess: (RemoteMessagesResult) -> Unit,
        onHttpError: (Int) -> Unit,
        onNetworkError: (Throwable) -> Unit,
        onAuthMissing: () -> Unit
    ) {
        val prefs = getPrefs(context)
        val token = getAuthTokenSecure(context, prefs)
        val currentUserId = prefs.getInt(KEY_AUTH_USER_ID, -1)
        if (token.isNullOrEmpty() || currentUserId <= 0) {
            onAuthMissing()
            return
        }

        val authHeader = "Bearer $token"

        try {
            // Siempre cargar todos los mensajes del servidor
            // El servidor en internet es la fuente de verdad
            HamChatApiClient.api.getMessages(authHeader, remoteUserId, 100)
                .enqueue(object : Callback<List<MessageDto>> {
                    override fun onResponse(
                        call: Call<List<MessageDto>>,
                        response: Response<List<MessageDto>>
                    ) {
                        val body = response.body()
                        if (response.isSuccessful && body != null) {
                            onSuccess(RemoteMessagesResult(currentUserId, body))
                        } else {
                            onHttpError(response.code())
                        }
                    }

                    override fun onFailure(call: Call<List<MessageDto>>, t: Throwable) {
                        onNetworkError(t)
                    }
                })
        } catch (e: Exception) {
            onNetworkError(e)
        }
    }

    // ========== Cola de mensajes pendientes ==========

    private fun getPendingKey(contactId: String): String {
        return KEY_PENDING_PREFIX + contactId
    }

    fun addPendingMessage(context: Context, contactId: String, content: String, timestamp: Long) {
        val key = getPendingKey(contactId)
        val prefs = getPrefs(context)
        val existing = prefs.getString(key, null)
        val array = if (existing.isNullOrEmpty()) {
            JSONArray()
        } else {
            try {
                JSONArray(existing)
            } catch (_: Exception) {
                JSONArray()
            }
        }
        val obj = JSONObject()
        obj.put("content", content)
        obj.put("timestamp", timestamp)
        array.put(obj)
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun loadPendingMessages(context: Context, contactId: String): MutableList<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        val key = getPendingKey(contactId)
        val prefs = getPrefs(context)
        val json = prefs.getString(key, null) ?: return result
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val content = obj.optString("content", "")
                if (content.isEmpty()) continue
                val ts = obj.optLong("timestamp", System.currentTimeMillis())
                result.add(content to ts)
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun savePendingMessages(context: Context, contactId: String, pending: List<Pair<String, Long>>) {
        val key = getPendingKey(contactId)
        val prefs = getPrefs(context)
        if (pending.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val array = JSONArray()
        for ((content, ts) in pending) {
            val obj = JSONObject()
            obj.put("content", content)
            obj.put("timestamp", ts)
            array.put(obj)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun flushPendingMessages(
        context: Context,
        contactId: String,
        remoteUserId: Int,
        onFinished: (() -> Unit)? = null
    ) {
        val prefs = getPrefs(context)
        val token = getAuthTokenSecure(context, prefs)
        if (token.isNullOrEmpty()) {
            onFinished?.invoke()
            return
        }
        val pendingList = loadPendingMessages(context, contactId)
        if (pendingList.isEmpty()) {
            onFinished?.invoke()
            return
        }
        sendNextPending(context, contactId, pendingList, remoteUserId, "Bearer $token", onFinished)
    }

    private fun sendNextPending(
        context: Context,
        contactId: String,
        pending: MutableList<Pair<String, Long>>,
        remoteUserId: Int,
        authHeader: String,
        onFinished: (() -> Unit)?
    ) {
        if (pending.isEmpty()) {
            savePendingMessages(context, contactId, emptyList())
            onFinished?.invoke()
            return
        }

        val (content, _) = pending.first()
        val request = MessageRequest(recipient_id = remoteUserId, content = content)

        try {
            HamChatApiClient.api.sendMessage(authHeader, request)
                .enqueue(object : Callback<MessageDto> {
                    override fun onResponse(
                        call: Call<MessageDto>,
                        response: Response<MessageDto>
                    ) {
                        if (response.isSuccessful) {
                            pending.removeAt(0)
                            savePendingMessages(context, contactId, pending)
                            sendNextPending(context, contactId, pending, remoteUserId, authHeader, onFinished)
                        } else {
                            onFinished?.invoke()
                        }
                    }

                    override fun onFailure(call: Call<MessageDto>, t: Throwable) {
                        onFinished?.invoke()
                    }
                })
        } catch (_: Exception) {
            onFinished?.invoke()
        }
    }

    // ========== Sondeo de Inbox ==========

    /**
     * Sondeo asincrono del inbox (para usar desde MainActivity con la app abierta).
     */
    fun pollInboxOnce(context: Context) {
        try {
            val securePrefs = SecurePreferences(context)
            val token = securePrefs.getAuthToken() ?: return
            HamChatApiClient.api.getInbox("Bearer $token")
                .enqueue(object : Callback<List<InboxItemDto>> {
                    override fun onResponse(
                        call: Call<List<InboxItemDto>>,
                        response: Response<List<InboxItemDto>>
                    ) {
                        if (response.isSuccessful && response.body() != null) {
                            processInboxItems(context, response.body() ?: emptyList())
                        }
                    }

                    override fun onFailure(
                        call: Call<List<InboxItemDto>>,
                        t: Throwable
                    ) {
                        // Silent failure
                    }
                })
        } catch (_: Exception) {
        }
    }

    /**
     * Sondeo bloqueante del inbox (para usar desde InboxWorker en background).
     */
    fun pollInboxBlocking(context: Context) {
        try {
            val securePrefs = SecurePreferences(context)
            val token = securePrefs.getAuthToken() ?: return
            val response = HamChatApiClient.api
                .getInbox("Bearer $token")
                .execute()
            if (!response.isSuccessful || response.body() == null) {
                return
            }
            val items = response.body() ?: emptyList()
            processInboxItems(context, items)
        } catch (e: Exception) {
            SecureLogger.e("HamChatSyncManager.pollInboxBlocking failed", e)
        }
    }

    /**
     * Procesar elementos del inbox: detectar mensajes nuevos y mostrar notificaciones.
     */
    fun processInboxItems(context: Context, items: List<InboxItemDto>) {
        if (items.isEmpty()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val notificationManager = HamChatNotificationManager(context)

            val contactsJson = prefs.getString("custom_contacts", "[]")
            val contactsArray = try {
                JSONArray(contactsJson)
            } catch (_: Exception) {
                JSONArray()
            }

            val contactNameByRemoteId = mutableMapOf<Int, String>()
            for (i in 0 until contactsArray.length()) {
                val obj = contactsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (!id.startsWith("remote_")) continue
                val remoteId = id.removePrefix("remote_").toIntOrNull() ?: continue
                val name = obj.optString("name", "Contacto")
                contactNameByRemoteId[remoteId] = name
            }

            val editor = prefs.edit()

            for (item in items) {
                val key = "inbox_last_id_${item.with_user_id}"
                val lastSeen = prefs.getInt(key, 0)
                if (item.last_message_id <= lastSeen) {
                    continue
                }

                val contactId = "remote_${item.with_user_id}"
                val contactName = contactNameByRemoteId[item.with_user_id]

                if (contactName != null) {
                    notificationManager.showMessageNotification(
                        contactName = contactName,
                        message = item.last_message,
                        contactId = contactId
                    )
                } else {
                    notificationManager.showNewChatNotification(
                        contactName = item.username,
                        message = item.last_message,
                        contactId = contactId,
                        remoteUserId = item.with_user_id,
                        phoneE164 = item.phone_e164
                    )
                }

                editor.putInt(key, item.last_message_id)
            }

            editor.apply()
        } catch (e: Exception) {
            SecureLogger.e("Error processing inbox items", e)
        }
    }
}
