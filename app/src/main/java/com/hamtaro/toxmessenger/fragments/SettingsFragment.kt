package com.hamtaro.toxmessenger.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.hamtaro.toxmessenger.HamtaroApplication
import com.hamtaro.toxmessenger.R

class SettingsFragment : Fragment() {
    
    private lateinit var languageRadioGroup: RadioGroup
    private lateinit var themeRadioGroup: RadioGroup
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        languageRadioGroup = view.findViewById(R.id.language_radio_group)
        themeRadioGroup = view.findViewById(R.id.theme_radio_group)
        
        setupCurrentSettings()
        setupListeners()
    }
    
    private fun setupCurrentSettings() {
        val currentLanguage = HamtaroApplication.instance.getCurrentLanguage()
        val currentTheme = HamtaroApplication.instance.getCurrentTheme()
        
        when (currentLanguage) {
            "es" -> languageRadioGroup.check(R.id.radio_spanish)
            "de" -> languageRadioGroup.check(R.id.radio_german)
            else -> languageRadioGroup.check(R.id.radio_spanish)
        }
        
        when (currentTheme) {
            HamtaroApplication.THEME_DARK -> themeRadioGroup.check(R.id.radio_dark)
            HamtaroApplication.THEME_HAMTARO -> themeRadioGroup.check(R.id.radio_hamtaro)
            else -> themeRadioGroup.check(R.id.radio_dark)
        }
    }
    
    private fun setupListeners() {
        languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val language = when (checkedId) {
                R.id.radio_spanish -> "es"
                R.id.radio_german -> "de"
                else -> "es"
            }
            HamtaroApplication.instance.saveLanguage(language)
            Toast.makeText(context, "Language changed", Toast.LENGTH_SHORT).show()
            activity?.recreate()
        }
        
        themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.radio_dark -> HamtaroApplication.THEME_DARK
                R.id.radio_hamtaro -> HamtaroApplication.THEME_HAMTARO
                else -> HamtaroApplication.THEME_DARK
            }
            HamtaroApplication.instance.saveTheme(theme)
            Toast.makeText(context, "Theme changed", Toast.LENGTH_SHORT).show()
            activity?.recreate()
        }
    }
}
