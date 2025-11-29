package com.hamtaro.toxmessenger.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hamtaro.hamchat.server.LocalDatabase
import com.hamtaro.hamchat.utils.InactivityManager
import com.hamtaro.toxmessenger.HamtaroApplication
import com.hamtaro.toxmessenger.R

class SettingsFragment : Fragment() {
    
    private lateinit var languageRadioGroup: RadioGroup
    private lateinit var themeRadioGroup: RadioGroup
    private lateinit var inactivityRadioGroup: RadioGroup
    private lateinit var clearDatabaseButton: Button
    private lateinit var dbStatsText: TextView
    private lateinit var localDatabase: LocalDatabase
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        try {
            localDatabase = LocalDatabase(requireContext())
            
            languageRadioGroup = view.findViewById(R.id.language_radio_group)
            themeRadioGroup = view.findViewById(R.id.theme_radio_group)
            
            // Estos pueden no existir en versiones antiguas del layout
            val inactivityGroup: RadioGroup? = view.findViewById(R.id.inactivity_radio_group)
            val clearBtn: Button? = view.findViewById(R.id.btn_clear_database)
            val statsText: TextView? = view.findViewById(R.id.db_stats)
            
            if (inactivityGroup != null) {
                inactivityRadioGroup = inactivityGroup
            }
            if (clearBtn != null) {
                clearDatabaseButton = clearBtn
            }
            if (statsText != null) {
                dbStatsText = statsText
            }
            
            setupCurrentSettings()
            setupListeners()
            
            if (::dbStatsText.isInitialized) {
                updateDbStats()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error cargando configuración: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun setupCurrentSettings() {
        val currentLanguage = HamtaroApplication.instance.getCurrentLanguage()
        val currentTheme = HamtaroApplication.instance.getCurrentTheme()
        
        when (currentLanguage) {
            "es" -> languageRadioGroup.check(R.id.radio_spanish)
            "de" -> languageRadioGroup.check(R.id.radio_german)
            else -> languageRadioGroup.check(R.id.radio_spanish)
        }
        
        when (currentTheme) {
            HamtaroApplication.THEME_DARK -> themeRadioGroup.check(R.id.radio_dark)
            HamtaroApplication.THEME_HAMTARO -> themeRadioGroup.check(R.id.radio_hamtaro)
            else -> themeRadioGroup.check(R.id.radio_dark)
        }
        
        // Configurar tiempo de inactividad actual
        if (::inactivityRadioGroup.isInitialized) {
            when (InactivityManager.getTimeoutMinutes()) {
                3 -> inactivityRadioGroup.check(R.id.radio_3min)
                5 -> inactivityRadioGroup.check(R.id.radio_5min)
                10 -> inactivityRadioGroup.check(R.id.radio_10min)
                else -> inactivityRadioGroup.check(R.id.radio_5min)
            }
        }
    }
    
    private fun setupListeners() {
        languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val language = when (checkedId) {
                R.id.radio_spanish -> "es"
                R.id.radio_german -> "de"
                else -> "es"
            }
            HamtaroApplication.instance.saveLanguage(language)
            Toast.makeText(context, "Language changed", Toast.LENGTH_SHORT).show()
            activity?.recreate()
        }
        
        themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.radio_dark -> HamtaroApplication.THEME_DARK
                R.id.radio_hamtaro -> HamtaroApplication.THEME_HAMTARO
                else -> HamtaroApplication.THEME_DARK
            }
            HamtaroApplication.instance.saveTheme(theme)
            Toast.makeText(context, "Theme changed", Toast.LENGTH_SHORT).show()
            activity?.recreate()
        }
        
        // Configuración de tiempo de inactividad
        if (::inactivityRadioGroup.isInitialized) {
            inactivityRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                val minutes = when (checkedId) {
                    R.id.radio_3min -> 3
                    R.id.radio_5min -> 5
                    R.id.radio_10min -> 10
                    else -> 5
                }
                InactivityManager.setTimeoutMinutes(minutes)
                Toast.makeText(context, "Cierre por inactividad: $minutes min", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Botón para limpiar base de datos
        if (::clearDatabaseButton.isInitialized) {
            clearDatabaseButton.setOnClickListener {
                showClearDatabaseDialog()
            }
        }
    }
    
    private fun updateDbStats() {
        try {
            val stats = localDatabase.getStats()
            dbStatsText.text = "Usuarios: ${stats["users"]} | Mensajes: ${stats["messages"]} | Contactos: ${stats["contacts"]}"
        } catch (e: Exception) {
            dbStatsText.text = "Error al obtener estadísticas"
        }
    }
    
    private fun showClearDatabaseDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("🗑️ Limpiar Base de Datos")
            .setMessage("¿Estás seguro? Esto eliminará:\n\n• Todos los usuarios\n• Todos los mensajes\n• Todos los contactos\n• Todos los tokens\n\nEsta acción NO se puede deshacer.")
            .setPositiveButton("Sí, limpiar") { _, _ ->
                clearDatabase()
            }
            .setNegativeButton("Cancelar", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
    
    private fun clearDatabase() {
        try {
            // Obtener estadísticas antes
            val statsBefore = localDatabase.getStats()
            
            // Limpiar BD
            localDatabase.resetDatabase()
            
            // Limpiar SharedPreferences de chats locales
            val prefs = requireContext().getSharedPreferences("hamchat_settings", android.content.Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Limpiar todas las claves de chat
            prefs.all.keys.filter { 
                it.startsWith("chat_") || 
                it.startsWith("pending_") || 
                it.startsWith("last_msg_id_") ||
                it.startsWith("inbox_last_id_") ||
                it.startsWith("deleted_msgs_")
            }.forEach { key ->
                editor.remove(key)
            }
            editor.apply()
            
            // Obtener estadísticas después
            val statsAfter = localDatabase.getStats()
            
            // Actualizar UI
            updateDbStats()
            
            val message = """
                ✅ Base de datos limpiada
                
                Antes:
                • Usuarios: ${statsBefore["users"]}
                • Mensajes: ${statsBefore["messages"]}
                • Contactos: ${statsBefore["contacts"]}
                
                Después:
                • Usuarios: ${statsAfter["users"]}
                • Mensajes: ${statsAfter["messages"]}
                • Contactos: ${statsAfter["contacts"]}
            """.trimIndent()
            
            AlertDialog.Builder(requireContext())
                .setTitle("✅ Completado")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
                
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
