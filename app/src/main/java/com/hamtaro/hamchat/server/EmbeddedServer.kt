package com.hamtaro.hamchat.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.hamtaro.hamchat.network.*
import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import java.util.*

/**
 * Servidor HTTP embebido que permite que cada dispositivo funcione como servidor
 * Compatible con la API de HamChat existente
 */
class EmbeddedServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val db = LocalDatabase(context)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                // Health check
                uri == "/api/health" && method == Method.GET -> {
                    jsonResponse(mapOf("status" to "ok"))
                }

                // Register
                uri == "/api/register" && method == Method.POST -> {
                    handleRegister(session)
                }

                // Login
                uri == "/api/login" && method == Method.POST -> {
                    handleLogin(session)
                }

                // Get user by username
                uri.startsWith("/api/users/by-username/") && method == Method.GET -> {
                    val username = uri.removePrefix("/api/users/by-username/")
                    handleGetUserByUsername(username)
                }

                // Get user by phone
                uri == "/api/users/by-phone" && method == Method.GET -> {
                    handleGetUserByPhone(session)
                }

                // Inbox
                uri == "/api/inbox" && method == Method.GET -> {
                    handleInbox(session)
                }

                // Ladas
                uri == "/api/ladas" && method == Method.GET -> {
                    jsonResponse(db.getLadas())
                }
                uri == "/api/ladas" && method == Method.POST -> {
                    handleAddLada(session)
                }

                // Messages
                uri == "/api/messages" && method == Method.POST -> {
                    handleSendMessage(session)
                }
                uri == "/api/messages" && method == Method.GET -> {
                    handleGetMessages(session)
                }
                uri == "/api/messages/since" && method == Method.GET -> {
                    handleGetMessagesSince(session)
                }

                // Contacts
                uri == "/api/contacts" && method == Method.GET -> {
                    handleGetContacts(session)
                }
                uri == "/api/contacts" && method == Method.POST -> {
                    handleAddContact(session)
                }
                uri.startsWith("/api/contacts/") && method == Method.DELETE -> {
                    val contactUserId = uri.removePrefix("/api/contacts/").toIntOrNull()
                    if (contactUserId != null) {
                        handleDeleteContact(session, contactUserId)
                    } else {
                        errorResponse(400, "Invalid contact ID")
                    }
                }

                // Database Admin (para pruebas)
                uri == "/api/admin/reset" && method == Method.POST -> {
                    handleResetDatabase(session)
                }
                uri == "/api/admin/stats" && method == Method.GET -> {
                    jsonResponse(db.getStats())
                }
                uri == "/api/admin/clear-messages" && method == Method.POST -> {
                    db.clearMessages()
                    jsonResponse(mapOf("status" to "ok", "message" to "Messages cleared"))
                }

                else -> {
                    errorResponse(404, "Not found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            errorResponse(500, e.message ?: "Internal server error")
        }
    }

    // ========== Auth Helpers ==========

    private fun getAuthToken(session: IHTTPSession): String? {
        val auth = session.headers["authorization"] ?: return null
        val parts = auth.split(" ", limit = 2)
        if (parts.size != 2 || parts[0].lowercase() != "bearer") return null
        return parts[1].trim().takeIf { it.isNotEmpty() }
    }

    private fun getUserIdFromToken(session: IHTTPSession): Int? {
        val token = getAuthToken(session) ?: return null
        return db.getUserIdByToken(token)
    }

    private fun requireAuth(session: IHTTPSession): Int {
        return getUserIdFromToken(session) 
            ?: throw AuthException("Invalid or missing token")
    }

    // ========== Handlers ==========

    private fun handleRegister(session: IHTTPSession): Response {
        val body = getBody(session)
        val req = gson.fromJson(body, RegisterRequest::class.java)

        val (cc, nat, e164) = normalizePhone(req.phone_country_code, req.phone_national)
        
        val existingByUsername = db.getUserByUsername(req.username)
        if (existingByUsername != null) {
            return errorResponse(400, "username already exists")
        }

        val existingByPhone = db.getUserByPhone(e164)
        if (existingByPhone != null) {
            return errorResponse(400, "phone already registered")
        }

        val userId = db.createUser(req.username, hashPassword(req.password), cc, nat, e164)
        
        return jsonResponse(RegisterResponse(
            id = userId,
            username = req.username,
            phone_country_code = cc,
            phone_national = nat,
            phone_e164 = e164
        ))
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val body = getBody(session)
        val req = gson.fromJson(body, LoginRequest::class.java)

        // Login por teléfono (prioridad)
        var user: LocalDatabase.UserRecord? = null
        if (!req.phone_country_code.isNullOrEmpty() && !req.phone_national.isNullOrEmpty()) {
            val phoneE164 = req.phone_country_code + req.phone_national
            user = db.getUserByPhone(phoneE164)
        }
        
        // Login por username (fallback)
        if (user == null && !req.username.isNullOrEmpty()) {
            user = db.getUserByUsername(req.username)
            // Verificar contraseña solo si se proporcionó
            if (user != null && !req.password.isNullOrEmpty()) {
                if (user.passwordHash != hashPassword(req.password)) {
                    return errorResponse(401, "invalid credentials")
                }
            }
        }
        
        if (user == null) {
            return errorResponse(401, "invalid credentials")
        }

        val token = UUID.randomUUID().toString()
        db.createToken(user.id, token)

        return jsonResponse(LoginResponse(user_id = user.id, token = token))
    }

    private fun handleGetUserByUsername(username: String): Response {
        val user = db.getUserByUsername(username)
            ?: return errorResponse(404, "user not found")

        return jsonResponse(UserLookupResponse(
            id = user.id,
            username = user.username,
            phone_e164 = user.phoneE164
        ))
    }

    private fun handleGetUserByPhone(session: IHTTPSession): Response {
        val params = session.parameters
        val cc = params["phone_country_code"]?.firstOrNull()
            ?: return errorResponse(400, "phone_country_code required")
        val nat = params["phone_national"]?.firstOrNull()
            ?: return errorResponse(400, "phone_national required")

        val (_, _, e164) = normalizePhone(cc, nat)
        val user = db.getUserByPhone(e164)
            ?: return errorResponse(404, "user not found")

        return jsonResponse(UserLookupResponse(
            id = user.id,
            username = user.username,
            phone_e164 = user.phoneE164
        ))
    }

    private fun handleInbox(session: IHTTPSession): Response {
        val userId = requireAuth(session)
        val inbox = db.getInbox(userId)
        return jsonResponse(inbox)
    }

    private fun handleAddLada(session: IHTTPSession): Response {
        val body = getBody(session)
        val req = gson.fromJson(body, LadaCreateRequest::class.java)
        
        var code = req.code.trim()
        if (!code.startsWith("+")) {
            code = "+" + code.trimStart('+', '0')
        }

        val lada = db.addLada(code, req.label)
        return jsonResponse(lada)
    }

    private fun handleSendMessage(session: IHTTPSession): Response {
        val userId = requireAuth(session)
        val body = getBody(session)
        val req = gson.fromJson(body, MessageRequest::class.java)

        val recipient = db.getUserById(req.recipient_id)
            ?: return errorResponse(404, "recipient not found")

        val message = db.createMessage(userId, req.recipient_id, req.content)
        return jsonResponse(message)
    }

    private fun handleGetMessages(session: IHTTPSession): Response {
        val userId = requireAuth(session)
        val params = session.parameters
        val withUserId = params["with_user_id"]?.firstOrNull()?.toIntOrNull()
            ?: return errorResponse(400, "with_user_id required")
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 50

        val messages = db.getMessages(userId, withUserId, limit)
        return jsonResponse(messages)
    }

    /**
     * Endpoint clave para evitar duplicados en reconexión
     * Solo devuelve mensajes con ID mayor a since_id
     */
    private fun handleGetMessagesSince(session: IHTTPSession): Response {
        val userId = requireAuth(session)
        val params = session.parameters
        val withUserId = params["with_user_id"]?.firstOrNull()?.toIntOrNull()
            ?: return errorResponse(400, "with_user_id required")
        val sinceId = params["since_id"]?.firstOrNull()?.toIntOrNull() ?: 0

        val messages = db.getMessagesSince(userId, withUserId, sinceId)
        return jsonResponse(messages)
    }

    private fun handleGetContacts(session: IHTTPSession): Response {
        val userId = requireAuth(session)
        val contacts = db.getContacts(userId)
        return jsonResponse(contacts)
    }

    private fun handleAddContact(session: IHTTPSession): Response {
        val userId = requireAuth(session)
        val body = getBody(session)
        val req = gson.fromJson(body, ContactCreateRequest::class.java)

        if (req.contact_user_id == userId) {
            return errorResponse(400, "cannot add yourself")
        }

        val contactUser = db.getUserById(req.contact_user_id)
            ?: return errorResponse(404, "user not found")

        val existing = db.getContact(userId, req.contact_user_id)
        if (existing != null) {
            return errorResponse(400, "contact already exists")
        }

        val contact = db.addContact(userId, req.contact_user_id, req.alias)
        return jsonResponse(contact)
    }

    private fun handleDeleteContact(session: IHTTPSession, contactUserId: Int): Response {
        val userId = requireAuth(session)
        val deleted = db.deleteContact(userId, contactUserId)
        
        return if (deleted) {
            jsonResponse(mapOf("status" to "ok"))
        } else {
            errorResponse(404, "contact not found")
        }
    }

    // ========== Admin Handlers ==========

    /**
     * Resetea completamente la base de datos
     * Requiere el secret key para seguridad
     */
    private fun handleResetDatabase(session: IHTTPSession): Response {
        val body = getBody(session)
        
        // Verificar secret key para seguridad
        val request = try {
            gson.fromJson(body, ResetRequest::class.java)
        } catch (e: Exception) {
            null
        }
        
        if (request?.secret != ADMIN_SECRET) {
            return errorResponse(403, "Invalid admin secret")
        }
        
        return try {
            db.resetDatabase()
            Log.i(TAG, "🗑️ Database reset completed")
            jsonResponse(mapOf(
                "status" to "ok",
                "message" to "Database reset successfully",
                "stats" to db.getStats()
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Database reset failed", e)
            errorResponse(500, "Reset failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "EmbeddedServer"
        private const val ADMIN_SECRET = "hamchat-reset-2024"
    }

    // ========== Utilities ==========

    private fun getBody(session: IHTTPSession): String {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun jsonResponse(data: Any, status: Response.Status = Response.Status.OK): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }

    private fun errorResponse(code: Int, message: String): Response {
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return jsonResponse(mapOf("detail" to message), status)
    }

    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun normalizePhone(countryCode: String, national: String): Triple<String, String, String> {
        var cc = countryCode.trim()
        if (!cc.startsWith("+")) {
            cc = "+" + cc.trimStart('+', '0')
        }

        val digits = national.filter { it.isDigit() }
        val e164 = cc + digits

        return Triple(cc, digits, e164)
    }

    class AuthException(message: String) : Exception(message)
}

// DTOs adicionales para el servidor
data class ContactCreateRequest(
    val contact_user_id: Int,
    val alias: String? = null
)

data class ContactDto(
    val id: Int,
    val contact_user_id: Int,
    val alias: String?,
    val username: String,
    val phone_e164: String
)

data class ResetRequest(
    val secret: String
)
