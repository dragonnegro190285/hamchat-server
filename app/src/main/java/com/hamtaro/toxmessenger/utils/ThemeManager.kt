package com.hamtaro.toxmessenger.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.*

object ThemeManager {
    
    fun applyTheme(theme: String) {
        when (theme) {
            HamtaroApplication.THEME_DARK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            HamtaroApplication.THEME_HAMTARO -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }
    
    fun applyLanguage(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
        }
    }
    
    fun isHamtaroThemeActive(): Boolean {
        return HamtaroApplication.instance.isHamtaroTheme()
    }
    
    fun getThemeColors(): ThemeColors {
        return if (isHamtaroThemeActive()) {
            ThemeColors.Hamtaro
        } else {
            ThemeColors.Dark
        }
    }
}

sealed class ThemeColors {
    object Dark : ThemeColors() {
        val primary = R.color.primary_dark
        val secondary = R.color.secondary_dark
        val background = R.color.background_dark
        val surface = R.color.surface_dark
        val onPrimary = R.color.on_primary_dark
        val onSecondary = R.color.on_secondary_dark
        val onBackground = R.color.on_background_dark
        val onSurface = R.color.on_surface_dark
    }
    
    object Hamtaro : ThemeColors() {
        val primary = R.color.hamtaro_orange
        val secondary = R.color.hamtaro_cream
        val background = R.color.hamtaro_cream
        val surface = R.color.hamtaro_cream
        val onPrimary = R.color.hamtaro_black
        val onSecondary = R.color.hamtaro_black
        val onBackground = R.color.hamtaro_black
        val onSurface = R.color.hamtaro_black
    }
}
