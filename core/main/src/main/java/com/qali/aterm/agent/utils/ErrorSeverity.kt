package com.qali.aterm.agent.utils

/**
 * Error severity levels for classification
 */
enum class ErrorSeverity {
    /**
     * Critical errors that prevent execution or cause crashes
     * Examples: Fatal errors, crashes, data corruption, security vulnerabilities
     */
    CRITICAL,
    
    /**
     * High priority errors that break functionality
     * Examples: Runtime errors, exceptions, missing dependencies, API failures
     */
    HIGH,
    
    /**
     * Medium priority errors that affect functionality but may have workarounds
     * Examples: Compilation errors, type errors, syntax errors, missing imports
     */
    MEDIUM,
    
    /**
     * Low priority issues that don't break functionality
     * Examples: Warnings, deprecation notices, style issues, minor bugs
     */
    LOW,
    
    /**
     * Informational messages and suggestions
     * Examples: Best practice suggestions, optimization hints, code style tips
     */
    INFO
}

/**
 * Error severity classifier
 */
object ErrorSeverityClassifier {
    
    /**
     * Classify error severity based on error message and context
     * 
     * @param errorMessage The error message or description
     * @param errorType Optional error type (e.g., "TypeError", "SyntaxError")
     * @param stackTraceDepth Optional stack trace depth
     * @param affectedFileCount Optional number of files affected
     * @return Classified severity level
     */
    fun classifySeverity(
        errorMessage: String,
        errorType: String? = null,
        stackTraceDepth: Int? = null,
        affectedFileCount: Int? = null
    ): ErrorSeverity {
        val lowerMessage = errorMessage.lowercase()
        val lowerType = errorType?.lowercase() ?: ""
        
        // Critical indicators
        val criticalKeywords = listOf(
            "fatal", "crash", "corruption", "security", "vulnerability",
            "data loss", "memory leak", "stack overflow", "out of memory",
            "segmentation fault", "access violation", "null pointer exception"
        )
        
        if (criticalKeywords.any { lowerMessage.contains(it) || lowerType.contains(it) }) {
            return ErrorSeverity.CRITICAL
        }
        
        // High priority indicators
        val highKeywords = listOf(
            "runtime error", "exception", "failed", "cannot", "unable",
            "missing", "not found", "undefined", "reference error",
            "type error", "import error", "module not found"
        )
        
        if (highKeywords.any { lowerMessage.contains(it) || lowerType.contains(it) }) {
            // Check if it's actually critical based on context
            if (stackTraceDepth != null && stackTraceDepth > 10) {
                return ErrorSeverity.CRITICAL // Deep stack trace suggests critical issue
            }
            if (affectedFileCount != null && affectedFileCount > 5) {
                return ErrorSeverity.CRITICAL // Many files affected suggests critical issue
            }
            return ErrorSeverity.HIGH
        }
        
        // Medium priority indicators
        val mediumKeywords = listOf(
            "compile error", "syntax error", "parse error", "type mismatch",
            "deprecated", "warning", "lint error", "style error"
        )
        
        if (mediumKeywords.any { lowerMessage.contains(it) || lowerType.contains(it) }) {
            return ErrorSeverity.MEDIUM
        }
        
        // Low priority indicators
        val lowKeywords = listOf(
            "deprecation", "style", "formatting", "unused", "optimization",
            "suggestion", "hint", "tip"
        )
        
        if (lowKeywords.any { lowerMessage.contains(it) || lowerType.contains(it) }) {
            return ErrorSeverity.LOW
        }
        
        // Info indicators
        val infoKeywords = listOf(
            "suggestion", "recommendation", "best practice", "consider",
            "you may want", "it's recommended"
        )
        
        if (infoKeywords.any { lowerMessage.contains(it) }) {
            return ErrorSeverity.INFO
        }
        
        // Default based on error type
        return when (lowerType) {
            "fatalerror", "fatal" -> ErrorSeverity.CRITICAL
            "runtimeerror", "exception" -> ErrorSeverity.HIGH
            "syntaxerror", "compilationerror", "typeerror" -> ErrorSeverity.MEDIUM
            "warning", "deprecation" -> ErrorSeverity.LOW
            else -> ErrorSeverity.MEDIUM // Default to medium if unknown
        }
    }
    
    /**
     * Get priority score for sorting (higher = more urgent)
     */
    fun getPriorityScore(severity: ErrorSeverity): Int {
        return when (severity) {
            ErrorSeverity.CRITICAL -> 100
            ErrorSeverity.HIGH -> 75
            ErrorSeverity.MEDIUM -> 50
            ErrorSeverity.LOW -> 25
            ErrorSeverity.INFO -> 10
        }
    }
    
    /**
     * Check if severity requires immediate attention
     */
    fun requiresImmediateAttention(severity: ErrorSeverity): Boolean {
        return severity == ErrorSeverity.CRITICAL || severity == ErrorSeverity.HIGH
    }
    
    /**
     * Get display color/emoji for severity
     */
    fun getDisplaySymbol(severity: ErrorSeverity): String {
        return when (severity) {
            ErrorSeverity.CRITICAL -> "🔴"
            ErrorSeverity.HIGH -> "🟠"
            ErrorSeverity.MEDIUM -> "🟡"
            ErrorSeverity.LOW -> "🔵"
            ErrorSeverity.INFO -> "ℹ️"
        }
    }
}
