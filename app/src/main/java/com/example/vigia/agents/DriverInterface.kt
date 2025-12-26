package com.example.vigia.agents

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.security.KeyStore

class DriverInterface(context: Context) {

    private var tts: TextToSpeech? = null
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.ERROR) {
                tts?.language = Locale.US
            }
        }
    }

    // "Actuation" - Speaking to the driver
    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // "Trust Layer" - Signing data inside the TEE
    fun logSecurely(data: String) {
        val entry = keyStore.getEntry("vigia_identity_key", null) as KeyStore.PrivateKeyEntry
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(data.toByteArray())
        val signedData = signature.sign()

        // Save 'data' + 'signedData' to local DB for upload
        // Database.save(data, signedData)
    }
}