package com.qali.aterm.agent.utils

import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.ppe.PpeApiClient
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

/**
 * AI-powered blueprint analyzer that detects potential errors before code generation
 * Analyzes blueprints for import/export mismatches, API incompatibilities, and other issues
 */
object BlueprintAnalyzer {
    
    /**
     * Result of blueprint analysis
     */
    data class BlueprintAnalysisResult(
        val isValid: Boolean,
        val errors: List<BlueprintError>,
        val warnings: List<BlueprintWarning>,
        val suggestions: List<BlueprintSuggestion>,
        val fixedBlueprint: String? = null // JSON blueprint with fixes applied
    )
    
    /**
     * Blueprint error
     */
    data class BlueprintError(
        val type: ErrorType,
        val message: String,
        val filePath: String? = null,
        val lineNumber: Int? = null,
        val severity: String = "HIGH"
    )
    
    /**
     * Blueprint warning
     */
    data class BlueprintWarning(
        val type: WarningType,
        val message: String,
        val filePath: String? = null,
        val suggestion: String? = null
    )
    
    /**
     * Blueprint suggestion
     */
    data class BlueprintSuggestion(
        val type: SuggestionType,
        val message: String,
        val filePath: String? = null,
        val improvement: String? = null
    )
    
    /**
     * Error types
     */
    enum class ErrorType {
        IMPORT_EXPORT_MISMATCH,  // Import doesn't have matching export
        MISSING_DEPENDENCY,       // Dependency file doesn't exist
        CIRCULAR_DEPENDENCY,      // Circular import chain
        API_INCOMPATIBILITY,      // API mismatch detected
        INVALID_FILE_PATH,        // File path is invalid
        DUPLICATE_EXPORT,         // Same export in multiple files
        MISSING_REQUIRED_FILE     // Required file missing
    }
    
    /**
     * Warning types
     */
    enum class WarningType {
        UNUSED_EXPORT,            // Export not used anywhere
        POTENTIAL_ISSUE,           // Potential problem
        OPTIMIZATION_OPPORTUNITY,  // Could be optimized
        VERSION_CONFLICT           // Version conflict possible
    }
    
    /**
     * Suggestion types
     */
    enum class SuggestionType {
        ADD_MISSING_EXPORT,        // Should add export
        FIX_IMPORT_PATH,           // Fix import path
        ADD_DEPENDENCY,            // Add missing dependency
        REORDER_FILES              // Reorder files for better structure
    }
    
    /**
     * Analyze blueprint for potential errors
     * 
     * @param blueprintJson The blueprint JSON string
     * @param apiClient API client for AI analysis
     * @param chatHistory Optional chat history
     * @return Analysis result with errors, warnings, and suggestions
     */
    suspend fun analyzeBlueprint(
        blueprintJson: String,
        apiClient: PpeApiClient?,
        chatHistory: List<Content> = emptyList()
    ): BlueprintAnalysisResult {
        if (apiClient == null) {
            Log.w("BlueprintAnalyzer", "API client not available, performing basic validation only")
            return performBasicValidation(blueprintJson)
        }
        
        return try {
            performAIAnalysis(blueprintJson, apiClient, chatHistory)
        } catch (e: Exception) {
            Log.e("BlueprintAnalyzer", "AI analysis failed: ${e.message}", e)
            performBasicValidation(blueprintJson)
        }
    }
    
    /**
     * Perform AI-powered blueprint analysis
     */
    private suspend fun performAIAnalysis(
        blueprintJson: String,
        apiClient: PpeApiClient,
        chatHistory: List<Content>
    ): BlueprintAnalysisResult {
        val prompt = """
You are an expert code architect. Analyze this project blueprint for potential errors BEFORE code generation.

Blueprint JSON:
$blueprintJson

Analyze the blueprint and identify:

1. **Import/Export Mismatches**: Files that import something that isn't exported anywhere
2. **Missing Dependencies**: Files that depend on files that don't exist in the blueprint
3. **Circular Dependencies**: Circular import chains
4. **API Incompatibilities**: API mismatches (e.g., SQLite vs MySQL, async vs callback)
5. **Invalid File Paths**: File paths that are invalid or problematic
6. **Duplicate Exports**: Same export name in multiple files
7. **Missing Required Files**: Essential files missing (e.g., package.json for Node.js)

For each issue found, provide:
- Type of error/warning
- File path affected
- Description of the issue
- Suggested fix (if applicable)

Return your response as a JSON object in this EXACT format (no markdown, just JSON):

{
  "isValid": true/false,
  "errors": [
    {
      "type": "IMPORT_EXPORT_MISMATCH" | "MISSING_DEPENDENCY" | "CIRCULAR_DEPENDENCY" | "API_INCOMPATIBILITY" | "INVALID_FILE_PATH" | "DUPLICATE_EXPORT" | "MISSING_REQUIRED_FILE",
      "message": "Description of the error",
      "filePath": "path/to/file.js",
      "lineNumber": null or number,
      "severity": "CRITICAL" | "HIGH" | "MEDIUM"
    }
  ],
  "warnings": [
    {
      "type": "UNUSED_EXPORT" | "POTENTIAL_ISSUE" | "OPTIMIZATION_OPPORTUNITY" | "VERSION_CONFLICT",
      "message": "Description of the warning",
      "filePath": "path/to/file.js",
      "suggestion": "Optional suggestion"
    }
  ],
  "suggestions": [
    {
      "type": "ADD_MISSING_EXPORT" | "FIX_IMPORT_PATH" | "ADD_DEPENDENCY" | "REORDER_FILES",
      "message": "Description of the suggestion",
      "filePath": "path/to/file.js",
      "improvement": "What improvement this would make"
    }
  ],
  "fixedBlueprint": null or "JSON blueprint with fixes applied (same format as input)"
}

IMPORTANT:
- Return ONLY valid JSON, no markdown, no code blocks
- Be specific about file paths and issues
- Provide actionable fixes in fixedBlueprint if possible
- Mark isValid as false if there are CRITICAL or HIGH severity errors

JSON Response:
""".trimIndent()
        
        val messages = chatHistory.toMutableList()
        messages.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = prompt))
            )
        )
        
        val result = apiClient.callApi(
            messages = messages,
            temperature = 0.2, // Very low temperature for consistent analysis
            disableTools = true
        )
        
        val response = result.getOrNull()
        if (response == null || response.text.isEmpty()) {
            throw Exception("AI returned empty response for blueprint analysis")
        }
        
        return parseAnalysisResult(response.text, blueprintJson)
    }
    
    /**
     * Parse analysis result from JSON
     */
    private fun parseAnalysisResult(jsonText: String, originalBlueprint: String): BlueprintAnalysisResult {
        var jsonStr = jsonText.trim()
        // Remove markdown code blocks if present
        jsonStr = jsonStr.removePrefix("```json").removePrefix("```").trim()
        jsonStr = jsonStr.removeSuffix("```").trim()
        
        // Extract JSON object
        val jsonStart = jsonStr.indexOf('{')
        val jsonEnd = jsonStr.lastIndexOf('}') + 1
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            jsonStr = jsonStr.substring(jsonStart, jsonEnd)
        }
        
        val json = JSONObject(jsonStr)
        
        val isValid = json.optBoolean("isValid", true)
        
        // Parse errors
        val errors = mutableListOf<BlueprintError>()
        val errorsArray = json.optJSONArray("errors")
        if (errorsArray != null) {
            for (i in 0 until errorsArray.length()) {
                val errorObj = errorsArray.getJSONObject(i)
                val typeStr = errorObj.optString("type", "")
                val errorType = try {
                    ErrorType.valueOf(typeStr)
                } catch (e: Exception) {
                    ErrorType.IMPORT_EXPORT_MISMATCH
                }
                
                errors.add(
                    BlueprintError(
                        type = errorType,
                        message = errorObj.optString("message", ""),
                        filePath = errorObj.optString("filePath", null),
                        lineNumber = if (errorObj.has("lineNumber")) errorObj.optInt("lineNumber") else null,
                        severity = errorObj.optString("severity", "HIGH")
                    )
                )
            }
        }
        
        // Parse warnings
        val warnings = mutableListOf<BlueprintWarning>()
        val warningsArray = json.optJSONArray("warnings")
        if (warningsArray != null) {
            for (i in 0 until warningsArray.length()) {
                val warningObj = warningsArray.getJSONObject(i)
                val typeStr = warningObj.optString("type", "")
                val warningType = try {
                    WarningType.valueOf(typeStr)
                } catch (e: Exception) {
                    WarningType.POTENTIAL_ISSUE
                }
                
                warnings.add(
                    BlueprintWarning(
                        type = warningType,
                        message = warningObj.optString("message", ""),
                        filePath = warningObj.optString("filePath", null),
                        suggestion = warningObj.optString("suggestion", null)
                    )
                )
            }
        }
        
        // Parse suggestions
        val suggestions = mutableListOf<BlueprintSuggestion>()
        val suggestionsArray = json.optJSONArray("suggestions")
        if (suggestionsArray != null) {
            for (i in 0 until suggestionsArray.length()) {
                val suggestionObj = suggestionsArray.getJSONObject(i)
                val typeStr = suggestionObj.optString("type", "")
                val suggestionType = try {
                    SuggestionType.valueOf(typeStr)
                } catch (e: Exception) {
                    SuggestionType.FIX_IMPORT_PATH
                }
                
                suggestions.add(
                    BlueprintSuggestion(
                        type = suggestionType,
                        message = suggestionObj.optString("message", ""),
                        filePath = suggestionObj.optString("filePath", null),
                        improvement = suggestionObj.optString("improvement", null)
                    )
                )
            }
        }
        
        val fixedBlueprint = json.optString("fixedBlueprint", null)
        
        return BlueprintAnalysisResult(
            isValid = isValid,
            errors = errors,
            warnings = warnings,
            suggestions = suggestions,
            fixedBlueprint = fixedBlueprint
        )
    }
    
    /**
     * Perform basic validation (without AI)
     */
    private fun performBasicValidation(blueprintJson: String): BlueprintAnalysisResult {
        val errors = mutableListOf<BlueprintError>()
        val warnings = mutableListOf<BlueprintWarning>()
        
        try {
            val json = JSONObject(blueprintJson)
            val filesArray = json.optJSONArray("files") ?: JSONArray()
            
            if (filesArray.length() == 0) {
                errors.add(
                    BlueprintError(
                        type = ErrorType.MISSING_REQUIRED_FILE,
                        message = "Blueprint has no files",
                        severity = "CRITICAL"
                    )
                )
            }
            
            // Basic validation: check for duplicate paths
            val paths = mutableSetOf<String>()
            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(i)
                val path = fileObj.optString("path", "")
                if (path.isNotEmpty()) {
                    if (paths.contains(path)) {
                        errors.add(
                            BlueprintError(
                                type = ErrorType.INVALID_FILE_PATH,
                                message = "Duplicate file path: $path",
                                filePath = path,
                                severity = "HIGH"
                            )
                        )
                    }
                    paths.add(path)
                }
            }
        } catch (e: Exception) {
            errors.add(
                BlueprintError(
                    type = ErrorType.INVALID_FILE_PATH,
                    message = "Invalid blueprint JSON: ${e.message}",
                    severity = "CRITICAL"
                )
            )
        }
        
        return BlueprintAnalysisResult(
            isValid = errors.isEmpty() || errors.none { it.severity == "CRITICAL" },
            errors = errors,
            warnings = warnings,
            suggestions = emptyList()
        )
    }
}
