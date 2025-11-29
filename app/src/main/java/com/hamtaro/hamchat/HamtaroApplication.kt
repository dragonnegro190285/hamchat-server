package com.hamtaro.hamchat

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hamtaro.hamchat.data.ChatRepository
import com.hamtaro.hamchat.security.SecureLogger
import com.hamtaro.hamchat.server.HamChatServerService
import com.hamtaro.hamchat.utils.InactivityManager
import com.hamtaro.hamchat.workers.InboxWorker
import java.util.concurrent.TimeUnit

/**
 * Application base para Ham-Chat
 * Inicializa el servidor embebido y el repositorio de chat
 */
class HamtaroApplication : Application() {

    lateinit var chatRepository: ChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Inicializar repositorio de chat
        chatRepository = ChatRepository(this)
        
        // Inicializar gestor de inactividad (5 minutos por defecto)
        InactivityManager.init(this, timeoutMinutes = 5)
        
        SecureLogger.i("HamtaroApplication initialized")

        // Iniciar servidor embebido
        try {
            HamChatServerService.start(this)
            SecureLogger.i("Embedded server started")
        } catch (e: Exception) {
            SecureLogger.e("Failed to start embedded server: ${e.message}")
        }

        // Configurar WorkManager para sincronización en segundo plano
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<InboxWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "HamChatInboxWork",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (_: Exception) {
        }
    }

    override fun onTerminate() {
        HamChatServerService.stop(this)
        super.onTerminate()
    }

    companion object {
        lateinit var instance: HamtaroApplication
            private set
    }
}
