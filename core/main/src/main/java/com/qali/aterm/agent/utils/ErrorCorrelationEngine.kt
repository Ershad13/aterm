package com.qali.aterm.agent.utils

import java.io.File
import android.util.Log

/**
 * Error correlation engine that identifies relationships between errors
 * and finds root causes
 */
object ErrorCorrelationEngine {
    
    /**
     * Error correlation result
     */
    data class CorrelationResult(
        val errorGraph: ErrorDependencyGraph,
        val rootCauses: List<RootCause>,
        val errorChains: List<ErrorChain>,
        val suggestedFixOrder: List<String> // Error IDs in suggested fix order
    )
    
    /**
     * Error dependency graph
     */
    data class ErrorDependencyGraph(
        val nodes: List<ErrorNode>,
        val edges: List<ErrorEdge>
    )
    
    /**
     * Error node in graph
     */
    data class ErrorNode(
        val errorId: String,
        val errorLocation: ErrorDetectionUtils.ErrorLocation,
        val errorMessage: String,
        val errorType: String? = null,
        val severity: ErrorSeverity
    )
    
    /**
     * Edge between errors (dependency relationship)
     */
    data class ErrorEdge(
        val fromErrorId: String,
        val toErrorId: String,
        val relationshipType: RelationshipType,
        val confidence: Double = 0.8
    )
    
    /**
     * Relationship types between errors
     */
    enum class RelationshipType {
        FILE_DEPENDENCY,      // Errors in files that depend on each other
        FUNCTION_CALL,        // Errors in function call chain
        IMPORT_DEPENDENCY,    // Errors related to imports
        DATA_FLOW,            // Errors in data flow
        CAUSAL                // One error causes another
    }
    
    /**
     * Root cause analysis
     */
    data class RootCause(
        val errorId: String,
        val description: String,
        val confidence: Double,
        val affectedErrors: List<String>, // IDs of errors caused by this root cause
        val suggestedFix: String? = null
    )
    
    /**
     * Error chain (sequence of related errors)
     */
    data class ErrorChain(
        val chainId: String,
        val errors: List<String>, // Error IDs in chain order
        val chainType: ChainType,
        val description: String
    )
    
    /**
     * Chain type
     */
    enum class ChainType {
        IMPORT_CHAIN,      // Import → Import → Error
        CALL_CHAIN,        // Function → Function → Error
        DEPENDENCY_CHAIN,  // File → File → Error
        CAUSAL_CHAIN       // Error → Error → Error
    }
    
    /**
     * Correlate multiple errors and find root causes
     * 
     * @param errorLocations List of error locations
     * @param errorMessages List of error messages
     * @param workspaceRoot Workspace root directory
     * @return Correlation result with graph, root causes, and chains
     */
    fun correlateErrors(
        errorLocations: List<ErrorDetectionUtils.ErrorLocation>,
        errorMessages: List<String>,
        workspaceRoot: String
    ): CorrelationResult {
        if (errorLocations.isEmpty()) {
            return CorrelationResult(
                errorGraph = ErrorDependencyGraph(emptyList(), emptyList()),
                rootCauses = emptyList(),
                errorChains = emptyList(),
                suggestedFixOrder = emptyList()
            )
        }
        
        // Create error nodes
        val nodes = errorLocations.mapIndexed { index, location ->
            val message = errorMessages.getOrNull(index) ?: "Unknown error"
            ErrorNode(
                errorId = "error_$index",
                errorLocation = location,
                errorMessage = message,
                errorType = extractErrorType(message),
                severity = location.severity ?: ErrorSeverityClassifier.classifySeverity(message)
            )
        }
        
        // Build dependency graph
        val edges = buildDependencyGraph(nodes, workspaceRoot)
        
        val graph = ErrorDependencyGraph(nodes = nodes, edges = edges)
        
        // Find root causes
        val rootCauses = findRootCauses(graph, workspaceRoot)
        
        // Build error chains
        val errorChains = buildErrorChains(graph)
        
        // Suggest fix order (root causes first)
        val suggestedFixOrder = determineFixOrder(graph, rootCauses)
        
        return CorrelationResult(
            errorGraph = graph,
            rootCauses = rootCauses,
            errorChains = errorChains,
            suggestedFixOrder = suggestedFixOrder
        )
    }
    
    /**
     * Build dependency graph between errors
     */
    private fun buildDependencyGraph(
        nodes: List<ErrorNode>,
        workspaceRoot: String
    ): List<ErrorEdge> {
        val edges = mutableListOf<ErrorEdge>()
        
        // Get dependency matrix
        val matrix = CodeDependencyAnalyzer.getDependencyMatrix(workspaceRoot)
        
        for (i in nodes.indices) {
            for (j in nodes.indices) {
                if (i == j) continue
                
                val node1 = nodes[i]
                val node2 = nodes[j]
                
                // Check file dependency
                val file1 = node1.errorLocation.filePath
                val file2 = node2.errorLocation.filePath
                
                val deps1 = matrix.dependencies[file1] ?: emptySet()
                val deps2 = matrix.dependencies[file2] ?: emptySet()
                
                // File dependency relationship
                if (deps1.contains(file2)) {
                    edges.add(
                        ErrorEdge(
                            fromErrorId = node1.errorId,
                            toErrorId = node2.errorId,
                            relationshipType = RelationshipType.FILE_DEPENDENCY,
                            confidence = 0.9
                        )
                    )
                }
                
                // Import dependency
                val metadata1 = matrix.files[file1]
                val metadata2 = matrix.files[file2]
                
                if (metadata1 != null && metadata2 != null) {
                    // Check if file1 imports from file2
                    val importsFrom2 = metadata1.imports.any { importPath ->
                        importPath.contains(File(file2).nameWithoutExtension) ||
                        file2.contains(importPath)
                    }
                    
                    if (importsFrom2) {
                        edges.add(
                            ErrorEdge(
                                fromErrorId = node1.errorId,
                                toErrorId = node2.errorId,
                                relationshipType = RelationshipType.IMPORT_DEPENDENCY,
                                confidence = 0.85
                            )
                        )
                    }
                }
                
                // Same file relationship
                if (file1 == file2 && node1.errorLocation.lineNumber != null && 
                    node2.errorLocation.lineNumber != null) {
                    val line1 = node1.errorLocation.lineNumber
                    val line2 = node2.errorLocation.lineNumber
                    
                    // If errors are close together, likely related
                    if (kotlin.math.abs(line1 - line2) < 10) {
                        edges.add(
                            ErrorEdge(
                                fromErrorId = node1.errorId,
                                toErrorId = node2.errorId,
                                relationshipType = RelationshipType.CAUSAL,
                                confidence = 0.7
                            )
                        )
                    }
                }
                
                // Same error type relationship
                if (node1.errorType != null && node1.errorType == node2.errorType) {
                    edges.add(
                        ErrorEdge(
                            fromErrorId = node1.errorId,
                            toErrorId = node2.errorId,
                            relationshipType = RelationshipType.CAUSAL,
                            confidence = 0.6
                        )
                    )
                }
            }
        }
        
        return edges.distinctBy { "${it.fromErrorId}_${it.toErrorId}" }
    }
    
    /**
     * Find root causes of errors
     */
    private fun findRootCauses(
        graph: ErrorDependencyGraph,
        workspaceRoot: String
    ): List<RootCause> {
        val rootCauses = mutableListOf<RootCause>()
        
        // Find nodes with no incoming edges (potential root causes)
        val nodesWithIncoming = graph.edges.map { it.toErrorId }.toSet()
        val rootNodes = graph.nodes.filter { it.errorId !in nodesWithIncoming }
        
        rootNodes.forEach { node ->
            // Find all errors that depend on this node
            val affectedErrors = graph.edges
                .filter { it.fromErrorId == node.errorId }
                .map { it.toErrorId }
                .toList()
            
            if (affectedErrors.isNotEmpty() || rootNodes.size == 1) {
                val description = buildRootCauseDescription(node, affectedErrors.size)
                val suggestedFix = suggestFixForRootCause(node)
                
                rootCauses.add(
                    RootCause(
                        errorId = node.errorId,
                        description = description,
                        confidence = if (affectedErrors.isNotEmpty()) 0.9 else 0.7,
                        affectedErrors = affectedErrors,
                        suggestedFix = suggestedFix
                    )
                )
            }
        }
        
        // If no clear root causes, identify by severity
        if (rootCauses.isEmpty()) {
            val criticalErrors = graph.nodes.filter { 
                it.severity == ErrorSeverity.CRITICAL || it.severity == ErrorSeverity.HIGH 
            }
            
            criticalErrors.forEach { node ->
                rootCauses.add(
                    RootCause(
                        errorId = node.errorId,
                        description = "High severity error: ${node.errorMessage.take(100)}",
                        confidence = 0.8,
                        affectedErrors = emptyList(),
                        suggestedFix = suggestFixForRootCause(node)
                    )
                )
            }
        }
        
        return rootCauses.sortedByDescending { it.confidence }
    }
    
    /**
     * Build error chains
     */
    private fun buildErrorChains(graph: ErrorDependencyGraph): List<ErrorChain> {
        val chains = mutableListOf<ErrorChain>()
        
        // Build import chains
        val importEdges = graph.edges.filter { it.relationshipType == RelationshipType.IMPORT_DEPENDENCY }
        val importChains = buildChainsFromEdges(importEdges, graph.nodes, ChainType.IMPORT_CHAIN)
        chains.addAll(importChains)
        
        // Build call chains (if function information available)
        val callEdges = graph.edges.filter { it.relationshipType == RelationshipType.FUNCTION_CALL }
        val callChains = buildChainsFromEdges(callEdges, graph.nodes, ChainType.CALL_CHAIN)
        chains.addAll(callChains)
        
        // Build dependency chains
        val depEdges = graph.edges.filter { it.relationshipType == RelationshipType.FILE_DEPENDENCY }
        val depChains = buildChainsFromEdges(depEdges, graph.nodes, ChainType.DEPENDENCY_CHAIN)
        chains.addAll(depChains)
        
        // Build causal chains
        val causalEdges = graph.edges.filter { it.relationshipType == RelationshipType.CAUSAL }
        val causalChains = buildChainsFromEdges(causalEdges, graph.nodes, ChainType.CAUSAL_CHAIN)
        chains.addAll(causalChains)
        
        return chains.distinctBy { it.errors.joinToString("_") }
    }
    
    /**
     * Build chains from edges
     */
    private fun buildChainsFromEdges(
        edges: List<ErrorEdge>,
        nodes: List<ErrorNode>,
        chainType: ChainType
    ): List<ErrorChain> {
        val chains = mutableListOf<ErrorChain>()
        
        // Find chain starts (nodes with no incoming edges in this relationship type)
        val nodesWithIncoming = edges.map { it.toErrorId }.toSet()
        val chainStarts = nodes.filter { it.errorId !in nodesWithIncoming }
        
        chainStarts.forEach { startNode ->
            val chain = mutableListOf<String>()
            chain.add(startNode.errorId)
            
            var currentId = startNode.errorId
            var nextEdge = edges.find { it.fromErrorId == currentId }
            
            while (nextEdge != null && chain.size < 10) { // Limit chain length
                chain.add(nextEdge.toErrorId)
                currentId = nextEdge.toErrorId
                nextEdge = edges.find { it.fromErrorId == currentId }
            }
            
            if (chain.size > 1) {
                val description = when (chainType) {
                    ChainType.IMPORT_CHAIN -> "Import dependency chain"
                    ChainType.CALL_CHAIN -> "Function call chain"
                    ChainType.DEPENDENCY_CHAIN -> "File dependency chain"
                    ChainType.CAUSAL_CHAIN -> "Causal error chain"
                }
                
                chains.add(
                    ErrorChain(
                        chainId = "chain_${chainType.name}_${startNode.errorId}",
                        errors = chain,
                        chainType = chainType,
                        description = description
                    )
                )
            }
        }
        
        return chains
    }
    
    /**
     * Determine fix order based on root causes and dependencies
     */
    private fun determineFixOrder(
        graph: ErrorDependencyGraph,
        rootCauses: List<RootCause>
    ): List<String> {
        val fixOrder = mutableListOf<String>()
        
        // Add root causes first
        rootCauses.forEach { rootCause ->
            if (rootCause.errorId !in fixOrder) {
                fixOrder.add(rootCause.errorId)
            }
        }
        
        // Add errors in dependency order
        val remainingErrors = graph.nodes.map { it.errorId }.filter { it !in fixOrder }
        
        // Topological sort of remaining errors
        val sorted = topologicalSortErrors(remainingErrors, graph.edges)
        fixOrder.addAll(sorted)
        
        return fixOrder
    }
    
    /**
     * Topological sort of errors
     */
    private fun topologicalSortErrors(
        errorIds: List<String>,
        edges: List<ErrorEdge>
    ): List<String> {
        val inDegree = mutableMapOf<String, Int>()
        errorIds.forEach { inDegree[it] = 0 }
        
        edges.forEach { edge ->
            if (edge.toErrorId in errorIds) {
                inDegree[edge.toErrorId] = (inDegree[edge.toErrorId] ?: 0) + 1
            }
        }
        
        val queue = mutableListOf<String>()
        inDegree.forEach { (errorId, degree) ->
            if (degree == 0) {
                queue.add(errorId)
            }
        }
        
        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            result.add(current)
            
            edges.filter { it.fromErrorId == current }.forEach { edge ->
                if (edge.toErrorId in errorIds) {
                    val newDegree = (inDegree[edge.toErrorId] ?: 0) - 1
                    inDegree[edge.toErrorId] = newDegree
                    if (newDegree == 0) {
                        queue.add(edge.toErrorId)
                    }
                }
            }
        }
        
        // Add any remaining errors
        errorIds.forEach { errorId ->
            if (errorId !in result) {
                result.add(errorId)
            }
        }
        
        return result
    }
    
    /**
     * Build root cause description
     */
    private fun buildRootCauseDescription(node: ErrorNode, affectedCount: Int): String {
        return buildString {
            append("Root cause: ${node.errorMessage.take(80)}")
            if (node.errorLocation.filePath.isNotEmpty()) {
                append(" in ${node.errorLocation.filePath}")
            }
            if (node.errorLocation.lineNumber != null) {
                append(" at line ${node.errorLocation.lineNumber}")
            }
            if (affectedCount > 0) {
                append(" (affects $affectedCount other error(s))")
            }
        }
    }
    
    /**
     * Suggest fix for root cause
     */
    private fun suggestFixForRootCause(node: ErrorNode): String? {
        return when {
            node.errorMessage.contains("import", ignoreCase = true) ||
            node.errorMessage.contains("module", ignoreCase = true) ||
            node.errorMessage.contains("require", ignoreCase = true) -> {
                "Check import path and ensure module exists. Verify file paths match import statements."
            }
            node.errorMessage.contains("undefined", ignoreCase = true) -> {
                "Variable or function is undefined. Check if it's declared, imported, or exported correctly."
            }
            node.errorMessage.contains("not a function", ignoreCase = true) -> {
                "API mismatch detected. Check library version and API documentation. May need to use different method name."
            }
            node.errorType == "SyntaxError" -> {
                "Fix syntax error first. Check for missing brackets, quotes, or semicolons."
            }
            node.errorType == "TypeError" -> {
                "Type mismatch. Check variable types and function parameter types."
            }
            else -> null
        }
    }
    
    /**
     * Extract error type from message
     */
    private fun extractErrorType(message: String): String? {
        val errorTypes = listOf(
            "SyntaxError", "TypeError", "ReferenceError", "ImportError",
            "RuntimeError", "CompileError", "NullPointerException",
            "NameError", "AttributeError", "KeyError"
        )
        
        for (type in errorTypes) {
            if (message.contains(type, ignoreCase = true)) {
                return type
            }
        }
        
        return null
    }
    
    /**
     * Format correlation result for display
     */
    fun formatCorrelationResult(result: CorrelationResult): String {
        return buildString {
            appendLine("=== Error Correlation Analysis ===")
            appendLine()
            
            appendLine("Root Causes (${result.rootCauses.size}):")
            result.rootCauses.forEachIndexed { index, rootCause ->
                appendLine("${index + 1}. ${rootCause.description}")
                appendLine("   Confidence: ${(rootCause.confidence * 100).toInt()}%")
                if (rootCause.affectedErrors.isNotEmpty()) {
                    appendLine("   Affects: ${rootCause.affectedErrors.size} error(s)")
                }
                if (rootCause.suggestedFix != null) {
                    appendLine("   Suggested Fix: ${rootCause.suggestedFix}")
                }
                appendLine()
            }
            
            if (result.errorChains.isNotEmpty()) {
                appendLine("Error Chains (${result.errorChains.size}):")
                result.errorChains.take(5).forEachIndexed { index, chain ->
                    appendLine("${index + 1}. ${chain.description} (${chain.errors.size} errors)")
                    appendLine("   Chain: ${chain.errors.joinToString(" → ")}")
                    appendLine()
                }
            }
            
            appendLine("Suggested Fix Order:")
            result.suggestedFixOrder.forEachIndexed { index, errorId ->
                appendLine("${index + 1}. $errorId")
            }
        }
    }
}
