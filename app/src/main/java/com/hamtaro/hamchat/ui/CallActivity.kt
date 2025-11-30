package com.hamtaro.hamchat.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.network.HamChatApiClient
import com.hamtaro.hamchat.security.SecurePreferences
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Tipos de llamada soportados
 */
enum class CallType {
    VOICE_WIFI,      // Llamada de voz por WiFi/datos (VoIP)
    VIDEO,           // Videollamada
    PHONE            // Llamada telefónica tradicional
}

/**
 * Estados de la llamada
 */
enum class CallState {
    IDLE,
    CALLING,         // Llamando (esperando respuesta)
    RINGING,         // Recibiendo llamada
    CONNECTED,       // En llamada
    ENDED            // Llamada terminada
}

/**
 * Activity para gestionar llamadas de voz, video y telefónicas
 */
class CallActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_REMOTE_USER_ID = "remote_user_id"
        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_IS_INCOMING = "is_incoming"
        
        private const val PERMISSION_REQUEST_CODE = 100
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        /**
         * Iniciar una llamada saliente
         */
        fun startCall(context: Context, contactId: String, contactName: String, 
                      remoteUserId: Int, callType: CallType) {
            val intent = Intent(context, CallActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_REMOTE_USER_ID, remoteUserId)
                putExtra(EXTRA_CALL_TYPE, callType.name)
                putExtra(EXTRA_IS_INCOMING, false)
            }
            context.startActivity(intent)
        }
    }
    
    // UI Elements
    private lateinit var contactNameText: TextView
    private lateinit var callStatusText: TextView
    private lateinit var callTimer: Chronometer
    private lateinit var contactAvatar: ImageView
    private lateinit var btnMute: Button
    private lateinit var btnSpeaker: Button
    private lateinit var btnEndCall: Button
    private lateinit var btnAnswer: Button
    private lateinit var btnDecline: Button
    private lateinit var incomingCallLayout: LinearLayout
    private lateinit var activeCallLayout: LinearLayout
    
    // Call data
    private var contactId: String = ""
    private var contactName: String = ""
    private var remoteUserId: Int = 0
    private var callType: CallType = CallType.VOICE_WIFI
    private var isIncoming: Boolean = false
    private var callState: CallState = CallState.IDLE
    
    // Audio
    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isMuted = false
    private var isSpeakerOn = false
    
    // VoIP
    private var voipSocket: DatagramSocket? = null
    private var voipThread: Thread? = null
    private var receiveThread: Thread? = null
    
    // Handler para actualizaciones de UI
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)
        
        // Obtener datos del intent
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID) ?: ""
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Contacto"
        remoteUserId = intent.getIntExtra(EXTRA_REMOTE_USER_ID, 0)
        callType = CallType.valueOf(intent.getStringExtra(EXTRA_CALL_TYPE) ?: "VOICE_WIFI")
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        
        initializeUI()
        setupAudio()
        
        if (checkPermissions()) {
            if (isIncoming) {
                showIncomingCall()
            } else {
                startOutgoingCall()
            }
        }
    }
    
    private fun initializeUI() {
        contactNameText = findViewById(R.id.tv_contact_name)
        callStatusText = findViewById(R.id.tv_call_status)
        callTimer = findViewById(R.id.call_timer)
        contactAvatar = findViewById(R.id.iv_contact_avatar)
        btnMute = findViewById(R.id.btn_mute)
        btnSpeaker = findViewById(R.id.btn_speaker)
        btnEndCall = findViewById(R.id.btn_end_call)
        btnAnswer = findViewById(R.id.btn_answer)
        btnDecline = findViewById(R.id.btn_decline)
        incomingCallLayout = findViewById(R.id.layout_incoming_call)
        activeCallLayout = findViewById(R.id.layout_active_call)
        
        contactNameText.text = contactName
        
        // Configurar botones
        btnMute.setOnClickListener { toggleMute() }
        btnSpeaker.setOnClickListener { toggleSpeaker() }
        btnEndCall.setOnClickListener { endCall() }
        btnAnswer.setOnClickListener { answerCall() }
        btnDecline.setOnClickListener { declineCall() }
        
        // Mostrar tipo de llamada
        val callTypeText = when (callType) {
            CallType.VOICE_WIFI -> "🎤 Llamada de voz"
            CallType.VIDEO -> "📹 Videollamada"
            CallType.PHONE -> "📞 Llamada telefónica"
        }
        callStatusText.text = callTypeText
    }
    
    private fun setupAudio() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    }
    
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )
        
        if (callType == CallType.VIDEO) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        if (callType == CallType.PHONE) {
            permissions.add(Manifest.permission.CALL_PHONE)
        }
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        return if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
            false
        } else {
            true
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (isIncoming) {
                    showIncomingCall()
                } else {
                    startOutgoingCall()
                }
            } else {
                Toast.makeText(this, "Se necesitan permisos para realizar llamadas", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun showIncomingCall() {
        callState = CallState.RINGING
        incomingCallLayout.visibility = View.VISIBLE
        activeCallLayout.visibility = View.GONE
        callStatusText.text = "Llamada entrante..."
        
        // Reproducir tono de llamada
        playRingtone()
    }
    
    private fun startOutgoingCall() {
        when (callType) {
            CallType.PHONE -> startPhoneCall()
            CallType.VOICE_WIFI -> startVoIPCall()
            CallType.VIDEO -> startVideoCall()
        }
    }
    
    /**
     * Iniciar llamada telefónica tradicional
     */
    private fun startPhoneCall() {
        // Obtener número de teléfono del contacto
        val prefs = getSharedPreferences("hamchat_settings", MODE_PRIVATE)
        val contactsJson = prefs.getString("contacts_list", "[]")
        
        try {
            val contacts = org.json.JSONArray(contactsJson)
            var phoneNumber: String? = null
            
            for (i in 0 until contacts.length()) {
                val contact = contacts.getJSONObject(i)
                if (contact.optInt("remote_user_id") == remoteUserId) {
                    phoneNumber = contact.optString("phone_e164", null)
                    break
                }
            }
            
            if (phoneNumber != null) {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                }
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) 
                    == PackageManager.PERMISSION_GRANTED) {
                    startActivity(intent)
                    finish()
                } else {
                    // Abrir marcador si no hay permiso
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$phoneNumber")
                    }
                    startActivity(dialIntent)
                    finish()
                }
            } else {
                Toast.makeText(this, "No se encontró el número de teléfono", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar llamada", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    /**
     * Iniciar llamada VoIP
     */
    private fun startVoIPCall() {
        callState = CallState.CALLING
        incomingCallLayout.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        callStatusText.text = "Llamando..."
        
        // Notificar al servidor sobre la llamada
        notifyCallToServer("start")
        
        // Simular conexión después de 2 segundos (en producción, esperar respuesta del servidor)
        handler.postDelayed({
            if (callState == CallState.CALLING) {
                connectVoIPCall()
            }
        }, 2000)
    }
    
    /**
     * Conectar llamada VoIP
     */
    private fun connectVoIPCall() {
        callState = CallState.CONNECTED
        callStatusText.text = "En llamada"
        
        // Iniciar cronómetro
        callTimer.visibility = View.VISIBLE
        callTimer.base = SystemClock.elapsedRealtime()
        callTimer.start()
        
        // Iniciar transmisión de audio
        startAudioStreaming()
    }
    
    /**
     * Iniciar videollamada
     */
    private fun startVideoCall() {
        callState = CallState.CALLING
        incomingCallLayout.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        callStatusText.text = "Conectando video..."
        
        // Notificar al servidor
        notifyCallToServer("video_start")
        
        // Por ahora, mostrar mensaje de que está en desarrollo
        Toast.makeText(this, "📹 Videollamada en desarrollo", Toast.LENGTH_SHORT).show()
        
        // Simular conexión
        handler.postDelayed({
            if (callState == CallState.CALLING) {
                callState = CallState.CONNECTED
                callStatusText.text = "Videollamada activa"
                callTimer.visibility = View.VISIBLE
                callTimer.base = SystemClock.elapsedRealtime()
                callTimer.start()
                startAudioStreaming()
            }
        }, 2000)
    }
    
    /**
     * Iniciar streaming de audio
     */
    private fun startAudioStreaming() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        isRecording = true
        
        // Thread para capturar y enviar audio
        voipThread = Thread {
            val buffer = ByteArray(bufferSize)
            audioRecord?.startRecording()
            
            while (isRecording && callState == CallState.CONNECTED) {
                if (!isMuted) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        sendAudioPacket(buffer, bytesRead)
                    }
                }
                Thread.sleep(20) // 20ms de audio por paquete
            }
            
            audioRecord?.stop()
        }
        voipThread?.start()
        
        // Thread para recibir y reproducir audio
        receiveThread = Thread {
            audioTrack?.play()
            
            while (isRecording && callState == CallState.CONNECTED) {
                val audioData = receiveAudioPacket()
                if (audioData != null && audioData.isNotEmpty()) {
                    audioTrack?.write(audioData, 0, audioData.size)
                }
            }
            
            audioTrack?.stop()
        }
        receiveThread?.start()
    }
    
    /**
     * Enviar paquete de audio (simulado - en producción usar WebRTC o servidor TURN)
     */
    private fun sendAudioPacket(data: ByteArray, length: Int) {
        // En una implementación real, esto enviaría datos a través de WebRTC o un servidor
        // Por ahora, solo simulamos el envío
        try {
            // Aquí iría la lógica de envío real
            // voipSocket?.send(DatagramPacket(data, length, serverAddress, serverPort))
        } catch (e: Exception) {
            // Manejar error silenciosamente
        }
    }
    
    /**
     * Recibir paquete de audio (simulado)
     */
    private fun receiveAudioPacket(): ByteArray? {
        // En una implementación real, esto recibiría datos de WebRTC o servidor
        // Por ahora, retornamos null para simular silencio
        Thread.sleep(20)
        return null
    }
    
    /**
     * Notificar al servidor sobre el estado de la llamada
     */
    private fun notifyCallToServer(action: String) {
        Thread {
            try {
                val securePrefs = SecurePreferences(this)
                val token = securePrefs.getAuthToken() ?: return@Thread
                
                // Aquí iría la llamada al endpoint del servidor para notificar la llamada
                // HamChatApiClient.api.notifyCall(...)
                
            } catch (e: Exception) {
                // Manejar error silenciosamente
            }
        }.start()
    }
    
    /**
     * Reproducir tono de llamada
     */
    private fun playRingtone() {
        try {
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_RINGTONE
            )
            val ringtone = android.media.RingtoneManager.getRingtone(this, ringtoneUri)
            ringtone?.play()
        } catch (e: Exception) {
            // Usar tono por defecto si falla
        }
    }
    
    /**
     * Contestar llamada entrante
     */
    private fun answerCall() {
        incomingCallLayout.visibility = View.GONE
        activeCallLayout.visibility = View.VISIBLE
        
        when (callType) {
            CallType.VOICE_WIFI -> connectVoIPCall()
            CallType.VIDEO -> {
                callState = CallState.CONNECTED
                callStatusText.text = "Videollamada activa"
                callTimer.visibility = View.VISIBLE
                callTimer.base = SystemClock.elapsedRealtime()
                callTimer.start()
                startAudioStreaming()
            }
            CallType.PHONE -> {
                // Las llamadas telefónicas se manejan por el sistema
            }
        }
        
        notifyCallToServer("answer")
    }
    
    /**
     * Rechazar llamada entrante
     */
    private fun declineCall() {
        notifyCallToServer("decline")
        callState = CallState.ENDED
        finish()
    }
    
    /**
     * Alternar silencio del micrófono
     */
    private fun toggleMute() {
        isMuted = !isMuted
        btnMute.text = if (isMuted) "🔇 Silenciado" else "🎤 Micrófono"
        btnMute.alpha = if (isMuted) 0.5f else 1.0f
    }
    
    /**
     * Alternar altavoz
     */
    private fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        audioManager?.isSpeakerphoneOn = isSpeakerOn
        btnSpeaker.text = if (isSpeakerOn) "🔊 Altavoz ON" else "🔈 Altavoz"
        btnSpeaker.alpha = if (isSpeakerOn) 1.0f else 0.7f
    }
    
    /**
     * Terminar llamada
     */
    private fun endCall() {
        callState = CallState.ENDED
        isRecording = false
        
        // Detener audio
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignorar errores al liberar recursos
        }
        
        // Cerrar socket
        voipSocket?.close()
        
        // Detener cronómetro
        callTimer.stop()
        
        // Notificar al servidor
        notifyCallToServer("end")
        
        // Mostrar duración final
        Toast.makeText(this, "Llamada finalizada", Toast.LENGTH_SHORT).show()
        
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (callState != CallState.ENDED) {
            endCall()
        }
    }
    
    override fun onBackPressed() {
        // Confirmar antes de colgar
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("¿Terminar llamada?")
            .setMessage("¿Deseas finalizar la llamada con $contactName?")
            .setPositiveButton("Terminar") { _, _ -> endCall() }
            .setNegativeButton("Continuar", null)
            .show()
    }
}
