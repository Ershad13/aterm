package com.qali.aterm.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rk.settings.Preference

enum class ApiProviderType(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GOOGLE("Google Gemini"),
    COHERE("Cohere"),
    MISTRAL("Mistral AI"),
    CUSTOM("Custom")
}

data class ApiKey(
    val id: String = java.util.UUID.randomUUID().toString(),
    val key: String,
    val label: String = "",
    val isActive: Boolean = true
)

data class ApiProvider(
    val type: ApiProviderType,
    val apiKeys: MutableList<ApiKey> = mutableListOf(),
    val model: String = "",
    val baseUrl: String = "", // Base URL for custom provider
    val config: ProviderConfig? = null, // LLM parameters - null-safe with default fallback
    val isActive: Boolean = true
) {
    fun getActiveKeys(): List<ApiKey> = apiKeys.filter { it.isActive }
    
    // Get config with safe default
    fun getSafeConfig(model: String = ""): ProviderConfig {
        return config ?: ProviderConfig.getModelDefaults(model.ifBlank { "gemini-2.5-flash-lite" })
    }
}

object ApiProviderManager {
    private val gson = Gson()
    
    // Current provider
    var selectedProvider: ApiProviderType
        get() {
            val providerName = Preference.getString("api_provider_selected", ApiProviderType.OPENAI.name)
            return try {
                ApiProviderType.valueOf(providerName)
            } catch (e: Exception) {
                ApiProviderType.OPENAI
            }
        }
        set(value) {
            Preference.setString("api_provider_selected", value.name)
        }
    
    // Get all providers with their keys
    fun getProviders(): Map<ApiProviderType, ApiProvider> {
        val providersJson = Preference.getString("api_providers", "{}")
        return try {
            val type = object : TypeToken<Map<String, ApiProvider>>() {}.type
            val providersMap: Map<String, ApiProvider> = gson.fromJson(providersJson, type)
            providersMap.mapKeys { ApiProviderType.valueOf(it.key) }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    // Save providers
    fun saveProviders(providers: Map<ApiProviderType, ApiProvider>) {
        val providersMap = providers.mapKeys { it.key.name }
        val json = gson.toJson(providersMap)
        Preference.setString("api_providers", json)
    }
    
    // Get current provider with keys - always returns provider with non-null config
    fun getCurrentProvider(): ApiProvider {
        val providers = getProviders()
        val defaultModel = getDefaultModel(selectedProvider)
        val provider = providers[selectedProvider] ?: ApiProvider(
            selectedProvider, 
            model = defaultModel,
            config = ProviderConfig.getModelDefaults(defaultModel)
        )
        // Ensure config is never null
        return if (provider.config == null) {
            provider.copy(config = ProviderConfig.getModelDefaults(provider.model.ifBlank { defaultModel }))
        } else {
            provider
        }
    }
    
    // Get model for current provider - always returns non-null string
    fun getCurrentModel(): String {
        val provider = getCurrentProvider()
        val model = provider.model ?: ""
        return if (model.isNotBlank()) {
            model
        } else {
            getDefaultModel(selectedProvider)
        }
    }
    
    // Get current config with safe defaults - always returns non-null ProviderConfig
    fun getCurrentConfig(): ProviderConfig {
        val provider = getCurrentProvider()
        val model = getCurrentModel()
        // Use safe config getter with fallback
        return provider.getSafeConfig(model)
    }
    
    // Set config for current provider - never accepts null
    fun setCurrentConfig(config: ProviderConfig?) {
        val safeConfig = config ?: ProviderConfig.getModelDefaults(getCurrentModel())
        val providers = getProviders().toMutableMap()
        val provider = providers.getOrPut(selectedProvider) { 
            ApiProvider(selectedProvider, model = getDefaultModel(selectedProvider))
        }
        providers[selectedProvider] = provider.copy(config = safeConfig)
        saveProviders(providers)
    }
    
    // Get temperature with safe default - always returns valid float
    fun getCurrentTemperature(): Float {
        val config = getCurrentConfig()
        return config.temperature
    }
    
    // Set temperature - never accepts null
    fun setCurrentTemperature(temperature: Float?) {
        val safeTemp = ProviderConfig.clampTemperature(temperature ?: 0.7f)
        val config = getCurrentConfig().copy(
            temperature = safeTemp,
            userOverridden = true
        )
        setCurrentConfig(config)
    }
    
    // Get maxTokens with safe default - always returns valid int
    fun getCurrentMaxTokens(): Int {
        val model = getCurrentModel()
        val config = getCurrentConfig()
        return ProviderConfig.clampMaxTokens(model, config.maxTokens)
    }
    
    // Set maxTokens - never accepts null
    fun setCurrentMaxTokens(maxTokens: Int?) {
        val model = getCurrentModel()
        val safeTokens = maxTokens ?: 2048
        val config = getCurrentConfig().copy(
            maxTokens = ProviderConfig.clampMaxTokens(model, safeTokens),
            userOverridden = true
        )
        setCurrentConfig(config)
    }
    
    // Get topP with safe default - always returns valid float
    fun getCurrentTopP(): Float {
        val config = getCurrentConfig()
        return config.topP
    }
    
    // Set topP - never accepts null
    fun setCurrentTopP(topP: Float?) {
        val safeTopP = (topP ?: 1.0f).coerceIn(0.0f, 1.0f)
        val config = getCurrentConfig().copy(
            topP = safeTopP,
            userOverridden = true
        )
        setCurrentConfig(config)
    }
    
    // Set model for current provider - never accepts null
    // Automatically updates config with model-specific defaults unless user overrode
    fun setCurrentModel(model: String?) {
        val safeModel = model ?: ""
        val providers = getProviders().toMutableMap()
        val provider = providers.getOrPut(selectedProvider) { ApiProvider(selectedProvider) }
        
        // Get current config safely (handle null case)
        val currentConfig = provider.getSafeConfig(safeModel)
        
        // If user hasn't overridden config, apply model defaults
        val newConfig = if (!currentConfig.userOverridden) {
            ProviderConfig.getModelDefaults(safeModel)
        } else {
            currentConfig // Keep user's settings
        }
        
        providers[selectedProvider] = provider.copy(
            model = safeModel,
            config = newConfig
        )
        saveProviders(providers)
    }
    
    // Get base URL for current provider - always returns non-null string
    fun getCurrentBaseUrl(): String {
        val provider = getCurrentProvider()
        return provider.baseUrl ?: ""
    }
    
    // Set base URL for current provider
    fun setCurrentBaseUrl(baseUrl: String) {
        val safeBaseUrl = baseUrl ?: ""
        val providers = getProviders().toMutableMap()
        val provider = providers.getOrPut(selectedProvider) { ApiProvider(selectedProvider) }
        providers[selectedProvider] = provider.copy(baseUrl = safeBaseUrl)
        saveProviders(providers)
    }
    
    // Get default model for provider type
    fun getDefaultModel(providerType: ApiProviderType): String {
        return when (providerType) {
            ApiProviderType.OPENAI -> "gpt-4"
            ApiProviderType.ANTHROPIC -> "claude-3-5-sonnet-20241022"
            ApiProviderType.GOOGLE -> "gemini-2.0-flash-exp" // Supports web search
            ApiProviderType.COHERE -> "command-r-plus"
            ApiProviderType.MISTRAL -> "mistral-large-latest"
            ApiProviderType.CUSTOM -> ""
        }
    }
    
    // Add API key to provider
    fun addApiKey(providerType: ApiProviderType, apiKey: ApiKey) {
        val providers = getProviders().toMutableMap()
        val provider = providers.getOrPut(providerType) { ApiProvider(providerType) }
        provider.apiKeys.add(apiKey)
        providers[providerType] = provider
        saveProviders(providers)
    }
    
    // Remove API key
    fun removeApiKey(providerType: ApiProviderType, keyId: String) {
        val providers = getProviders().toMutableMap()
        providers[providerType]?.apiKeys?.removeAll { it.id == keyId }
        saveProviders(providers)
    }
    
    // Update API key
    fun updateApiKey(providerType: ApiProviderType, apiKey: ApiKey) {
        val providers = getProviders().toMutableMap()
        val provider = providers.getOrPut(providerType) { ApiProvider(providerType) }
        val index = provider.apiKeys.indexOfFirst { it.id == apiKey.id }
        if (index >= 0) {
            provider.apiKeys[index] = apiKey
        }
        providers[providerType] = provider
        saveProviders(providers)
    }
    
    // Get next API key (for rotation)
    private var currentKeyIndex = 0
    private var lastProviderType: ApiProviderType? = null
    
    fun getNextApiKey(): String? {
        val provider = getCurrentProvider()
        val activeKeys = provider.getActiveKeys()
        
        // Reset index if provider changed
        if (lastProviderType != selectedProvider) {
            currentKeyIndex = 0
            lastProviderType = selectedProvider
        }
        
        if (activeKeys.isEmpty()) {
            return null
        }
        
        val key = activeKeys[currentKeyIndex % activeKeys.size]
        currentKeyIndex = (currentKeyIndex + 1) % activeKeys.size
        return key.key ?: null
    }
    
    // Reset to first key (call this when starting a new request cycle)
    fun resetKeyRotation() {
        currentKeyIndex = 0
    }
    
    // Check if error is rate limit or service unavailable related (retryable)
    fun isRateLimitError(error: Throwable?): Boolean {
        if (error == null) return false
        val message = error.message?.lowercase() ?: ""
        return message.contains("rate limit") ||
               message.contains("rpm") ||
               message.contains("rpd") ||
               message.contains("429") ||
               message.contains("503") ||
               message.contains("unavailable") ||
               message.contains("overloaded") ||
               message.contains("quota") ||
               message.contains("too many requests")
    }
    
    // Make API call with automatic retry on rate limit
    suspend fun <T> makeApiCallWithRetry(
        maxRetries: Int = 10,
        call: suspend (String) -> Result<T>
    ): Result<T> {
        resetKeyRotation()
        var lastError: Throwable? = null
        val provider = getCurrentProvider()
        val activeKeys = provider.getActiveKeys()
        
        if (activeKeys.isEmpty()) {
            return Result.failure(Exception("No API keys configured for ${selectedProvider.displayName}"))
        }
        
        // Try each key once
        for (key in activeKeys) {
            val result = call(key.key)
            
            if (result.isSuccess) {
                return result
            }
            
            lastError = result.exceptionOrNull()
            
            // If it's a rate limit error, try next key
            if (isRateLimitError(lastError)) {
                continue
            }
            
            // For non-rate-limit errors, return immediately
            return result
        }
        
        // All keys exhausted with rate limit errors
        return Result.failure(
            KeysExhaustedException(
                "All API keys are exhausted for ${selectedProvider.displayName}",
                lastError
            )
        )
    }
    
    // Exception for when all keys are exhausted
    class KeysExhaustedException(
        message: String,
        val originalError: Throwable? = null
    ) : Exception(message, originalError)
}
