package com.hamtaro.toxmessenger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.hamtaro.toxmessenger.game.VolleyballGame

class VolleyballGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    
    private var game: VolleyballGame? = null
    private var gameThread: GameThread? = null
    
    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#87CEEB") // Sky blue
    }
    
    private val groundPaint = Paint().apply {
        color = Color.parseColor("#8B7355") // Brown ground
    }
    
    private val netPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 4f
    }
    
    private val hamtaroPaint = Paint().apply {
        color = Color.parseColor("#8B4513") // Brown
    }
    
    private val lacitosPaint = Paint().apply {
        color = Color.parseColor("#FF69B4") // Pink
    }
    
    private val ballPaint = Paint().apply {
        color = Color.WHITE
    }
    
    init {
        holder.addCallback(this)
    }
    
    fun setGame(game: VolleyballGame) {
        this.game = game
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        gameThread = GameThread(holder, this).apply {
            running = true
            start()
        }
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handle surface changes
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        gameThread?.running = false
        var retry = true
        while (retry) {
            try {
                gameThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                // Retry
            }
        }
    }
    
    fun resume() {
        gameThread?.running = true
    }
    
    fun pause() {
        gameThread?.running = false
    }
    
    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        
        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Draw ground
        val groundHeight = height * 0.2f
        canvas.drawRect(0f, height - groundHeight, width.toFloat(), height.toFloat(), groundPaint)
        
        // Draw net
        val netX = width / 2f
        canvas.drawLine(netX, height - groundHeight, netX, height - groundHeight - 200f, netPaint)
        
        game?.let { game ->
            // Draw Hamtaro (left player)
            val hamtaroX = width * 0.25f
            val hamtaroY = height - groundHeight - game.hamtaroY
            canvas.drawCircle(hamtaroX, hamtaroY, 30f, hamtaroPaint)
            
            // Draw Lacitos (right player)
            val lacitosX = width * 0.75f
            val lacitosY = height - groundHeight - game.lacitosY
            canvas.drawCircle(lacitosX, lacitosY, 30f, lacitosPaint)
            
            // Draw ball(s)
            game.balls.forEach { ball ->
                canvas.drawCircle(ball.x, ball.y, 10f, ballPaint)
            }
            
            // Draw score
            val scorePaint = Paint().apply {
                color = Color.BLACK
                textSize = 48f
            }
            canvas.drawText("Score: ${game.getScore()}", 50f, 100f, scorePaint)
            canvas.drawText("Faults: ${game.getFaults()}", 50f, 150f, scorePaint)
        }
    }
    
    private class GameThread(
        private val surfaceHolder: SurfaceHolder,
        private val gameView: VolleyballGameView
    ) : Thread() {
        
        var running = false
        
        override fun run() {
            while (running) {
                var canvas: Canvas? = null
                
                try {
                    canvas = surfaceHolder.lockCanvas()
                    synchronized(surfaceHolder) {
                        gameView.game?.update()
                        gameView.draw(canvas!!)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    canvas?.let {
                        surfaceHolder.unlockCanvasAndPost(it)
                    }
                }
                
                try {
                    sleep(16) // ~60 FPS
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }
    }
}
