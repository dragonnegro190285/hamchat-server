package com.hamtaro.hamchat.server

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hamtaro.hamchat.network.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Base de datos SQLite local para el servidor embebido
 * Almacena usuarios, mensajes, contactos y tokens de autenticación
 */
class LocalDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "hamchat_local.db"
        private const val DATABASE_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                phone_country_code TEXT NOT NULL,
                phone_national TEXT NOT NULL,
                phone_e164 TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender_id INTEGER NOT NULL,
                recipient_id INTEGER NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(sender_id) REFERENCES users(id),
                FOREIGN KEY(recipient_id) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_user_id INTEGER NOT NULL,
                contact_user_id INTEGER NOT NULL,
                alias TEXT,
                created_at TEXT NOT NULL,
                UNIQUE(owner_user_id, contact_user_id),
                FOREIGN KEY(owner_user_id) REFERENCES users(id),
                FOREIGN KEY(contact_user_id) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS auth_tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                token TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ladas (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL UNIQUE,
                label TEXT,
                created_at TEXT NOT NULL
            )
        """)

        // Índices para mejor rendimiento
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sender ON messages(sender_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_recipient ON messages(recipient_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_owner ON contacts(owner_user_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Manejar migraciones aquí si es necesario
    }

    private fun nowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // ========== Users ==========

    data class UserRecord(
        val id: Int,
        val username: String,
        val passwordHash: String,
        val phoneCountryCode: String,
        val phoneNational: String,
        val phoneE164: String,
        val createdAt: String
    )

    fun createUser(username: String, passwordHash: String, cc: String, nat: String, e164: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("username", username)
            put("password_hash", passwordHash)
            put("phone_country_code", cc)
            put("phone_national", nat)
            put("phone_e164", e164)
            put("created_at", nowIso())
        }
        return db.insert("users", null, values).toInt()
    }

    fun getUserByUsername(username: String): UserRecord? {
        val db = readableDatabase
        val cursor = db.query("users", null, "username = ?", arrayOf(username), null, null, null)
        return cursor.use {
            if (it.moveToFirst()) cursorToUser(it) else null
        }
    }

    fun getUserByPhone(e164: String): UserRecord? {
        val db = readableDatabase
        val cursor = db.query("users", null, "phone_e164 = ?", arrayOf(e164), null, null, null)
        return cursor.use {
            if (it.moveToFirst()) cursorToUser(it) else null
        }
    }

    fun getUserById(id: Int): UserRecord? {
        val db = readableDatabase
        val cursor = db.query("users", null, "id = ?", arrayOf(id.toString()), null, null, null)
        return cursor.use {
            if (it.moveToFirst()) cursorToUser(it) else null
        }
    }

    private fun cursorToUser(cursor: android.database.Cursor): UserRecord {
        return UserRecord(
            id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
            username = cursor.getString(cursor.getColumnIndexOrThrow("username")),
            passwordHash = cursor.getString(cursor.getColumnIndexOrThrow("password_hash")),
            phoneCountryCode = cursor.getString(cursor.getColumnIndexOrThrow("phone_country_code")),
            phoneNational = cursor.getString(cursor.getColumnIndexOrThrow("phone_national")),
            phoneE164 = cursor.getString(cursor.getColumnIndexOrThrow("phone_e164")),
            createdAt = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
        )
    }

    // ========== Auth Tokens ==========

    fun createToken(userId: Int, token: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("user_id", userId)
            put("token", token)
            put("created_at", nowIso())
        }
        db.insert("auth_tokens", null, values)
    }

    fun getUserIdByToken(token: String): Int? {
        val db = readableDatabase
        val cursor = db.query("auth_tokens", arrayOf("user_id"), "token = ?", arrayOf(token), null, null, null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else null
        }
    }

    // ========== Messages ==========

    fun createMessage(senderId: Int, recipientId: Int, content: String): MessageDto {
        val db = writableDatabase
        val createdAt = nowIso()
        val values = ContentValues().apply {
            put("sender_id", senderId)
            put("recipient_id", recipientId)
            put("content", content)
            put("created_at", createdAt)
        }
        val id = db.insert("messages", null, values).toInt()
        return MessageDto(id, senderId, recipientId, content, createdAt)
    }

    fun getMessages(currentUserId: Int, withUserId: Int, limit: Int): List<MessageDto> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT id, sender_id, recipient_id, content, created_at
            FROM messages
            WHERE (sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?)
            ORDER BY id DESC
            LIMIT ?
        """, arrayOf(
            currentUserId.toString(), withUserId.toString(),
            withUserId.toString(), currentUserId.toString(),
            limit.toString()
        ))

        val messages = mutableListOf<MessageDto>()
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages.reversed()
    }

    /**
     * Obtiene solo mensajes con ID mayor a sinceId
     * Esta función es clave para evitar duplicados en reconexión
     */
    fun getMessagesSince(currentUserId: Int, withUserId: Int, sinceId: Int): List<MessageDto> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT id, sender_id, recipient_id, content, created_at
            FROM messages
            WHERE id > ?
              AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))
            ORDER BY id ASC
        """, arrayOf(
            sinceId.toString(),
            currentUserId.toString(), withUserId.toString(),
            withUserId.toString(), currentUserId.toString()
        ))

        val messages = mutableListOf<MessageDto>()
        cursor.use {
            while (it.moveToNext()) {
                messages.add(cursorToMessage(it))
            }
        }
        return messages
    }

    private fun cursorToMessage(cursor: android.database.Cursor): MessageDto {
        return MessageDto(
            id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
            sender_id = cursor.getInt(cursor.getColumnIndexOrThrow("sender_id")),
            recipient_id = cursor.getInt(cursor.getColumnIndexOrThrow("recipient_id")),
            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
            created_at = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
        )
    }

    // ========== Inbox ==========

    fun getInbox(userId: Int): List<InboxItemDto> {
        val db = readableDatabase
        
        val idsCursor = db.rawQuery("""
            SELECT MAX(id) AS max_id
            FROM (
                SELECT id,
                       CASE WHEN sender_id = ? THEN recipient_id ELSE sender_id END AS other_user_id
                FROM messages
                WHERE sender_id = ? OR recipient_id = ?
            )
            GROUP BY other_user_id
        """, arrayOf(userId.toString(), userId.toString(), userId.toString()))

        val lastIds = mutableListOf<Int>()
        idsCursor.use {
            while (it.moveToNext()) {
                lastIds.add(it.getInt(0))
            }
        }

        if (lastIds.isEmpty()) return emptyList()

        val placeholders = lastIds.joinToString(",") { "?" }
        val args = arrayOf(userId.toString()) + lastIds.map { it.toString() }

        val cursor = db.rawQuery("""
            SELECT m.id, m.sender_id, m.recipient_id, m.content, m.created_at,
                   u.id AS other_id, u.username, u.phone_e164
            FROM messages m
            JOIN users u ON u.id = CASE WHEN m.sender_id = ? THEN m.recipient_id ELSE m.sender_id END
            WHERE m.id IN ($placeholders)
            ORDER BY m.id DESC
        """, args)

        val result = mutableListOf<InboxItemDto>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(InboxItemDto(
                    with_user_id = it.getInt(it.getColumnIndexOrThrow("other_id")),
                    username = it.getString(it.getColumnIndexOrThrow("username")),
                    phone_e164 = it.getString(it.getColumnIndexOrThrow("phone_e164")),
                    last_message = it.getString(it.getColumnIndexOrThrow("content")),
                    last_message_at = it.getString(it.getColumnIndexOrThrow("created_at")),
                    last_message_id = it.getInt(it.getColumnIndexOrThrow("id"))
                ))
            }
        }
        return result
    }

    // ========== Contacts ==========

    fun getContacts(userId: Int): List<ContactDto> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT c.id, c.contact_user_id, c.alias, u.username, u.phone_e164
            FROM contacts c
            JOIN users u ON u.id = c.contact_user_id
            WHERE c.owner_user_id = ?
            ORDER BY u.username
        """, arrayOf(userId.toString()))

        val contacts = mutableListOf<ContactDto>()
        cursor.use {
            while (it.moveToNext()) {
                contacts.add(ContactDto(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    contact_user_id = it.getInt(it.getColumnIndexOrThrow("contact_user_id")),
                    alias = it.getString(it.getColumnIndexOrThrow("alias")),
                    username = it.getString(it.getColumnIndexOrThrow("username")),
                    phone_e164 = it.getString(it.getColumnIndexOrThrow("phone_e164"))
                ))
            }
        }
        return contacts
    }

    fun getContact(ownerId: Int, contactUserId: Int): ContactDto? {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT c.id, c.contact_user_id, c.alias, u.username, u.phone_e164
            FROM contacts c
            JOIN users u ON u.id = c.contact_user_id
            WHERE c.owner_user_id = ? AND c.contact_user_id = ?
        """, arrayOf(ownerId.toString(), contactUserId.toString()))

        return cursor.use {
            if (it.moveToFirst()) {
                ContactDto(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    contact_user_id = it.getInt(it.getColumnIndexOrThrow("contact_user_id")),
                    alias = it.getString(it.getColumnIndexOrThrow("alias")),
                    username = it.getString(it.getColumnIndexOrThrow("username")),
                    phone_e164 = it.getString(it.getColumnIndexOrThrow("phone_e164"))
                )
            } else null
        }
    }

    fun addContact(ownerId: Int, contactUserId: Int, alias: String?): ContactDto {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("owner_user_id", ownerId)
            put("contact_user_id", contactUserId)
            put("alias", alias)
            put("created_at", nowIso())
        }
        db.insert("contacts", null, values)

        return getContact(ownerId, contactUserId)!!
    }

    fun deleteContact(ownerId: Int, contactUserId: Int): Boolean {
        val db = writableDatabase
        val deleted = db.delete("contacts", 
            "owner_user_id = ? AND contact_user_id = ?", 
            arrayOf(ownerId.toString(), contactUserId.toString()))
        return deleted > 0
    }

    // ========== Ladas ==========

    fun getLadas(): List<LadaDto> {
        val db = readableDatabase
        val cursor = db.query("ladas", null, null, null, null, null, "code")
        
        val ladas = mutableListOf<LadaDto>()
        cursor.use {
            while (it.moveToNext()) {
                ladas.add(LadaDto(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    code = it.getString(it.getColumnIndexOrThrow("code")),
                    label = it.getString(it.getColumnIndexOrThrow("label")),
                    created_at = it.getString(it.getColumnIndexOrThrow("created_at"))
                ))
            }
        }
        return ladas
    }

    fun addLada(code: String, label: String?): LadaDto {
        val db = writableDatabase
        val createdAt = nowIso()
        
        val values = ContentValues().apply {
            put("code", code)
            put("label", label)
            put("created_at", createdAt)
        }
        
        val id = db.insertWithOnConflict("ladas", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        
        return if (id > 0) {
            LadaDto(id.toInt(), code, label, createdAt)
        } else {
            val cursor = db.query("ladas", null, "code = ?", arrayOf(code), null, null, null)
            cursor.use {
                it.moveToFirst()
                LadaDto(
                    id = it.getInt(it.getColumnIndexOrThrow("id")),
                    code = it.getString(it.getColumnIndexOrThrow("code")),
                    label = it.getString(it.getColumnIndexOrThrow("label")),
                    created_at = it.getString(it.getColumnIndexOrThrow("created_at"))
                )
            }
        }
    }

    // ========== Database Reset ==========

    /**
     * Limpia TODOS los datos de la base de datos
     * Útil para pruebas en limpio
     */
    fun resetDatabase() {
        val db = writableDatabase
        db.execSQL("DELETE FROM auth_tokens")
        db.execSQL("DELETE FROM messages")
        db.execSQL("DELETE FROM contacts")
        db.execSQL("DELETE FROM users")
        db.execSQL("DELETE FROM ladas")
        
        // Reset autoincrement counters
        db.execSQL("DELETE FROM sqlite_sequence")
    }

    /**
     * Limpia solo los mensajes
     */
    fun clearMessages() {
        val db = writableDatabase
        db.execSQL("DELETE FROM messages")
    }

    /**
     * Limpia solo los tokens de autenticación
     */
    fun clearTokens() {
        val db = writableDatabase
        db.execSQL("DELETE FROM auth_tokens")
    }

    /**
     * Obtiene estadísticas de la base de datos
     */
    fun getStats(): Map<String, Int> {
        val db = readableDatabase
        return mapOf(
            "users" to getCount(db, "users"),
            "messages" to getCount(db, "messages"),
            "contacts" to getCount(db, "contacts"),
            "tokens" to getCount(db, "auth_tokens"),
            "ladas" to getCount(db, "ladas")
        )
    }

    private fun getCount(db: SQLiteDatabase, table: String): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
