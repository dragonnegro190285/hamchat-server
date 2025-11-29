package com.hamtaro.hamchat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.multimedia.AudioFormat
import com.hamtaro.hamchat.multimedia.MediaManager
import com.hamtaro.hamchat.notifications.HamChatNotificationManager
import com.hamtaro.hamchat.security.SecureLogger

/**
 * 📱 Media Activity para Ham-Chat
 * Manejo de fotos, audio y texto
 */
class MediaActivity : AppCompatActivity() {
    
    private lateinit var mediaManager: MediaManager
    private lateinit var notificationManager: HamChatNotificationManager
    
    // UI Components
    private lateinit var selectPhotoButton: Button
    private lateinit var recordAudioButton: Button
    private lateinit var sendTextButton: Button
    private lateinit var textInput: EditText
    private lateinit var mediaPreview: ImageView
    private lateinit var recordingIndicator: TextView
    private lateinit var playbackButton: Button
    private lateinit var audioFormatSpinner: Spinner
    private lateinit var formatInfoButton: Button
    
    // Estado
    private var isRecording = false
    private var selectedContactId = ""
    private var selectedImagePath: String? = null
    private var currentAudioPath: String? = null
    private var selectedAudioFormat = "wav" // Default a WAV
    
    companion object {
        private const val REQUEST_PICK_PHOTO = 1001
        private const val REQUEST_RECORD_AUDIO = 1002
        private const val PERMISSION_REQUEST_CODE = 1003
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        
        // Inicializar managers
        mediaManager = MediaManager(this)
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
        selectPhotoButton = findViewById(R.id.btn_select_photo)
        recordAudioButton = findViewById(R.id.btn_record_audio)
        sendTextButton = findViewById(R.id.btn_send_text)
        textInput = findViewById(R.id.et_text_input)
        mediaPreview = findViewById(R.id.iv_media_preview)
        recordingIndicator = findViewById(R.id.tv_recording_indicator)
        playbackButton = findViewById(R.id.btn_playback_audio)
        audioFormatSpinner = findViewById(R.id.spinner_audio_format)
        formatInfoButton = findViewById(R.id.btn_format_info)
        
        // Configurar spinner de formatos de audio
        setupAudioFormatSpinner()
        
        // Estado inicial
        recordingIndicator.visibility = View.GONE
        playbackButton.visibility = View.GONE
        mediaPreview.visibility = View.GONE
    }
    
    /**
     * 🔘 Configurar listeners
     */
    private fun setupClickListeners() {
        // Seleccionar foto
        selectPhotoButton.setOnClickListener {
            if (checkPhotoPermission()) {
                pickPhoto()
            } else {
                requestPhotoPermission()
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
    }
    
    /**
     * 📸 Seleccionar foto
     */
    private fun pickPhoto() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQUEST_PICK_PHOTO)
            SecureLogger.i("Photo picker opened")
        } catch (e: Exception) {
            SecureLogger.e("Error opening photo picker", e)
            Toast.makeText(this, "Error al abrir galería", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 🎤 Iniciar grabación de audio
     */
    private fun startRecording() {
        if (mediaManager.startAudioRecording(selectedContactId)) {
            isRecording = true
            recordAudioButton.text = "⏹️ Detener"
            recordingIndicator.visibility = View.VISIBLE
            recordingIndicator.text = "🎤 Grabando..."
            Toast.makeText(this, "Grabación iniciada", Toast.LENGTH_SHORT).show()
            SecureLogger.i("Audio recording started")
        } else {
            Toast.makeText(this, "Error al iniciar grabación", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * ⏹️ Detener grabación de audio
     */
    private fun stopRecording() {
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
                "Contacto", "Audio", "audio_${System.currentTimeMillis()}.m4a", selectedContactId
            )
            
            SecureLogger.i("Audio recording completed")
        } else {
            Toast.makeText(this, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
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
        val hasPhotoPermission = checkPhotoPermission()
        val hasAudioPermission = checkAudioPermission()
        val hasStoragePermission = checkStoragePermission()
        
        if (!hasPhotoPermission || !hasAudioPermission || !hasStoragePermission) {
            requestMultiplePermissions()
            return false
        }
        
        return true
    }
    
    /**
     * 📷 Verificar permiso de cámara/fotos
     */
    private fun checkPhotoPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
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
     * 💾 Verificar permiso de almacenamiento
     */
    private fun checkStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 📷 Solicitar permiso de fotos
     */
    private fun requestPhotoPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            PERMISSION_REQUEST_CODE
        )
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
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            PERMISSION_REQUEST_CODE
        )
    }
    
    /**
     * 📱 Resultado de actividad
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_PICK_PHOTO -> {
                if (resultCode == RESULT_OK && data != null) {
                    data.data?.let { imageUri ->
                        handleSelectedPhoto(imageUri)
                    }
                }
            }
        }
    }
    
    /**
     * 📸 Manejar foto seleccionada
     */
    private fun handleSelectedPhoto(imageUri: Uri) {
        try {
            // Mostrar preview
            mediaPreview.setImageURI(imageUri)
            mediaPreview.visibility = View.VISIBLE
            
            // Enviar foto
            val result = mediaManager.sendPhoto(imageUri, selectedContactId)
            if (result.success) {
                selectedImagePath = result.filePath
                Toast.makeText(this, "Foto enviada exitosamente", Toast.LENGTH_SHORT).show()
                
                // Enviar notificación
                notificationManager.showMediaNotification(
                    "Contacto", "Foto", "photo_${System.currentTimeMillis()}.webp", selectedContactId
                )
                
                SecureLogger.i("Photo sent successfully")
            } else {
                Toast.makeText(this, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                mediaPreview.visibility = View.GONE
            }
        } catch (e: Exception) {
            SecureLogger.e("Error handling selected photo", e)
            Toast.makeText(this, "Error al procesar foto", Toast.LENGTH_SHORT).show()
            mediaPreview.visibility = View.GONE
        }
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
                Toast.makeText(this, "Se requieren permisos para multimedia", Toast.LENGTH_LONG).show()
                SecureLogger.w("Some permissions denied")
            }
        }
    }
    
    /**
     * 🎵 Configurar spinner de formatos de audio
     */
    private fun setupAudioFormatSpinner() {
        val formats = listOf("WAV - Estándar", "TTA - Alta Calidad", "MP3 - Compresión")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formats)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioFormatSpinner.adapter = adapter
        
        audioFormatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedAudioFormat = when (position) {
                    0 -> "wav"
                    1 -> "tta"
                    2 -> "mp3"
                    else -> "wav"
                }
                
                // Mostrar información del formato
                val formatInfo = mediaManager.getAudioFormatInfo(selectedAudioFormat)
                Toast.makeText(this@MediaActivity, 
                    "Formato: ${formatInfo.name}\n${formatInfo.description}", 
                    Toast.LENGTH_SHORT).show()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedAudioFormat = "wav"
            }
        }
    }
    
    /**
     * 📊 Mostrar información de formatos de audio
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
        mediaManager.release()
        SecureLogger.i("MediaActivity destroyed")
    }
}
