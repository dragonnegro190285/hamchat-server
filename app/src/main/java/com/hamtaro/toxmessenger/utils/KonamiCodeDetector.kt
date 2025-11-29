package com.hamtaro.toxmessenger.utils

object KonamiCodeDetector {
    
    private val konamiCode = HamtaroApplication.KONAMI_CODE
    private val konamiCodeAlt = HamtaroApplication.KONAMI_CODE_ALT
    
    fun checkSequence(sequence: List<String>): Boolean {
        if (sequence.size < 8) return false
        
        val lastEight = sequence.takeLast(8)
        
        // Check primary Konami code (for keitai phones)
        if (lastEight == konamiCode) {
            return true
        }
        
        // Check alternative Konami code (for other phones)
        if (lastEight == konamiCodeAlt) {
            return true
        }
        
        return false
    }
    
    fun reset() {
        // This can be used to reset the detector if needed
    }
}
