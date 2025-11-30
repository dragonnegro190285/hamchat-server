package com.hamtaro.hamchat.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hamtaro.hamchat.R
import java.io.ByteArrayOutputStream

/**
 * Activity para ver y crear estados (stories)
 */
class StatusActivity : AppCompatActivity() {
    
    companion object {
        private const val REQUEST_GALLERY = 1001
        private const val REQUEST_CAMERA = 1002
        private const val PERMISSION_CAMERA = 2001
    }
    
    private lateinit var statusManager: StatusManager
    private lateinit var themeManager: ThemeManager
    
    private lateinit var statusContainer: LinearLayout
    private lateinit var myStatusPreview: LinearLayout
    private lateinit var contactsStatusList: LinearLayout
    private lateinit var btnAddStatus: Button
    
    // Colores predefinidos para estados de texto
    private val statusColors = listOf(
        0xFF1A1A2E.toInt(), // Azul oscuro
        0xFF16213E.toInt(), // Azul marino
        0xFF0F3460.toInt(), // Azul
        0xFF533483.toInt(), // Púrpura
        0xFF7B2CBF.toInt(), // Violeta
        0xFFE94560.toInt(), // Rosa
        0xFFFF6B6B.toInt(), // Coral
        0xFFFF8C00.toInt(), // Naranja
        0xFF4ECDC4.toInt(), // Turquesa
        0xFF2ECC71.toInt(), // Verde
        0xFF27AE60.toInt(), // Verde oscuro
        0xFF3498DB.toInt()  // Azul claro
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        
        statusManager = StatusManager(this)
        themeManager = ThemeManager(this)
        
        initializeUI()
        loadStatuses()
    }
    
    private fun initializeUI() {
        statusContainer = findViewById(R.id.status_container)
        myStatusPreview = findViewById(R.id.my_status_preview)
        contactsStatusList = findViewById(R.id.contacts_status_list)
        btnAddStatus = findViewById(R.id.btn_add_status)
        
        btnAddStatus.setOnClickListener {
            showAddStatusOptions()
        }
        
        // Botón de volver
        findViewById<Button>(R.id.btn_back)?.setOnClickListener {
            finish()
        }
    }
    
    private fun loadStatuses() {
        // Limpiar estados expirados
        statusManager.cleanExpiredStatuses()
        
        // Cargar mis estados
        loadMyStatuses()
        
        // Cargar estados de contactos
        loadContactsStatuses()
    }
    
    private fun loadMyStatuses() {
        myStatusPreview.removeAllViews()
        
        val myStatuses = statusManager.getMyStatuses()
        
        if (myStatuses.isEmpty()) {
            // Mostrar opción para crear primer estado
            val emptyView = TextView(this).apply {
                text = "📷 Toca para agregar un estado"
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 32)
                setOnClickListener { showAddStatusOptions() }
            }
            myStatusPreview.addView(emptyView)
        } else {
            // Mostrar preview del último estado
            val lastStatus = myStatuses.first()
            val previewView = createStatusPreview(lastStatus, true)
            myStatusPreview.addView(previewView)
            
            // Contador de estados
            if (myStatuses.size > 1) {
                val countText = TextView(this).apply {
                    text = "${myStatuses.size} estados"
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                    gravity = Gravity.CENTER
                }
                myStatusPreview.addView(countText)
            }
        }
    }
    
    private fun loadContactsStatuses() {
        contactsStatusList.removeAllViews()
        
        val contactsStatuses = statusManager.getContactsStatuses()
        
        if (contactsStatuses.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No hay estados recientes de tus contactos"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                setPadding(16, 48, 16, 48)
            }
            contactsStatusList.addView(emptyView)
        } else {
            // Agrupar por contacto
            contactsStatuses.forEach { (userId, statuses) ->
                if (statuses.isNotEmpty()) {
                    val contactView = createContactStatusRow(userId, statuses)
                    contactsStatusList.addView(contactView)
                }
            }
        }
    }
    
    private fun createStatusPreview(status: StatusManager.Status, isMyStatus: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        
        // Círculo con preview
        val previewCircle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(64, 64)
        }
        
        val circleView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(64, 64)
            background = ContextCompat.getDrawable(this@StatusActivity, R.drawable.circle_background)
        }
        previewCircle.addView(circleView)
        
        // Si es imagen, mostrar thumbnail
        if (status.type == StatusManager.StatusType.IMAGE) {
            try {
                val bytes = android.util.Base64.decode(status.content, android.util.Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val imageView = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(60, 60).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                previewCircle.addView(imageView)
            } catch (e: Exception) {
                // Usar color de fondo
                circleView.setBackgroundColor(status.backgroundColor)
            }
        } else {
            circleView.setBackgroundColor(status.backgroundColor)
        }
        
        container.addView(previewCircle)
        
        // Información
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }
        
        val nameText = TextView(this).apply {
            text = if (isMyStatus) "Mi estado" else status.username
            textSize = 16f
            setTextColor(0xFF1A1A1A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        val timeText = TextView(this).apply {
            text = status.getTimeAgo()
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        
        infoLayout.addView(nameText)
        infoLayout.addView(timeText)
        container.addView(infoLayout)
        
        // Click para ver
        container.setOnClickListener {
            viewStatus(status)
        }
        
        // Long click para opciones (solo mis estados)
        if (isMyStatus) {
            container.setOnLongClickListener {
                showStatusOptions(status)
                true
            }
        }
        
        return container
    }
    
    private fun createContactStatusRow(userId: String, statuses: List<StatusManager.Status>): View {
        val firstStatus = statuses.first()
        val hasUnviewed = statuses.any { !statusManager.isStatusViewed(it.id) }
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        
        // Círculo con borde (verde si hay no vistos)
        val circleColor = if (hasUnviewed) 0xFF4CAF50.toInt() else 0xFFCCCCCC.toInt()
        
        val previewCircle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(64, 64)
            setBackgroundColor(circleColor)
            setPadding(3, 3, 3, 3)
        }
        
        // Imagen o color
        if (firstStatus.type == StatusManager.StatusType.IMAGE) {
            try {
                val bytes = android.util.Base64.decode(firstStatus.content, android.util.Base64.NO_WRAP)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val imageView = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(58, 58)
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                previewCircle.addView(imageView)
            } catch (e: Exception) {
                val colorView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(58, 58)
                    setBackgroundColor(firstStatus.backgroundColor)
                }
                previewCircle.addView(colorView)
            }
        } else {
            val colorView = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(58, 58)
                setBackgroundColor(firstStatus.backgroundColor)
            }
            previewCircle.addView(colorView)
        }
        
        container.addView(previewCircle)
        
        // Info
        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }
        
        val nameText = TextView(this).apply {
            text = firstStatus.username
            textSize = 16f
            setTextColor(0xFF1A1A1A.toInt())
            setTypeface(null, if (hasUnviewed) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        
        val timeText = TextView(this).apply {
            text = "${statuses.size} estado${if (statuses.size > 1) "s" else ""} • ${firstStatus.getTimeAgo()}"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        
        infoLayout.addView(nameText)
        infoLayout.addView(timeText)
        container.addView(infoLayout)
        
        container.setOnClickListener {
            viewContactStatuses(userId, statuses)
        }
        
        return container
    }
    
    private fun showAddStatusOptions() {
        val options = arrayOf(
            "📝 Estado de texto",
            "📷 Foto de galería",
            "📸 Tomar foto"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Crear estado")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showTextStatusCreator()
                    1 -> openGalleryForStatus()
                    2 -> openCameraForStatus()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun showTextStatusCreator() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        
        // Campo de texto
        val textInput = EditText(this).apply {
            hint = "Escribe tu estado..."
            minLines = 3
            maxLines = 5
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(16, 16, 16, 16)
        }
        container.addView(textInput)
        
        // Selector de color
        val colorLabel = TextView(this).apply {
            text = "Color de fondo:"
            setPadding(0, 16, 0, 8)
        }
        container.addView(colorLabel)
        
        var selectedColor = statusColors[0]
        
        val colorScroll = HorizontalScrollView(this)
        val colorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        statusColors.forEach { color ->
            val colorBtn = Button(this).apply {
                layoutParams = LinearLayout.LayoutParams(48, 48).apply {
                    marginEnd = 8
                }
                setBackgroundColor(color)
                setOnClickListener {
                    selectedColor = color
                    // Actualizar preview
                    textInput.setBackgroundColor(color)
                    textInput.setTextColor(Color.WHITE)
                }
            }
            colorContainer.addView(colorBtn)
        }
        
        colorScroll.addView(colorContainer)
        container.addView(colorScroll)
        
        AlertDialog.Builder(this)
            .setTitle("📝 Nuevo estado")
            .setView(container)
            .setPositiveButton("Publicar") { _, _ ->
                val text = textInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    statusManager.createTextStatus(text, selectedColor, Color.WHITE)
                    Toast.makeText(this, "✅ Estado publicado", Toast.LENGTH_SHORT).show()
                    loadStatuses()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun openGalleryForStatus() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, REQUEST_GALLERY)
    }
    
    private fun openCameraForStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_CAMERA)
            return
        }
        
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        startActivityForResult(intent, REQUEST_CAMERA)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode != Activity.RESULT_OK) return
        
        when (requestCode) {
            REQUEST_GALLERY -> {
                data?.data?.let { uri ->
                    showImageStatusCreator(uri)
                }
            }
            REQUEST_CAMERA -> {
                val bitmap = data?.extras?.get("data") as? Bitmap
                bitmap?.let {
                    showImageStatusCreatorWithBitmap(it)
                }
            }
        }
    }
    
    private fun showImageStatusCreator(uri: Uri) {
        val bitmap = themeManager.loadBitmapFromUri(uri, 1080)
        if (bitmap != null) {
            showImageStatusCreatorWithBitmap(bitmap)
        } else {
            Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showImageStatusCreatorWithBitmap(bitmap: Bitmap) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        // Preview de imagen
        val imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            )
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        container.addView(imageView)
        
        // Caption opcional
        val captionInput = EditText(this).apply {
            hint = "Agregar texto (opcional)..."
            setSingleLine(true)
            setPadding(16, 16, 16, 16)
        }
        container.addView(captionInput)
        
        AlertDialog.Builder(this)
            .setTitle("📷 Nuevo estado")
            .setView(container)
            .setPositiveButton("Publicar") { _, _ ->
                val imageBase64 = themeManager.bitmapToBase64(bitmap, 85)
                val caption = captionInput.text.toString().trim().ifEmpty { null }
                statusManager.createImageStatus(imageBase64, caption)
                Toast.makeText(this, "✅ Estado publicado", Toast.LENGTH_SHORT).show()
                loadStatuses()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun viewStatus(status: StatusManager.Status) {
        // Marcar como visto
        statusManager.markStatusAsViewed(status.id)
        
        // Mostrar estado en pantalla completa
        val intent = Intent(this, StatusViewerActivity::class.java).apply {
            putExtra("status_id", status.id)
            putExtra("status_type", status.type.name)
            putExtra("status_content", status.content)
            putExtra("status_caption", status.caption)
            putExtra("status_bg_color", status.backgroundColor)
            putExtra("status_text_color", status.textColor)
            putExtra("status_username", status.username)
            putExtra("status_time", status.getTimeAgo())
        }
        startActivity(intent)
    }
    
    private fun viewContactStatuses(userId: String, statuses: List<StatusManager.Status>) {
        // Ver el primer estado no visto, o el primero si todos están vistos
        val statusToView = statuses.firstOrNull { !statusManager.isStatusViewed(it.id) } ?: statuses.first()
        viewStatus(statusToView)
    }
    
    private fun showStatusOptions(status: StatusManager.Status) {
        val options = arrayOf("🗑️ Eliminar estado", "📊 Ver quién lo vio")
        
        AlertDialog.Builder(this)
            .setTitle("Opciones")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        statusManager.deleteMyStatus(status.id)
                        Toast.makeText(this, "Estado eliminado", Toast.LENGTH_SHORT).show()
                        loadStatuses()
                    }
                    1 -> {
                        Toast.makeText(this, "Función en desarrollo", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_CAMERA && grantResults.isNotEmpty() 
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCameraForStatus()
        }
    }
}
