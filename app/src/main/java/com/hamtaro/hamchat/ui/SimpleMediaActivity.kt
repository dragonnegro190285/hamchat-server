package com.hamtaro.hamchat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.multimedia.AudioFormat
import com.hamtaro.hamchat.multimedia.SimpleMediaManager
import com.hamtaro.hamchat.notifications.HamChatNotificationManager
import com.hamtaro.hamchat.security.SecureLogger

/**
 * 📞🎤📄 Simple Media Activity para Ham-Chat
 * Solo: Llamadas, Audio y Texto
 */
class SimpleMediaActivity : AppCompatActivity() {
    
    private lateinit var mediaManager: SimpleMediaManager
    private lateinit var notificationManager: HamChatNotificationManager
    
    // UI Components
    private lateinit var callButton: Button
    private lateinit var recordAudioButton: Button
    private lateinit var sendTextButton: Button
    private lateinit var textInput: EditText
    private lateinit var recordingIndicator: TextView
    private lateinit var playbackButton: Button
    private lateinit var audioFormatSpinner: Spinner
    private lateinit var formatInfoButton: Button
    private lateinit var callStatusText: TextView
    
    // Estado
    private var isRecording = false
    private var isInCall = false
    private var selectedContactId = ""
    private var currentAudioPath: String? = null
    private var selectedAudioFormat = "wav" // Default a WAV
    
    companion object {
        private const val REQUEST_RECORD_AUDIO = 1002
        private const val PERMISSION_REQUEST_CODE = 1003
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_media)
        
        // Inicializar managers
        mediaManager = SimpleMediaManager(this)
        notificationManager = HamChatNotificationManager(this)
        
        // Obtener contact ID del intent
        selectedContactId = intent.getStringExtra("contact_id") ?: ""
        
        // Inicializar UI
        initializeUI()
        setupClickListeners()
        checkPermissions()
    }
    
    /**
     * 🎨 Inicializar componentes UI
     */
    private fun initializeUI() {
        callButton = findViewById(R.id.btn_voice_call)
        recordAudioButton = findViewById(R.id.btn_record_audio)
        sendTextButton = findViewById(R.id.btn_send_text)
        textInput = findViewById(R.id.et_text_input)
        recordingIndicator = findViewById(R.id.tv_recording_indicator)
        playbackButton = findViewById(R.id.btn_playback_audio)
        audioFormatSpinner = findViewById(R.id.spinner_audio_format)
        formatInfoButton = findViewById(R.id.btn_format_info)
        callStatusText = findViewById(R.id.tv_call_status)
        
        // Configurar spinner de formatos de audio
        setupAudioFormatSpinner()
        
        // Estado inicial
        recordingIndicator.visibility = View.GONE
        playbackButton.visibility = View.GONE
        callStatusText.visibility = View.GONE
    }
    
    /**
     * 🔘 Configurar listeners
     */
    private fun setupClickListeners() {
        // Llamada de voz
        callButton.setOnClickListener {
            if (isInCall) {
                endCall()
            } else {
                startCall()
            }
        }
        
        // Grabar audio
        recordAudioButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                if (checkAudioPermission()) {
                    startRecording()
                } else {
                    requestAudioPermission()
                }
            }
        }
        
        // Enviar texto
        sendTextButton.setOnClickListener {
            sendTextFile()
        }
        
        // Reproducir audio
        playbackButton.setOnClickListener {
            currentAudioPath?.let { path ->
                mediaManager.playAudio(path)
            }
        }
        
        // Información de formato
        formatInfoButton.setOnClickListener {
            showAudioFormatInfo()
        }
    }
    
    /**
     * 📞 Iniciar llamada de voz
     */
    private fun startCall() {
        if (mediaManager.startVoiceCall(selectedContactId)) {
            isInCall = true
            callButton.text = "📞 Colgar"
            callButton.setBackgroundColor(0xFFFF0000.toInt()) // Rojo para colgar
            callStatusText.visibility = View.VISIBLE
            callStatusText.text = "📞 En llamada con $selectedContactId..."
            callStatusText.setTextColor(0xFF00FF00.toInt()) // Verde
            
            // Notificación de llamada
            notificationManager.showCallNotification(selectedContactId, false, selectedContactId)
            
            Toast.makeText(this, "Llamada iniciada", Toast.LENGTH_SHORT).show()
            SecureLogger.i("Voice call started")
        } else {
            Toast.makeText(this, "Error al iniciar llamada", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 📞 Finalizar llamada de voz
     */
    private fun endCall() {
        if (mediaManager.endVoiceCall(selectedContactId)) {
            isInCall = false
            callButton.text = "📞 Llamar"
            callButton.setBackgroundColor(0xFF00FF00.toInt()) // Verde para llamar
            callStatusText.visibility = View.GONE
            
            // Limpiar notificaciones
            notificationManager.clearNotifications(selectedContactId)
            
            Toast.makeText(this, "Llamada finalizada", Toast.LENGTH_SHORT).show()
            SecureLogger.i("Voice call ended")
        } else {
            Toast.makeText(this, "Error al finalizar llamada", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 🎤 Iniciar grabación de audio
     */
    private fun startRecording() {
        if (mediaManager.startAudioRecording(selectedContactId, selectedAudioFormat)) {
            isRecording = true
            recordAudioButton.text = "⏹️ Detener"
            recordingIndicator.visibility = View.VISIBLE
            recordingIndicator.text = "🎤 Grabando ($selectedAudioFormat)..."
            Toast.makeText(this, "Grabación iniciada ($selectedAudioFormat)", Toast.LENGTH_SHORT).show()
            SecureLogger.i("Audio recording started: $selectedAudioFormat")
        } else {
            Toast.makeText(this, "Error al iniciar grabación", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * ⏹️ Detener grabación de audio
     */
    private fun stopRecording() {
        try {
            val result = mediaManager.stopAudioRecording()
            isRecording = false
            recordAudioButton.text = "🎤 Grabar"
            recordingIndicator.visibility = View.GONE
            
            if (result.success) {
                currentAudioPath = result.filePath
                playbackButton.visibility = View.VISIBLE
                Toast.makeText(this, "Audio grabado exitosamente", Toast.LENGTH_SHORT).show()
                
                // Enviar notificación
                notificationManager.showMediaNotification(
                    "Contacto", "Audio", "audio_${System.currentTimeMillis()}.opus", selectedContactId
                )
                
                SecureLogger.i("Audio recording completed")
            } else {
                Toast.makeText(this, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                SecureLogger.w("Audio recording failed: ${result.message}")
            }
        } catch (e: Exception) {
            isRecording = false
            recordAudioButton.text = "🎤 Grabar"
            recordingIndicator.visibility = View.GONE
            Toast.makeText(this, "Error al detener grabación", Toast.LENGTH_SHORT).show()
            SecureLogger.e("Error stopping recording", e)
        }
    }
    
    /**
     * 📄 Enviar archivo de texto
     */
    private fun sendTextFile() {
        val textContent = textInput.text.toString()
        if (textContent.isBlank()) {
            Toast.makeText(this, "Escribe un mensaje", Toast.LENGTH_SHORT).show()
            return
        }
        
        val result = mediaManager.sendTextFile(textContent, selectedContactId)
        if (result.success) {
            Toast.makeText(this, "Texto enviado exitosamente", Toast.LENGTH_SHORT).show()
            textInput.text.clear()
            
            // Enviar notificación
            notificationManager.showMediaNotification(
                "Contacto", "Texto", "text_${System.currentTimeMillis()}.txt", selectedContactId
            )
            
            SecureLogger.i("Text file sent successfully")
        } else {
            Toast.makeText(this, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 🔍 Verificar permisos
     */
    private fun checkPermissions(): Boolean {
        val hasAudioPermission = checkAudioPermission()
        
        if (!hasAudioPermission) {
            requestMultiplePermissions()
            return false
        }
        
        return true
    }
    
    /**
     * 🎤 Verificar permiso de audio
     */
    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 🎤 Solicitar permiso de audio
     */
    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }
    
    /**
     * 💾 Solicitar múltiples permisos
     */
    private fun requestMultiplePermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            PERMISSION_REQUEST_CODE
        )
    }
    
    /**
     * 📱 Resultado de permisos
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show()
                SecureLogger.i("All permissions granted")
            } else {
                Toast.makeText(this, "Se requieren permisos para audio", Toast.LENGTH_LONG).show()
                SecureLogger.w("Some permissions denied")
            }
        }
    }
    
    /**
     * Configurar spinner de formatos de audio (solo Opus para mensajes)
     */
    private fun setupAudioFormatSpinner() {
        val formats = listOf("Opus - 48kbps (Mensajes)", "TTA - Tonos de Llamada")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formats)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioFormatSpinner.adapter = adapter
        
        audioFormatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedAudioFormat = when (position) {
                    0 -> "opus"  // Solo para mensajes de audio
                    1 -> "tta"   // Solo para tonos de llamada
                    else -> "opus"
                }
                
                // Mostrar información del formato
                val formatInfo = mediaManager.getAudioFormatInfo(selectedAudioFormat)
                Toast.makeText(this@SimpleMediaActivity, 
                    "Formato: ${formatInfo.name}\n${formatInfo.description}", 
                    Toast.LENGTH_SHORT).show()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedAudioFormat = "opus"
            }
        }
    }
    
    /**
     * Mostrar información de formatos de audio
     */
    private fun showAudioFormatInfo() {
        val supportedFormats = mediaManager.getSupportedAudioFormats()
        val formatInfo = supportedFormats.joinToString("\n\n") { format ->
            val info = when {
                format.contains("TTA") -> mediaManager.getAudioFormatInfo("tta")
                format.contains("WAV") -> mediaManager.getAudioFormatInfo("wav")
                format.contains("MP3") -> mediaManager.getAudioFormatInfo("mp3")
                else -> null
            }
            
            if (info != null) {
                "🎵 ${info.name}\n${info.description}\n" +
                "📊 Bitrate máximo: ${info.maxBitrate} bps\n" +
                "🔒 Sin pérdida: ${if (info.isLossless) "Sí" else "No"}\n" +
                "💡 Uso recomendado: ${info.recommendedUse}"
            } else {
                format
            }
        }
        
        // Mostrar diálogo con información
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎵 Formatos de Audio Soportados")
            .setMessage(formatInfo)
            .setPositiveButton("OK", null)
            .show()
    }
    
    /**
     * 🧹 Cleanup
     */
    override fun onDestroy() {
        super.onDestroy()
        try {
            // Finalizar llamada si está activa
            if (isInCall) {
                try {
                    endCall()
                } catch (e: Exception) {
                    SecureLogger.e("Error ending call during cleanup", e)
                }
            }
            
            // Detener grabación si está activa
            if (isRecording) {
                try {
                    stopRecording()
                } catch (e: Exception) {
                    SecureLogger.e("Error stopping recording during cleanup", e)
                }
            }
            
            // Detener reproducción de audio
            try {
                mediaManager.stopAudioPlayback()
            } catch (e: Exception) {
                SecureLogger.e("Error stopping audio playback during cleanup", e)
            }
            
            // Liberar recursos del media manager
            try {
                mediaManager.release()
            } catch (e: Exception) {
                SecureLogger.e("Error releasing media manager", e)
            }
            
            // Limpiar notificaciones
            try {
                notificationManager.clearNotifications(selectedContactId)
            } catch (e: Exception) {
                SecureLogger.e("Error clearing notifications", e)
            }
            
            SecureLogger.i("SimpleMediaActivity destroyed and cleaned up")
        } catch (e: Exception) {
            SecureLogger.e("Error during activity cleanup", e)
        }
    }
}
