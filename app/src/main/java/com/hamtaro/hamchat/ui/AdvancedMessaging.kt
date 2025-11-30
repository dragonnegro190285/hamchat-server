package com.hamtaro.hamchat.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestor de funciones avanzadas de mensajería
 */
class AdvancedMessaging(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hamchat_advanced"
        private const val KEY_REACTIONS = "message_reactions"
        private const val KEY_PINNED = "pinned_messages"
        private const val KEY_SCHEDULED = "scheduled_messages"
        private const val KEY_POLLS = "polls"
        private const val KEY_MUTED_CHATS = "muted_chats"
        private const val KEY_CUSTOM_SOUNDS = "custom_sounds"
        
        // Reacciones disponibles
        val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "😡", "🔥", "👏", "🎉", "💯")
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // ========== Reacciones a mensajes ==========
    
    data class Reaction(
        val emoji: String,
        val userId: String,
        val username: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Agregar reacción a un mensaje
     */
    fun addReaction(messageId: String, emoji: String, userId: String, username: String) {
        val reactions = getReactionsForMessage(messageId).toMutableList()
        // Remover reacción anterior del mismo usuario
        reactions.removeAll { it.userId == userId }
        reactions.add(Reaction(emoji, userId, username))
        saveReactions(messageId, reactions)
    }
    
    /**
     * Remover reacción de un mensaje
     */
    fun removeReaction(messageId: String, userId: String) {
        val reactions = getReactionsForMessage(messageId).toMutableList()
        reactions.removeAll { it.userId == userId }
        saveReactions(messageId, reactions)
    }
    
    /**
     * Obtener reacciones de un mensaje
     */
    fun getReactionsForMessage(messageId: String): List<Reaction> {
        val allReactions = loadAllReactions()
        return allReactions[messageId] ?: emptyList()
    }
    
    /**
     * Obtener resumen de reacciones (emoji -> count)
     */
    fun getReactionsSummary(messageId: String): Map<String, Int> {
        return getReactionsForMessage(messageId)
            .groupBy { it.emoji }
            .mapValues { it.value.size }
    }
    
    private fun loadAllReactions(): Map<String, List<Reaction>> {
        val json = prefs.getString(KEY_REACTIONS, "{}")
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, List<Reaction>>()
            obj.keys().forEach { messageId ->
                val array = obj.getJSONArray(messageId)
                val reactions = mutableListOf<Reaction>()
                for (i in 0 until array.length()) {
                    val r = array.getJSONObject(i)
                    reactions.add(Reaction(
                        emoji = r.getString("emoji"),
                        userId = r.getString("userId"),
                        username = r.getString("username"),
                        timestamp = r.optLong("timestamp", 0)
                    ))
                }
                map[messageId] = reactions
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    private fun saveReactions(messageId: String, reactions: List<Reaction>) {
        val allReactions = loadAllReactions().toMutableMap()
        allReactions[messageId] = reactions
        
        val obj = JSONObject()
        allReactions.forEach { (msgId, reactionList) ->
            val array = JSONArray()
            reactionList.forEach { r ->
                array.put(JSONObject().apply {
                    put("emoji", r.emoji)
                    put("userId", r.userId)
                    put("username", r.username)
                    put("timestamp", r.timestamp)
                })
            }
            obj.put(msgId, array)
        }
        prefs.edit().putString(KEY_REACTIONS, obj.toString()).apply()
    }
    
    // ========== Mensajes fijados ==========
    
    data class PinnedMessage(
        val messageId: String,
        val chatId: String,
        val content: String,
        val pinnedBy: String,
        val pinnedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Fijar un mensaje
     */
    fun pinMessage(chatId: String, messageId: String, content: String, pinnedBy: String) {
        val pinned = getPinnedMessages(chatId).toMutableList()
        pinned.removeAll { it.messageId == messageId }
        pinned.add(0, PinnedMessage(messageId, chatId, content, pinnedBy))
        // Máximo 5 mensajes fijados
        if (pinned.size > 5) pinned.subList(5, pinned.size).clear()
        savePinnedMessages(chatId, pinned)
    }
    
    /**
     * Desfijar un mensaje
     */
    fun unpinMessage(chatId: String, messageId: String) {
        val pinned = getPinnedMessages(chatId).toMutableList()
        pinned.removeAll { it.messageId == messageId }
        savePinnedMessages(chatId, pinned)
    }
    
    /**
     * Obtener mensajes fijados de un chat
     */
    fun getPinnedMessages(chatId: String): List<PinnedMessage> {
        val json = prefs.getString(KEY_PINNED, "{}")
        return try {
            val obj = JSONObject(json)
            if (!obj.has(chatId)) return emptyList()
            val array = obj.getJSONArray(chatId)
            val list = mutableListOf<PinnedMessage>()
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                list.add(PinnedMessage(
                    messageId = p.getString("messageId"),
                    chatId = p.getString("chatId"),
                    content = p.getString("content"),
                    pinnedBy = p.getString("pinnedBy"),
                    pinnedAt = p.optLong("pinnedAt", 0)
                ))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun savePinnedMessages(chatId: String, pinned: List<PinnedMessage>) {
        val json = prefs.getString(KEY_PINNED, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        
        val array = JSONArray()
        pinned.forEach { p ->
            array.put(JSONObject().apply {
                put("messageId", p.messageId)
                put("chatId", p.chatId)
                put("content", p.content)
                put("pinnedBy", p.pinnedBy)
                put("pinnedAt", p.pinnedAt)
            })
        }
        obj.put(chatId, array)
        prefs.edit().putString(KEY_PINNED, obj.toString()).apply()
    }
    
    // ========== Mensajes programados ==========
    
    data class ScheduledMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val chatId: String,
        val content: String,
        val scheduledTime: Long,
        val messageType: String = "text",
        val createdAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Programar un mensaje
     */
    fun scheduleMessage(chatId: String, content: String, scheduledTime: Long, messageType: String = "text"): ScheduledMessage {
        val scheduled = getScheduledMessages(chatId).toMutableList()
        val msg = ScheduledMessage(
            chatId = chatId,
            content = content,
            scheduledTime = scheduledTime,
            messageType = messageType
        )
        scheduled.add(msg)
        saveScheduledMessages(chatId, scheduled)
        return msg
    }
    
    /**
     * Cancelar mensaje programado
     */
    fun cancelScheduledMessage(chatId: String, messageId: String) {
        val scheduled = getScheduledMessages(chatId).toMutableList()
        scheduled.removeAll { it.id == messageId }
        saveScheduledMessages(chatId, scheduled)
    }
    
    /**
     * Obtener mensajes programados
     */
    fun getScheduledMessages(chatId: String): List<ScheduledMessage> {
        val json = prefs.getString(KEY_SCHEDULED, "{}")
        return try {
            val obj = JSONObject(json)
            if (!obj.has(chatId)) return emptyList()
            val array = obj.getJSONArray(chatId)
            val list = mutableListOf<ScheduledMessage>()
            for (i in 0 until array.length()) {
                val s = array.getJSONObject(i)
                list.add(ScheduledMessage(
                    id = s.getString("id"),
                    chatId = s.getString("chatId"),
                    content = s.getString("content"),
                    scheduledTime = s.getLong("scheduledTime"),
                    messageType = s.optString("messageType", "text"),
                    createdAt = s.optLong("createdAt", 0)
                ))
            }
            list.filter { it.scheduledTime > System.currentTimeMillis() }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Obtener mensajes listos para enviar
     */
    fun getReadyToSendMessages(): List<ScheduledMessage> {
        val json = prefs.getString(KEY_SCHEDULED, "{}")
        val ready = mutableListOf<ScheduledMessage>()
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { chatId ->
                val array = obj.getJSONArray(chatId)
                for (i in 0 until array.length()) {
                    val s = array.getJSONObject(i)
                    val scheduledTime = s.getLong("scheduledTime")
                    if (scheduledTime <= System.currentTimeMillis()) {
                        ready.add(ScheduledMessage(
                            id = s.getString("id"),
                            chatId = s.getString("chatId"),
                            content = s.getString("content"),
                            scheduledTime = scheduledTime,
                            messageType = s.optString("messageType", "text")
                        ))
                    }
                }
            }
        } catch (e: Exception) {}
        return ready
    }
    
    private fun saveScheduledMessages(chatId: String, scheduled: List<ScheduledMessage>) {
        val json = prefs.getString(KEY_SCHEDULED, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        
        val array = JSONArray()
        scheduled.forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id)
                put("chatId", s.chatId)
                put("content", s.content)
                put("scheduledTime", s.scheduledTime)
                put("messageType", s.messageType)
                put("createdAt", s.createdAt)
            })
        }
        obj.put(chatId, array)
        prefs.edit().putString(KEY_SCHEDULED, obj.toString()).apply()
    }
    
    // ========== Encuestas ==========
    
    data class Poll(
        val id: String = java.util.UUID.randomUUID().toString(),
        val chatId: String,
        val question: String,
        val options: List<String>,
        val votes: MutableMap<String, Int> = mutableMapOf(), // optionIndex -> count
        val voters: MutableMap<String, Int> = mutableMapOf(), // oderId -> optionIndex
        val createdBy: String,
        val createdAt: Long = System.currentTimeMillis(),
        val expiresAt: Long? = null,
        val allowMultiple: Boolean = false
    )
    
    /**
     * Crear encuesta
     */
    fun createPoll(chatId: String, question: String, options: List<String>, createdBy: String, expiresAt: Long? = null): Poll {
        val poll = Poll(
            chatId = chatId,
            question = question,
            options = options,
            createdBy = createdBy,
            expiresAt = expiresAt
        )
        savePoll(poll)
        return poll
    }
    
    /**
     * Votar en encuesta
     */
    fun vote(pollId: String, optionIndex: Int, oderId: String): Boolean {
        val poll = getPoll(pollId) ?: return false
        if (poll.expiresAt != null && poll.expiresAt < System.currentTimeMillis()) return false
        
        // Remover voto anterior si existe
        val previousVote = poll.voters[oderId]
        if (previousVote != null) {
            poll.votes[previousVote.toString()] = (poll.votes[previousVote.toString()] ?: 1) - 1
        }
        
        // Agregar nuevo voto
        poll.voters[oderId] = optionIndex
        poll.votes[optionIndex.toString()] = (poll.votes[optionIndex.toString()] ?: 0) + 1
        
        savePoll(poll)
        return true
    }
    
    /**
     * Obtener encuesta
     */
    fun getPoll(pollId: String): Poll? {
        val json = prefs.getString(KEY_POLLS, "{}")
        return try {
            val obj = JSONObject(json)
            if (!obj.has(pollId)) return null
            val p = obj.getJSONObject(pollId)
            
            val options = mutableListOf<String>()
            val optionsArray = p.getJSONArray("options")
            for (i in 0 until optionsArray.length()) {
                options.add(optionsArray.getString(i))
            }
            
            val votes = mutableMapOf<String, Int>()
            val votesObj = p.optJSONObject("votes")
            votesObj?.keys()?.forEach { key ->
                votes[key] = votesObj.getInt(key)
            }
            
            val voters = mutableMapOf<String, Int>()
            val votersObj = p.optJSONObject("voters")
            votersObj?.keys()?.forEach { key ->
                voters[key] = votersObj.getInt(key)
            }
            
            Poll(
                id = p.getString("id"),
                chatId = p.getString("chatId"),
                question = p.getString("question"),
                options = options,
                votes = votes,
                voters = voters,
                createdBy = p.getString("createdBy"),
                createdAt = p.optLong("createdAt", 0),
                expiresAt = if (p.has("expiresAt")) p.getLong("expiresAt") else null
            )
        } catch (e: Exception) {
            null
        }
    }
    
    private fun savePoll(poll: Poll) {
        val json = prefs.getString(KEY_POLLS, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        
        val pollObj = JSONObject().apply {
            put("id", poll.id)
            put("chatId", poll.chatId)
            put("question", poll.question)
            put("options", JSONArray(poll.options))
            put("votes", JSONObject(poll.votes.mapKeys { it.key }))
            put("voters", JSONObject(poll.voters.mapKeys { it.key }))
            put("createdBy", poll.createdBy)
            put("createdAt", poll.createdAt)
            poll.expiresAt?.let { put("expiresAt", it) }
        }
        obj.put(poll.id, pollObj)
        prefs.edit().putString(KEY_POLLS, obj.toString()).apply()
    }
    
    // ========== Silenciar chats ==========
    
    data class MutedChat(
        val chatId: String,
        val mutedUntil: Long, // -1 = forever
        val mutedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Silenciar chat
     */
    fun muteChat(chatId: String, duration: Long) {
        val mutedUntil = if (duration == -1L) -1L else System.currentTimeMillis() + duration
        val muted = MutedChat(chatId, mutedUntil)
        
        val json = prefs.getString(KEY_MUTED_CHATS, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        obj.put(chatId, JSONObject().apply {
            put("chatId", muted.chatId)
            put("mutedUntil", muted.mutedUntil)
            put("mutedAt", muted.mutedAt)
        })
        prefs.edit().putString(KEY_MUTED_CHATS, obj.toString()).apply()
    }
    
    /**
     * Desilenciar chat
     */
    fun unmuteChat(chatId: String) {
        val json = prefs.getString(KEY_MUTED_CHATS, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        obj.remove(chatId)
        prefs.edit().putString(KEY_MUTED_CHATS, obj.toString()).apply()
    }
    
    /**
     * Verificar si chat está silenciado
     */
    fun isChatMuted(chatId: String): Boolean {
        val json = prefs.getString(KEY_MUTED_CHATS, "{}")
        return try {
            val obj = JSONObject(json)
            if (!obj.has(chatId)) return false
            val muted = obj.getJSONObject(chatId)
            val mutedUntil = muted.getLong("mutedUntil")
            mutedUntil == -1L || mutedUntil > System.currentTimeMillis()
        } catch (e: Exception) {
            false
        }
    }
    
    // ========== Sonidos personalizados ==========
    
    /**
     * Establecer sonido personalizado para un chat
     */
    fun setCustomSound(chatId: String, soundUri: String?) {
        val json = prefs.getString(KEY_CUSTOM_SOUNDS, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        if (soundUri != null) {
            obj.put(chatId, soundUri)
        } else {
            obj.remove(chatId)
        }
        prefs.edit().putString(KEY_CUSTOM_SOUNDS, obj.toString()).apply()
    }
    
    /**
     * Obtener sonido personalizado de un chat
     */
    fun getCustomSound(chatId: String): String? {
        val json = prefs.getString(KEY_CUSTOM_SOUNDS, "{}")
        return try {
            val obj = JSONObject(json)
            if (obj.has(chatId)) obj.getString(chatId) else null
        } catch (e: Exception) {
            null
        }
    }
}
