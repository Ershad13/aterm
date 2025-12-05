package com.qali.aterm.agent.utils

import android.util.Log
import java.util.regex.Pattern

/**
 * Advanced error extraction from natural language user prompts
 * Handles conversational prompts, multi-sentence descriptions, and implicit errors
 */
object PromptErrorExtractor {
    
    /**
     * Extracted error information from user prompt
     */
    data class ExtractedErrorInfo(
        val errorType: String? = null,
        val errorMessage: String? = null,
        val affectedFiles: List<String> = emptyList(),
        val lineNumbers: List<Int> = emptyList(),
        val functionNames: List<String> = emptyList(),
        val severity: ErrorSeverity? = null,
        val context: String? = null, // What user was doing when error occurred
        val timeline: String? = null, // When did it start, frequency
        val affectedComponents: List<String> = emptyList(),
        val implicitError: Boolean = false // True if error was inferred from symptoms
    )
    
    /**
     * Extract error information from user prompt
     * 
     * @param userPrompt The user's natural language prompt
     * @return Extracted error information
     */
    fun extractErrorInfo(userPrompt: String): ExtractedErrorInfo {
        val lowerPrompt = userPrompt.lowercase()
        
        // Detect if this is an implicit error (user describes symptoms)
        val isImplicit = detectImplicitError(lowerPrompt)
        
        // Extract error type
        val errorType = extractErrorType(userPrompt, lowerPrompt)
        
        // Extract error message
        val errorMessage = extractErrorMessage(userPrompt, lowerPrompt)
        
        // Extract file paths
        val affectedFiles = extractFilePaths(userPrompt)
        
        // Extract line numbers
        val lineNumbers = extractLineNumbers(userPrompt)
        
        // Extract function names
        val functionNames = extractFunctionNames(userPrompt)
        
        // Extract severity
        val severity = extractSeverity(userPrompt, lowerPrompt)
        
        // Extract context (what user was doing)
        val context = extractContext(userPrompt)
        
        // Extract timeline
        val timeline = extractTimeline(userPrompt, lowerPrompt)
        
        // Extract affected components
        val affectedComponents = extractAffectedComponents(userPrompt, lowerPrompt)
        
        return ExtractedErrorInfo(
            errorType = errorType,
            errorMessage = errorMessage,
            affectedFiles = affectedFiles,
            lineNumbers = lineNumbers,
            functionNames = functionNames,
            severity = severity,
            context = context,
            timeline = timeline,
            affectedComponents = affectedComponents,
            implicitError = isImplicit
        )
    }
    
    /**
     * Detect if error is implicit (user describes symptoms, not explicit error)
     */
    private fun detectImplicitError(lowerPrompt: String): Boolean {
        val symptomKeywords = listOf(
            "doesn't work", "not working", "broken", "crash", "crashes",
            "freeze", "freezes", "slow", "hangs", "won't start",
            "can't", "unable to", "nothing happens", "blank screen"
        )
        
        val explicitErrorKeywords = listOf(
            "error", "exception", "failed", "failure", "bug",
            "syntax error", "runtime error", "type error"
        )
        
        val hasSymptoms = symptomKeywords.any { lowerPrompt.contains(it) }
        val hasExplicitError = explicitErrorKeywords.any { lowerPrompt.contains(it) }
        
        return hasSymptoms && !hasExplicitError
    }
    
    /**
     * Extract error type from prompt
     */
    private fun extractErrorType(prompt: String, lowerPrompt: String): String? {
        val errorTypePatterns = mapOf(
            "SyntaxError" to listOf("syntax error", "parse error", "syntax"),
            "TypeError" to listOf("type error", "type mismatch", "wrong type"),
            "ReferenceError" to listOf("reference error", "undefined", "not defined"),
            "ImportError" to listOf("import error", "cannot find module", "module not found"),
            "RuntimeError" to listOf("runtime error", "runtime exception"),
            "CompilationError" to listOf("compile error", "compilation error", "won't compile"),
            "NullPointerException" to listOf("null pointer", "null reference", "null exception"),
            "IndexOutOfBounds" to listOf("index out of bounds", "array index", "out of range")
        )
        
        for ((errorType, keywords) in errorTypePatterns) {
            if (keywords.any { lowerPrompt.contains(it) }) {
                return errorType
            }
        }
        
        // Try to extract from explicit error messages
        val explicitPattern = Pattern.compile("""(?:error|exception|failed):\s*(\w+(?:\s+\w+)?)""", Pattern.CASE_INSENSITIVE)
        val matcher = explicitPattern.matcher(prompt)
        if (matcher.find()) {
            return matcher.group(1)?.trim()?.capitalize()
        }
        
        return null
    }
    
    /**
     * Extract error message from prompt
     */
    private fun extractErrorMessage(prompt: String, lowerPrompt: String): String? {
        // Look for quoted error messages
        val quotedPattern = Pattern.compile("""["']([^"']+)["']""")
        val quotedMatcher = quotedPattern.matcher(prompt)
        if (quotedMatcher.find()) {
            val quoted = quotedMatcher.group(1)
            if (quoted.length > 10 && quoted.length < 200) {
                return quoted
            }
        }
        
        // Look for "error:" or "exception:" patterns
        val errorPattern = Pattern.compile("""(?:error|exception|failed):\s*(.+?)(?:\.|$|\n)""", Pattern.CASE_INSENSITIVE)
        val errorMatcher = errorPattern.matcher(prompt)
        if (errorMatcher.find()) {
            return errorMatcher.group(1)?.trim()
        }
        
        // Extract from stack trace patterns
        val stackPattern = Pattern.compile("""at\s+[^(]+\([^)]+\)\s*:\s*(.+)""")
        val stackMatcher = stackPattern.matcher(prompt)
        if (stackMatcher.find()) {
            return stackMatcher.group(1)?.trim()
        }
        
        return null
    }
    
    /**
     * Extract file paths from prompt
     */
    private fun extractFilePaths(prompt: String): List<String> {
        val filePaths = mutableSetOf<String>()
        
        // Pattern for file paths
        val filePattern = Pattern.compile("""([/\w\-\.]+\.(?:js|ts|jsx|tsx|py|java|kt|go|rs|cpp|h|hpp|json|yaml|yml))""")
        val matcher = filePattern.matcher(prompt)
        while (matcher.find()) {
            val path = matcher.group(1)
            if (path != null && path.length > 3) {
                filePaths.add(path)
            }
        }
        
        // Pattern for "in file.js" or "at file.js"
        val inFilePattern = Pattern.compile("""(?:in|at|from|file)\s+['"]?([/\w\-\.]+\.(?:js|ts|py|java|kt))['"]?""", Pattern.CASE_INSENSITIVE)
        val inFileMatcher = inFilePattern.matcher(prompt)
        while (inFileMatcher.find()) {
            val path = inFileMatcher.group(1)
            if (path != null) {
                filePaths.add(path)
            }
        }
        
        return filePaths.toList()
    }
    
    /**
     * Extract line numbers from prompt
     */
    private fun extractLineNumbers(prompt: String): List<Int> {
        val lineNumbers = mutableSetOf<Int>()
        
        // Pattern for "line 123" or "line:123" or ":123:"
        val linePattern = Pattern.compile("""(?:line|line\s*:)\s*(\d+)""", Pattern.CASE_INSENSITIVE)
        val matcher = linePattern.matcher(prompt)
        while (matcher.find()) {
            val lineNum = matcher.group(1)?.toIntOrNull()
            if (lineNum != null && lineNum > 0) {
                lineNumbers.add(lineNum)
            }
        }
        
        // Pattern for "file.js:123" or "file.js:123:45"
        val fileLinePattern = Pattern.compile("""\.(?:js|ts|py|java|kt):(\d+)""")
        val fileLineMatcher = fileLinePattern.matcher(prompt)
        while (fileLineMatcher.find()) {
            val lineNum = fileLineMatcher.group(1)?.toIntOrNull()
            if (lineNum != null && lineNum > 0) {
                lineNumbers.add(lineNum)
            }
        }
        
        return lineNumbers.sorted()
    }
    
    /**
     * Extract function names from prompt
     */
    private fun extractFunctionNames(prompt: String): List<String> {
        val functionNames = mutableSetOf<String>()
        
        // Pattern for "function name" or "functionName"
        val functionPattern = Pattern.compile("""(?:function|method|fn)\s+['"]?(\w+)['"]?""", Pattern.CASE_INSENSITIVE)
        val matcher = functionPattern.matcher(prompt)
        while (matcher.find()) {
            val funcName = matcher.group(1)
            if (funcName != null && funcName.length > 2) {
                functionNames.add(funcName)
            }
        }
        
        // Pattern for "in functionName" or "at functionName"
        val inFunctionPattern = Pattern.compile("""(?:in|at)\s+(\w+)\s*(?:\(|function)""", Pattern.CASE_INSENSITIVE)
        val inFunctionMatcher = inFunctionPattern.matcher(prompt)
        while (inFunctionMatcher.find()) {
            val funcName = inFunctionMatcher.group(1)
            if (funcName != null && funcName.length > 2) {
                functionNames.add(funcName)
            }
        }
        
        return functionNames.toList()
    }
    
    /**
     * Extract severity from prompt
     */
    private fun extractSeverity(prompt: String, lowerPrompt: String): ErrorSeverity? {
        val severityKeywords = mapOf(
            ErrorSeverity.CRITICAL to listOf("critical", "fatal", "crash", "broken", "urgent", "blocking"),
            ErrorSeverity.HIGH to listOf("important", "major", "serious", "significant", "high priority"),
            ErrorSeverity.MEDIUM to listOf("medium", "moderate", "normal"),
            ErrorSeverity.LOW to listOf("minor", "low", "small", "trivial", "cosmetic"),
            ErrorSeverity.INFO to listOf("suggestion", "hint", "tip", "consider")
        )
        
        for ((severity, keywords) in severityKeywords) {
            if (keywords.any { lowerPrompt.contains(it) }) {
                return severity
            }
        }
        
        return null
    }
    
    /**
     * Extract context (what user was doing)
     */
    private fun extractContext(prompt: String): String? {
        val contextPatterns = listOf(
            Pattern.compile("""(?:when|while|during)\s+(?:i\s+)?(?:was\s+)?(?:trying\s+to\s+)?(.+?)(?:\.|,|$)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:i\s+)?(?:was\s+)?(?:trying\s+to\s+)?(.+?)(?:and\s+then|when|but)""", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in contextPatterns) {
            val matcher = pattern.matcher(prompt)
            if (matcher.find()) {
                val context = matcher.group(1)?.trim()
                if (context != null && context.length > 5 && context.length < 100) {
                    return context
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract timeline information
     */
    private fun extractTimeline(prompt: String, lowerPrompt: String): String? {
        val timelinePatterns = listOf(
            Pattern.compile("""(?:started|began|happened)\s+(?:yesterday|today|recently|a\s+week\s+ago|since)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:always|every\s+time|sometimes|occasionally|frequently)""", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in timelinePatterns) {
            val matcher = pattern.matcher(prompt)
            if (matcher.find()) {
                return matcher.group(0)?.trim()
            }
        }
        
        return null
    }
    
    /**
     * Extract affected components
     */
    private fun extractAffectedComponents(prompt: String, lowerPrompt: String): List<String> {
        val components = mutableListOf<String>()
        
        val componentKeywords = listOf(
            "database", "api", "server", "client", "frontend", "backend",
            "router", "controller", "model", "view", "service", "component"
        )
        
        for (keyword in componentKeywords) {
            if (lowerPrompt.contains(keyword)) {
                components.add(keyword)
            }
        }
        
        return components
    }
    
    /**
     * Format extracted error info for display
     */
    fun formatExtractedInfo(info: ExtractedErrorInfo): String {
        return buildString {
            if (info.errorType != null) {
                appendLine("Error Type: ${info.errorType}")
            }
            if (info.errorMessage != null) {
                appendLine("Error Message: ${info.errorMessage}")
            }
            if (info.affectedFiles.isNotEmpty()) {
                appendLine("Affected Files: ${info.affectedFiles.joinToString(", ")}")
            }
            if (info.lineNumbers.isNotEmpty()) {
                appendLine("Line Numbers: ${info.lineNumbers.joinToString(", ")}")
            }
            if (info.functionNames.isNotEmpty()) {
                appendLine("Functions: ${info.functionNames.joinToString(", ")}")
            }
            if (info.severity != null) {
                appendLine("Severity: ${info.severity.name}")
            }
            if (info.context != null) {
                appendLine("Context: ${info.context}")
            }
            if (info.implicitError) {
                appendLine("(Implicit error detected from symptoms)")
            }
        }
    }
}
