package com.hamtaro.toxmessenger.game

import kotlin.random.Random

data class Ball(
    var x: Float,
    var y: Float,
    var velocityX: Float,
    var velocityY: Float,
    var isActive: Boolean = true
)

class VolleyballGame(private val difficulty: String) {
    
    interface GameListener {
        fun onScoreChanged(score: Int)
        fun onFaultsChanged(faults: Int)
        fun onGameOver()
        fun onSpecialMessage(message: String)
    }
    
    var gameListener: GameListener? = null
    
    private var score = 0
    private var faults = 0
    private var attempts = 3
    private var gameRunning = true
    
    var hamtaroY = 0f
    var lacitosY = 0f
    val balls = mutableListOf<Ball>()
    
    private var ballSpeed = 2f
    private var maxBalls = 3
    
    init {
        setupDifficulty()
        resetPositions()
        addNewBall()
    }
    
    private fun setupDifficulty() {
        when (difficulty) {
            "A" -> {
                ballSpeed = 2f
                maxBalls = 3
            }
            "B" -> {
                ballSpeed = 3f
                maxBalls = 4
            }
        }
    }
    
    private fun resetPositions() {
        hamtaroY = 50f
        lacitosY = 50f
        balls.clear()
    }
    
    private fun addNewBall() {
        if (balls.size < maxBalls && gameRunning) {
            val newBall = Ball(
                x = 100f + Random.nextFloat() * 200f,
                y = 50f,
                velocityX = ballSpeed * (if (Random.nextBoolean()) 1 else -1),
                velocityY = ballSpeed
            )
            balls.add(newBall)
        }
    }
    
    fun update() {
        if (!gameRunning) return
        
        // Update ball positions
        balls.forEach { ball ->
            if (ball.isActive) {
                ball.x += ball.velocityX
                ball.y += ball.velocityY
                
                // Ball physics - gravity and bouncing
                ball.velocityY += 0.2f // Gravity
                
                // Check if ball hits the ground
                if (ball.y > 300f) {
                    ball.isActive = false
                    faults++
                    gameListener?.onFaultsChanged(faults)
                    
                    if (faults >= 3) {
                        gameOver()
                    }
                }
                
                // Ball hits player (Hamtaro - left side)
                if (ball.x < 200 && ball.x > 100 && 
                    ball.y > (300 - hamtaroY) && ball.y < (300 - hamtaroY + 60)) {
                    ball.velocityX = abs(ball.velocityX)
                    ball.velocityY = -abs(ball.velocityY) * 1.1f
                    score += 10
                    gameListener?.onScoreChanged(score)
                    
                    // Increase difficulty in mode B
                    if (difficulty == "B" && score % 100 == 0) {
                        ballSpeed += 0.1f
                    }
                }
                
                // Ball hits player (Lacitos - right side)
                if (ball.x > 400 && ball.x < 500 && 
                    ball.y > (300 - lacitosY) && ball.y < (300 - lacitosY + 60)) {
                    ball.velocityX = -abs(ball.velocityX)
                    ball.velocityY = -abs(ball.velocityY) * 1.1f
                    score += 10
                    gameListener?.onScoreChanged(score)
                }
                
                // Ball bounces off walls
                if (ball.x < 50 || ball.x > 550) {
                    ball.velocityX = -ball.velocityX
                }
                
                // Ball bounces off ceiling
                if (ball.y < 50) {
                    ball.velocityY = abs(ball.velocityY)
                }
            }
        }
        
        // Remove inactive balls and add new ones
        balls.removeAll { !it.isActive }
        
        // Add new ball periodically
        if (Random.nextFloat() < 0.01f && balls.size < maxBalls) {
            addNewBall()
        }
    }
    
    fun hitBall() {
        if (!gameRunning) return
        
        // Simulate player hitting the ball
        balls.forEach { ball ->
            if (ball.isActive && ball.x < 300) { // Ball on Hamtaro's side
                ball.velocityX = abs(ball.velocityX) * 1.2f
                ball.velocityY = -abs(ball.velocityY) * 1.2f
                score += 5
                gameListener?.onScoreChanged(score)
            }
        }
    }
    
    fun moveHamtaroUp() {
        hamtaroY = (hamtaroY + 20).coerceAtMost(150f)
    }
    
    fun moveHamtaroDown() {
        hamtaroY = (hamtaroY - 20).coerceAtLeast(0f)
    }
    
    fun moveLacitosUp() {
        lacitosY = (lacitosY + 20).coerceAtMost(150f)
    }
    
    fun moveLacitosDown() {
        lacitosY = (lacitosY - 20).coerceAtLeast(0f)
    }
    
    fun getScore(): Int = score
    
    fun getFaults(): Int = faults
    
    fun clearFaults() {
        faults = 0
        gameListener?.onFaultsChanged(faults)
    }
    
    fun gameOver() {
        gameRunning = false
        gameListener?.onGameOver()
    }
    
    fun restart() {
        score = 0
        faults = 0
        attempts = 3
        gameRunning = true
        ballSpeed = if (difficulty == "A") 2f else 3f
        resetPositions()
        addNewBall()
        
        gameListener?.onScoreChanged(score)
        gameListener?.onFaultsChanged(faults)
    }
    
    private fun abs(value: Float): Float = if (value < 0) -value else value
}
