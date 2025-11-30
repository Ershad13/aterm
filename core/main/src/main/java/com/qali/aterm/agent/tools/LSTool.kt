package com.qali.aterm.agent.tools

import com.rk.libcommons.alpineDir
import com.qali.aterm.agent.core.FunctionDeclaration
import com.qali.aterm.agent.core.FunctionParameters
import com.qali.aterm.agent.core.PropertySchema
import com.qali.aterm.agent.utils.CodeDependencyAnalyzer
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LSToolParams(
    val dir_path: String,
    val ignore: List<String>? = null
)

data class LSToolFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: String
)

class LSToolInvocation(
    toolParams: LSToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<LSToolParams, ToolResult> {
    
    override val params: LSToolParams = toolParams
    
    private val resolvedPath: String
        get() = File(workspaceRoot, params.dir_path).absolutePath
    
    override fun getDescription(): String {
        return "Listing directory: ${params.dir_path}"
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return listOf(ToolLocation(resolvedPath))
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Directory listing cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val dir = File(resolvedPath)
        
        if (!dir.exists()) {
            return ToolResult(
                llmContent = "Directory not found: ${params.dir_path}",
                returnDisplay = "Error: Directory not found",
                error = ToolError(
                    message = "Directory not found",
                    type = ToolErrorType.FILE_NOT_FOUND
                )
            )
        }
        
        if (!dir.isDirectory) {
            return ToolResult(
                llmContent = "Path is not a directory: ${params.dir_path}",
                returnDisplay = "Error: Not a directory",
                error = ToolError(
                    message = "Path is not a directory",
                    type = ToolErrorType.INVALID_PARAMETERS
                )
            )
        }
        
        return try {
            val files = dir.listFiles()?.toList() ?: emptyList()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            
            val entries = files
                .filter { file ->
                    // Apply ignore patterns
                    params.ignore?.none { pattern ->
                        file.name.matches(pattern.toRegex())
                    } != false
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { file ->
                    LSToolFileEntry(
                        name = file.name,
                        path = file.absolutePath,
                        isDirectory = file.isDirectory,
                        size = if (file.isFile) file.length() else 0,
                        modifiedTime = dateFormat.format(Date(file.lastModified()))
                    )
                }
            
            val output = buildString {
                appendLine("Directory: ${params.dir_path}")
                appendLine("Total: ${entries.size} items")
                appendLine()
                entries.forEach { entry ->
                    val type = if (entry.isDirectory) "DIR" else "FILE"
                    val size = if (entry.isDirectory) "" else formatSize(entry.size)
                    appendLine("$type  ${entry.name.padEnd(40)} $size  ${entry.modifiedTime}")
                }
            }
            
            // Add dependency matrix information for code files
            val codeFiles = entries.filter { !it.isDirectory && 
                (it.name.endsWith(".js") || it.name.endsWith(".ts") || 
                 it.name.endsWith(".py") || it.name.endsWith(".java") || 
                 it.name.endsWith(".kt")) }
            
            val dependencyInfo = if (codeFiles.isNotEmpty()) {
                try {
                    val matrix = withContext(Dispatchers.IO) {
                        CodeDependencyAnalyzer.getDependencyMatrix(workspaceRoot)
                    }
                    if (matrix.files.isNotEmpty()) {
                        buildString {
                            appendLine("\n[Code Dependency Matrix]")
                            codeFiles.forEach { file ->
                                val relativePath = file.path.removePrefix(workspaceRoot + File.separator)
                                val metadata = matrix.files[relativePath]
                                if (metadata != null) {
                                    appendLine("\nFile: $relativePath")
                                    if (metadata.imports.isNotEmpty()) {
                                        appendLine("  Imports: ${metadata.imports.joinToString(", ")}")
                                    }
                                    if (metadata.exports.isNotEmpty()) {
                                        appendLine("  Exports: ${metadata.exports.joinToString(", ")}")
                                    }
                                    val deps = matrix.dependencies[relativePath]
                                    if (deps != null && deps.isNotEmpty()) {
                                        appendLine("  Depends on: ${deps.joinToString(", ")}")
                                    }
                                    // Find files that depend on this one
                                    val dependents = matrix.dependencies.filter { it.value.contains(relativePath) }.keys
                                    if (dependents.isNotEmpty()) {
                                        appendLine("  Used by: ${dependents.joinToString(", ")}")
                                    }
                                }
                            }
                        }
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    android.util.Log.w("LSTool", "Error getting dependency matrix: ${e.message}")
                    ""
                }
            } else {
                ""
            }
            
            val finalOutput = output + dependencyInfo
            
            updateOutput?.invoke(finalOutput)
            
            ToolResult(
                llmContent = finalOutput,
                returnDisplay = "Listed ${entries.size} items"
            )
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error listing directory: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }
}

class LSTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<LSToolParams, ToolResult>() {
    
    override val name = "ls"
    override val displayName = "ListDirectory"
    override val description = "Lists the contents of a directory, showing files and subdirectories with their metadata."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "dir_path" to PropertySchema(
                type = "string",
                description = "The path to the directory to list."
            ),
            "ignore" to PropertySchema(
                type = "array",
                description = "Optional array of glob patterns to ignore.",
                items = PropertySchema(
                    type = "string",
                    description = "A glob pattern to ignore"
                )
            )
        ),
        required = listOf("dir_path")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: LSToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<LSToolParams, ToolResult> {
        return LSToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): LSToolParams {
        val dirPath = params["dir_path"] as? String
            ?: throw IllegalArgumentException("dir_path is required")
        
        if (dirPath.trim().isEmpty()) {
            throw IllegalArgumentException("dir_path must be non-empty")
        }
        
        val ignore = (params["ignore"] as? List<*>)?.mapNotNull { it as? String }
        
        return LSToolParams(
            dir_path = dirPath,
            ignore = ignore
        )
    }
}
