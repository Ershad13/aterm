package com.qali.aterm.agent.utils

import android.util.Log
import java.io.File
import java.util.regex.Pattern

/**
 * Utilities for detecting and parsing errors from stack traces and error messages
 * Helps the agent locate relevant files and lines when debugging
 */
object ErrorDetectionUtils {
    
    /**
     * Parse error message to extract file paths and line numbers
     * Supports various error formats:
     * - JavaScript: "at /path/to/file.js:123:45"
     * - Python: "File \"/path/to/file.py\", line 123"
     * - Java: "at com.example.Class.method(Class.java:123)"
     * - Generic: "Error in file.js:123"
     */
    data class ErrorLocation(
        val filePath: String,
        val lineNumber: Int? = null,
        val columnNumber: Int? = null,
        val functionName: String? = null,
        val severity: com.qali.aterm.agent.utils.ErrorSeverity? = null
    )
    
    /**
     * Extract error locations from error message or stack trace
     * Now uses ErrorPatternLibrary for multi-language support
     */
    fun parseErrorLocations(errorMessage: String, workspaceRoot: String): List<ErrorLocation> {
        val locations = mutableListOf<ErrorLocation>()
        
        // Detect language from error message
        val detectedLanguage = ErrorPatternLibrary.detectLanguage("", errorMessage)
        val languagePatterns = ErrorPatternLibrary.getPatternsForLanguage(detectedLanguage)
        
        // Also try generic patterns as fallback
        val genericPatterns = ErrorPatternLibrary.getPatternsForLanguage("generic")
        
        // Try all patterns (language-specific first, then generic)
        val allPatterns = languagePatterns.patterns + genericPatterns.patterns
        
        for (pattern in allPatterns) {
            val matcher = pattern.matcher(errorMessage)
            while (matcher.find()) {
                try {
                    val filePath = matcher.group(1)?.trim() ?: continue
                    val lineNum = matcher.group(2)?.toIntOrNull()
                    val colNum = matcher.group(3)?.toIntOrNull()
                    val funcName = if (matcher.groupCount() >= 4) matcher.group(4) else null
                    
                    // Resolve relative paths
                    val resolvedPath = resolveFilePath(filePath, workspaceRoot)
                    if (resolvedPath != null) {
                        // Detect error type from language-specific patterns
                        var errorType: String? = null
                        for ((type, pattern) in languagePatterns.errorTypePatterns) {
                            if (pattern.matcher(errorMessage).find()) {
                                errorType = type
                                break
                            }
                        }
                        
                        // Classify severity based on error message and type
                        val severity = ErrorSeverityClassifier.classifySeverity(
                            errorMessage = errorMessage,
                            errorType = errorType,
                            stackTraceDepth = null,
                            affectedFileCount = null
                        )
                        
                        locations.add(ErrorLocation(
                            filePath = resolvedPath,
                            lineNumber = lineNum,
                            columnNumber = colNum,
                            functionName = funcName,
                            severity = severity
                        ))
                    }
                } catch (e: Exception) {
                    Log.w("ErrorDetectionUtils", "Failed to parse error location: ${e.message}")
                }
            }
        }
        
        return locations.distinctBy { "${it.filePath}:${it.lineNumber}" }
    }
    
    /**
     * Resolve file path from error message to actual file in workspace
     */
    private fun resolveFilePath(pathFromError: String, workspaceRoot: String): String? {
        val workspaceDir = File(workspaceRoot)
        
        // Remove quotes if present
        val cleanPath = pathFromError.trim().removeSurrounding("\"").removeSurrounding("'")
        
        // Try as absolute path first
        val absoluteFile = File(cleanPath)
        if (absoluteFile.exists() && absoluteFile.isFile) {
            try {
                val relative = absoluteFile.relativeTo(workspaceDir).path.replace("\\", "/")
                return relative
            } catch (e: Exception) {
                // File is outside workspace, use as-is
                return cleanPath
            }
        }
        
        // Try as relative path from workspace root
        val relativeFile = File(workspaceDir, cleanPath)
        if (relativeFile.exists() && relativeFile.isFile) {
            return cleanPath.replace("\\", "/")
        }
        
        // Try to find file by name only (last component)
        val fileName = File(cleanPath).name
        workspaceDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name == fileName) {
                try {
                    val relative = file.relativeTo(workspaceDir).path.replace("\\", "/")
                    // Don't return files in ignored directories
                    if (!AtermIgnoreManager.shouldIgnoreFile(file, workspaceRoot)) {
                        return relative
                    }
                } catch (e: Exception) {
                    // Skip
                }
            }
        }
        
        return null
    }
    
    /**
     * Detect common API mismatches from error messages
     * e.g., "db.execute is not a function" suggests SQLite vs MySQL API mismatch
     */
    data class ApiMismatch(
        val errorType: String,
        val suggestedFix: String,
        val affectedFiles: List<String>
    )
    
    fun detectApiMismatch(errorMessage: String, filePath: String? = null): ApiMismatch? {
        // Use enhanced API mismatch library
        return ApiMismatchLibrary.detectApiMismatch(errorMessage, filePath) ?: detectLegacyApiMismatch(errorMessage)
    }
    
    /**
     * Legacy API mismatch detection (fallback)
     */
    private fun detectLegacyApiMismatch(errorMessage: String): ApiMismatch? {
        val lowerError = errorMessage.lowercase()
        
        // SQLite vs MySQL detection
        if (lowerError.contains("execute") && lowerError.contains("not a function")) {
            return ApiMismatch(
                errorType = "SQLite API Mismatch",
                suggestedFix = "SQLite uses db.all(), db.get(), db.run() instead of db.execute(). Check database.js for correct API usage.",
                affectedFiles = listOf("database.js", "db.js", "routes/", "controllers/")
            )
        }
        
        if (lowerError.contains("query") && lowerError.contains("not a function")) {
            return ApiMismatch(
                errorType = "Database API Mismatch",
                suggestedFix = "Check if using correct database library API. SQLite uses different methods than MySQL/PostgreSQL.",
                affectedFiles = listOf("database.js", "db.js")
            )
        }
        
        // Promise vs callback detection
        if (lowerError.contains("cannot read property") && lowerError.contains("then")) {
            return ApiMismatch(
                errorType = "Promise/Callback Mismatch",
                suggestedFix = "Function may return a callback instead of a Promise. Use callback pattern or promisify the function.",
                affectedFiles = emptyList()
            )
        }
        
        return null
    }
    
    /**
     * Get enhanced error context for a specific error location
     */
    fun getErrorContext(
        errorLocation: ErrorLocation,
        workspaceRoot: String,
        contextLines: Int = 5
    ): com.qali.aterm.agent.utils.ErrorContextExtractor.ErrorContext? {
        if (errorLocation.lineNumber == null) {
            return null
        }
        
        return com.qali.aterm.agent.utils.ErrorContextExtractor.extractContext(
            filePath = errorLocation.filePath,
            lineNumber = errorLocation.lineNumber,
            columnNumber = errorLocation.columnNumber,
            contextLines = contextLines,
            workspaceRoot = workspaceRoot
        )
    }
    
    /**
     * Handle multiple errors simultaneously
     * Groups errors and creates prioritized fix order
     */
    fun handleMultipleErrors(
        errorLocations: List<ErrorLocation>,
        errorMessages: List<String>,
        workspaceRoot: String
    ): MultiErrorHandler.MultiErrorAnalysis {
        return MultiErrorHandler.handleMultipleErrors(
            errorLocations = errorLocations,
            errorMessages = errorMessages,
            workspaceRoot = workspaceRoot
        )
    }
    
    /**
     * Correlate errors and find root causes
     */
    fun correlateErrors(
        errorLocations: List<ErrorLocation>,
        errorMessages: List<String>,
        workspaceRoot: String
    ): ErrorCorrelationEngine.CorrelationResult {
        return ErrorCorrelationEngine.correlateErrors(
            errorLocations = errorLocations,
            errorMessages = errorMessages,
            workspaceRoot = workspaceRoot
        )
    }
    
    /**
     * Record error in history
     */
    fun recordErrorInHistory(
        errorMessage: String,
        errorLocation: ErrorLocation,
        errorType: String? = null,
        workspaceRoot: String
    ) {
        ErrorHistoryManager.addError(
            errorMessage = errorMessage,
            errorType = errorType,
            severity = errorLocation.severity ?: ErrorSeverity.MEDIUM,
            filePath = errorLocation.filePath,
            lineNumber = errorLocation.lineNumber,
            functionName = errorLocation.functionName,
            workspaceRoot = workspaceRoot
        )
    }
    
    /**
     * Get fix suggestion from history
     */
    fun getFixSuggestionFromHistory(
        errorMessage: String,
        filePath: String? = null
    ): String? {
        return ErrorHistoryManager.suggestFixFromHistory(errorMessage, filePath)
    }
    
    /**
     * Get related files for debugging based on error location
     * Returns files that are likely related to the error (routes, controllers, models, etc.)
     */
    fun getRelatedFilesForError(errorLocation: ErrorLocation, workspaceRoot: String): List<String> {
        val relatedFiles = mutableListOf<String>()
        val workspaceDir = File(workspaceRoot)
        
        // If error is in a route file, also check controllers and models
        if (errorLocation.filePath.contains("route", ignoreCase = true) ||
            errorLocation.filePath.contains("api", ignoreCase = true)) {
            relatedFiles.addAll(findFilesByPattern(workspaceDir, listOf(
                "controllers/", "models/", "database.js", "db.js", "config.js"
            )))
        }
        
        // If error is in database file, check routes and models
        if (errorLocation.filePath.contains("database", ignoreCase = true) ||
            errorLocation.filePath.contains("db", ignoreCase = true)) {
            relatedFiles.addAll(findFilesByPattern(workspaceDir, listOf(
                "routes/", "models/", "server.js", "app.js"
            )))
        }
        
        // If error is in a model, check database and routes
        if (errorLocation.filePath.contains("model", ignoreCase = true)) {
            relatedFiles.addAll(findFilesByPattern(workspaceDir, listOf(
                "database.js", "db.js", "routes/", "controllers/"
            )))
        }
        
        return relatedFiles.distinct().filter { file ->
            val fileObj = File(workspaceDir, file)
            fileObj.exists() && !AtermIgnoreManager.shouldIgnoreFile(fileObj, workspaceRoot)
        }
    }
    
    private fun findFilesByPattern(workspaceDir: File, patterns: List<String>): List<String> {
        val files = mutableListOf<String>()
        
        for (pattern in patterns) {
            if (pattern.endsWith("/")) {
                // Directory pattern
                val dir = File(workspaceDir, pattern.removeSuffix("/"))
                if (dir.exists() && dir.isDirectory) {
                    dir.walkTopDown().forEach { file ->
                        if (file.isFile && !AtermIgnoreManager.shouldIgnoreFile(file, workspaceDir.absolutePath)) {
                            try {
                                files.add(file.relativeTo(workspaceDir).path.replace("\\", "/"))
                            } catch (e: Exception) {
                                // Skip
                            }
                        }
                    }
                }
            } else {
                // File pattern
                val file = File(workspaceDir, pattern)
                if (file.exists() && file.isFile) {
                    try {
                        files.add(file.relativeTo(workspaceDir).path.replace("\\", "/"))
                    } catch (e: Exception) {
                        // Skip
                    }
                }
            }
        }
        
        return files
    }
}
