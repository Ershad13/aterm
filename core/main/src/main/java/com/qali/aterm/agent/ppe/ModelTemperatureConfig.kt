package com.qali.aterm.agent.ppe

import android.util.Log

/**
 * Model Temperature Configuration
 * Adjusts temperature based on model capabilities and task type
 */
object ModelTemperatureConfig {
    
    /**
     * Model capability categories
     */
    enum class ModelCategory {
        PRECISION,      // High precision models (GPT-4, Claude Opus) - lower temperature
        BALANCED,       // Balanced models (GPT-3.5, Claude Sonnet) - medium temperature
        CREATIVE,       // Creative models (Gemini, some open models) - higher temperature
        CODE_FOCUSED,   // Code-focused models - very low temperature
        ANALYSIS        // Analysis-focused models - medium-high temperature
    }
    
    /**
     * Model to category mapping
     */
    private val modelCategories = mapOf(
        // GPT-4 family - precision
        "gpt-4" to ModelCategory.PRECISION,
        "gpt-4-turbo" to ModelCategory.PRECISION,
        "gpt-4o" to ModelCategory.PRECISION,
        "gpt-4o-mini" to ModelCategory.BALANCED,
        
        // GPT-3.5 family - balanced
        "gpt-3.5-turbo" to ModelCategory.BALANCED,
        "gpt-3.5" to ModelCategory.BALANCED,
        
        // Claude family
        "claude-3-opus" to ModelCategory.PRECISION,
        "claude-3-5-sonnet" to ModelCategory.PRECISION,
        "claude-3-sonnet" to ModelCategory.BALANCED,
        "claude-3-haiku" to ModelCategory.BALANCED,
        "claude-3-5-haiku" to ModelCategory.BALANCED,
        
        // Gemini family - creative/balanced
        "gemini-pro" to ModelCategory.CREATIVE,
        "gemini-2.0" to ModelCategory.CREATIVE,
        "gemini-2.5" to ModelCategory.CREATIVE,
        "gemini-1.5" to ModelCategory.CREATIVE,
        "gemini-ultra" to ModelCategory.PRECISION,
        
        // Code-focused models
        "codex" to ModelCategory.CODE_FOCUSED,
        "code-davinci" to ModelCategory.CODE_FOCUSED,
        "code-cushman" to ModelCategory.CODE_FOCUSED,
        
        // Analysis models
        "text-davinci" to ModelCategory.ANALYSIS,
        "text-curie" to ModelCategory.ANALYSIS,
    )
    
    /**
     * Default temperatures by category
     */
    private val defaultTemperatures = mapOf(
        ModelCategory.PRECISION to 0.3,
        ModelCategory.BALANCED to 0.6,
        ModelCategory.CREATIVE to 0.8,
        ModelCategory.CODE_FOCUSED to 0.2,
        ModelCategory.ANALYSIS to 0.7
    )
    
    /**
     * Task-specific temperature adjustments
     */
    enum class TaskType {
        CODE_GENERATION,    // Lower temperature for deterministic code
        CODE_ANALYSIS,       // Medium-low for code understanding
        CREATIVE_WRITING,    // Higher temperature for creativity
        PROBLEM_SOLVING,     // Medium for reasoning
        DATA_ANALYSIS,       // Medium-high for analysis
        DEFAULT              // Use model default
    }
    
    /**
     * Task type adjustments (multipliers)
     */
    private val taskAdjustments = mapOf(
        TaskType.CODE_GENERATION to 0.7,      // Reduce by 30%
        TaskType.CODE_ANALYSIS to 0.85,        // Reduce by 15%
        TaskType.CREATIVE_WRITING to 1.3,      // Increase by 30%
        TaskType.PROBLEM_SOLVING to 1.0,       // No change
        TaskType.DATA_ANALYSIS to 1.1,         // Increase by 10%
        TaskType.DEFAULT to 1.0                // No change
    )
    
    /**
     * Get optimal temperature for a model
     * @param model Model name (e.g., "gpt-4", "gemini-pro")
     * @param taskType Optional task type for fine-tuning
     * @param userTemperature Optional user-specified temperature (if null, uses model default)
     * @return Optimal temperature value
     */
    fun getOptimalTemperature(
        model: String?,
        taskType: TaskType = TaskType.DEFAULT,
        userTemperature: Double? = null
    ): Double {
        // If user explicitly sets temperature, use it (but clamp to reasonable range)
        if (userTemperature != null) {
            return userTemperature.coerceIn(0.0, 2.0)
        }
        
        // Get model category
        val category = getModelCategory(model)
        val baseTemperature = defaultTemperatures[category] ?: 0.7
        
        // Apply task-specific adjustment
        val adjustment = taskAdjustments[taskType] ?: 1.0
        val adjustedTemperature = (baseTemperature * adjustment).coerceIn(0.0, 2.0)
        
        Log.d("ModelTemperatureConfig", "Temperature for model '$model' (category: $category, task: $taskType): $adjustedTemperature (base: $baseTemperature)")
        
        return adjustedTemperature
    }
    
    /**
     * Get model category from model name
     */
    private fun getModelCategory(model: String?): ModelCategory {
        if (model == null) {
            return ModelCategory.BALANCED
        }
        
        val modelLower = model.lowercase()
        
        // Check exact matches first
        modelCategories[modelLower]?.let { return it }
        
        // Check partial matches
        for ((key, category) in modelCategories) {
            if (modelLower.contains(key, ignoreCase = true)) {
                return category
            }
        }
        
        // Default categorization based on common patterns
        return when {
            modelLower.contains("gpt-4") -> ModelCategory.PRECISION
            modelLower.contains("gpt-3.5") -> ModelCategory.BALANCED
            modelLower.contains("claude-3-opus") || modelLower.contains("claude-3-5-sonnet") -> ModelCategory.PRECISION
            modelLower.contains("claude") -> ModelCategory.BALANCED
            modelLower.contains("gemini") -> ModelCategory.CREATIVE
            modelLower.contains("code") -> ModelCategory.CODE_FOCUSED
            modelLower.contains("davinci") -> ModelCategory.ANALYSIS
            else -> ModelCategory.BALANCED // Default fallback
        }
    }
    
    /**
     * Detect task type from user message or context
     */
    fun detectTaskType(userMessage: String, context: String = ""): TaskType {
        val combined = "$userMessage $context".lowercase()
        
        return when {
            // Code generation keywords
            combined.contains(Regex("""(write|create|generate|implement|code|function|class|script).*(code|file|program|application)""")) -> 
                TaskType.CODE_GENERATION
            
            // Code analysis keywords
            combined.contains(Regex("""(analyze|review|debug|fix|error|bug|issue).*(code|file|program)""")) -> 
                TaskType.CODE_ANALYSIS
            
            // Creative writing keywords
            combined.contains(Regex("""(write|create|generate).*(story|article|blog|content|creative|poem|essay)""")) -> 
                TaskType.CREATIVE_WRITING
            
            // Problem solving keywords
            combined.contains(Regex("""(solve|fix|resolve|help|how|why|what|problem|issue)""")) -> 
                TaskType.PROBLEM_SOLVING
            
            // Data analysis keywords
            combined.contains(Regex("""(analyze|analysis|data|statistics|report|summary|insights)""")) -> 
                TaskType.DATA_ANALYSIS
            
            else -> TaskType.DEFAULT
        }
    }
    
    /**
     * Get temperature with automatic task detection
     */
    fun getOptimalTemperatureWithTaskDetection(
        model: String?,
        userMessage: String,
        context: String = "",
        userTemperature: Double? = null
    ): Double {
        val taskType = detectTaskType(userMessage, context)
        return getOptimalTemperature(model, taskType, userTemperature)
    }
}
