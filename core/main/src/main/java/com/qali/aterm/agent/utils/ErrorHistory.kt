package com.qali.aterm.agent.utils

import java.io.File
import java.io.FileWriter
import java.io.BufferedReader
import java.io.FileReader
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * Error history entry
 */
data class ErrorHistoryEntry(
    val errorId: String,
    val timestamp: Long,
    val errorMessage: String,
    val errorType: String? = null,
    val severity: ErrorSeverity,
    val filePath: String? = null,
    val lineNumber: Int? = null,
    val functionName: String? = null,
    val fixApplied: String? = null, // How it was fixed
    val fixSuccessful: Boolean? = null, // Whether fix worked
    val userCorrection: String? = null, // User's correction if AI fix was wrong
    val frequency: Int = 1 // How many times this error occurred
)

/**
 * Error history manager with persistence and learning
 */
object ErrorHistoryManager {
    
    private val historyFile = File("/data/data/com.qali.aterm/files/error_history.json")
    private val history = mutableListOf<ErrorHistoryEntry>()
    private var isLoaded = false
    
    /**
     * Load error history from disk
     */
    fun loadHistory(workspaceRoot: String) {
        if (isLoaded) return
        
        try {
            val historyFile = File(workspaceRoot, ".aterm/error_history.json")
            historyFile.parentFile?.mkdirs()
            
            if (historyFile.exists()) {
                val content = historyFile.readText()
                val json = JSONObject(content)
                val entriesArray = json.optJSONArray("entries") ?: JSONArray()
                
                for (i in 0 until entriesArray.length()) {
                    val entryObj = entriesArray.getJSONObject(i)
                    val severityStr = entryObj.optString("severity", "MEDIUM")
                    val severity = try {
                        ErrorSeverity.valueOf(severityStr)
                    } catch (e: Exception) {
                        ErrorSeverity.MEDIUM
                    }
                    
                    history.add(
                        ErrorHistoryEntry(
                            errorId = entryObj.optString("errorId", ""),
                            timestamp = entryObj.optLong("timestamp", System.currentTimeMillis()),
                            errorMessage = entryObj.optString("errorMessage", ""),
                            errorType = entryObj.optString("errorType", null),
                            severity = severity,
                            filePath = entryObj.optString("filePath", null),
                            lineNumber = if (entryObj.has("lineNumber")) entryObj.optInt("lineNumber") else null,
                            functionName = entryObj.optString("functionName", null),
                            fixApplied = entryObj.optString("fixApplied", null),
                            fixSuccessful = if (entryObj.has("fixSuccessful")) entryObj.optBoolean("fixSuccessful") else null,
                            userCorrection = entryObj.optString("userCorrection", null),
                            frequency = entryObj.optInt("frequency", 1)
                        )
                    )
                }
            }
            
            isLoaded = true
            Log.d("ErrorHistoryManager", "Loaded ${history.size} error history entries")
        } catch (e: Exception) {
            Log.e("ErrorHistoryManager", "Failed to load error history: ${e.message}", e)
        }
    }
    
    /**
     * Save error history to disk
     */
    fun saveHistory(workspaceRoot: String) {
        try {
            val historyFile = File(workspaceRoot, ".aterm/error_history.json")
            historyFile.parentFile?.mkdirs()
            
            val json = JSONObject()
            val entriesArray = JSONArray()
            
            history.forEach { entry ->
                val entryObj = JSONObject()
                entryObj.put("errorId", entry.errorId)
                entryObj.put("timestamp", entry.timestamp)
                entryObj.put("errorMessage", entry.errorMessage)
                entry.errorType?.let { entryObj.put("errorType", it) }
                entryObj.put("severity", entry.severity.name)
                entry.filePath?.let { entryObj.put("filePath", it) }
                entry.lineNumber?.let { entryObj.put("lineNumber", it) }
                entry.functionName?.let { entryObj.put("functionName", it) }
                entry.fixApplied?.let { entryObj.put("fixApplied", it) }
                entry.fixSuccessful?.let { entryObj.put("fixSuccessful", it) }
                entry.userCorrection?.let { entryObj.put("userCorrection", it) }
                entryObj.put("frequency", entry.frequency)
                entriesArray.put(entryObj)
            }
            
            json.put("entries", entriesArray)
            json.put("lastUpdated", System.currentTimeMillis())
            
            historyFile.writeText(json.toString(2))
            Log.d("ErrorHistoryManager", "Saved ${history.size} error history entries")
        } catch (e: Exception) {
            Log.e("ErrorHistoryManager", "Failed to save error history: ${e.message}", e)
        }
    }
    
    /**
     * Add error to history
     */
    fun addError(
        errorMessage: String,
        errorType: String? = null,
        severity: ErrorSeverity,
        filePath: String? = null,
        lineNumber: Int? = null,
        functionName: String? = null,
        workspaceRoot: String
    ) {
        // Check if similar error exists
        val similarError = findSimilarError(errorMessage, filePath, lineNumber)
        
        if (similarError != null) {
            // Update frequency
            val index = history.indexOf(similarError)
            if (index >= 0) {
                history[index] = similarError.copy(frequency = similarError.frequency + 1)
            }
        } else {
            // Add new entry
            val errorId = "error_${System.currentTimeMillis()}_${history.size}"
            history.add(
                ErrorHistoryEntry(
                    errorId = errorId,
                    timestamp = System.currentTimeMillis(),
                    errorMessage = errorMessage,
                    errorType = errorType,
                    severity = severity,
                    filePath = filePath,
                    lineNumber = lineNumber,
                    functionName = functionName
                )
            )
        }
        
        // Auto-save periodically (every 10 errors)
        if (history.size % 10 == 0) {
            saveHistory(workspaceRoot)
        }
    }
    
    /**
     * Record fix applied
     */
    fun recordFix(
        errorId: String,
        fixApplied: String,
        successful: Boolean,
        workspaceRoot: String
    ) {
        val index = history.indexOfFirst { it.errorId == errorId }
        if (index >= 0) {
            history[index] = history[index].copy(
                fixApplied = fixApplied,
                fixSuccessful = successful
            )
            saveHistory(workspaceRoot)
        }
    }
    
    /**
     * Record user correction
     */
    fun recordUserCorrection(
        errorId: String,
        correction: String,
        workspaceRoot: String
    ) {
        val index = history.indexOfFirst { it.errorId == errorId }
        if (index >= 0) {
            history[index] = history[index].copy(
                userCorrection = correction,
                fixSuccessful = true // User correction is assumed successful
            )
            saveHistory(workspaceRoot)
        }
    }
    
    /**
     * Find similar error in history
     */
    private fun findSimilarError(
        errorMessage: String,
        filePath: String?,
        lineNumber: Int?
    ): ErrorHistoryEntry? {
        return history.firstOrNull { entry ->
            // Similar if same file and similar message
            entry.filePath == filePath &&
            entry.lineNumber == lineNumber &&
            similarityScore(entry.errorMessage, errorMessage) > 0.7
        }
    }
    
    /**
     * Calculate similarity score between two strings
     */
    private fun similarityScore(str1: String, str2: String): Double {
        val words1 = str1.lowercase().split(Regex("\\s+")).toSet()
        val words2 = str2.lowercase().split(Regex("\\s+")).toSet()
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }
    
    /**
     * Get error history for a file
     */
    fun getHistoryForFile(filePath: String): List<ErrorHistoryEntry> {
        return history.filter { it.filePath == filePath }
            .sortedByDescending { it.timestamp }
    }
    
    /**
     * Get error history for a function
     */
    fun getHistoryForFunction(functionName: String, filePath: String? = null): List<ErrorHistoryEntry> {
        return history.filter { 
            it.functionName == functionName && 
            (filePath == null || it.filePath == filePath)
        }.sortedByDescending { it.timestamp }
    }
    
    /**
     * Get recurring errors (errors that occurred multiple times)
     */
    fun getRecurringErrors(minFrequency: Int = 2): List<ErrorHistoryEntry> {
        return history.filter { it.frequency >= minFrequency }
            .sortedByDescending { it.frequency }
    }
    
    /**
     * Get errors by type
     */
    fun getErrorsByType(errorType: String): List<ErrorHistoryEntry> {
        return history.filter { it.errorType == errorType }
            .sortedByDescending { it.timestamp }
    }
    
    /**
     * Get successful fixes (for learning)
     */
    fun getSuccessfulFixes(): List<ErrorHistoryEntry> {
        return history.filter { it.fixSuccessful == true && it.fixApplied != null }
    }
    
    /**
     * Get user corrections (for learning)
     */
    fun getUserCorrections(): List<ErrorHistoryEntry> {
        return history.filter { it.userCorrection != null }
    }
    
    /**
     * Learn from history - suggest fixes based on past successful fixes
     */
    fun suggestFixFromHistory(
        errorMessage: String,
        filePath: String? = null
    ): String? {
        // Find similar errors with successful fixes
        val similarErrors = history.filter { entry ->
            entry.fixSuccessful == true &&
            entry.fixApplied != null &&
            similarityScore(entry.errorMessage, errorMessage) > 0.6 &&
            (filePath == null || entry.filePath == filePath)
        }.sortedByDescending { it.timestamp }
        
        return similarErrors.firstOrNull()?.fixApplied
    }
    
    /**
     * Get error frequency analysis
     */
    fun getFrequencyAnalysis(): Map<String, Int> {
        val frequencyMap = mutableMapOf<String, Int>()
        
        history.forEach { entry ->
            val key = "${entry.errorType ?: "unknown"}_${entry.filePath ?: "unknown"}"
            frequencyMap[key] = (frequencyMap[key] ?: 0) + entry.frequency
        }
        
        return frequencyMap.toMap()
    }
    
    /**
     * Clear history
     */
    fun clearHistory(workspaceRoot: String) {
        history.clear()
        isLoaded = false
        val historyFile = File(workspaceRoot, ".aterm/error_history.json")
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }
}
