package com.hamtaro.hamchat.workers

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.hamtaro.hamchat.security.SecureLogger

/**
 * InboxWorker
 * Sondea el endpoint /api/inbox para detectar mensajes nuevos en segundo plano
 * y dispara notificaciones locales sin abrir la app.
 * Delega toda la logica a HamChatSyncManager.
 */
class InboxWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            HamChatSyncManager.pollInboxBlocking(applicationContext)
            Result.success()
        } catch (e: Exception) {
            SecureLogger.e("InboxWorker failed", e)
            Result.success()
        }
    }
}
