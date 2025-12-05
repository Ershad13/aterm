package com.qali.aterm.agent.utils

import java.io.File
import android.util.Log

/**
 * Enhanced error context extraction
 * Extracts comprehensive context around errors for better debugging
 */
object ErrorContextExtractor {
    
    /**
     * Error context with surrounding code and metadata
     */
    data class ErrorContext(
        val filePath: String,
        val lineNumber: Int,
        val columnNumber: Int? = null,
        val surroundingCode: CodeContext,
        val variableState: List<VariableInfo> = emptyList(),
        val callStack: List<CallStackFrame> = emptyList(),
        val imports: List<String> = emptyList(),
        val exports: List<String> = emptyList(),
        val dependencies: List<String> = emptyList()
    )
    
    /**
     * Code context (lines before and after error)
     */
    data class CodeContext(
        val linesBefore: List<String>,
        val errorLine: String,
        val linesAfter: List<String>,
        val totalContextLines: Int
    )
    
    /**
     * Variable information
     */
    data class VariableInfo(
        val name: String,
        val type: String? = null,
        val value: String? = null,
        val scope: String? = null
    )
    
    /**
     * Call stack frame
     */
    data class CallStackFrame(
        val functionName: String?,
        val filePath: String,
        val lineNumber: Int?,
        val columnNumber: Int? = null
    )
    
    /**
     * Extract comprehensive error context
     * 
     * @param filePath Path to the file with error
     * @param lineNumber Line number where error occurred
     * @param columnNumber Optional column number
     * @param contextLines Number of lines before/after to include (default: 5)
     * @param workspaceRoot Root directory of workspace
     * @return Error context with surrounding code and metadata
     */
    fun extractContext(
        filePath: String,
        lineNumber: Int,
        columnNumber: Int? = null,
        contextLines: Int = 5,
        workspaceRoot: String
    ): ErrorContext? {
        val file = File(workspaceRoot, filePath)
        if (!file.exists() || !file.isFile) {
            Log.w("ErrorContextExtractor", "File not found: $filePath")
            return null
        }
        
        return try {
            val content = file.readText()
            val lines = content.lines()
            
            if (lineNumber < 1 || lineNumber > lines.size) {
                Log.w("ErrorContextExtractor", "Line number out of range: $lineNumber (file has ${lines.size} lines)")
                return null
            }
            
            // Extract surrounding code
            val startLine = (lineNumber - contextLines - 1).coerceAtLeast(0)
            val endLine = (lineNumber + contextLines).coerceAtMost(lines.size)
            
            val linesBefore = lines.subList(startLine, lineNumber - 1)
            val errorLine = lines[lineNumber - 1]
            val linesAfter = lines.subList(lineNumber, endLine)
            
            val codeContext = CodeContext(
                linesBefore = linesBefore,
                errorLine = errorLine,
                linesAfter = linesAfter,
                totalContextLines = contextLines * 2 + 1
            )
            
            // Extract imports and exports using CodeDependencyAnalyzer
            val metadata = CodeDependencyAnalyzer.analyzeFile(filePath, content, workspaceRoot)
            
            // Extract variable state (simplified - would need AST parsing for full implementation)
            val variableState = extractVariableState(lines, lineNumber, contextLines)
            
            // Extract call stack from error line context
            val callStack = extractCallStack(lines, lineNumber, contextLines)
            
            ErrorContext(
                filePath = filePath,
                lineNumber = lineNumber,
                columnNumber = columnNumber,
                surroundingCode = codeContext,
                variableState = variableState,
                callStack = callStack,
                imports = metadata.imports,
                exports = metadata.exports,
                dependencies = metadata.imports // Dependencies are imports
            )
        } catch (e: Exception) {
            Log.e("ErrorContextExtractor", "Failed to extract context: ${e.message}", e)
            null
        }
    }
    
    /**
     * Extract variable state from code (simplified implementation)
     */
    private fun extractVariableState(
        lines: List<String>,
        errorLine: Int,
        contextLines: Int
    ): List<VariableInfo> {
        val variables = mutableListOf<VariableInfo>()
        val startLine = (errorLine - contextLines - 1).coerceAtLeast(0)
        val endLine = (errorLine + contextLines).coerceAtMost(lines.size)
        
        // Simple pattern matching for variable declarations
        val varPattern = java.util.regex.Pattern.compile("""(?:const|let|var|val)\s+(\w+)\s*[=:]\s*(.+)""")
        
        for (i in startLine until endLine) {
            val line = lines[i]
            val matcher = varPattern.matcher(line)
            if (matcher.find()) {
                val varName = matcher.group(1)
                val varValue = matcher.group(2)?.trim()?.take(50) // Limit value length
                if (varName != null) {
                    variables.add(
                        VariableInfo(
                            name = varName,
                            value = varValue,
                            scope = if (i < errorLine) "before error" else "after error"
                        )
                    )
                }
            }
        }
        
        return variables
    }
    
    /**
     * Extract call stack from code context
     */
    private fun extractCallStack(
        lines: List<String>,
        errorLine: Int,
        contextLines: Int
    ): List<CallStackFrame> {
        val stack = mutableListOf<CallStackFrame>()
        val startLine = (errorLine - contextLines - 1).coerceAtLeast(0)
        val endLine = (errorLine + contextLines).coerceAtMost(lines.size)
        
        // Look for function calls in context
        val functionCallPattern = java.util.regex.Pattern.compile("""(\w+)\s*\([^)]*\)""")
        
        for (i in startLine until endLine) {
            val line = lines[i]
            val matcher = functionCallPattern.matcher(line)
            while (matcher.find()) {
                val funcName = matcher.group(1)
                if (funcName != null && !isKeyword(funcName)) {
                    stack.add(
                        CallStackFrame(
                            functionName = funcName,
                            filePath = "", // Would need to track file path
                            lineNumber = i + 1
                        )
                    )
                }
            }
        }
        
        return stack.distinctBy { it.functionName }
    }
    
    /**
     * Check if word is a keyword
     */
    private fun isKeyword(word: String): Boolean {
        val keywords = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "return",
            "break", "continue", "try", "catch", "finally", "new", "this"
        )
        return keywords.contains(word.lowercase())
    }
    
    /**
     * Format error context for display
     */
    fun formatContext(context: ErrorContext): String {
        return buildString {
            appendLine("=== Error Context ===")
            appendLine("File: ${context.filePath}")
            appendLine("Line: ${context.lineNumber}")
            if (context.columnNumber != null) {
                appendLine("Column: ${context.columnNumber}")
            }
            appendLine()
            
            appendLine("--- Surrounding Code ---")
            context.surroundingCode.linesBefore.forEachIndexed { index, line ->
                val lineNum = context.lineNumber - context.surroundingCode.linesBefore.size + index
                appendLine("$lineNum: $line")
            }
            appendLine("${context.lineNumber}: ${context.surroundingCode.errorLine} <-- ERROR")
            context.surroundingCode.linesAfter.forEachIndexed { index, line ->
                val lineNum = context.lineNumber + index + 1
                appendLine("$lineNum: $line")
            }
            appendLine()
            
            if (context.variableState.isNotEmpty()) {
                appendLine("--- Variables in Context ---")
                context.variableState.forEach { variable ->
                    append("  ${variable.name}")
                    if (variable.value != null) {
                        append(" = ${variable.value}")
                    }
                    if (variable.scope != null) {
                        append(" (${variable.scope})")
                    }
                    appendLine()
                }
                appendLine()
            }
            
            if (context.imports.isNotEmpty()) {
                appendLine("--- Imports ---")
                context.imports.forEach { import ->
                    appendLine("  $import")
                }
                appendLine()
            }
            
            if (context.exports.isNotEmpty()) {
                appendLine("--- Exports ---")
                context.exports.forEach { export ->
                    appendLine("  $export")
                }
            }
        }
    }
}
