package com.qali.aterm.agent.client

import com.qali.aterm.agent.client.api.ApiResponseParser
import com.qali.aterm.agent.core.FunctionCall
import com.qali.aterm.agent.client.AgentEvent
import com.qali.aterm.agent.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ollama Client for local LLM inference
 * Now supports ChatGPT Python Script API (Ollama-compatible format)
 */
class OllamaClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String,
    private val baseUrl: String = "http://localhost:11434", // Default Ollama port
    private val model: String = "gptfree", // Changed default to match your script
    private val apiKey: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // Increased for ChatGPT responses
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val chatHistory = mutableListOf<Map<String, Any>>()
    
    /**
     * Send a message and get streaming response
     * 
     * This function returns a Flow that runs entirely on the IO dispatcher to avoid
     * NetworkOnMainThreadException. The flow builder itself is moved to IO using flowOn(),
     * ensuring all network operations happen off the main thread.
     */
    suspend fun sendMessage(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<AgentEvent> = flow {
        android.util.Log.d("OllamaClient", "Sending message to ChatGPT Python Script API")
        
        // Add user message to history (thread-safe operation on mutable list)
        synchronized(chatHistory) {
            chatHistory.add(mapOf("role" to "user", "content" to userMessage))
        }
        
        try {
            // Build request body in Ollama format (which your Python script supports)
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    // Access chat history in synchronized block
                    synchronized(chatHistory) {
                        chatHistory.forEach { msg ->
                            put(JSONObject().apply {
                                put("role", msg["role"])
                                put("content", msg["content"])
                            })
                        }
                    }
                })
                put("stream", false) // Python script returns non-streaming responses
                
                // Optional: Add tool support if available
                val tools = toolRegistry.getAvailableTools()
                if (tools.isNotEmpty()) {
                    val toolsArray = JSONArray()
                    tools.forEach { tool ->
                        val toolObj = JSONObject().apply {
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description ?: "")
                                // Add parameters if available
                                tool.parameters?.let { params ->
                                    put("parameters", JSONObject(params))
                                }
                            })
                        }
                        toolsArray.put(toolObj)
                    }
                    put("tools", toolsArray)
                }
            }
            
            android.util.Log.d("OllamaClient", "Request body: ${requestBody.toString().take(500)}...")
            
            val requestBuilder = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            
            // Add API key if provided
            apiKey?.let { key ->
                requestBuilder.addHeader("Authorization", "Bearer $key")
                // Also try API-Key header for compatibility
                requestBuilder.addHeader("API-Key", key)
            }
            
            // Add X-Requested-With header for CORS support
            requestBuilder.addHeader("X-Requested-With", "XMLHttpRequest")
            
            val request = requestBuilder.build()
            
            // Execute network call - flow is already on IO dispatcher due to flowOn()
            android.util.Log.d("OllamaClient", "Executing request to ${request.url}")
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = try {
                        response.body?.string() ?: "Unknown error"
                    } catch (e: Exception) {
                        "Failed to read error body: ${e.message}"
                    }
                    android.util.Log.e("OllamaClient", "API error ${response.code}: $errorBody")
                    emit(AgentEvent.Error("ChatGPT API error: ${response.code} - $errorBody"))
                    return@flow
                }
                
                response.body?.string()?.let { responseBody ->
                    android.util.Log.d("OllamaClient", "Response received (length: ${responseBody.length})")
                    android.util.Log.d("OllamaClient", "Response: ${responseBody.take(500)}...")
                    
                    // Parse the response
                    val toolCallsToExecute = mutableListOf<Triple<FunctionCall, ToolResult, String>>()
                    
                    // Parse using the ChatGPT Python Script parser
                    val finishReason = ApiResponseParser.parseChatGPTPythonResponse(
                        responseBody,
                        { chunk ->
                            android.util.Log.d("OllamaClient", "Emitting chunk: ${chunk.take(100)}...")
                            // Emit chunk as event
                            emit(AgentEvent.Chunk(chunk))
                        },
                        onToolCall,
                        onToolResult,
                        toolCallsToExecute
                    ) { json ->
                        // Convert JSONObject to Map<String, Any>
                        val map = mutableMapOf<String, Any>()
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = json.get(key)
                            map[key] = value
                        }
                        map
                    }
                    
                    // If we have tool calls to execute, handle them
                    if (toolCallsToExecute.isNotEmpty()) {
                        android.util.Log.d("OllamaClient", "Processing ${toolCallsToExecute.size} tool calls")
                        
                        for ((functionCall, toolResult, callId) in toolCallsToExecute) {
                            // Execute tool
                            val tool = toolRegistry.getTool(functionCall.name)
                            if (tool != null) {
                                try {
                                    val result = tool.execute(
                                        functionCall.args,
                                        workspaceRoot,
                                        toolResult.llmContent,
                                        toolResult.returnDisplay
                                    )
                                    
                                    // Emit tool result event
                                    emit(AgentEvent.ToolResult(callId, result.llmContent))
                                    
                                    // Add tool result to history for follow-up
                                    synchronized(chatHistory) {
                                        chatHistory.add(mapOf(
                                            "role" to "tool",
                                            "name" to functionCall.name,
                                            "content" to result.llmContent,
                                            "tool_call_id" to callId
                                        ))
                                    }
                                    
                                    // Send tool result back to assistant
                                    onToolResult(callId, mapOf("content" to result.llmContent))
                                    
                                } catch (e: Exception) {
                                    android.util.Log.e("OllamaClient", "Tool execution error", e)
                                    emit(AgentEvent.Error("Tool execution failed: ${e.message}"))
                                }
                            } else {
                                android.util.Log.w("OllamaClient", "Tool not found: ${functionCall.name}")
                                emit(AgentEvent.Error("Tool not found: ${functionCall.name}"))
                            }
                        }
                    } else {
                        // No tool calls, just add assistant response to history
                        val fullResponse = responseBody.let {
                            try {
                                val json = JSONObject(it)
                                json.optJSONObject("message")?.optString("content", "") ?: ""
                            } catch (e: Exception) {
                                ""
                            }
                        }
                        
                        if (fullResponse.isNotEmpty()) {
                            synchronized(chatHistory) {
                                chatHistory.add(mapOf("role" to "assistant", "content" to fullResponse))
                            }
                            android.util.Log.d("OllamaClient", "Added assistant response to history")
                        }
                    }
                    
                    // Emit completion event
                    emit(AgentEvent.Complete(finishReason ?: "STOP"))
                    
                } ?: run {
                    emit(AgentEvent.Error("Empty response body"))
                }
            }
        } catch (e: IOException) {
            val errorMsg = e.message ?: "Network error"
            android.util.Log.e("OllamaClient", "Network error", e)
            emit(AgentEvent.Error("Network error: $errorMsg\n\nMake sure the Python script is running: python script.py"))
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            android.util.Log.e("OllamaClient", "Error", e)
            emit(AgentEvent.Error("Error: $errorMsg"))
        }
    }.flowOn(Dispatchers.IO) // Move entire flow execution to IO dispatcher
    
    /**
     * Alternative method for streaming responses (if Python script supports streaming)
     */
    private suspend fun handleStreamingResponse(
        response: Response,
        emit: (AgentEvent) -> Unit,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ) {
        response.body?.source()?.let { source ->
            var buffer = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                
                try {
                    val json = JSONObject(line)
                    val message = json.optJSONObject("message")
                    val content = message?.optString("content", "") ?: ""
                    
                    if (content.isNotEmpty()) {
                        buffer += content
                        // Emit event immediately
                        emit(AgentEvent.Chunk(content))
                    }
                    
                    if (json.optBoolean("done", false)) {
                        // Add assistant response to history (thread-safe)
                        synchronized(chatHistory) {
                            chatHistory.add(mapOf("role" to "assistant", "content" to buffer))
                        }
                        emit(AgentEvent.Complete("STOP"))
                        break
                    }
                } catch (e: Exception) {
                    // Skip malformed JSON
                    android.util.Log.d("OllamaClient", "Failed to parse JSON: ${e.message}")
                }
            }
        }
    }
    
    fun resetChat() {
        synchronized(chatHistory) {
            chatHistory.clear()
        }
    }
    
    fun getHistory(): List<Map<String, Any>> = synchronized(chatHistory) {
        chatHistory.toList()
    }
    
    /**
     * Helper to build proper tool parameter schema
     */
    private fun buildToolParameters(tool: Tool): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            val properties = JSONObject()
            tool.parameters?.forEach { (paramName, paramInfo) ->
                properties.put(paramName, JSONObject().apply {
                    put("type", paramInfo["type"] ?: "string")
                    paramInfo["description"]?.let { put("description", it) }
                    // Add enum if present
                    paramInfo["enum"]?.let { enumList ->
                        if (enumList is List<*>) {
                            put("enum", JSONArray(enumList))
                        }
                    }
                })
            }
            put("properties", properties)
            put("required", JSONArray(tool.parameters?.filter { (_, info) ->
                info["required"] == true
            }?.keys?.toList() ?: emptyList()))
        }
    }
}