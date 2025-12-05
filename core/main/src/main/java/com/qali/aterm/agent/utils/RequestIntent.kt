package com.qali.aterm.agent.utils

/**
 * Represents the intent of a user request
 */
enum class RequestIntent {
    /**
     * User wants to debug/fix an error
     */
    ERROR_DEBUG,
    
    /**
     * User wants to upgrade/enhance the application
     */
    UPGRADE,
    
    /**
     * User wants to both fix an error AND upgrade
     */
    BOTH,
    
    /**
     * Cannot determine intent - needs clarification
     */
    UNKNOWN
}

/**
 * Result of request classification with confidence score
 */
data class RequestClassificationResult(
    val intent: RequestIntent,
    val confidence: Double, // 0.0 to 1.0
    val reasoning: String? = null,
    val errorIndicators: List<String> = emptyList(),
    val upgradeIndicators: List<String> = emptyList()
) {
    /**
     * Check if confidence is high enough to proceed automatically
     */
    fun isHighConfidence(): Boolean = confidence >= 0.8
    
    /**
     * Check if clarification is needed
     */
    fun needsClarification(): Boolean = confidence < 0.5
    
    /**
     * Check if medium confidence (may need user confirmation)
     */
    fun isMediumConfidence(): Boolean = confidence >= 0.5 && confidence < 0.8
}
