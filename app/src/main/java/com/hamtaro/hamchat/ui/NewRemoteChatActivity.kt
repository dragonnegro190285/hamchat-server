package com.hamtaro.hamchat.ui

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.hamchat.notifications.HamChatNotificationManager
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "hamchat_settings"
private const val KEY_FRIEND_IDS = "friend_ids"

/**
 * 🐹 NewRemoteChatActivity
 * Pantalla que se abre desde la notificación "Nuevo chat" para aceptar o
 * ignorar la creación de un contacto remoto en este dispositivo.
 */
class NewRemoteChatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val remoteUserId = intent.getIntExtra("remote_user_id", -1)
        val username = intent.getStringExtra("remote_username") ?: "Usuario"
        val phoneE164 = intent.getStringExtra("remote_phone_e164") ?: ""
        val lastMessage = intent.getStringExtra("last_message") ?: ""

        if (remoteUserId <= 0) {
            Toast.makeText(this, "Datos de chat no válidos", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleView = TextView(this).apply {
            text = "Nuevo chat de $username"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }

        val infoView = TextView(this).apply {
            val phoneText = if (phoneE164.isNotEmpty()) "\nTeléfono: $phoneE164" else ""
            val msgText = if (lastMessage.isNotEmpty()) "\n\nÚltimo mensaje:\n$lastMessage" else ""
            text = "Puedes elegir si quieres agregar a este usuario como contacto en este dispositivo.$phoneText$msgText"
            setPadding(0, 0, 0, 32)
        }

        val acceptButton = Button(this).apply {
            text = "Aceptar y agregar contacto"
            setOnClickListener {
                acceptRemoteContact(remoteUserId, username, phoneE164)
            }
        }

        val ignoreButton = Button(this).apply {
            text = "Ignorar"
            setOnClickListener {
                Toast.makeText(
                    this@NewRemoteChatActivity,
                    "Solicitud de chat ignorada",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }

        container.addView(titleView)
        container.addView(infoView)
        container.addView(acceptButton)
        container.addView(ignoreButton)

        setContentView(container)
    }

    private fun acceptRemoteContact(remoteUserId: Int, username: String, phoneE164: String) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val contactsJson = prefs.getString("custom_contacts", "[]")
            val contactsArray = try {
                JSONArray(contactsJson)
            } catch (_: Exception) {
                JSONArray()
            }

            val contactId = "remote_$remoteUserId"

            // Evitar duplicados
            for (i in 0 until contactsArray.length()) {
                val obj = contactsArray.optJSONObject(i) ?: continue
                if (obj.optString("id") == contactId) {
                    Toast.makeText(this, "El contacto ya existe", Toast.LENGTH_SHORT).show()
                    HamChatNotificationManager(this).clearNotifications(contactId)
                    finish()
                    return
                }
            }

            val newContact = JSONObject().apply {
                put("id", contactId)
                put("name", username)
                put("lastMessage", "Nuevo mensaje")
                put("timestamp", "Ahora")
                put("isOnline", false)
                put("unreadCount", 1)
                put("whatsappNumber", "")
            }
            contactsArray.put(newContact)

            // Actualizar contactos y marcar como amigo
            val friendIds = prefs.getStringSet(KEY_FRIEND_IDS, emptySet())?.toMutableSet() ?: mutableSetOf()
            friendIds.add(contactId)

            prefs.edit()
                .putString("custom_contacts", contactsArray.toString())
                .putStringSet(KEY_FRIEND_IDS, friendIds)
                .apply()

            HamChatNotificationManager(this).clearNotifications(contactId)

            Toast.makeText(this, "Contacto remoto agregado", Toast.LENGTH_SHORT).show()

            // Abrir directamente el chat con este contacto remoto
            try {
                val intent = android.content.Intent(this, ChatActivity::class.java).apply {
                    putExtra("contact_id", contactId)
                    putExtra("contact_name", username)
                }
                startActivity(intent)
            } catch (_: Exception) {
            }

            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo agregar el contacto", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
