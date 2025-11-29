package com.hamtaro.toxmessenger.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.MutableLiveData
import im.tox.tox4j.ToxCore
import im.tox.tox4j.ToxOptions
import im.tox.tox4j.exceptions.ToxException
import im.tox.tox4j.impl.jni.ToxCoreImpl
import java.io.File
import java.util.*

class ToxService : Service() {
    
    private val binder = LocalBinder()
    private var tox: ToxCore? = null
    
    // LiveData for UI updates
    val connectionStatus = MutableLiveData<Boolean>()
    val messages = MutableLiveData<List<ToxMessage>>()
    val friends = MutableLiveData<List<ToxFriend>>()
    
    private val messageList = mutableListOf<ToxMessage>()
    private val friendList = mutableListOf<ToxFriend>()
    
    inner class LocalBinder : Binder() {
        fun getService(): ToxService = this@ToxService
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        initializeTox()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    private fun initializeTox() {
        try {
            val options = ToxOptions.Builder()
                .ipv6Enabled(false)
                .udpEnabled(true)
                .tcpEnabled(true)
                .build()
            
            val saveFile = File(filesDir, "tox_save")
            tox = if (saveFile.exists()) {
                ToxCoreImpl(options, saveFile.readBytes())
            } else {
                ToxCoreImpl(options)
            }
            
            tox?.let { toxInstance ->
                // Set up callbacks
                setupCallbacks(toxInstance)
                
                // Start Tox instance
                toxInstance.bootstrap(
                    "tox.zetok.net", 33445,
                    "A1451B3DC7E04B3B8F558733A31778E627F8C73C7"
                )
                
                connectionStatus.postValue(true)
                startToxLoop()
            }
            
        } catch (e: ToxException) {
            connectionStatus.postValue(false)
        }
    }
    
    private fun setupCallbacks(toxInstance: ToxCore) {
        toxInstance.callbackFriendRequest { publicKey, timeDelta, message ->
            // Handle friend request
            val friend = ToxFriend(
                publicKey = publicKey.joinToString("") { "%02X".format(it) },
                displayName = "Unknown",
                statusMessage = "",
                isOnline = false
            )
            friendList.add(friend)
            friends.postValue(friendList.toList())
        }
        
        toxInstance.callbackFriendMessage { friendNumber, messageType, timeDelta, messageBytes ->
            val message = String(messageBytes)
            val friend = friendList.find { it.friendNumber == friendNumber }
            friend?.let {
                val toxMessage = ToxMessage(
                    sender = it.displayName,
                    content = message,
                    timestamp = System.currentTimeMillis(),
                    isIncoming = true
                )
                messageList.add(toxMessage)
                messages.postValue(messageList.toList())
            }
        }
        
        toxInstance.callbackFriendStatusMessage { friendNumber, message ->
            friendList.find { it.friendNumber == friendNumber }?.statusMessage = message
            friends.postValue(friendList.toList())
        }
        
        toxInstance.callbackFriendConnectionStatus { friendNumber, connectionStatus ->
            friendList.find { it.friendNumber == friendNumber }?.isOnline = 
                connectionStatus != im.tox.tox4j.enums.ToxConnection.NONE
            friends.postValue(friendList.toList())
        }
    }
    
    private fun startToxLoop() {
        Thread {
            while (true) {
                tox?.iterate()
                Thread.sleep(tox?.iterationInterval() ?: 20)
            }
        }.start()
    }
    
    fun sendMessage(friendNumber: Int, message: String): Boolean {
        return try {
            tox?.friendSendMessage(friendNumber, im.tox.tox4j.enums.ToxMessageKind.NORMAL, message.toByteArray()) ?: -1 >= 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun addFriend(toxId: String, message: String = "Hello from Hamtaro Tox!"): Boolean {
        return try {
            val publicKey = toxId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val friendNumber = tox?.friendAdd(publicKey, message.toByteArray()) ?: -1
            if (friendNumber >= 0) {
                val friend = ToxFriend(
                    friendNumber = friendNumber,
                    publicKey = toxId,
                    displayName = "Friend $friendNumber",
                    statusMessage = "",
                    isOnline = false
                )
                friendList.add(friend)
                friends.postValue(friendList.toList())
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    fun getToxId(): String {
        return tox?.address?.joinToString("") { "%02X".format(it) } ?: ""
    }
    
    fun getShortToxId(): String {
        val fullId = getToxId()
        return fullId.take(6).uppercase()
    }
    
    fun getDisplayName(friendNumber: Int): String {
        return tox?.friendGetName(friendNumber) ?: "Unknown"
    }
    
    fun setDisplayName(name: String): Boolean {
        return try {
            tox?.setName(name.toByteArray())
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        tox?.let { toxInstance ->
            val saveData = toxInstance.save()
            val saveFile = File(filesDir, "tox_save")
            saveFile.writeBytes(saveData)
            toxInstance.close()
        }
    }
}

data class ToxMessage(
    val sender: String,
    val content: String,
    val timestamp: Long,
    val isIncoming: Boolean
)

data class ToxFriend(
    val friendNumber: Int = -1,
    val publicKey: String,
    var displayName: String,
    var statusMessage: String,
    var isOnline: Boolean
)
