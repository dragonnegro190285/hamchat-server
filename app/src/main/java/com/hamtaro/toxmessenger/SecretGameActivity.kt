package com.hamtaro.toxmessenger

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hamtaro.toxmessenger.game.VolleyballGame

class SecretGameActivity : AppCompatActivity() {
    
    private lateinit var gameView: VolleyballGameView
    private lateinit var scoreText: TextView
    private lateinit var faultsText: TextView
    private lateinit var difficultyButton: Button
    private lateinit var restartButton: Button
    
    private var volleyballGame: VolleyballGame? = null
    private var currentDifficulty = "A" // A or B
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret_game)
        
        initViews()
        setupGame()
        setupListeners()
    }
    
    private fun initViews() {
        gameView = findViewById(R.id.game_view)
        scoreText = findViewById(R.id.score_text)
        faultsText = findViewById(R.id.faults_text)
        difficultyButton = findViewById(R.id.difficulty_button)
        restartButton = findViewById(R.id.restart_button)
    }
    
    private fun setupGame() {
        volleyballGame = VolleyballGame(currentDifficulty).apply {
            gameListener = object : VolleyballGame.GameListener {
                override fun onScoreChanged(score: Int) {
                    scoreText.text = "Score: $score"
                    checkSpecialMessages(score)
                }
                
                override fun onFaultsChanged(faults: Int) {
                    faultsText.text = "Faults: $faults"
                }
                
                override fun onGameOver() {
                    showGameOver()
                }
                
                override fun onSpecialMessage(message: String) {
                    showSpecialMessage(message)
                }
            }
        }
        
        gameView.setGame(volleyballGame!!)
        updateUI()
    }
    
    private fun setupListeners() {
        difficultyButton.setOnClickListener {
            toggleDifficulty()
        }
        
        restartButton.setOnClickListener {
            restartGame()
        }
        
        gameView.setOnClickListener {
            volleyballGame?.hitBall()
        }
    }
    
    private fun toggleDifficulty() {
        currentDifficulty = if (currentDifficulty == "A") "B" else "A"
        difficultyButton.text = "Difficulty: $currentDifficulty"
        restartGame()
    }
    
    private fun restartGame() {
        volleyballGame?.restart()
        updateUI()
    }
    
    private fun updateUI() {
        val game = volleyballGame ?: return
        scoreText.text = "Score: ${game.getScore()}"
        faultsText.text = "Faults: ${game.getFaults()}"
        difficultyButton.text = "Difficulty: $currentDifficulty"
    }
    
    private fun checkSpecialMessages(score: Int) {
        val game = volleyballGame ?: return
        
        // Check for fault clearing at specific scores
        val clearScores = listOf(200, 500, 1000, 1600, 2000)
        if (clearScores.contains(score)) {
            game.clearFaults()
        }
        
        // After 2000 points, clear faults every 1000 points
        if (score > 2000 && score % 1000 == 0) {
            game.clearFaults()
        }
        
        // Special message at 1000 points per game
        if (score == 1000) {
            showSpecialMessage(getString(R.string.special_message))
        }
    }
    
    private fun showSpecialMessage(message: String) {
        // This would show a toast or dialog with the special message
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
    
    private fun showGameOver() {
        val game = volleyballGame ?: return
        val finalScore = game.getScore()
        android.widget.Toast.makeText(this, "Game Over! Final Score: $finalScore", android.widget.Toast.LENGTH_LONG).show()
        
        // Return to main activity after 3 seconds
        gameView.postDelayed({
            finish()
        }, 3000)
    }
    
    override fun onResume() {
        super.onResume()
        gameView.resume()
    }
    
    override fun onPause() {
        super.onPause()
        gameView.pause()
    }
}
