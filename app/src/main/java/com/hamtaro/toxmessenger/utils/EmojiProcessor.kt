package com.hamtaro.toxmessenger.utils

object EmojiProcessor {
    
    private val emojiMap = mapOf(
        "n.n" to "😊",
        "u.u" to "😢",
        "x.xU" to "😵",
        "._.U" to "😐",
        "*O*" to "😮",
        "^_^" to "😄",
        "T_T" to "😭",
        ";_;" to "😿",
        ">_<" to "😤",
        "-_-" to "😑",
        "O_o" to "😲",
        "o_O" to "😲",
        "B)" to "😎",
        "(y)" to "👍",
        "(n)" to "👎",
        "<3" to "❤️",
        "</3" to "💔",
        ":*" to "💋"
    )
    
    fun processEmojis(text: String): String {
        var processedText = text
        
        emojiMap.forEach { (key, value) ->
            processedText = processedText.replace(key, value)
        }
        
        return processedText
    }
    
    fun containsJapaneseEmoji(text: String): Boolean {
        return emojiMap.keys.any { text.contains(it) }
    }
}
