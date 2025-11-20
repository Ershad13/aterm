package com.rk.terminal.gemini

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rk.libcommons.application
import com.rk.terminal.ui.screens.agent.AgentMessage
import java.lang.reflect.Type

data class SerializableAgentMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
) {
    fun toAgentMessage(): AgentMessage = AgentMessage(text, isUser, timestamp)
    
    companion object {
        fun fromAgentMessage(msg: AgentMessage): SerializableAgentMessage {
            return SerializableAgentMessage(msg.text, msg.isUser, msg.timestamp)
        }
    }
}

object HistoryPersistenceService {
    private const val PREFS_NAME = "agent_history"
    private const val KEY_SESSIONS = "sessions"
    private val gson = Gson()
    
    private val prefs: SharedPreferences
        get() = application!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val sessionsType: Type = object : TypeToken<Map<String, String>>() {}.type
    private val messagesType: Type = object : TypeToken<List<SerializableAgentMessage>>() {}.type
    
    /**
     * Save chat history for a session
     */
    fun saveHistory(sessionId: String, messages: List<AgentMessage>) {
        try {
            val serializableMessages = messages.map { SerializableAgentMessage.fromAgentMessage(it) }
            val messagesJson = gson.toJson(serializableMessages)
            
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = try {
                gson.fromJson<Map<String, String>>(sessionsJson, sessionsType) ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
            
            val updatedSessions = sessions.toMutableMap()
            updatedSessions[sessionId] = messagesJson
            
            prefs.edit()
                .putString(KEY_SESSIONS, gson.toJson(updatedSessions))
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to save history", e)
        }
    }
    
    /**
     * Load chat history for a session
     */
    fun loadHistory(sessionId: String): List<AgentMessage> {
        return try {
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = gson.fromJson<Map<String, String>>(sessionsJson, sessionsType) ?: emptyMap()
            val messagesJson = sessions[sessionId] ?: return emptyList()
            
            val serializableMessages = gson.fromJson<List<SerializableAgentMessage>>(messagesJson, messagesType)
                ?: return emptyList()
            
            serializableMessages.map { it.toAgentMessage() }
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to load history", e)
            emptyList()
        }
    }
    
    /**
     * Get all session IDs
     */
    fun getAllSessionIds(): List<String> {
        return try {
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = gson.fromJson<Map<String, String>>(sessionsJson, sessionsType) ?: emptyMap()
            sessions.keys.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Delete history for a session
     */
    fun deleteHistory(sessionId: String) {
        try {
            val sessionsJson = prefs.getString(KEY_SESSIONS, "{}") ?: "{}"
            val sessions = gson.fromJson<MutableMap<String, String>>(sessionsJson, sessionsType) ?: mutableMapOf()
            sessions.remove(sessionId)
            
            prefs.edit()
                .putString(KEY_SESSIONS, gson.toJson(sessions))
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("HistoryPersistence", "Failed to delete history", e)
        }
    }
    
    /**
     * Clear all history
     */
    fun clearAllHistory() {
        prefs.edit().clear().apply()
    }
}
