package com.example.vigia

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * RouterAgent = System-1 "cerebellum" on the edge.
 * Refined for Imagine Cup 2025: Thread-safe, crash-proof, and latency-optimized.
 */
class RouterAgent(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: BertTokenizer

    // --- Tier 0: Reflex Keywords ---
    private val safetyKeywords = listOf("brake", "stop", "hazard", "watch out", "danger", "look out")

    // --- Anchors for Semantic Routing ---
    private val anchors: MutableMap<String, FloatArray> = mutableMapOf()

    // Model Config
    private val maxSeqLen = 128
    private val embeddingDim = 384

    // ONNX Input Names
    private val inputIdsName = "input_ids"
    private val attentionMaskName = "attention_mask"
    private val tokenTypeIdName = "token_type_ids"

    private val tier1ConfidenceThreshold = 0.9f

    init {
        Log.d("RouterAgent", "Initializing ONNX Runtime...")
        val modelBytes = context.assets.open("model_quantized.onnx").readBytes()
        session = env.createSession(modelBytes)
        tokenizer = BertTokenizer(context)

        // LOAD DUMMY ANCHORS (Ideally replace with real Python-calculated centroids)
        anchors["AGENT_SAFETY"] = FloatArray(embeddingDim) { 0.01f }
        anchors["AGENT_LOCAL"]  = FloatArray(embeddingDim) { 0.05f }
        anchors["AGENT_CLOUD"]  = FloatArray(embeddingDim) { 0.10f }

        Log.d("RouterAgent", "RouterAgent Ready. Anchors initialized with dim: $embeddingDim")
    }

    suspend fun route(
        text: String,
        speed: Float,
        hasHazard: Boolean,
        hasConnectivity: Boolean = true
    ): String = withContext(Dispatchers.Default) {

        // TIER 0: REFLEX RULES
        if (hasHazard) return@withContext "AGENT_SAFETY"
        if (hasSafetyKeyword(text)) return@withContext "AGENT_SAFETY"

        // TIER 1: TINY CLASSIFIER
        val (tier1Route, tier1Confidence) = tinyClassifierPredict(text, speed, hasConnectivity)

        if (tier1Confidence >= tier1ConfidenceThreshold) {
            return@withContext if (!hasConnectivity && tier1Route == "AGENT_CLOUD") {
                "AGENT_LOCAL"
            } else {
                tier1Route
            }
        }

        // TIER 2: SEMANTIC ROUTING (ONNX AI)
        try {
            val embedding = getEmbedding(text)
            return@withContext findBestRoute(embedding, speed, hasConnectivity)
        } catch (e: Exception) {
            Log.e("RouterAgent", "ONNX Inference Failed", e)
            return@withContext tier1Route
        }
    }

    // ---------- Tier 0 Helpers ----------

    private fun hasSafetyKeyword(text: String): Boolean {
        val clean = text.lowercase()
        return safetyKeywords.any { kw ->
            Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(clean)
        }
    }

    // ---------- Tier 1: Tiny Classifier ----------

    private fun tinyClassifierPredict(
        text: String,
        speed: Float,
        hasConnectivity: Boolean
    ): Pair<String, Float> {
        val lower = text.lowercase()
        var bestRoute = "AGENT_LOCAL"
        var confidence = 0.5f

        val cloudWords = listOf("summary", "history", "trend", "report", "stats", "analyze", "why")
        if (cloudWords.any { lower.contains(it) }) {
            if (hasConnectivity) {
                bestRoute = "AGENT_CLOUD"
                confidence = 0.95f
            } else {
                bestRoute = "AGENT_LOCAL"
                confidence = 0.6f
            }
        }

        val localWords = listOf("speed", "distance", "turn", "pothole", "traffic", "nearest")
        if (localWords.any { lower.contains(it) }) {
            if (confidence < 0.9f) {
                bestRoute = "AGENT_LOCAL"
                confidence = 0.9f
            }
        }

        if (speed > 50f && (lower.contains("slow") || lower.contains("careful"))) {
            bestRoute = "AGENT_SAFETY"
            confidence = 0.95f
        }

        return bestRoute to confidence
    }

    // ---------- Tier 2: ONNX Embedding & Pooling ----------

    private fun getEmbedding(text: String): FloatArray {
        // 1. Tokenize
        val (inputIds, attentionMask) = tokenizer.encode(text, maxSeqLen)

        // 2. Prepare Tensors
        val shape = longArrayOf(1, maxSeqLen.toLong())

        val idsBuffer = LongBuffer.wrap(inputIds.map { it.toLong() }.toLongArray())
        val maskBuffer = LongBuffer.wrap(attentionMask.map { it.toLong() }.toLongArray())
        val typeIdsBuffer = LongBuffer.wrap(LongArray(maxSeqLen) { 0 })

        val idsTensor = OnnxTensor.createTensor(env, idsBuffer, shape)
        val maskTensor = OnnxTensor.createTensor(env, maskBuffer, shape)
        val typeTensor = OnnxTensor.createTensor(env, typeIdsBuffer, shape)

        val inputs = mutableMapOf<String, OnnxTensor>(
            inputIdsName to idsTensor,
            attentionMaskName to maskTensor,
            tokenTypeIdName to typeTensor
        )

        try {
            val results = session.run(inputs)
            results.use { res ->
                // --- FIX 1: Cast to 3D Array [Batch, Seq, Hidden] ---
                @Suppress("UNCHECKED_CAST")
                val output3D = res[0].value as Array<Array<FloatArray>>

                // Extract the sequence for Batch 0 -> [128, 384]
                val tokenEmbeddings = output3D[0]

                // --- FIX 2: Mean Pooling ---
                // We must average the token vectors to get one sentence vector
                return meanPooling(tokenEmbeddings, attentionMask)
            }
        } finally {
            idsTensor.close()
            maskTensor.close()
            typeTensor.close()
        }
    }

    /**
     * Collapses [SeqLen, Dim] -> [Dim] by averaging valid tokens.
     */
    private fun meanPooling(
        tokenEmbeddings: Array<FloatArray>,
        attentionMask: IntArray
    ): FloatArray {
        val sentenceEmbedding = FloatArray(embeddingDim) { 0f }
        var validTokenCount = 0

        // Iterate through sequence
        for (i in tokenEmbeddings.indices) {
            // Only sum tokens that are NOT padding (mask == 1)
            if (attentionMask[i] == 1) {
                val tokenVector = tokenEmbeddings[i]
                for (j in 0 until embeddingDim) {
                    sentenceEmbedding[j] += tokenVector[j]
                }
                validTokenCount++
            }
        }

        // Divide sum by count to get mean
        if (validTokenCount > 0) {
            for (j in 0 until embeddingDim) {
                sentenceEmbedding[j] /= validTokenCount.toFloat()
            }
        }

        return sentenceEmbedding
    }

    private fun findBestRoute(embedding: FloatArray, speed: Float, hasConnectivity: Boolean): String {
        var bestRoute = "AGENT_LOCAL"
        var bestScore = -1.0f

        anchors.forEach { (route, anchor) ->
            if (!hasConnectivity && route == "AGENT_CLOUD") return@forEach

            if (anchor.size == embedding.size) {
                val score = cosineSim(embedding, anchor)
                if (score > bestScore) {
                    bestScore = score
                    bestRoute = route
                }
            }
        }

        val safetyThreshold = if (speed > 60f) 0.35f else 0.55f

        if (bestRoute == "AGENT_SAFETY" && bestScore > safetyThreshold) return "AGENT_SAFETY"
        if (bestRoute == "AGENT_CLOUD" && hasConnectivity && bestScore > 0.4f) return "AGENT_CLOUD"

        return "AGENT_LOCAL"
    }

    private fun cosineSim(v1: FloatArray, v2: FloatArray): Float {
        var dot = 0.0f
        var n1 = 0.0f
        var n2 = 0.0f
        for (i in v1.indices) {
            val a = v1[i]
            val b = v2[i]
            dot += a * b
            n1 += a * a
            n2 += b * b
        }
        val denom = (sqrt(n1) * sqrt(n2)).takeIf { it != 0f } ?: 1e-8f
        return dot / denom
    }

    override fun close() {
        session.close()
        env.close()
    }
}