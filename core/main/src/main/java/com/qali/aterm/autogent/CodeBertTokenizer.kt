package com.qali.aterm.autogent

import org.json.JSONObject
import java.io.File

/**
 * Simple WordPiece/BPE tokenizer for CodeBERT
 * Handles vocabulary and BPE merges
 */
class CodeBertTokenizer(
    vocabPath: String,
    mergesPath: String
) {
    private val vocab: Map<String, Int>
    private val merges: List<Pair<String, String>>
    private val unkToken = "[UNK]"
    private val clsToken = "[CLS]"
    private val sepToken = "[SEP]"
    private val padToken = "[PAD]"
    private val maskToken = "[MASK]"
    
    init {
        // Load vocabulary
        val vocabFile = File(vocabPath)
        val vocabJson = JSONObject(vocabFile.readText())
        vocab = vocabJson.keys().asSequence().associateWith { vocabJson.getInt(it) }
        
        // Load BPE merges
        val mergesFile = File(mergesPath)
        merges = mergesFile.readLines()
            .drop(1) // Skip header
            .map { line ->
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    parts[0] to parts[1]
                } else {
                    "" to ""
                }
            }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    }
    
    /**
     * Encode text into token IDs
     */
    fun encode(text: String): LongArray {
        // Basic tokenization: split by whitespace and punctuation
        val words = text.split(Regex("\\s+|(?=[.,;:!?(){}[\\]<>])|(?<=[.,;:!?(){}[\\]<>])"))
            .filter { it.isNotBlank() }
        
        val tokens = mutableListOf<Int>()
        
        // Add [CLS] token
        tokens.add(vocab[clsToken] ?: vocab[unkToken] ?: 0)
        
        // Tokenize each word
        for (word in words) {
            val wordTokens = tokenizeWord(word)
            tokens.addAll(wordTokens)
        }
        
        // Add [SEP] token
        tokens.add(vocab[sepToken] ?: vocab[unkToken] ?: 0)
        
        return tokens.map { it.toLong() }.toLongArray()
    }
    
    /**
     * Tokenize a single word using BPE
     */
    private fun tokenizeWord(word: String): List<Int> {
        // Simple BPE-like tokenization
        // Split word into characters and apply merges
        var tokens = word.toCharArray().map { it.toString() }.toMutableList()
        
        // Apply BPE merges
        for ((first, second) in merges) {
            var i = 0
            while (i < tokens.size - 1) {
                if (tokens[i] == first && tokens[i + 1] == second) {
                    tokens[i] = first + second
                    tokens.removeAt(i + 1)
                } else {
                    i++
                }
            }
        }
        
        // Convert to token IDs
        return tokens.map { token ->
            vocab[token] ?: vocab[token.lowercase()] ?: vocab[unkToken] ?: 0
        }
    }
    
    /**
     * Get vocabulary size
     */
    fun vocabSize(): Int = vocab.size
    
    /**
     * Get special token IDs
     */
    fun getClsTokenId(): Int = vocab[clsToken] ?: 0
    fun getSepTokenId(): Int = vocab[sepToken] ?: 0
    fun getPadTokenId(): Int = vocab[padToken] ?: 0
    fun getUnkTokenId(): Int = vocab[unkToken] ?: 0
}
