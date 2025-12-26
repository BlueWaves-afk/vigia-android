package com.example.vigia  // CHANGE THIS to your package name

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import kotlin.math.min

class BertTokenizer(context: Context) {

    private val vocab = HashMap<String, Int>()
    private val idsToTokens = HashMap<Int, String>()
    private val basicTokenizer = BasicTokenizer()
    private val wordpieceTokenizer = WordpieceTokenizer(vocab)

    private val unkToken = "[UNK]"
    private val clsToken = "[CLS]"
    private val sepToken = "[SEP]"
    private val padToken = "[PAD]"

    init {
        loadVocab(context)
    }

    private fun loadVocab(context: Context) {
        try {
            context.assets.open("vocab.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        val token = line.trim()
                        if (token.isNotEmpty()) {
                            val id = vocab.size
                            vocab[token] = id
                            idsToTokens[id] = token
                        }
                    }
                }
            }
            Log.d("Tokenizer", "Loaded vocab size: ${vocab.size}")
        } catch (e: Exception) {
            Log.e("Tokenizer", "Failed to load vocab.txt", e)
        }
    }

    /**
     * Tokenize to token IDs, including [CLS] and [SEP].
     */
    fun tokenize(text: String): List<Int> {
        val wordpieceTokens = ArrayList<String>()

        // 1. Basic tokenization (clean, lower, split punctuation/whitespace)
        for (token in basicTokenizer.tokenize(text)) {
            // 2. WordPiece on each basic token
            wordpieceTokens.addAll(wordpieceTokenizer.tokenize(token))
        }

        // 3. Add special tokens
        val finalTokens = ArrayList<String>()
        finalTokens.add(clsToken)
        finalTokens.addAll(wordpieceTokens)
        finalTokens.add(sepToken)

        // 4. Map to IDs
        val unkId = vocab[unkToken] ?: 0
        return finalTokens.map { token -> vocab[token] ?: unkId }
    }

    /**
     * Encode text into:
     *  - inputIds: IntArray of size maxLen (token IDs, padded/truncated)
     *  - attentionMask: IntArray of size maxLen (1 for real tokens, 0 for padding)
     *
     * This is what you typically feed to a BERT-like ONNX model:
     *   input_ids      -> inputIds
     *   attention_mask -> attentionMask
     */
    fun encode(text: String, maxLen: Int = 128): Pair<IntArray, IntArray> {
        val tokenIds = tokenize(text)

        // Determine padding ID (default 0 if [PAD] not found)
        val padId = vocab[padToken] ?: 0

        val inputIds = IntArray(maxLen) { padId }
        val attentionMask = IntArray(maxLen) { 0 }

        val seqLen = min(tokenIds.size, maxLen)
        for (i in 0 until seqLen) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1
        }

        return inputIds to attentionMask
    }

    // --- Inner Helper Classes (Simplified Logic) ---

    private class BasicTokenizer {

        fun tokenize(text: String): List<String> {
            val clean = cleanText(text.lowercase())
            val tokens = ArrayList<String>()
            val sb = StringBuilder()

            for (c in clean.toCharArray()) {
                if (isPunctuation(c)) {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.setLength(0)
                    }
                    tokens.add(c.toString())
                } else if (c.isWhitespace()) {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.setLength(0)
                    }
                } else {
                    sb.append(c)
                }
            }
            if (sb.isNotEmpty()) {
                tokens.add(sb.toString())
            }
            return tokens
        }

        private fun cleanText(text: String): String {
            // Remove accents and combining marks
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
            return normalized.replace("[\\p{InCombiningDiacriticalMarks}]".toRegex(), "")
        }

        private fun isPunctuation(c: Char): Boolean {
            val code = c.code
            return (code in 33..47) || (code in 58..64) ||
                    (code in 91..96) || (code in 123..126)
        }

        private fun Char.isWhitespace(): Boolean {
            return Character.isWhitespace(this)
        }
    }

    private class WordpieceTokenizer(private val vocab: Map<String, Int>) {

        private val unkToken = "[UNK]"

        /**
         * Tokenizes a single *basic* token into WordPiece sub-tokens.
         */
        fun tokenize(token: String): List<String> {
            if (token.isEmpty()) return emptyList()

            val outputTokens = ArrayList<String>()
            var start = 0
            val tokenLen = token.length

            while (start < tokenLen) {
                var end = tokenLen
                var curSubstr: String? = null

                while (start < end) {
                    var substr = token.substring(start, end)
                    if (start > 0) {
                        substr = "##$substr"
                    }
                    if (vocab.containsKey(substr)) {
                        curSubstr = substr
                        break
                    }
                    end -= 1
                }

                if (curSubstr == null) {
                    // No valid subword found for this part → emit [UNK] for this *whole* token
                    outputTokens.add(unkToken)
                    break
                }

                outputTokens.add(curSubstr)
                start = end
            }

            return outputTokens
        }
    }
}