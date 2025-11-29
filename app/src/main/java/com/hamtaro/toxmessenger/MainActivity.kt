package com.hamtaro.toxmessenger

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.hamtaro.toxmessenger.fragments.ChatListFragment
import com.hamtaro.toxmessenger.fragments.FriendsFragment
import com.hamtaro.toxmessenger.fragments.SettingsFragment
import com.hamtaro.toxmessenger.utils.KonamiCodeDetector

class MainActivity : AppCompatActivity() {
    
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var konamiCodeDetector: KonamiCodeDetector
    private var inputSequence = mutableListOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        konamiCodeDetector = KonamiCodeDetector()
        
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        
        // Set up bottom navigation
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    replaceFragment(ChatListFragment())
                    true
                }
                R.id.nav_friends -> {
                    replaceFragment(FriendsFragment())
                    true
                }
                R.id.nav_settings -> {
                    replaceFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
        
        // Set default fragment
        replaceFragment(ChatListFragment())
        bottomNavigationView.selectedItemId = R.id.nav_chats
    }
    
    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle Konami code detection
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            KeyEvent.KEYCODE_2 -> "2"
            KeyEvent.KEYCODE_B -> "B"
            KeyEvent.KEYCODE_A -> "A"
            else -> null
        }
        
        direction?.let {
            inputSequence.add(it)
            if (konamiCodeDetector.checkSequence(inputSequence)) {
                startSecretGame()
                inputSequence.clear()
                return true
            }
            
            // Keep only last 8 inputs
            if (inputSequence.size > 8) {
                inputSequence.removeAt(0)
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
    private fun startSecretGame() {
        val intent = Intent(this, SecretGameActivity::class.java)
        startActivity(intent)
    }
    
    fun checkSecretPhrase(message: String) {
        if (message.contains(HamtaroApplication.SECRET_PHRASE, ignoreCase = true)) {
            HamtaroApplication.instance.triggerHamtaroTheme()
            recreate() // Recreate activity to apply theme
        }
    }
}
