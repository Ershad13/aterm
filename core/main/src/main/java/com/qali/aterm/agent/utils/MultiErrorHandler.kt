package com.qali.aterm.agent.utils

import android.util.Log

/**
 * Handles scenarios with multiple simultaneous errors
 * Groups errors, prioritizes fixes, and creates execution plans
 */
object MultiErrorHandler {
    
    /**
     * Grouped errors with priority
     */
    data class ErrorGroup(
        val groupId: String,
        val errors: List<GroupedError>,
        val priority: Int, // Higher = more urgent
        val groupType: GroupType,
        val rootCause: String? = null
    )
    
    /**
     * Error with grouping information
     */
    data class GroupedError(
        val errorLocation: ErrorDetectionUtils.ErrorLocation,
        val errorMessage: String,
        val errorType: String? = null,
        val severity: ErrorSeverity,
        val relatedErrors: List<String> = emptyList() // IDs of related errors
    )
    
    /**
     * Group type
     */
    enum class GroupType {
        BY_FILE,        // Errors in same file
        BY_TYPE,        // Same error type
        BY_SEVERITY,    // Same severity level
        BY_DEPENDENCY,  // Related by dependencies
        BY_FUNCTION     // Errors in same function
    }
    
    /**
     * Multi-error analysis result
     */
    data class MultiErrorAnalysis(
        val errorGroups: List<ErrorGroup>,
        val fixOrder: List<FixOrderItem>,
        val totalErrors: Int,
        val criticalErrors: Int,
        val estimatedTime: String? = null
    )
    
    /**
     * Fix order item
     */
    data class FixOrderItem(
        val stepNumber: Int,
        val groupId: String,
        val description: String,
        val errorsToFix: List<String>, // Error IDs
        val dependencies: List<Int> = emptyList() // Step numbers that must complete first
    )
    
    /**
     * Handle multiple errors
     * 
     * @param errorLocations List of error locations
     * @param errorMessages List of error messages
     * @param workspaceRoot Workspace root directory
     * @return Multi-error analysis with grouped errors and fix order
     */
    fun handleMultipleErrors(
        errorLocations: List<ErrorDetectionUtils.ErrorLocation>,
        errorMessages: List<String>,
        workspaceRoot: String
    ): MultiErrorAnalysis {
        if (errorLocations.isEmpty()) {
            return MultiErrorAnalysis(
                errorGroups = emptyList(),
                fixOrder = emptyList(),
                totalErrors = 0,
                criticalErrors = 0
            )
        }
        
        // Create grouped errors
        val groupedErrors = errorLocations.mapIndexed { index, location ->
            val errorMessage = errorMessages.getOrNull(index) ?: "Unknown error"
            val severity = location.severity ?: ErrorSeverityClassifier.classifySeverity(errorMessage)
            
            GroupedError(
                errorLocation = location,
                errorMessage = errorMessage,
                errorType = extractErrorType(errorMessage),
                severity = severity,
                relatedErrors = emptyList()
            )
        }
        
        // Group errors
        val errorGroups = groupErrors(groupedErrors, workspaceRoot)
        
        // Prioritize groups
        val prioritizedGroups = prioritizeGroups(errorGroups)
        
        // Create fix order
        val fixOrder = createFixOrder(prioritizedGroups)
        
        val criticalErrors = groupedErrors.count { it.severity == ErrorSeverity.CRITICAL || it.severity == ErrorSeverity.HIGH }
        
        return MultiErrorAnalysis(
            errorGroups = prioritizedGroups,
            fixOrder = fixOrder,
            totalErrors = errorLocations.size,
            criticalErrors = criticalErrors,
            estimatedTime = estimateTime(prioritizedGroups)
        )
    }
    
    /**
     * Group errors by various criteria
     */
    private fun groupErrors(
        errors: List<GroupedError>,
        workspaceRoot: String
    ): List<ErrorGroup> {
        val groups = mutableListOf<ErrorGroup>()
        
        // Group by file
        val byFile = errors.groupBy { it.errorLocation.filePath }
        byFile.forEach { (filePath, fileErrors) ->
            if (fileErrors.size > 1) {
                groups.add(
                    ErrorGroup(
                        groupId = "file_${filePath.hashCode()}",
                        errors = fileErrors,
                        priority = calculatePriority(fileErrors),
                        groupType = GroupType.BY_FILE,
                        rootCause = "Multiple errors in same file: $filePath"
                    )
                )
            }
        }
        
        // Group by error type
        val byType = errors.groupBy { it.errorType ?: "unknown" }
        byType.forEach { (errorType, typeErrors) ->
            if (typeErrors.size > 1 && errorType != "unknown") {
                groups.add(
                    ErrorGroup(
                        groupId = "type_${errorType.hashCode()}",
                        errors = typeErrors,
                        priority = calculatePriority(typeErrors),
                        groupType = GroupType.BY_TYPE,
                        rootCause = "Multiple $errorType errors detected"
                    )
                )
            }
        }
        
        // Group by severity
        val bySeverity = errors.groupBy { it.severity }
        bySeverity.forEach { (severity, severityErrors) ->
            if (severityErrors.size > 1 && severity == ErrorSeverity.CRITICAL) {
                groups.add(
                    ErrorGroup(
                        groupId = "severity_${severity.name}",
                        errors = severityErrors,
                        priority = 100, // Highest priority for critical
                        groupType = GroupType.BY_SEVERITY,
                        rootCause = "Multiple ${severity.name} severity errors"
                    )
                )
            }
        }
        
        // If no groups created, create individual groups
        if (groups.isEmpty()) {
            errors.forEachIndexed { index, error ->
                groups.add(
                    ErrorGroup(
                        groupId = "error_$index",
                        errors = listOf(error),
                        priority = ErrorSeverityClassifier.getPriorityScore(error.severity),
                        groupType = GroupType.BY_FILE,
                        rootCause = "Individual error: ${error.errorMessage.take(50)}"
                    )
                )
            }
        }
        
        return groups
    }
    
    /**
     * Calculate priority for error group
     */
    private fun calculatePriority(errors: List<GroupedError>): Int {
        if (errors.isEmpty()) return 0
        
        val maxSeverity = errors.maxOfOrNull { ErrorSeverityClassifier.getPriorityScore(it.severity) } ?: 0
        val errorCount = errors.size
        
        // Priority = max severity * 10 + error count
        return maxSeverity * 10 + errorCount
    }
    
    /**
     * Prioritize error groups
     */
    private fun prioritizeGroups(groups: List<ErrorGroup>): List<ErrorGroup> {
        return groups.sortedByDescending { it.priority }
    }
    
    /**
     * Create fix order based on dependencies
     */
    private fun createFixOrder(groups: List<ErrorGroup>): List<FixOrderItem> {
        val fixOrder = mutableListOf<FixOrderItem>()
        
        groups.forEachIndexed { index, group ->
            // Determine dependencies (groups with same files should be fixed together)
            val dependencies = mutableListOf<Int>()
            groups.forEachIndexed { otherIndex, otherGroup ->
                if (otherIndex < index && 
                    group.errors.any { error1 -> 
                        otherGroup.errors.any { error2 -> 
                            error1.errorLocation.filePath == error2.errorLocation.filePath
                        }
                    }) {
                    dependencies.add(otherIndex + 1) // Step numbers start at 1
                }
            }
            
            fixOrder.add(
                FixOrderItem(
                    stepNumber = index + 1,
                    groupId = group.groupId,
                    description = "Fix ${group.errors.size} error(s) in ${group.groupType.name.lowercase()} group: ${group.rootCause?.take(50)}",
                    errorsToFix = group.errors.mapIndexed { i, _ -> "${group.groupId}_$i" },
                    dependencies = dependencies
                )
            )
        }
        
        return fixOrder
    }
    
    /**
     * Extract error type from message
     */
    private fun extractErrorType(errorMessage: String): String? {
        val errorTypePatterns = listOf(
            "SyntaxError", "TypeError", "ReferenceError", "ImportError",
            "RuntimeError", "CompileError", "NullPointerException",
            "NameError", "AttributeError", "KeyError"
        )
        
        for (type in errorTypePatterns) {
            if (errorMessage.contains(type, ignoreCase = true)) {
                return type
            }
        }
        
        return null
    }
    
    /**
     * Estimate time to fix
     */
    private fun estimateTime(groups: List<ErrorGroup>): String {
        val totalErrors = groups.sumOf { it.errors.size }
        val criticalCount = groups.count { it.priority >= 100 }
        
        return when {
            criticalCount > 5 -> "2-4 hours (many critical errors)"
            totalErrors > 20 -> "1-2 hours (many errors)"
            totalErrors > 10 -> "30-60 minutes"
            totalErrors > 5 -> "15-30 minutes"
            else -> "5-15 minutes"
        }
    }
    
    /**
     * Format multi-error analysis for display
     */
    fun formatAnalysis(analysis: MultiErrorAnalysis): String {
        return buildString {
            appendLine("=== Multi-Error Analysis ===")
            appendLine("Total Errors: ${analysis.totalErrors}")
            appendLine("Critical Errors: ${analysis.criticalErrors}")
            if (analysis.estimatedTime != null) {
                appendLine("Estimated Fix Time: ${analysis.estimatedTime}")
            }
            appendLine()
            
            appendLine("Error Groups (${analysis.errorGroups.size}):")
            analysis.errorGroups.forEachIndexed { index, group ->
                appendLine("${index + 1}. ${group.groupType.name} - ${group.errors.size} error(s) - Priority: ${group.priority}")
                if (group.rootCause != null) {
                    appendLine("   Root Cause: ${group.rootCause}")
                }
            }
            appendLine()
            
            appendLine("Fix Order:")
            analysis.fixOrder.forEach { item ->
                appendLine("Step ${item.stepNumber}: ${item.description}")
                if (item.dependencies.isNotEmpty()) {
                    appendLine("   Depends on: Steps ${item.dependencies.joinToString(", ")}")
                }
            }
        }
    }
}
