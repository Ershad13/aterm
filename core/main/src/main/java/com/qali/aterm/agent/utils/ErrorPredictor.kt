package com.qali.aterm.agent.utils

import java.io.File
import android.util.Log

/**
 * Error prediction and prevention system
 * Predicts potential errors before they occur and suggests preventive measures
 */
object ErrorPredictor {
    
    /**
     * Predicted error
     */
    data class PredictedError(
        val errorType: String,
        val description: String,
        val filePath: String? = null,
        val lineNumber: Int? = null,
        val confidence: Double, // 0.0 to 1.0
        val riskLevel: RiskLevel,
        val preventiveFix: String? = null,
        val codePattern: String? = null // The pattern that triggered prediction
    )
    
    /**
     * Risk level
     */
    enum class RiskLevel {
        LOW,      // Low risk, minor issue
        MEDIUM,   // Medium risk, may cause problems
        HIGH,     // High risk, likely to cause errors
        CRITICAL  // Critical risk, will definitely cause errors
    }
    
    /**
     * Predict errors in code content
     * 
     * @param content Code content to analyze
     * @param filePath Path to the file
     * @param workspaceRoot Workspace root directory
     * @return List of predicted errors
     */
    fun predictErrors(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        
        // Detect language
        val language = ErrorPatternLibrary.detectLanguage(filePath, content)
        
        // Predict based on language-specific patterns
        when (language) {
            "javascript", "typescript" -> {
                predictions.addAll(predictJavaScriptErrors(content, filePath, workspaceRoot))
            }
            "python" -> {
                predictions.addAll(predictPythonErrors(content, filePath, workspaceRoot))
            }
            "java", "kotlin" -> {
                predictions.addAll(predictJavaKotlinErrors(content, filePath, workspaceRoot))
            }
            else -> {
                predictions.addAll(predictGenericErrors(content, filePath, workspaceRoot))
            }
        }
        
        // Predict import/export issues
        predictions.addAll(predictImportExportIssues(content, filePath, workspaceRoot))
        
        // Predict API mismatches
        predictions.addAll(predictApiMismatches(content, filePath, workspaceRoot))
        
        // Predict based on error history
        predictions.addAll(predictFromHistory(content, filePath, workspaceRoot))
        
        return predictions.sortedByDescending { it.confidence }
    }
    
    /**
     * Predict JavaScript/TypeScript errors
     */
    private fun predictJavaScriptErrors(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            
            // Null pointer risk
            if (line.contains(".") && !line.contains("?.") && !line.contains("??")) {
                val pattern = java.util.regex.Pattern.compile("""(\w+)\.[\w]+""")
                val matcher = pattern.matcher(line)
                while (matcher.find()) {
                    val variable = matcher.group(1)
                    // Check if variable might be null/undefined
                    if (!line.contains("$variable =") && !line.contains("const $variable") && 
                        !line.contains("let $variable") && !line.contains("var $variable")) {
                        predictions.add(
                            PredictedError(
                                errorType = "NullPointerRisk",
                                description = "Potential null/undefined access: $variable",
                                filePath = filePath,
                                lineNumber = lineNum,
                                confidence = 0.6,
                                riskLevel = RiskLevel.MEDIUM,
                                preventiveFix = "Use optional chaining: $variable?.property or nullish coalescing: $variable ?? defaultValue",
                                codePattern = line.trim()
                            )
                        )
                    }
                }
            }
            
            // Type mismatch risk
            if (line.contains("==") && !line.contains("===")) {
                predictions.add(
                    PredictedError(
                        errorType = "TypeMismatchRisk",
                        description = "Loose equality (==) may cause type coercion issues",
                        filePath = filePath,
                        lineNumber = lineNum,
                        confidence = 0.7,
                        riskLevel = RiskLevel.MEDIUM,
                        preventiveFix = "Use strict equality (===) instead of (==)",
                        codePattern = line.trim()
                    )
                )
            }
            
            // Async/await risk
            if (line.contains("await") && !line.contains("async")) {
                val functionStart = findFunctionStart(lines, index)
                if (functionStart != null && !lines[functionStart].contains("async")) {
                    predictions.add(
                        PredictedError(
                            errorType = "AsyncAwaitMismatch",
                            description = "await used in non-async function",
                            filePath = filePath,
                            lineNumber = lineNum,
                            confidence = 0.9,
                            riskLevel = RiskLevel.HIGH,
                            preventiveFix = "Add 'async' keyword to function declaration",
                            codePattern = line.trim()
                        )
                    )
                }
            }
        }
        
        return predictions
    }
    
    /**
     * Predict Python errors
     */
    private fun predictPythonErrors(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            
            // Indentation risk
            if (line.trim().startsWith("def ") || line.trim().startsWith("class ")) {
                val nextLine = lines.getOrNull(index + 1)
                if (nextLine != null && nextLine.isNotEmpty() && 
                    !nextLine.startsWith(" ") && !nextLine.startsWith("\t") && 
                    !nextLine.trim().isEmpty() && !nextLine.trim().startsWith("#")) {
                    predictions.add(
                        PredictedError(
                            errorType = "IndentationError",
                            description = "Missing indentation after definition",
                            filePath = filePath,
                            lineNumber = lineNum + 1,
                            confidence = 0.9,
                            riskLevel = RiskLevel.HIGH,
                            preventiveFix = "Add proper indentation (4 spaces or 1 tab) to the next line",
                            codePattern = line.trim()
                        )
                    )
                }
            }
            
            // NameError risk (undefined variable)
            val varPattern = java.util.regex.Pattern.compile("""\b([a-zA-Z_][a-zA-Z0-9_]*)\b""")
            val matcher = varPattern.matcher(line)
            while (matcher.find()) {
                val varName = matcher.group(1)
                if (!isPythonKeyword(varName) && !line.contains("$varName =") && 
                    !line.contains("def $varName") && !line.contains("import $varName") &&
                    !line.contains("from") && !line.contains("class $varName")) {
                    // Check if variable was defined earlier
                    val wasDefined = lines.take(index).any { 
                        it.contains("$varName =") || it.contains("def $varName") || 
                        it.contains("import $varName") || it.contains("from.*import.*$varName")
                    }
                    if (!wasDefined && index > 0) {
                        predictions.add(
                            PredictedError(
                                errorType = "NameErrorRisk",
                                description = "Variable '$varName' may be undefined",
                                filePath = filePath,
                                lineNumber = lineNum,
                                confidence = 0.5,
                                riskLevel = RiskLevel.MEDIUM,
                                preventiveFix = "Ensure '$varName' is defined or imported before use",
                                codePattern = line.trim()
                            )
                        )
                    }
                }
            }
        }
        
        return predictions
    }
    
    /**
     * Predict Java/Kotlin errors
     */
    private fun predictJavaKotlinErrors(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            
            // Null pointer risk
            if (line.contains(".") && !line.contains("?.") && !line.contains("!!")) {
                val pattern = java.util.regex.Pattern.compile("""(\w+)\.[\w]+""")
                val matcher = pattern.matcher(line)
                while (matcher.find()) {
                    val variable = matcher.group(1)
                    if (!line.contains("$variable = null") && !line.contains("$variable == null")) {
                        predictions.add(
                            PredictedError(
                                errorType = "NullPointerExceptionRisk",
                                description = "Potential null pointer: $variable",
                                filePath = filePath,
                                lineNumber = lineNum,
                                confidence = 0.6,
                                riskLevel = RiskLevel.MEDIUM,
                                preventiveFix = "Add null check: if ($variable != null) { ... } or use safe call: $variable?.method()",
                                codePattern = line.trim()
                            )
                        )
                    }
                }
            }
        }
        
        return predictions
    }
    
    /**
     * Predict generic errors
     */
    private fun predictGenericErrors(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        val lines = content.lines()
        
        // Check for unmatched brackets
        val openBraces = content.count { it == '{' }
        val closeBraces = content.count { it == '}' }
        if (openBraces != closeBraces) {
            predictions.add(
                PredictedError(
                    errorType = "SyntaxError",
                    description = "Unmatched braces: $openBraces open, $closeBraces close",
                    filePath = filePath,
                    confidence = 0.9,
                    riskLevel = RiskLevel.HIGH,
                    preventiveFix = "Match all opening braces { with closing braces }",
                    codePattern = "Brace count mismatch"
                )
            )
        }
        
        return predictions
    }
    
    /**
     * Predict import/export issues
     */
    private fun predictImportExportIssues(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        
        // Extract imports
        val imports = extractImports(content)
        val matrix = CodeDependencyAnalyzer.getDependencyMatrix(workspaceRoot)
        
        imports.forEach { importPath ->
            // Check if import can be resolved
            if (importPath.startsWith("./") || importPath.startsWith("../")) {
                val resolved = resolveImportPath(importPath, filePath, workspaceRoot)
                if (resolved == null) {
                    predictions.add(
                        PredictedError(
                            errorType = "ImportError",
                            description = "Import path may not resolve: $importPath",
                            filePath = filePath,
                            confidence = 0.8,
                            riskLevel = RiskLevel.HIGH,
                            preventiveFix = "Verify import path is correct. Check file exists at: $importPath",
                            codePattern = "import ... from '$importPath'"
                        )
                    )
                }
            }
        }
        
        return predictions
    }
    
    /**
     * Predict API mismatches
     */
    private fun predictApiMismatches(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            // Check for common API mismatch patterns
            ApiMismatchLibrary.mismatchPatterns.forEach { pattern ->
                val regex = java.util.regex.Pattern.compile(pattern.errorPattern, 
                    java.util.regex.Pattern.CASE_INSENSITIVE)
                if (regex.matcher(line).find()) {
                    predictions.add(
                        PredictedError(
                            errorType = pattern.errorType,
                            description = "Potential API mismatch detected",
                            filePath = filePath,
                            lineNumber = index + 1,
                            confidence = 0.7,
                            riskLevel = RiskLevel.HIGH,
                            preventiveFix = pattern.suggestedFix,
                            codePattern = line.trim()
                        )
                    )
                }
            }
        }
        
        return predictions
    }
    
    /**
     * Predict errors based on history
     */
    private fun predictFromHistory(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): List<PredictedError> {
        val predictions = mutableListOf<PredictedError>()
        
        // Load history if not loaded
        ErrorHistoryManager.loadHistory(workspaceRoot)
        
        // Get history for this file
        val fileHistory = ErrorHistoryManager.getHistoryForFile(filePath)
        
        // Check for recurring error patterns
        fileHistory.filter { it.frequency >= 2 }.forEach { entry ->
            // Check if current code might trigger same error
            if (content.contains(entry.errorMessage.take(20), ignoreCase = true)) {
                predictions.add(
                    PredictedError(
                        errorType = entry.errorType ?: "RecurringError",
                        description = "Recurring error pattern detected (occurred ${entry.frequency} times before)",
                        filePath = filePath,
                        lineNumber = entry.lineNumber,
                        confidence = 0.8,
                        riskLevel = RiskLevel.HIGH,
                        preventiveFix = entry.fixApplied ?: entry.userCorrection,
                        codePattern = entry.errorMessage.take(50)
                    )
                )
            }
        }
        
        return predictions
    }
    
    /**
     * Find function start for a given line
     */
    private fun findFunctionStart(lines: List<String>, currentIndex: Int): Int? {
        for (i in currentIndex downTo 0.coerceAtMost(currentIndex - 20)) {
            if (lines[i].contains("function") || lines[i].contains("=>") || 
                lines[i].contains("async") || lines[i].contains("const.*=")) {
                return i
            }
        }
        return null
    }
    
    /**
     * Check if word is Python keyword
     */
    private fun isPythonKeyword(word: String): Boolean {
        val keywords = setOf(
            "def", "class", "if", "else", "elif", "for", "while", "try", "except",
            "finally", "with", "import", "from", "as", "return", "pass", "break",
            "continue", "and", "or", "not", "in", "is", "None", "True", "False"
        )
        return keywords.contains(word.lowercase())
    }
    
    /**
     * Extract imports from code
     */
    private fun extractImports(content: String): List<String> {
        val imports = mutableListOf<String>()
        
        // JavaScript/TypeScript
        val jsPattern = java.util.regex.Pattern.compile("""(?:import|require)\s*\(?['"]([^'"]+)['"]""")
        val jsMatcher = jsPattern.matcher(content)
        while (jsMatcher.find()) {
            jsMatcher.group(1)?.let { imports.add(it) }
        }
        
        // Python
        val pyPattern = java.util.regex.Pattern.compile("""(?:from|import)\s+['"]?([^'"]+)['"]?""")
        val pyMatcher = pyPattern.matcher(content)
        while (pyMatcher.find()) {
            pyMatcher.group(1)?.let { imports.add(it) }
        }
        
        return imports.distinct()
    }
    
    /**
     * Resolve import path
     */
    private fun resolveImportPath(importPath: String, fromFile: String, workspaceRoot: String): File? {
        return try {
            val fromFileObj = File(workspaceRoot, fromFile)
            val importFile = File(fromFileObj.parent, importPath)
            
            // Try various extensions
            val extensions = listOf("", ".js", ".ts", ".jsx", ".tsx", ".py", "/index.js", "/index.ts")
            for (ext in extensions) {
                val candidate = File(importFile.path + ext)
                if (candidate.exists()) {
                    return candidate
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get risk assessment for code changes
     */
    fun assessRisk(
        content: String,
        filePath: String,
        workspaceRoot: String
    ): RiskAssessment {
        val predictions = predictErrors(content, filePath, workspaceRoot)
        
        val criticalRisks = predictions.count { it.riskLevel == RiskLevel.CRITICAL }
        val highRisks = predictions.count { it.riskLevel == RiskLevel.HIGH }
        val mediumRisks = predictions.count { it.riskLevel == RiskLevel.MEDIUM }
        val lowRisks = predictions.count { it.riskLevel == RiskLevel.LOW }
        
        val overallRisk = when {
            criticalRisks > 0 -> RiskLevel.CRITICAL
            highRisks > 2 -> RiskLevel.HIGH
            highRisks > 0 || mediumRisks > 3 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        
        return RiskAssessment(
            overallRisk = overallRisk,
            predictedErrors = predictions,
            criticalCount = criticalRisks,
            highCount = highRisks,
            mediumCount = mediumRisks,
            lowCount = lowRisks
        )
    }
    
    /**
     * Risk assessment result
     */
    data class RiskAssessment(
        val overallRisk: RiskLevel,
        val predictedErrors: List<PredictedError>,
        val criticalCount: Int,
        val highCount: Int,
        val mediumCount: Int,
        val lowCount: Int
    )
}
