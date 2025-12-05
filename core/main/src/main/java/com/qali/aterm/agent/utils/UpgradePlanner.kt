package com.qali.aterm.agent.utils

import com.qali.aterm.agent.core.Content
import com.qali.aterm.agent.core.Part
import com.qali.aterm.agent.ppe.PpeApiClient
import com.qali.aterm.agent.debug.DebugLogger
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * AI-powered upgrade planner that generates structured upgrade plans
 * for user upgrade requests. Works in non-streaming API mode.
 */
object UpgradePlanner {
    
    /**
     * Generate an upgrade plan based on user request and current codebase
     * 
     * @param userMessage The user's upgrade request
     * @param workspaceRoot Root directory of the workspace
     * @param apiClient The API client for AI calls (non-streaming mode)
     * @param chatHistory Optional chat history for context
     * @return Generated upgrade plan
     */
    suspend fun generateUpgradePlan(
        userMessage: String,
        workspaceRoot: String,
        apiClient: PpeApiClient?,
        chatHistory: List<Content> = emptyList()
    ): UpgradePlan? {
        if (apiClient == null) {
            Log.w("UpgradePlanner", "API client not available, cannot generate upgrade plan")
            return null
        }
        
        // Get current codebase structure (respecting .gitignore/.atermignore)
        val fileStructure = getFileStructure(workspaceRoot)
        val codebaseSummary = analyzeCodebase(workspaceRoot)
        
        // Load blueprint or .analysis file if available (for metadata context)
        val projectMetadata = loadProjectMetadata(workspaceRoot)
        
        return try {
            generatePlanWithAI(
                userMessage = userMessage,
                fileStructure = fileStructure,
                codebaseSummary = codebaseSummary,
                projectMetadata = projectMetadata,
                apiClient = apiClient,
                chatHistory = chatHistory
            )
        } catch (e: Exception) {
            Log.e("UpgradePlanner", "Failed to generate upgrade plan: ${e.message}", e)
            null
        }
    }
    
    /**
     * Generate upgrade plan using AI
     */
    private suspend fun generatePlanWithAI(
        userMessage: String,
        fileStructure: String,
        codebaseSummary: String,
        projectMetadata: String?,
        apiClient: PpeApiClient,
        chatHistory: List<Content>
    ): UpgradePlan {
        val prompt = buildString {
            appendLine("You are an expert software architect. Analyze the user's upgrade request and create a comprehensive upgrade plan.")
            appendLine()
            appendLine("User Upgrade Request: \"$userMessage\"")
            appendLine()
            appendLine("## Current Project Tree (excluding .gitignore files)")
            appendLine(fileStructure)
            appendLine()
            appendLine("## Codebase Summary")
            appendLine(codebaseSummary)
            
            // Include project metadata if available (from blueprint or .analysis file)
            if (projectMetadata != null) {
                appendLine()
                appendLine("## Project Metadata & Starting Point")
                appendLine("The following metadata comes from either a blueprint or .analysis file.")
                appendLine("Use this to understand the project's current structure, file purposes, functions, classes, and dependencies.")
                appendLine("This helps you identify the starting point for the upgrade.")
                appendLine()
                appendLine(projectMetadata)
            }
            
            appendLine()
            appendLine("Create a detailed upgrade plan that includes:")
        }
        
        val fullPrompt = prompt + """

1. **Summary**: Brief overview of what the upgrade will accomplish
2. **Files to Modify**: List of existing files that need changes
3. **Files to Create**: List of new files that need to be created
4. **Dependencies to Update**: List of package dependencies that need updating
5. **Execution Steps**: Step-by-step plan for implementing the upgrade
6. **Risk Assessment**: Potential risks and breaking changes
7. **Estimated Effort**: Complexity estimate (Low/Medium/High)

Return your response as a JSON object in this EXACT format (no markdown, just JSON):

{
  "summary": "Brief summary of the upgrade",
  "filesToModify": [
    {
      "filePath": "path/to/file.js",
      "changeType": "MODIFY" | "REFACTOR" | "ADD_FEATURE" | "REMOVE" | "REPLACE",
      "description": "What changes need to be made",
      "affectedFunctions": ["function1", "function2"],
      "affectedClasses": ["Class1"],
      "estimatedLinesChanged": 50
    }
  ],
  "filesToCreate": [
    {
      "filePath": "path/to/newfile.js",
      "fileType": "code" | "config" | "test" | "style" | "template",
      "description": "What this file will do",
      "dependencies": ["path/to/dependency.js"]
    }
  ],
  "dependenciesToUpdate": [
    {
      "packageName": "package-name",
      "currentVersion": "1.0.0" or null,
      "targetVersion": "2.0.0" or null,
      "updateType": "ADD" | "UPDATE" | "REMOVE" | "REPLACE",
      "reason": "Why this dependency needs updating"
    }
  ],
  "executionSteps": [
    {
      "stepNumber": 1,
      "description": "What to do in this step",
      "action": "modify" | "create" | "update" | "test",
      "target": "file path or dependency name",
      "prerequisites": [1, 2]
    }
  ],
  "riskAssessment": {
    "overallRisk": "LOW" | "MEDIUM" | "HIGH" | "CRITICAL",
    "breakingChanges": ["list of potential breaking changes"],
    "potentialIssues": ["list of potential issues"],
    "recommendations": ["list of recommendations"]
  },
  "estimatedEffort": "Low" | "Medium" | "High"
}

IMPORTANT:
- Return ONLY valid JSON, no markdown, no code blocks, no explanations
- Be specific about file paths and changes
- Order execution steps logically (dependencies first)
- Consider all impacts of the upgrade
${if (projectMetadata != null) "- Use the project metadata to understand existing file structures, functions, and dependencies\n- Reference existing function/class names from metadata when planning changes" else ""}

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
            temperature = 0.3, // Lower temperature for more structured planning
            disableTools = true
        )
        
        val response = result.getOrNull()
        if (response == null || response.text.isEmpty()) {
            throw Exception("AI returned empty response for upgrade plan")
        }
        
        // Parse JSON response
        return parseUpgradePlan(response.text)
    }
    
    /**
     * Parse upgrade plan from JSON response
     */
    private fun parseUpgradePlan(jsonText: String): UpgradePlan {
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
        
        val summary = json.optString("summary", "Upgrade plan")
        
        // Parse files to modify
        val filesToModify = mutableListOf<FileChange>()
        val modifyArray = json.optJSONArray("filesToModify")
        if (modifyArray != null) {
            for (i in 0 until modifyArray.length()) {
                val fileObj = modifyArray.getJSONObject(i)
                val changeTypeStr = fileObj.optString("changeType", "MODIFY")
                val changeType = try {
                    ChangeType.valueOf(changeTypeStr)
                } catch (e: Exception) {
                    ChangeType.MODIFY
                }
                
                val affectedFunctions = mutableListOf<String>()
                val funcArray = fileObj.optJSONArray("affectedFunctions")
                if (funcArray != null) {
                    for (j in 0 until funcArray.length()) {
                        affectedFunctions.add(funcArray.getString(j))
                    }
                }
                
                val affectedClasses = mutableListOf<String>()
                val classArray = fileObj.optJSONArray("affectedClasses")
                if (classArray != null) {
                    for (j in 0 until classArray.length()) {
                        affectedClasses.add(classArray.getString(j))
                    }
                }
                
                filesToModify.add(
                    FileChange(
                        filePath = fileObj.optString("filePath", ""),
                        changeType = changeType,
                        description = fileObj.optString("description", ""),
                        affectedFunctions = affectedFunctions,
                        affectedClasses = affectedClasses,
                        estimatedLinesChanged = if (fileObj.has("estimatedLinesChanged")) fileObj.optInt("estimatedLinesChanged") else null
                    )
                )
            }
        }
        
        // Parse files to create
        val filesToCreate = mutableListOf<NewFile>()
        val createArray = json.optJSONArray("filesToCreate")
        if (createArray != null) {
            for (i in 0 until createArray.length()) {
                val fileObj = createArray.getJSONObject(i)
                
                val dependencies = mutableListOf<String>()
                val depsArray = fileObj.optJSONArray("dependencies")
                if (depsArray != null) {
                    for (j in 0 until depsArray.length()) {
                        dependencies.add(depsArray.getString(j))
                    }
                }
                
                filesToCreate.add(
                    NewFile(
                        filePath = fileObj.optString("filePath", ""),
                        fileType = fileObj.optString("fileType", "code"),
                        description = fileObj.optString("description", ""),
                        dependencies = dependencies
                    )
                )
            }
        }
        
        // Parse dependencies to update
        val dependenciesToUpdate = mutableListOf<DependencyUpdate>()
        val depsArray = json.optJSONArray("dependenciesToUpdate")
        if (depsArray != null) {
            for (i in 0 until depsArray.length()) {
                val depObj = depsArray.getJSONObject(i)
                val updateTypeStr = depObj.optString("updateType", "UPDATE")
                val updateType = try {
                    DependencyUpdateType.valueOf(updateTypeStr)
                } catch (e: Exception) {
                    DependencyUpdateType.UPDATE
                }
                
                dependenciesToUpdate.add(
                    DependencyUpdate(
                        packageName = depObj.optString("packageName", ""),
                        currentVersion = depObj.optString("currentVersion", null),
                        targetVersion = depObj.optString("targetVersion", null),
                        updateType = updateType,
                        reason = depObj.optString("reason", "")
                    )
                )
            }
        }
        
        // Parse execution steps
        val executionSteps = mutableListOf<ExecutionStep>()
        val stepsArray = json.optJSONArray("executionSteps")
        if (stepsArray != null) {
            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                
                val prerequisites = mutableListOf<Int>()
                val prereqArray = stepObj.optJSONArray("prerequisites")
                if (prereqArray != null) {
                    for (j in 0 until prereqArray.length()) {
                        prerequisites.add(prereqArray.getInt(j))
                    }
                }
                
                executionSteps.add(
                    ExecutionStep(
                        stepNumber = stepObj.optInt("stepNumber", i + 1),
                        description = stepObj.optString("description", ""),
                        action = stepObj.optString("action", "modify"),
                        target = stepObj.optString("target", ""),
                        prerequisites = prerequisites
                    )
                )
            }
        }
        
        // Parse risk assessment
        val riskAssessment = json.optJSONObject("riskAssessment")?.let { riskObj ->
            val riskLevelStr = riskObj.optString("overallRisk", "MEDIUM")
            val riskLevel = try {
                RiskLevel.valueOf(riskLevelStr)
            } catch (e: Exception) {
                RiskLevel.MEDIUM
            }
            
            val breakingChanges = mutableListOf<String>()
            val breakingArray = riskObj.optJSONArray("breakingChanges")
            if (breakingArray != null) {
                for (i in 0 until breakingArray.length()) {
                    breakingChanges.add(breakingArray.getString(i))
                }
            }
            
            val potentialIssues = mutableListOf<String>()
            val issuesArray = riskObj.optJSONArray("potentialIssues")
            if (issuesArray != null) {
                for (i in 0 until issuesArray.length()) {
                    potentialIssues.add(issuesArray.getString(i))
                }
            }
            
            val recommendations = mutableListOf<String>()
            val recArray = riskObj.optJSONArray("recommendations")
            if (recArray != null) {
                for (i in 0 until recArray.length()) {
                    recommendations.add(recArray.getString(i))
                }
            }
            
            RiskAssessment(
                overallRisk = riskLevel,
                breakingChanges = breakingChanges,
                potentialIssues = potentialIssues,
                recommendations = recommendations
            )
        }
        
        val estimatedEffort = json.optString("estimatedEffort", null)
        
        return UpgradePlan(
            summary = summary,
            filesToModify = filesToModify,
            filesToCreate = filesToCreate,
            dependenciesToUpdate = dependenciesToUpdate,
            executionSteps = executionSteps,
            riskAssessment = riskAssessment,
            estimatedEffort = estimatedEffort
        )
    }
    
    /**
     * Get file structure summary (respecting .gitignore/.atermignore)
     * Includes file metadata: size, type, and basic info
     */
    private fun getFileStructure(workspaceRoot: String): String {
        val workspaceDir = File(workspaceRoot)
        if (!workspaceDir.exists()) {
            return "Workspace does not exist"
        }
        
        val fileList = mutableListOf<String>()
        workspaceDir.walkTopDown().forEach { file ->
            // Respect .atermignore (which includes .gitignore patterns)
            if (file.isFile && !AtermIgnoreManager.shouldIgnoreFile(file, workspaceRoot)) {
                val relativePath = file.relativeTo(workspaceDir).path.replace("\\", "/")
                val fileSize = file.length()
                val fileExtension = file.extension
                val fileType = when {
                    fileExtension.isEmpty() -> "file"
                    fileExtension in listOf("js", "ts", "jsx", "tsx", "py", "java", "kt", "go", "rs", "cpp", "c") -> "code"
                    fileExtension in listOf("json", "yaml", "yml", "toml", "xml") -> "config"
                    fileExtension in listOf("md", "txt", "rst") -> "documentation"
                    fileExtension in listOf("css", "scss", "sass", "less") -> "style"
                    fileExtension in listOf("html", "htm") -> "template"
                    else -> "other"
                }
                
                // Format: path [type, size]
                val sizeStr = when {
                    fileSize < 1024 -> "${fileSize}B"
                    fileSize < 1024 * 1024 -> "${fileSize / 1024}KB"
                    else -> "${fileSize / (1024 * 1024)}MB"
                }
                
                fileList.add("$relativePath [$fileType, $sizeStr]")
            }
        }
        
        return fileList.sorted().joinToString("\n")
    }
    
    /**
     * Load project metadata from blueprint or .analysis file
     * Returns formatted string with project context for AI
     */
    private fun loadProjectMetadata(workspaceRoot: String): String? {
        val workspaceDir = File(workspaceRoot)
        
        // Try to load .analysis file first (preferred - more detailed)
        val analysisFile = File(workspaceDir, ".analysis")
        if (analysisFile.exists() && analysisFile.isFile) {
            try {
                val analysisJson = analysisFile.readText()
                val json = JSONObject(analysisJson)
                
                return buildString {
                    appendLine("### Project Analysis File (.analysis)")
                    appendLine("Project Type: ${json.optString("projectType", "unknown")}")
                    
                    // Structure
                    json.optJSONObject("structure")?.let { structure ->
                        appendLine("\n**Structure:**")
                        appendLine("- Root: ${structure.optString("root", "")}")
                        structure.optJSONArray("entryPoints")?.let { entryPoints ->
                            if (entryPoints.length() > 0) {
                                appendLine("- Entry Points:")
                                for (i in 0 until entryPoints.length()) {
                                    appendLine("  - ${entryPoints.getString(i)}")
                                }
                            }
                        }
                    }
                    
                    // Dependencies
                    json.optJSONObject("dependencies")?.let { deps ->
                        appendLine("\n**Dependencies:**")
                        deps.optJSONArray("runtime")?.let { runtime ->
                            if (runtime.length() > 0) {
                                appendLine("- Runtime: ${(0 until runtime.length()).joinToString(", ") { runtime.getString(it) }}")
                            }
                        }
                    }
                    
                    // Files with metadata
                    json.optJSONArray("files")?.let { filesArray ->
                        if (filesArray.length() > 0) {
                            appendLine("\n**Files with Metadata:**")
                            for (i in 0 until filesArray.length().coerceAtMost(50)) { // Limit to 50 files
                                val fileObj = filesArray.getJSONObject(i)
                                appendLine("- ${fileObj.optString("path", "")} [${fileObj.optString("type", "unknown")}]")
                                
                                // Functions
                                fileObj.optJSONArray("functions")?.let { funcs ->
                                    if (funcs.length() > 0) {
                                        appendLine("  Functions: ${(0 until funcs.length()).joinToString(", ") { funcs.getString(it) }}")
                                    }
                                }
                                
                                // Classes
                                fileObj.optJSONArray("classes")?.let { classes ->
                                    if (classes.length() > 0) {
                                        appendLine("  Classes: ${(0 until classes.length()).joinToString(", ") { classes.getString(it) }}")
                                    }
                                }
                                
                                // Imports
                                fileObj.optJSONArray("imports")?.let { imports ->
                                    if (imports.length() > 0) {
                                        appendLine("  Imports: ${(0 until imports.length().coerceAtMost(5)).joinToString(", ") { imports.getString(it) }}${if (imports.length() > 5) "..." else ""}")
                                    }
                                }
                            }
                            if (filesArray.length() > 50) {
                                appendLine("  ... and ${filesArray.length() - 50} more files")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("UpgradePlanner", "Failed to parse .analysis file: ${e.message}", e)
            }
        }
        
        // Try to load blueprint file (fallback)
        val blueprintFile = File(workspaceDir, "blueprint.json")
        if (blueprintFile.exists() && blueprintFile.isFile) {
            try {
                val blueprintJson = blueprintFile.readText()
                val json = JSONObject(blueprintJson)
                
                return buildString {
                    appendLine("### Project Blueprint File (blueprint.json)")
                    appendLine("Project Type: ${json.optString("projectType", "unknown")}")
                    
                    // Files from blueprint
                    json.optJSONArray("files")?.let { filesArray ->
                        if (filesArray.length() > 0) {
                            appendLine("\n**Files from Blueprint:**")
                            for (i in 0 until filesArray.length().coerceAtMost(50)) {
                                val fileObj = filesArray.getJSONObject(i)
                                appendLine("- ${fileObj.optString("path", "")}")
                                fileObj.optString("description", null)?.let { desc ->
                                    appendLine("  Description: $desc")
                                }
                            }
                            if (filesArray.length() > 50) {
                                appendLine("  ... and ${filesArray.length() - 50} more files")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("UpgradePlanner", "Failed to parse blueprint.json: ${e.message}", e)
            }
        }
        
        return null
    }
    
    /**
     * Analyze codebase for summary
     */
    private fun analyzeCodebase(workspaceRoot: String): String {
        val matrix = CodeDependencyAnalyzer.getDependencyMatrix(workspaceRoot)
        
        return buildString {
            appendLine("Total files: ${matrix.files.size}")
            appendLine("Dependencies: ${matrix.dependencies.size}")
            if (matrix.files.isNotEmpty()) {
                appendLine("\nKey files:")
                matrix.files.keys.take(10).forEach { file ->
                    appendLine("  - $file")
                }
            }
        }
    }
}
