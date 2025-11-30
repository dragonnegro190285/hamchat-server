package com.hamtaro.hamchat.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.hamtaro.hamchat.network.HamChatApiClient
import com.hamtaro.hamchat.security.SecurePreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Worker para realizar backup semanal automático de mensajes del servidor
 * y guardarlos localmente antes del vaciado
 */
class WeeklyBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WeeklyBackupWorker"
        private const val WORK_NAME = "weekly_backup_work"
        private const val BACKUP_FOLDER = "hamchat_backups"
        private const val MAX_LOCAL_BACKUPS = 12 // Mantener últimos 3 meses de backups semanales
        
        /**
         * Programar backup semanal automático
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<WeeklyBackupWorker>(
                7, TimeUnit.DAYS // Cada 7 días
            )
                .setConstraints(constraints)
                .setInitialDelay(calculateInitialDelay(), TimeUnit.MILLISECONDS)
                .addTag("weekly_backup")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            
            Log.d(TAG, "✅ Backup semanal programado")
        }
        
        /**
         * Cancelar backup semanal
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "❌ Backup semanal cancelado")
        }
        
        /**
         * Ejecutar backup manual inmediato
         */
        fun runNow(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<WeeklyBackupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag("manual_backup")
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "🔄 Backup manual iniciado")
        }
        
        /**
         * Calcular delay inicial para ejecutar el domingo a las 3 AM
         */
        private fun calculateInitialDelay(): Long {
            val now = Calendar.getInstance()
            val nextSunday = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                set(Calendar.HOUR_OF_DAY, 3)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                
                if (before(now)) {
                    add(Calendar.WEEK_OF_YEAR, 1)
                }
            }
            
            return nextSunday.timeInMillis - now.timeInMillis
        }
        
        /**
         * Obtener carpeta de backups
         */
        fun getBackupFolder(context: Context): File {
            val folder = File(context.filesDir, BACKUP_FOLDER)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            return folder
        }
        
        /**
         * Listar backups locales disponibles
         */
        fun listLocalBackups(context: Context): List<BackupInfo> {
            val folder = getBackupFolder(context)
            return folder.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { file ->
                    try {
                        val json = JSONObject(file.readText())
                        BackupInfo(
                            fileName = file.name,
                            filePath = file.absolutePath,
                            createdAt = json.optString("created_at", ""),
                            messageCount = json.optJSONArray("messages")?.length() ?: 0,
                            contactCount = json.optJSONArray("contacts")?.length() ?: 0,
                            sizeBytes = file.length()
                        )
                    } catch (e: Exception) {
                        BackupInfo(
                            fileName = file.name,
                            filePath = file.absolutePath,
                            createdAt = "",
                            messageCount = 0,
                            contactCount = 0,
                            sizeBytes = file.length()
                        )
                    }
                }
                ?.sortedByDescending { it.createdAt }
                ?: emptyList()
        }
        
        /**
         * Restaurar backup local
         */
        fun restoreLocalBackup(context: Context, fileName: String): BackupData? {
            return try {
                val file = File(getBackupFolder(context), fileName)
                if (file.exists()) {
                    val json = JSONObject(file.readText())
                    BackupData(
                        messages = json.optJSONArray("messages") ?: JSONArray(),
                        contacts = json.optJSONArray("contacts") ?: JSONArray(),
                        createdAt = json.optString("created_at", "")
                    )
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error restaurando backup: ${e.message}")
                null
            }
        }
        
        /**
         * Eliminar backup local
         */
        fun deleteLocalBackup(context: Context, fileName: String): Boolean {
            return try {
                val file = File(getBackupFolder(context), fileName)
                file.delete()
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "🔄 Iniciando backup semanal completo...")
        
        return try {
            val prefs = applicationContext.getSharedPreferences("HamChatPrefs", Context.MODE_PRIVATE)
            val securePrefs = SecurePreferences(applicationContext)
            val token = securePrefs.getAuthToken()
            val userId = prefs.getInt("auth_user_id", -1)
            
            if (token.isNullOrEmpty() || userId == -1) {
                Log.w(TAG, "⚠️ Usuario no autenticado, saltando backup")
                return Result.success()
            }
            
            // 1. Obtener backup completo del servidor (todos los mensajes y contactos)
            val fullBackup = fetchFullBackupFromServer(token)
            
            if (fullBackup != null) {
                // 2. Guardar backup en archivo local
                saveBackupToFile(fullBackup)
                
                // 3. Limpiar backups antiguos locales
                cleanOldBackups()
                
                // 4. Limpiar mensajes antiguos del servidor (más de 7 días)
                cleanupServerMessages(token)
                
                val totalMessages = fullBackup.optInt("total_messages", 0)
                val totalContacts = fullBackup.optInt("total_contacts", 0)
                Log.d(TAG, "✅ Backup semanal completado: $totalMessages mensajes, $totalContacts contactos")
            } else {
                Log.w(TAG, "⚠️ No se pudo obtener backup del servidor")
            }
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en backup semanal: ${e.message}")
            Result.retry()
        }
    }
    
    /**
     * Obtener backup completo del servidor con TODOS los mensajes y contactos
     */
    private fun fetchFullBackupFromServer(token: String): JSONObject? {
        return try {
            val response = HamChatApiClient.api.getFullBackup("Bearer $token").execute()
            if (response.isSuccessful && response.body() != null) {
                val backup = response.body()!!
                
                // Convertir a JSONObject para guardar
                JSONObject().apply {
                    put("version", 2) // Versión 2 = backup completo
                    put("app_version", "1.0")
                    put("user_id", backup.userId)
                    put("username", backup.username)
                    put("phone_e164", backup.phoneE164)
                    put("backup_date", backup.backupDate)
                    put("total_messages", backup.totalMessages)
                    put("total_contacts", backup.totalContacts)
                    
                    // Contactos con nombre, teléfono y cantidad de mensajes
                    put("contacts", JSONArray().apply {
                        backup.contacts.forEach { contact ->
                            put(JSONObject().apply {
                                put("id", contact.id)
                                put("username", contact.username)
                                put("phone_e164", contact.phoneE164)
                                put("message_count", contact.messageCount)
                            })
                        }
                    })
                    
                    // TODOS los mensajes completos
                    put("messages", JSONArray().apply {
                        backup.messages.forEach { msg ->
                            put(JSONObject().apply {
                                put("id", msg.id)
                                put("sender_id", msg.senderId)
                                put("recipient_id", msg.recipientId)
                                put("content", msg.content)
                                put("created_at", msg.createdAt)
                                put("sent_at", msg.sentAt ?: "")
                                put("local_id", msg.localId ?: "")
                                put("sender_name", msg.senderName)
                                put("sender_phone", msg.senderPhone)
                                put("recipient_name", msg.recipientName)
                                put("recipient_phone", msg.recipientPhone)
                                put("is_outgoing", msg.isOutgoing)
                            })
                        }
                    })
                }
            } else {
                Log.e(TAG, "Error obteniendo backup: ${response.code()} - ${response.message()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo backup completo: ${e.message}")
            null
        }
    }
    
    private fun createLocalBackup(messages: JSONArray, contacts: JSONArray): JSONObject {
        val prefs = applicationContext.getSharedPreferences("HamChatPrefs", Context.MODE_PRIVATE)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        
        return JSONObject().apply {
            put("version", 1)
            put("app_version", "1.0")
            put("created_at", dateFormat.format(Date()))
            put("device_id", getDeviceId())
            put("user_id", prefs.getInt("auth_user_id", -1))
            put("username", prefs.getString("auth_username", ""))
            put("messages", messages)
            put("contacts", contacts)
            put("message_count", messages.length())
            put("contact_count", contacts.length())
        }
    }
    
    private fun saveBackupToFile(backupData: JSONObject) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val fileName = "backup_${dateFormat.format(Date())}.json"
        val file = File(getBackupFolder(applicationContext), fileName)
        
        file.writeText(backupData.toString(2))
        Log.d(TAG, "💾 Backup guardado: $fileName (${file.length()} bytes)")
    }
    
    private fun cleanOldBackups() {
        val folder = getBackupFolder(applicationContext)
        val backups = folder.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        
        // Mantener solo los últimos MAX_LOCAL_BACKUPS
        if (backups.size > MAX_LOCAL_BACKUPS) {
            backups.drop(MAX_LOCAL_BACKUPS).forEach { file ->
                file.delete()
                Log.d(TAG, "🗑️ Backup antiguo eliminado: ${file.name}")
            }
        }
    }
    
    private fun getDeviceId(): String {
        val prefs = applicationContext.getSharedPreferences("HamChatPrefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }
    
    private fun cleanupServerMessages(token: String) {
        try {
            val request = com.hamtaro.hamchat.network.CleanupRequest(daysToKeep = 7)
            val response = HamChatApiClient.api.cleanupMessages("Bearer $token", request).execute()
            if (response.isSuccessful) {
                val result = response.body()
                Log.d(TAG, "🗑️ Servidor limpiado: ${result?.deletedMessages} mensajes eliminados")
            } else {
                Log.w(TAG, "⚠️ Error limpiando servidor: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en limpieza del servidor: ${e.message}")
        }
    }
}

/**
 * Información de un backup local
 */
data class BackupInfo(
    val fileName: String,
    val filePath: String,
    val createdAt: String,
    val messageCount: Int,
    val contactCount: Int,
    val sizeBytes: Long
) {
    fun getSizeFormatted(): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> "${sizeBytes / (1024 * 1024)} MB"
        }
    }
    
    fun getDateFormatted(): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val outputFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            val date = inputFormat.parse(createdAt)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            createdAt
        }
    }
}

/**
 * Datos de un backup
 */
data class BackupData(
    val messages: JSONArray,
    val contacts: JSONArray,
    val createdAt: String
)
