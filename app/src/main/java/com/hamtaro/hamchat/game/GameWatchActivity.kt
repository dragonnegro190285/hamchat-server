package com.hamtaro.hamchat.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.hamchat.R
import com.hamtaro.hamchat.security.SecurityManager

/**
 * 🎮 Game & Watch Style Activity para Ham-Chat
 * Juego de voleibol estilo retro
 */
class GameWatchActivity : AppCompatActivity() {
    
    private lateinit var gameView: GameWatchView
    private lateinit var securityManager: SecurityManager
    private val handler = Handler(Looper.getMainLooper())
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable { onIdleTimeout() }
    
    companion object {
        private const val GAME_SPEED = 100L // 10 FPS estilo Game & Watch
        private const val BALL_SPEED = 5
        private const val PADDLE_SPEED = 12
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
    
    // 🎮 Estado del juego
    private var score = 0
    private var lives = 3
    private var gameRunning = false
    private var ballX = 0f
    private var ballY = 0f
    private var ballVX = BALL_SPEED.toFloat()
    private var ballVY = BALL_SPEED.toFloat()
    private var playerY = 0f
    private var aiY = 0f
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        securityManager = SecurityManager(this)
        gameView = GameWatchView(this)
        setContentView(gameView)
        
        initializeGame()
        startGame()
    }
    
    override fun onResume() {
        super.onResume()
        startIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        stopIdleTimer()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimer()
    }

    private fun startIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
        idleHandler.postDelayed(idleTimeoutRunnable, IDLE_TIMEOUT_MS)
    }

    private fun stopIdleTimer() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
    }

    private fun resetIdleTimer() {
        startIdleTimer()
    }

    private fun onIdleTimeout() {
        try {
            Toast.makeText(this, "Cerrando Ham-Chat por inactividad", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
        finishAffinity()
    }
    
    /**
     * 🎮 Inicializar juego
     */
    private fun initializeGame() {
        ballX = 100f
        ballY = 100f
        playerY = 150f
        aiY = 150f
        score = 0
        lives = 3
        gameRunning = true
        
        securityManager.logSecurityEvent("GAME_INITIALIZED", "Voleibol Hamtaro")
    }
    
    /**
     * 🎮 Iniciar bucle de juego
     */
    private fun startGame() {
        handler.post(object : Runnable {
            override fun run() {
                if (gameRunning) {
                    updateGame()
                    gameView.invalidate()
                    handler.postDelayed(this, GAME_SPEED)
                }
            }
        })
    }
    
    /**
     * 🎮 Actualizar estado del juego
     */
    private fun updateGame() {
        // 🏐 Mover pelota
        ballX += ballVX
        ballY += ballVY
        
        // 🏐 Rebote en paredes
        if (ballY <= 0 || ballY >= 300) {
            ballVY = -ballVY
        }
        
        // 🏐 Rebote en paletas
        if (ballX <= 30 && ballY >= playerY && ballY <= playerY + 60) {
            ballVX = -ballVX
            score += 10
        }
        
        if (ballX >= 270 && ballY >= aiY && ballY <= aiY + 60) {
            ballVX = -ballVX
        }
        
        // 🤖 IA simple
        if (ballX > 150) {
            if (aiY + 30 < ballY) aiY += PADDLE_SPEED.toFloat()
            else if (aiY + 30 > ballY) aiY -= PADDLE_SPEED.toFloat()
        }
        
        // 🏐 Fuera de límites
        if (ballX < 0) {
            lives--
            resetBall()
            if (lives <= 0) {
                gameOver()
            }
        }
        
        if (ballX > 300) {
            score += 50
            resetBall()
        }
    }
    
    /**
     * 🏐 Resetear pelota
     */
    private fun resetBall() {
        ballX = 150f
        ballY = 150f
        ballVX = if (Math.random() > 0.5) BALL_SPEED.toFloat() else -BALL_SPEED.toFloat()
        ballVY = (Math.random() * 10 - 5).toFloat()
    }
    
    /**
     * 🎮 Game Over
     */
    private fun gameOver() {
        gameRunning = false
        securityManager.logSecurityEvent("GAME_OVER", "Score: $score")
        
        // Mostrar resultado
        val message = if (score > 200) {
            "🏆 ¡Excelente! Puntuación: $score"
        } else if (score > 100) {
            "🥈 ¡Bien hecho! Puntuación: $score"
        } else {
            "🥉 Sigue practicando. Puntuación: $score"
        }
        
        // Guardar puntuación
        saveHighScore(score)
    }
    
    /**
     * 💾 Guardar puntuación alta
     */
    private fun saveHighScore(score: Int) {
        val prefs = getSharedPreferences("HamChatGame", Context.MODE_PRIVATE)
        val highScore = prefs.getInt("high_score", 0)
        
        if (score > highScore) {
            prefs.edit()
                .putInt("high_score", score)
                .putLong("high_score_date", System.currentTimeMillis())
                .apply()
        }
    }
    
    /**
     * 🎮 Control con teclado
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP -> {
                playerY = Math.max(0f, playerY - PADDLE_SPEED)
                return true
            }
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN -> {
                playerY = Math.min(240f, playerY + PADDLE_SPEED)
                return true
            }
            KeyEvent.KEYCODE_R -> {
                initializeGame()
                startGame()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    /**
     * 🎮 Control táctil
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val y = event.y
                val viewHeight = gameView.height.toFloat().takeIf { it > 0f } ?: 300f
                if (y < viewHeight / 2f) {
                    playerY = Math.max(0f, playerY - PADDLE_SPEED * 2)
                } else {
                    playerY = Math.min(240f, playerY + PADDLE_SPEED * 2)
                }
            }
        }
        return true
    }
    
    /**
     * 🎮 Vista del juego estilo Game & Watch
     */
    inner class GameWatchView(context: Context) : View(context) {
        
        private val backgroundPaint = Paint().apply {
            color = Color.parseColor("#FFF5E6") // Fondo crema Hamtaro
            isAntiAlias = true
        }
        
        private val ballPaint = Paint().apply {
            color = Color.parseColor("#FF9500") // Naranja Hamtaro
            isAntiAlias = true
        }
        
        private val paddlePaint = Paint().apply {
            color = Color.parseColor("#333333") // Negro
            isAntiAlias = true
        }
        
        private val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isAntiAlias = true
        }
        
        private val scorePaint = Paint().apply {
            color = Color.parseColor("#FF9500")
            textSize = 32f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        
        private val playerBitmap = loadBitmap("colitas_mini", "colitas mini")
        private val aiBitmap = loadBitmap("hamtaro_mini", "hamtaro mini")
        private val ballBitmap = loadBitmap("pelota_mini", "pelota mini")

        private fun loadBitmap(preferred: String, fallback: String): android.graphics.Bitmap? {
            val res = context.resources
            val pkg = context.packageName
            val idPreferred = res.getIdentifier(preferred, "drawable", pkg)
            val id = if (idPreferred != 0) {
                idPreferred
            } else {
                res.getIdentifier(fallback, "drawable", pkg)
            }
            return if (id != 0) {
                try {
                    android.graphics.BitmapFactory.decodeResource(res, id)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 🎨 Fondo a pantalla completa
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            canvas.drawRect(0f, 0f, viewWidth, viewHeight, backgroundPaint)

            // Sistema de coordenadas lógico 300x400 escalado al ancho de la vista
            val baseWidth = 300f
            val scale = if (baseWidth > 0f) viewWidth / baseWidth else 1f

            canvas.save()
            canvas.scale(scale, scale)

            // 🏐 Pelota: usar sprite si está disponible, si no, rectángulo
            val ballSize = 24f
            val halfBall = ballSize / 2f
            val ballRect = android.graphics.RectF(
                ballX - halfBall,
                ballY - halfBall,
                ballX + halfBall,
                ballY + halfBall
            )
            if (ballBitmap != null) {
                canvas.drawBitmap(ballBitmap, null, ballRect, null)
            } else {
                canvas.drawRect(ballX - 5f, ballY - 5f, ballX + 5f, ballY + 5f, ballPaint)
            }

            // 🏓 Paleta del jugador: sprite colitas mini
            val paddleWidth = 40f
            val paddleHeight = 80f
            val playerRect = android.graphics.RectF(
                20f,
                playerY,
                20f + paddleWidth,
                playerY + paddleHeight
            )
            if (playerBitmap != null) {
                canvas.drawBitmap(playerBitmap, null, playerRect, null)
            } else {
                canvas.drawRect(20f, playerY, 30f, playerY + 60f, paddlePaint)
            }

            // 🤖 Paleta de la IA: sprite hamtaro mini
            val aiRect = android.graphics.RectF(
                280f - paddleWidth,
                aiY,
                280f,
                aiY + paddleHeight
            )
            if (aiBitmap != null) {
                canvas.drawBitmap(aiBitmap, null, aiRect, null)
            } else {
                canvas.drawRect(270f, aiY, 280f, aiY + 60f, paddlePaint)
            }

            // 📊 Puntuación y vidas
            canvas.drawText("PUNTOS: $score", 10f, 30f, scorePaint)
            canvas.drawText("VIDAS: $lives", 200f, 30f, textPaint)

            // 🎮 Instrucciones
            if (gameRunning) {
                canvas.drawText("W/S o Táctil para mover", 60f, 350f, textPaint)
                canvas.drawText("R para reiniciar", 100f, 380f, textPaint)
            } else {
                canvas.drawText("¡GAME OVER!", 80f, 200f, scorePaint)
                canvas.drawText("R para jugar de nuevo", 70f, 230f, textPaint)
            }

            // 🐹 Firma Hamtaro
            canvas.drawText("🐹 Ham-Chat", 220f, 380f, textPaint)

            canvas.restore()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        gameRunning = false
        handler.removeCallbacksAndMessages(null)
        securityManager.logSecurityEvent("GAME_DESTROYED", "Voleibol Hamtaro")
    }
}
