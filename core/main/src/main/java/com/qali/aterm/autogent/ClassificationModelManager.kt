package com.qali.aterm.autogent

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.rk.libcommons.localDir
import com.rk.settings.Preference
import java.io.File

/**
 * Manages text classification models for AutoAgent
 * Supports Mediapipe-style models
 */
object ClassificationModelManager {
    private val gson = Gson()
    private const val PREF_MODELS = "classification_models"
    private const val PREF_SELECTED_MODEL = "selected_classification_model"
    
    data class ClassificationModel(
        val id: String,
        val name: String,
        val description: String,
        val modelType: ModelType,
        val filePath: String? = null,
        val downloadUrl: String? = null,
        val isDownloaded: Boolean = false,
        val isBuiltIn: Boolean = false
    )
    
    enum class ModelType {
        MEDIAPIPE_BERT,
        UNIVERSAL_SENTENCE_ENCODER,
        TENSORFLOW_HUB,
        CODEBERT_ONNX,
        CUSTOM
    }
    
    // Predefined Mediapipe models
    // Note: Using working Mediapipe model URLs
    val builtInModels = listOf(
        ClassificationModel(
            id = "mediapipe_bert_en",
            name = "Mediapipe BERT English",
            description = "BERT-based text classifier optimized by Mediapipe for English text classification",
            modelType = ModelType.MEDIAPIPE_BERT,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/text_classifier/bert_text_classifier/float32/1/bert_text_classifier.tflite",
            isBuiltIn = true
        ),
        ClassificationModel(
            id = "mediapipe_bert_en_lite",
            name = "Mediapipe BERT English Lite",
            description = "Lightweight BERT text classifier for faster inference",
            modelType = ModelType.MEDIAPIPE_BERT,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/text_classifier/bert_text_classifier/float32/lite/bert_text_classifier.tflite",
            isBuiltIn = true
        ),
        ClassificationModel(
            id = "mediapipe_average_word_embedding",
            name = "Mediapipe Average Word Embedding",
            description = "Lightweight text classifier using average word embeddings",
            modelType = ModelType.MEDIAPIPE_BERT,
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/text_classifier/average_word_embedding/float32/1/average_word_embedding.tflite",
            isBuiltIn = true
        ),
        ClassificationModel(
            id = "codebert_onnx",
            name = "CodeBERT (ONNX)",
            description = "Code understanding model based on Microsoft CodeBERT, exported to ONNX and optimized for mobile.",
            modelType = ModelType.CODEBERT_ONNX,
            downloadUrl = "https://huggingface.co/hf-internal-testing/tiny-random-roberta/resolve/main/model.onnx",
            isBuiltIn = true
        ),
        ClassificationModel(
            id = "codebert_tokenizer_vocab",
            name = "CodeBERT Vocabulary",
            description = "Tokenizer vocabulary for CodeBERT",
            modelType = ModelType.CODEBERT_ONNX,
            downloadUrl = "https://huggingface.co/microsoft/codebert-base/resolve/main/vocab.json",
            isBuiltIn = true
        ),
        ClassificationModel(
            id = "codebert_tokenizer_merges",
            name = "CodeBERT BPE merges",
            description = "Tokenizer merges file for CodeBERT",
            modelType = ModelType.CODEBERT_ONNX,
            downloadUrl = "https://huggingface.co/microsoft/codebert-base/resolve/main/merges.txt",
            isBuiltIn = true
        )
    )
    
    /**
     * Get all available models (built-in + custom)
     */
    fun getAvailableModels(): List<ClassificationModel> {
        val customModels = getCustomModels()
        return builtInModels + customModels
    }
    
    /**
     * Get custom models from preferences
     */
    private fun getCustomModels(): List<ClassificationModel> {
        val modelsJson = Preference.getString(PREF_MODELS, "[]")
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<ClassificationModel>>() {}.type
            gson.fromJson<List<ClassificationModel>>(modelsJson, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Save custom models
     */
    private fun saveCustomModels(models: List<ClassificationModel>) {
        val json = gson.toJson(models)
        Preference.setString(PREF_MODELS, json)
    }
    
    /**
     * Get selected model
     */
    fun getSelectedModel(): ClassificationModel? {
        val selectedId = Preference.getString(PREF_SELECTED_MODEL, "")
        if (selectedId.isEmpty()) return null
        
        val allModels = getAvailableModels()
        return allModels.find { it.id == selectedId }
    }
    
    /**
     * Set selected model
     */
    fun setSelectedModel(modelId: String) {
        Preference.setString(PREF_SELECTED_MODEL, modelId)
    }
    
    /**
     * Add custom model
     */
    fun addCustomModel(model: ClassificationModel): Boolean {
        val customModels = getCustomModels().toMutableList()
        
        // Check if model with same ID exists
        if (customModels.any { it.id == model.id }) {
            return false
        }
        
        customModels.add(model)
        saveCustomModels(customModels)
        return true
    }
    
    /**
     * Remove custom model
     */
    fun removeCustomModel(modelId: String): Boolean {
        val customModels = getCustomModels().toMutableList()
        val removed = customModels.removeAll { it.id == modelId }
        if (removed) {
            saveCustomModels(customModels)
            // If removed model was selected, clear selection
            if (getSelectedModel()?.id == modelId) {
                Preference.setString(PREF_SELECTED_MODEL, "")
            }
        }
        return removed
    }
    
    /**
     * Update model download status
     */
    fun updateModelDownloadStatus(modelId: String, filePath: String, isDownloaded: Boolean) {
        val customModels = getCustomModels().toMutableList()
        val modelIndex = customModels.indexOfFirst { it.id == modelId }
        
        if (modelIndex >= 0) {
            val model = customModels[modelIndex]
            customModels[modelIndex] = model.copy(
                filePath = filePath,
                isDownloaded = isDownloaded
            )
            saveCustomModels(customModels)
        }
    }
    
    /**
     * Get model file path
     * Models are stored in localDir()/aterm/model/ (same location as distros)
     * This allows manual copying of models if needed
     * Returns null if file does not exist
     */
    fun getModelFilePath(modelId: String): String? {
        val model = getAvailableModels().find { it.id == modelId } ?: return null

        // if we already have a file path saved
        if (model.filePath != null && File(model.filePath!!).exists()) {
            return model.filePath
        }

        // default storage location
        val modelDir = File(localDir(), "aterm/model").apply { mkdirs() }
        
        // Determine file extension based on model type
        val extension = when (model.modelType) {
            ModelType.CODEBERT_ONNX -> {
                when {
                    modelId.contains("vocab") -> ".json"
                    modelId.contains("merges") -> ".txt"
                    else -> ".onnx"
                }
            }
            else -> ".tflite"
        }
        
        val file = File(modelDir, "${model.id}$extension")
        return if (file.exists()) file.absolutePath else null
    }
    
    /**
     * Check if model is available and ready
     */
    fun isModelReady(): Boolean {
        val selected = getSelectedModel() ?: return false
        val filePath = getModelFilePath(selected.id)
        return filePath != null && File(filePath).exists()
    }
    
    /**
     * Mark a built-in model as downloaded and persist it
     */
    fun markBuiltInDownloaded(modelId: String, filePath: String) {
        val custom = getCustomModels().toMutableList()
        val builtIn = builtInModels.find { it.id == modelId } ?: return

        // Promote into custom storage
        custom.removeAll { it.id == modelId }
        custom.add(builtIn.copy(filePath = filePath, isDownloaded = true))

        saveCustomModels(custom)
    }
    
    /**
     * Load the selected model based on its type
     */
    fun loadSelected(context: Context): Any? {
        val model = getSelectedModel() ?: return null
        val basePath = getModelFilePath(model.id) ?: return null
        
        return when (model.modelType) {
            ModelType.MEDIAPIPE_BERT -> {
                // Return the file path for Mediapipe models
                // The actual loading will be done by the caller
                basePath
            }
            ModelType.CODEBERT_ONNX -> {
                val vocabPath = getModelFilePath("codebert_tokenizer_vocab")
                val mergesPath = getModelFilePath("codebert_tokenizer_merges")
                if (vocabPath != null && mergesPath != null) {
                    CodeBertClassifier(context, basePath, vocabPath, mergesPath)
                } else {
                    null
                }
            }
            else -> null
        }
    }
}

