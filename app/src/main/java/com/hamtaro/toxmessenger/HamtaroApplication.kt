package com.hamtaro.toxmessenger

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.hamtaro.toxmessenger.utils.ThemeManager

class HamtaroApplication : Application() {
    
    companion object {
        lateinit var instance: HamtaroApplication
            private set
        
        const val PREF_THEME = "theme_preference"
        const val PREF_LANGUAGE = "language_preference"
        const val THEME_DARK = "dark"
        const val THEME_HAMTARO = "hamtaro"
        
        const val SECRET_PHRASE = "Mirania Du bist zartlich >////<"
        
        // Konami Code sequence
        val KONAMI_CODE = listOf(
            "UP", "UP", "DOWN", "DOWN", "LEFT", "RIGHT", "2", "2"
        )
        
        // Alternative Konami Code for non-keitai phones
        val KONAMI_CODE_ALT = listOf(
            "UP", "UP", "DOWN", "DOWN", "LEFT", "RIGHT", "B", "A"
        )
    }
    
    private lateinit var sharedPreferences: SharedPreferences
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Apply saved theme
        applySavedTheme()
        
        // Apply saved language
        applySavedLanguage()
    }
    
    private fun applySavedTheme() {
        val theme = sharedPreferences.getString(PREF_THEME, THEME_DARK) ?: THEME_DARK
        ThemeManager.applyTheme(theme)
    }
    
    private fun applySavedLanguage() {
        val language = sharedPreferences.getString(PREF_LANGUAGE, "es") ?: "es"
        ThemeManager.applyLanguage(this, language)
    }
    
    fun saveTheme(theme: String) {
        sharedPreferences.edit()
            .putString(PREF_THEME, theme)
            .apply()
        ThemeManager.applyTheme(theme)
    }
    
    fun saveLanguage(language: String) {
        sharedPreferences.edit()
            .putString(PREF_LANGUAGE, language)
            .apply()
        ThemeManager.applyLanguage(this, language)
    }
    
    fun getCurrentTheme(): String {
        return sharedPreferences.getString(PREF_THEME, THEME_DARK) ?: THEME_DARK
    }
    
    fun getCurrentLanguage(): String {
        return sharedPreferences.getString(PREF_LANGUAGE, "es") ?: "es"
    }
    
    fun isHamtaroTheme(): Boolean {
        return getCurrentTheme() == THEME_HAMTARO
    }
    
    fun triggerHamtaroTheme() {
        saveTheme(THEME_HAMTARO)
    }
}
