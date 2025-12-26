package com.example.vigia.agents

import android.content.Context
import android.util.Log

import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RoadPilotAgent(val context: Context) {

    private var model: Model? = null
    private var tokenizer: Tokenizer? = null
    private val modelPath = File(context.filesDir, "phi-3.5-mini-int4-cpu").absolutePath

    init {
        Log.d("RoadPilot", "Loading Phi-3.5 Model from: $modelPath")
        try {
            model = Model(modelPath)
            tokenizer = Tokenizer(model)
            Log.d("RoadPilot", "✅ AI Model Loaded Successfully")
        } catch (e: Exception) {
            Log.e("RoadPilot", "❌ Failed to load AI Model.", e)
        }
    }

    suspend fun decideAction(observation: String): String = withContext(Dispatchers.Default) {
        if (model == null || tokenizer == null) return@withContext "Error: AI not initialized"

        val prompt = "<|user|>\nYou are a driving safety assistant. Concise warnings only.\nContext: $observation<|end|>\n<|assistant|>\n"
        val sequences = tokenizer!!.encode(prompt)

        val params = GeneratorParams(model)
        // Fix 1: Use Double for search option
        params.setSearchOption("max_length", 128.0)

        val generator = Generator(model, params)
        // Fix 2: Use appendTokenSequences instead of setInput
        generator.appendTokenSequences(sequences)

        val outputString = StringBuilder()

        while (!generator.isDone) {
            // Fix 3: Removed generator.computeLogits() (Deprecated/Removed in 0.11.0+)
            generator.generateNextToken()

            // Get the new token (returns Int)
            val newToken = generator.getLastTokenInSequence(0)

            try {
                // Fix 4: Use IntArray (not LongArray) for decoding
                val decoded = tokenizer!!.decode(intArrayOf(newToken))
                outputString.append(decoded)
            } catch (e: Exception) {
                // Ignore partial decode errors
            }
        }

        generator.close()
        params.close()

        val response = outputString.toString().trim()
        Log.i("RoadPilot", "AI Output: $response")
        return@withContext response
    }

    fun close() {
        model?.close()
        tokenizer?.close()
    }
}