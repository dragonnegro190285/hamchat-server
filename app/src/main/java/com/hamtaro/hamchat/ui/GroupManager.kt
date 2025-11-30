package com.hamtaro.hamchat.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestor de chats grupales
 */
class GroupManager(private val context: Context) {
    
    companion object {
        private const val PREFS_NAME = "hamchat_groups"
        private const val KEY_GROUPS = "groups"
        private const val KEY_GROUP_MESSAGES = "group_messages"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    enum class MemberRole {
        OWNER,      // Creador del grupo
        ADMIN,      // Administrador
        MODERATOR,  // Moderador
        MEMBER      // Miembro normal
    }
    
    data class GroupMember(
        val oderId: String,
        val username: String,
        val role: MemberRole = MemberRole.MEMBER,
        val joinedAt: Long = System.currentTimeMillis(),
        val addedBy: String? = null
    )
    
    data class Group(
        val id: String = java.util.UUID.randomUUID().toString(),
        val name: String,
        val description: String = "",
        val photoBase64: String? = null,
        val members: MutableList<GroupMember> = mutableListOf(),
        val createdBy: String,
        val createdAt: Long = System.currentTimeMillis(),
        val settings: GroupSettings = GroupSettings()
    )
    
    data class GroupSettings(
        val onlyAdminsCanPost: Boolean = false,
        val onlyAdminsCanEditInfo: Boolean = true,
        val onlyAdminsCanAddMembers: Boolean = false,
        val approvalRequired: Boolean = false,
        val maxMembers: Int = 256
    )
    
    data class GroupMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val groupId: String,
        val senderId: String,
        val senderName: String,
        val content: String,
        val messageType: String = "text",
        val timestamp: Long = System.currentTimeMillis(),
        val replyToId: String? = null,
        val mentions: List<String> = emptyList(),
        val isEdited: Boolean = false,
        val isDeleted: Boolean = false
    )
    
    // ========== Gestión de grupos ==========
    
    /**
     * Crear nuevo grupo
     */
    fun createGroup(name: String, description: String, creatorId: String, creatorName: String): Group {
        val group = Group(
            name = name,
            description = description,
            createdBy = creatorId,
            members = mutableListOf(
                GroupMember(creatorId, creatorName, MemberRole.OWNER)
            )
        )
        saveGroup(group)
        return group
    }
    
    /**
     * Obtener grupo por ID
     */
    fun getGroup(groupId: String): Group? {
        val groups = getAllGroups()
        return groups.find { it.id == groupId }
    }
    
    /**
     * Obtener todos los grupos del usuario
     */
    fun getMyGroups(oderId: String): List<Group> {
        return getAllGroups().filter { group ->
            group.members.any { it.oderId == oderId }
        }
    }
    
    /**
     * Actualizar información del grupo
     */
    fun updateGroup(groupId: String, name: String? = null, description: String? = null, photoBase64: String? = null) {
        val group = getGroup(groupId) ?: return
        val updated = group.copy(
            name = name ?: group.name,
            description = description ?: group.description,
            photoBase64 = photoBase64 ?: group.photoBase64
        )
        saveGroup(updated)
    }
    
    /**
     * Eliminar grupo
     */
    fun deleteGroup(groupId: String) {
        val groups = getAllGroups().toMutableList()
        groups.removeAll { it.id == groupId }
        saveAllGroups(groups)
    }
    
    // ========== Gestión de miembros ==========
    
    /**
     * Agregar miembro al grupo
     */
    fun addMember(groupId: String, oderId: String, username: String, addedBy: String): Boolean {
        val group = getGroup(groupId) ?: return false
        if (group.members.any { it.oderId == oderId }) return false
        if (group.members.size >= group.settings.maxMembers) return false
        
        group.members.add(GroupMember(oderId, username, addedBy = addedBy))
        saveGroup(group)
        return true
    }
    
    /**
     * Remover miembro del grupo
     */
    fun removeMember(groupId: String, oderId: String, removedBy: String): Boolean {
        val group = getGroup(groupId) ?: return false
        val remover = group.members.find { it.oderId == removedBy } ?: return false
        val toRemove = group.members.find { it.oderId == oderId } ?: return false
        
        // Verificar permisos
        if (remover.role.ordinal > toRemove.role.ordinal) return false
        if (toRemove.role == MemberRole.OWNER) return false
        
        group.members.removeAll { it.oderId == oderId }
        saveGroup(group)
        return true
    }
    
    /**
     * Cambiar rol de miembro
     */
    fun changeRole(groupId: String, oderId: String, newRole: MemberRole, changedBy: String): Boolean {
        val group = getGroup(groupId) ?: return false
        val changer = group.members.find { it.oderId == changedBy } ?: return false
        
        // Solo owner y admins pueden cambiar roles
        if (changer.role != MemberRole.OWNER && changer.role != MemberRole.ADMIN) return false
        
        val memberIndex = group.members.indexOfFirst { it.oderId == oderId }
        if (memberIndex == -1) return false
        
        val member = group.members[memberIndex]
        // No se puede cambiar el rol del owner
        if (member.role == MemberRole.OWNER) return false
        
        group.members[memberIndex] = member.copy(role = newRole)
        saveGroup(group)
        return true
    }
    
    /**
     * Salir del grupo
     */
    fun leaveGroup(groupId: String, oderId: String): Boolean {
        val group = getGroup(groupId) ?: return false
        val member = group.members.find { it.oderId == oderId } ?: return false
        
        // Si es owner, transferir ownership al siguiente admin o miembro más antiguo
        if (member.role == MemberRole.OWNER && group.members.size > 1) {
            val newOwner = group.members
                .filter { it.oderId != oderId }
                .sortedBy { it.role.ordinal }
                .firstOrNull()
            
            if (newOwner != null) {
                val index = group.members.indexOf(newOwner)
                group.members[index] = newOwner.copy(role = MemberRole.OWNER)
            }
        }
        
        group.members.removeAll { it.oderId == oderId }
        
        // Si no quedan miembros, eliminar grupo
        if (group.members.isEmpty()) {
            deleteGroup(groupId)
        } else {
            saveGroup(group)
        }
        return true
    }
    
    // ========== Mensajes de grupo ==========
    
    /**
     * Enviar mensaje al grupo
     */
    fun sendMessage(groupId: String, senderId: String, senderName: String, content: String, 
                    messageType: String = "text", replyToId: String? = null): GroupMessage? {
        val group = getGroup(groupId) ?: return null
        val member = group.members.find { it.oderId == senderId } ?: return null
        
        // Verificar si puede enviar mensajes
        if (group.settings.onlyAdminsCanPost && 
            member.role != MemberRole.OWNER && 
            member.role != MemberRole.ADMIN) {
            return null
        }
        
        // Detectar menciones
        val mentions = extractMentions(content, group.members)
        
        val message = GroupMessage(
            groupId = groupId,
            senderId = senderId,
            senderName = senderName,
            content = content,
            messageType = messageType,
            replyToId = replyToId,
            mentions = mentions
        )
        
        saveGroupMessage(message)
        return message
    }
    
    /**
     * Editar mensaje
     */
    fun editMessage(messageId: String, newContent: String, editorId: String): Boolean {
        val message = getGroupMessage(messageId) ?: return false
        if (message.senderId != editorId) return false
        
        val edited = message.copy(content = newContent, isEdited = true)
        saveGroupMessage(edited)
        return true
    }
    
    /**
     * Eliminar mensaje para todos
     */
    fun deleteMessageForAll(messageId: String, deleterId: String): Boolean {
        val message = getGroupMessage(messageId) ?: return false
        val group = getGroup(message.groupId) ?: return false
        val deleter = group.members.find { it.oderId == deleterId } ?: return false
        
        // Solo el autor o admins pueden eliminar
        if (message.senderId != deleterId && 
            deleter.role != MemberRole.OWNER && 
            deleter.role != MemberRole.ADMIN) {
            return false
        }
        
        val deleted = message.copy(content = "🚫 Mensaje eliminado", isDeleted = true)
        saveGroupMessage(deleted)
        return true
    }
    
    /**
     * Obtener mensajes del grupo
     */
    fun getGroupMessages(groupId: String, limit: Int = 100): List<GroupMessage> {
        val json = prefs.getString(KEY_GROUP_MESSAGES, "{}")
        return try {
            val obj = JSONObject(json)
            if (!obj.has(groupId)) return emptyList()
            val array = obj.getJSONArray(groupId)
            val messages = mutableListOf<GroupMessage>()
            for (i in 0 until minOf(array.length(), limit)) {
                val m = array.getJSONObject(i)
                messages.add(parseGroupMessage(m))
            }
            messages.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun getGroupMessage(messageId: String): GroupMessage? {
        val json = prefs.getString(KEY_GROUP_MESSAGES, "{}")
        try {
            val obj = JSONObject(json)
            obj.keys().forEach { groupId ->
                val array = obj.getJSONArray(groupId)
                for (i in 0 until array.length()) {
                    val m = array.getJSONObject(i)
                    if (m.getString("id") == messageId) {
                        return parseGroupMessage(m)
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }
    
    private fun parseGroupMessage(m: JSONObject): GroupMessage {
        val mentionsArray = m.optJSONArray("mentions")
        val mentions = mutableListOf<String>()
        if (mentionsArray != null) {
            for (i in 0 until mentionsArray.length()) {
                mentions.add(mentionsArray.getString(i))
            }
        }
        
        return GroupMessage(
            id = m.getString("id"),
            groupId = m.getString("groupId"),
            senderId = m.getString("senderId"),
            senderName = m.getString("senderName"),
            content = m.getString("content"),
            messageType = m.optString("messageType", "text"),
            timestamp = m.getLong("timestamp"),
            replyToId = m.optString("replyToId", null),
            mentions = mentions,
            isEdited = m.optBoolean("isEdited", false),
            isDeleted = m.optBoolean("isDeleted", false)
        )
    }
    
    private fun saveGroupMessage(message: GroupMessage) {
        val json = prefs.getString(KEY_GROUP_MESSAGES, "{}")
        val obj = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        
        val array = if (obj.has(message.groupId)) obj.getJSONArray(message.groupId) else JSONArray()
        
        // Buscar y actualizar si existe
        var found = false
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getString("id") == message.id) {
                array.put(i, messageToJson(message))
                found = true
                break
            }
        }
        if (!found) {
            array.put(messageToJson(message))
        }
        
        obj.put(message.groupId, array)
        prefs.edit().putString(KEY_GROUP_MESSAGES, obj.toString()).apply()
    }
    
    private fun messageToJson(message: GroupMessage): JSONObject {
        return JSONObject().apply {
            put("id", message.id)
            put("groupId", message.groupId)
            put("senderId", message.senderId)
            put("senderName", message.senderName)
            put("content", message.content)
            put("messageType", message.messageType)
            put("timestamp", message.timestamp)
            put("replyToId", message.replyToId)
            put("mentions", JSONArray(message.mentions))
            put("isEdited", message.isEdited)
            put("isDeleted", message.isDeleted)
        }
    }
    
    // ========== Menciones ==========
    
    /**
     * Extraer menciones del contenido
     */
    private fun extractMentions(content: String, members: List<GroupMember>): List<String> {
        val mentions = mutableListOf<String>()
        val regex = Regex("@(\\w+)")
        regex.findAll(content).forEach { match ->
            val username = match.groupValues[1]
            val member = members.find { it.username.equals(username, ignoreCase = true) }
            if (member != null) {
                mentions.add(member.oderId)
            }
        }
        // @todos o @all menciona a todos
        if (content.contains("@todos", ignoreCase = true) || content.contains("@all", ignoreCase = true)) {
            members.forEach { mentions.add(it.oderId) }
        }
        return mentions.distinct()
    }
    
    // ========== Persistencia ==========
    
    private fun getAllGroups(): List<Group> {
        val json = prefs.getString(KEY_GROUPS, "[]")
        return try {
            val array = JSONArray(json)
            val groups = mutableListOf<Group>()
            for (i in 0 until array.length()) {
                val g = array.getJSONObject(i)
                groups.add(parseGroup(g))
            }
            groups
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun parseGroup(g: JSONObject): Group {
        val membersArray = g.getJSONArray("members")
        val members = mutableListOf<GroupMember>()
        for (i in 0 until membersArray.length()) {
            val m = membersArray.getJSONObject(i)
            members.add(GroupMember(
                oderId = m.getString("oderId"),
                username = m.getString("username"),
                role = MemberRole.valueOf(m.optString("role", "MEMBER")),
                joinedAt = m.optLong("joinedAt", 0),
                addedBy = m.optString("addedBy", null)
            ))
        }
        
        val settingsObj = g.optJSONObject("settings")
        val settings = if (settingsObj != null) {
            GroupSettings(
                onlyAdminsCanPost = settingsObj.optBoolean("onlyAdminsCanPost", false),
                onlyAdminsCanEditInfo = settingsObj.optBoolean("onlyAdminsCanEditInfo", true),
                onlyAdminsCanAddMembers = settingsObj.optBoolean("onlyAdminsCanAddMembers", false),
                approvalRequired = settingsObj.optBoolean("approvalRequired", false),
                maxMembers = settingsObj.optInt("maxMembers", 256)
            )
        } else GroupSettings()
        
        return Group(
            id = g.getString("id"),
            name = g.getString("name"),
            description = g.optString("description", ""),
            photoBase64 = g.optString("photoBase64", null),
            members = members,
            createdBy = g.getString("createdBy"),
            createdAt = g.optLong("createdAt", 0),
            settings = settings
        )
    }
    
    private fun saveGroup(group: Group) {
        val groups = getAllGroups().toMutableList()
        groups.removeAll { it.id == group.id }
        groups.add(group)
        saveAllGroups(groups)
    }
    
    private fun saveAllGroups(groups: List<Group>) {
        val array = JSONArray()
        groups.forEach { group ->
            val membersArray = JSONArray()
            group.members.forEach { m ->
                membersArray.put(JSONObject().apply {
                    put("oderId", m.oderId)
                    put("username", m.username)
                    put("role", m.role.name)
                    put("joinedAt", m.joinedAt)
                    put("addedBy", m.addedBy)
                })
            }
            
            val settingsObj = JSONObject().apply {
                put("onlyAdminsCanPost", group.settings.onlyAdminsCanPost)
                put("onlyAdminsCanEditInfo", group.settings.onlyAdminsCanEditInfo)
                put("onlyAdminsCanAddMembers", group.settings.onlyAdminsCanAddMembers)
                put("approvalRequired", group.settings.approvalRequired)
                put("maxMembers", group.settings.maxMembers)
            }
            
            array.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("description", group.description)
                put("photoBase64", group.photoBase64)
                put("members", membersArray)
                put("createdBy", group.createdBy)
                put("createdAt", group.createdAt)
                put("settings", settingsObj)
            })
        }
        prefs.edit().putString(KEY_GROUPS, array.toString()).apply()
    }
}
