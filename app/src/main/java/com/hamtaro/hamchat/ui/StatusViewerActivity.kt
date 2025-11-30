package com.hamtaro.hamchat.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.hamchat.R

/**
 * Activity para ver un estado en pantalla completa
 */
class StatusViewerActivity : AppCompatActivity() {
    
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var currentProgress = 0
    private val statusDuration = 5000L // 5 segundos por estado
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_viewer)
        
        val statusType = intent.getStringExtra("status_type") ?: "TEXT"
        val statusContent = intent.getStringExtra("status_content") ?: ""
        val statusCaption = intent.getStringExtra("status_caption")
        val bgColor = intent.getIntExtra("status_bg_color", 0xFF1A1A2E.toInt())
        val textColor = intent.getIntExtra("status_text_color", 0xFFFFFFFF.toInt())
        val username = intent.getStringExtra("status_username") ?: ""
        val timeAgo = intent.getStringExtra("status_time") ?: ""
        
        setupUI(statusType, statusContent, statusCaption, bgColor, textColor, username, timeAgo)
        startProgressTimer()
    }
    
    private fun setupUI(
        type: String,
        content: String,
        caption: String?,
        bgColor: Int,
        textColor: Int,
        username: String,
        timeAgo: String
    ) {
        val container = findViewById<FrameLayout>(R.id.status_viewer_container)
        val progressBar = findViewById<ProgressBar>(R.id.status_progress)
        val headerLayout = findViewById<LinearLayout>(R.id.status_header)
        val usernameText = findViewById<TextView>(R.id.tv_status_username)
        val timeText = findViewById<TextView>(R.id.tv_status_time)
        
        usernameText.text = username
        timeText.text = timeAgo
        
        when (type) {
            "TEXT" -> {
                container.setBackgroundColor(bgColor)
                
                val textView = TextView(this).apply {
                    text = content
                    setTextColor(textColor)
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setPadding(48, 48, 48, 48)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                container.addView(textView, 0)
            }
            "IMAGE" -> {
                try {
                    val bytes = android.util.Base64.decode(content, android.util.Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    
                    val imageView = ImageView(this).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                    container.addView(imageView, 0)
                    
                    // Caption si existe
                    if (!caption.isNullOrEmpty()) {
                        val captionView = TextView(this).apply {
                            text = caption
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 18f
                            gravity = Gravity.CENTER
                            setPadding(24, 16, 24, 16)
                            setBackgroundColor(0x80000000.toInt())
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = Gravity.BOTTOM
                                bottomMargin = 100
                            }
                        }
                        container.addView(captionView)
                    }
                } catch (e: Exception) {
                    container.setBackgroundColor(bgColor)
                    val errorText = TextView(this).apply {
                        text = "Error al cargar imagen"
                        setTextColor(0xFFFFFFFF.toInt())
                        gravity = Gravity.CENTER
                    }
                    container.addView(errorText)
                }
            }
        }
        
        // Click para cerrar
        container.setOnClickListener {
            finish()
        }
    }
    
    private fun startProgressTimer() {
        val progressBar = findViewById<ProgressBar>(R.id.status_progress)
        progressBar.max = 100
        
        progressRunnable = object : Runnable {
            override fun run() {
                currentProgress += 2
                progressBar.progress = currentProgress
                
                if (currentProgress >= 100) {
                    finish()
                } else {
                    handler.postDelayed(this, statusDuration / 50)
                }
            }
        }
        
        handler.post(progressRunnable!!)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { handler.removeCallbacks(it) }
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
