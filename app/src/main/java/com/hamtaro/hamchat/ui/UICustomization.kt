package com.hamtaro.hamchat.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import org.json.JSONObject

/**
 * Gestor de personalización de interfaz de usuario
 */
class UICustomization(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hamchat_ui"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BUBBLE_STYLE = "bubble_style"
        private const val KEY_FONT_STYLE = "font_style"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_CUSTOM_COLORS = "custom_colors"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // ========== Modo de tema ==========
    
    enum class ThemeMode {
        LIGHT,      // Tema claro
        DARK,       // Tema oscuro
        SYSTEM,     // Seguir sistema
        HAMTARO     // Tema Hamtaro (naranja)
    }
    
    fun getThemeMode(): ThemeMode {
        val mode = prefs.getString(KEY_THEME_MODE, "HAMTARO")
        return try {
            ThemeMode.valueOf(mode ?: "HAMTARO")
        } catch (e: Exception) {
            ThemeMode.HAMTARO
        }
    }
    
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }
    
    /**
     * Obtener colores según el tema actual
     */
    fun getThemeColors(): ThemeColors {
        return when (getThemeMode()) {
            ThemeMode.DARK -> ThemeColors.DARK
            ThemeMode.LIGHT -> ThemeColors.LIGHT
            ThemeMode.HAMTARO -> ThemeColors.HAMTARO
            ThemeMode.SYSTEM -> {
                val nightMode = context.resources.configuration.uiMode and 
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    ThemeColors.DARK
                } else {
                    ThemeColors.LIGHT
                }
            }
        }
    }
    
    data class ThemeColors(
        val background: Int,
        val surface: Int,
        val primary: Int,
        val primaryDark: Int,
        val accent: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val bubbleSent: Int,
        val bubbleReceived: Int,
        val bubbleTextSent: Int,
        val bubbleTextReceived: Int,
        val divider: Int,
        val statusBar: Int
    ) {
        companion object {
            val LIGHT = ThemeColors(
                background = 0xFFF5F5F5.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                primary = 0xFF2196F3.toInt(),
                primaryDark = 0xFF1976D2.toInt(),
                accent = 0xFF03A9F4.toInt(),
                textPrimary = 0xFF212121.toInt(),
                textSecondary = 0xFF757575.toInt(),
                bubbleSent = 0xFFDCF8C6.toInt(),
                bubbleReceived = 0xFFFFFFFF.toInt(),
                bubbleTextSent = 0xFF1A1A1A.toInt(),
                bubbleTextReceived = 0xFF1A1A1A.toInt(),
                divider = 0xFFE0E0E0.toInt(),
                statusBar = 0xFF1976D2.toInt()
            )
            
            val DARK = ThemeColors(
                background = 0xFF121212.toInt(),
                surface = 0xFF1E1E1E.toInt(),
                primary = 0xFF00BCD4.toInt(),
                primaryDark = 0xFF0097A7.toInt(),
                accent = 0xFF00E5FF.toInt(),
                textPrimary = 0xFFFFFFFF.toInt(),
                textSecondary = 0xFFB0B0B0.toInt(),
                bubbleSent = 0xFF005D4B.toInt(),
                bubbleReceived = 0xFF2A2A2A.toInt(),
                bubbleTextSent = 0xFFFFFFFF.toInt(),
                bubbleTextReceived = 0xFFFFFFFF.toInt(),
                divider = 0xFF2A2A2A.toInt(),
                statusBar = 0xFF000000.toInt()
            )
            
            val HAMTARO = ThemeColors(
                background = 0xFFFFF8E1.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                primary = 0xFFFF8C00.toInt(),
                primaryDark = 0xFFE67E00.toInt(),
                accent = 0xFFFFAB40.toInt(),
                textPrimary = 0xFF1A1A1A.toInt(),
                textSecondary = 0xFF666666.toInt(),
                bubbleSent = 0xFFFFE0B2.toInt(),
                bubbleReceived = 0xFFFFFFFF.toInt(),
                bubbleTextSent = 0xFF1A1A1A.toInt(),
                bubbleTextReceived = 0xFF1A1A1A.toInt(),
                divider = 0xFFFFCC80.toInt(),
                statusBar = 0xFFE67E00.toInt()
            )
        }
    }
    
    // ========== Estilos de burbujas ==========
    
    enum class BubbleStyle {
        ROUNDED,        // Bordes redondeados (estándar)
        SQUARE,         // Bordes cuadrados
        MODERN,         // Bordes muy redondeados
        CLASSIC,        // Estilo clásico con cola
        MINIMAL         // Minimalista sin bordes
    }
    
    fun getBubbleStyle(): BubbleStyle {
        val style = prefs.getString(KEY_BUBBLE_STYLE, "ROUNDED")
        return try {
            BubbleStyle.valueOf(style ?: "ROUNDED")
        } catch (e: Exception) {
            BubbleStyle.ROUNDED
        }
    }
    
    fun setBubbleStyle(style: BubbleStyle) {
        prefs.edit().putString(KEY_BUBBLE_STYLE, style.name).apply()
    }
    
    /**
     * Crear drawable para burbuja de mensaje
     */
    fun createBubbleDrawable(isSent: Boolean): GradientDrawable {
        val colors = getThemeColors()
        val style = getBubbleStyle()
        
        val drawable = GradientDrawable()
        drawable.setColor(if (isSent) colors.bubbleSent else colors.bubbleReceived)
        
        val radius = when (style) {
            BubbleStyle.ROUNDED -> 16f
            BubbleStyle.SQUARE -> 4f
            BubbleStyle.MODERN -> 24f
            BubbleStyle.CLASSIC -> 12f
            BubbleStyle.MINIMAL -> 8f
        }
        
        // Radios diferentes para simular cola de mensaje
        if (style == BubbleStyle.CLASSIC) {
            if (isSent) {
                drawable.cornerRadii = floatArrayOf(
                    radius, radius,  // top-left
                    radius, radius,  // top-right
                    4f, 4f,          // bottom-right (cola)
                    radius, radius   // bottom-left
                )
            } else {
                drawable.cornerRadii = floatArrayOf(
                    radius, radius,  // top-left
                    radius, radius,  // top-right
                    radius, radius,  // bottom-right
                    4f, 4f           // bottom-left (cola)
                )
            }
        } else {
            drawable.cornerRadius = radius * context.resources.displayMetrics.density
        }
        
        // Borde para estilo minimal
        if (style == BubbleStyle.MINIMAL) {
            drawable.setStroke(1, colors.divider)
        }
        
        return drawable
    }
    
    // ========== Fuentes ==========
    
    enum class FontStyle {
        DEFAULT,        // Fuente del sistema
        SERIF,          // Con serifa
        MONOSPACE,      // Monoespaciada
        CASUAL,         // Casual/manuscrita
        ROUNDED         // Redondeada
    }
    
    fun getFontStyle(): FontStyle {
        val style = prefs.getString(KEY_FONT_STYLE, "DEFAULT")
        return try {
            FontStyle.valueOf(style ?: "DEFAULT")
        } catch (e: Exception) {
            FontStyle.DEFAULT
        }
    }
    
    fun setFontStyle(style: FontStyle) {
        prefs.edit().putString(KEY_FONT_STYLE, style.name).apply()
    }
    
    /**
     * Obtener Typeface según el estilo
     */
    fun getTypeface(): Typeface {
        return when (getFontStyle()) {
            FontStyle.DEFAULT -> Typeface.DEFAULT
            FontStyle.SERIF -> Typeface.SERIF
            FontStyle.MONOSPACE -> Typeface.MONOSPACE
            FontStyle.CASUAL -> Typeface.create("casual", Typeface.NORMAL)
            FontStyle.ROUNDED -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
    
    // ========== Tamaño de fuente ==========
    
    enum class FontSize(val scale: Float) {
        SMALL(0.85f),
        MEDIUM(1.0f),
        LARGE(1.15f),
        EXTRA_LARGE(1.3f)
    }
    
    fun getFontSize(): FontSize {
        val size = prefs.getString(KEY_FONT_SIZE, "MEDIUM")
        return try {
            FontSize.valueOf(size ?: "MEDIUM")
        } catch (e: Exception) {
            FontSize.MEDIUM
        }
    }
    
    fun setFontSize(size: FontSize) {
        prefs.edit().putString(KEY_FONT_SIZE, size.name).apply()
    }
    
    /**
     * Aplicar tamaño de fuente a un TextView
     */
    fun applyFontSize(textView: TextView, baseSize: Float) {
        textView.textSize = baseSize * getFontSize().scale
    }
    
    // ========== Aplicar tema a vista ==========
    
    /**
     * Aplicar tema completo a una vista
     */
    fun applyTheme(view: View) {
        val colors = getThemeColors()
        view.setBackgroundColor(colors.background)
    }
    
    /**
     * Aplicar estilo a mensaje
     */
    fun styleMessage(textView: TextView, isSent: Boolean) {
        val colors = getThemeColors()
        
        textView.setTextColor(if (isSent) colors.bubbleTextSent else colors.bubbleTextReceived)
        textView.typeface = getTypeface()
        applyFontSize(textView, 15f)
        textView.background = createBubbleDrawable(isSent)
    }
    
    // ========== Colores personalizados ==========
    
    fun setCustomColor(key: String, color: Int) {
        val json = prefs.getString(KEY_CUSTOM_COLORS, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        obj.put(key, color)
        prefs.edit().putString(KEY_CUSTOM_COLORS, obj.toString()).apply()
    }
    
    fun getCustomColor(key: String, default: Int): Int {
        val json = prefs.getString(KEY_CUSTOM_COLORS, "{}")
        return try {
            val obj = JSONObject(json)
            if (obj.has(key)) obj.getInt(key) else default
        } catch (e: Exception) {
            default
        }
    }
    
    fun resetCustomColors() {
        prefs.edit().remove(KEY_CUSTOM_COLORS).apply()
    }
    
    // ========== Presets de tema ==========
    
    data class ThemePreset(
        val name: String,
        val mode: ThemeMode,
        val bubbleStyle: BubbleStyle,
        val fontStyle: FontStyle,
        val fontSize: FontSize
    )
    
    val PRESETS = listOf(
        ThemePreset("Hamtaro Clásico", ThemeMode.HAMTARO, BubbleStyle.ROUNDED, FontStyle.DEFAULT, FontSize.MEDIUM),
        ThemePreset("Moderno Oscuro", ThemeMode.DARK, BubbleStyle.MODERN, FontStyle.ROUNDED, FontSize.MEDIUM),
        ThemePreset("Minimalista", ThemeMode.LIGHT, BubbleStyle.MINIMAL, FontStyle.DEFAULT, FontSize.SMALL),
        ThemePreset("Retro", ThemeMode.LIGHT, BubbleStyle.SQUARE, FontStyle.MONOSPACE, FontSize.MEDIUM),
        ThemePreset("Elegante", ThemeMode.DARK, BubbleStyle.CLASSIC, FontStyle.SERIF, FontSize.LARGE)
    )
    
    fun applyPreset(preset: ThemePreset) {
        setThemeMode(preset.mode)
        setBubbleStyle(preset.bubbleStyle)
        setFontStyle(preset.fontStyle)
        setFontSize(preset.fontSize)
    }
}
