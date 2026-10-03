package com.example.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * Moteur vocal Studio (Text-To-Speech) pour Agnes Studio.
 * Permet aux personnages et à la voix off de parler en temps réel pendant la lecture des scènes
 * et génère les pistes audio pour le mixage et l'assemblage hors-ligne.
 */
object AgnesVoiceManager {
    private const val TAG = "AgnesVoiceManager"

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isInitializing = false
    private val pendingUtterances = mutableListOf<() -> Unit>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        if (_isMuted.value) {
            stop()
        }
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted) {
            stop()
        }
    }

    /**
     * Initialise le moteur TTS si ce n'est pas déjà fait.
     */
    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (isInitialized) {
            onReady?.invoke()
            return
        }

        if (onReady != null) {
            pendingUtterances.add(onReady)
        }

        if (isInitializing) return
        isInitializing = true

        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                isInitializing = false
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.FRENCH)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Français non supporté nativement, repli sur langue par défaut")
                        tts?.setLanguage(Locale.getDefault())
                    }
                    tts?.setPitch(1.0f)
                    tts?.setSpeechRate(0.98f) // Rythme naturel cinématographique

                    val audioAttrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(audioAttrs)

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                        }

                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                        }
                    })

                    isInitialized = true
                    Log.i(TAG, "Moteur vocal TTS initialisé avec succès")

                    val callbacks = pendingUtterances.toList()
                    pendingUtterances.clear()
                    callbacks.forEach { it.invoke() }
                } else {
                    Log.e(TAG, "Échec d'initialisation du moteur vocal TTS: $status")
                }
            }
        } catch (e: Exception) {
            isInitializing = false
            Log.e(TAG, "Exception lors de l'initialisation du TTS: ${e.message}")
        }
    }

    /**
     * Extrait le texte parlé pur en retirant le nom du personnage (ex: « Elena : Attention ! » -> « Attention ! »).
     */
    fun cleanDialogueText(dialogue: String): String {
        var text = dialogue.replace("«", "").replace("»", "").replace("\"", "").replace("“", "").replace("”", "").trim()
        // Retirer les didascalies entre parenthèses, crochets ou astérisques (ex: (chuchotant), [soupir], *regarde*)
        text = text.replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^\]]*\]"""), "")
            .replace(Regex("""\*[^*]*\*"""), "")
            .trim()

        // Retirer le nom du personnage locuteur s'il est préfixé avec un deux-points (ex: "Elena : ", "Le capitaine : ", "Voix off : ")
        val colonIndex = text.indexOf(':')
        if (colonIndex in 1..40) {
            text = text.substring(colonIndex + 1).trim()
        }
        return text
    }

    /**
     * Fait parler le personnage ou la voix off avec la réplique indiquée.
     */
    fun speak(
        context: Context,
        rawDialogue: String,
        language: String = "fr",
        utteranceId: String = "scene_speech_${System.currentTimeMillis()}"
    ) {
        if (_isMuted.value) return

        val textToSpeak = cleanDialogueText(rawDialogue)
        if (textToSpeak.isBlank()) {
            stop()
            return
        }

        if (!isInitialized) {
            init(context) {
                doSpeak(textToSpeak, language, utteranceId)
            }
        } else {
            doSpeak(textToSpeak, language, utteranceId)
        }
    }

    private fun doSpeak(text: String, language: String, utteranceId: String) {
        try {
            val isFr = language.equals("fr", ignoreCase = true)
            val targetLocale = if (isFr) Locale.FRENCH else Locale.ENGLISH

            val langResult = tts?.setLanguage(targetLocale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (isFr) {
                    val franceRes = tts?.setLanguage(Locale.FRANCE)
                    if (franceRes == TextToSpeech.LANG_MISSING_DATA || franceRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.getDefault())
                    }
                } else {
                    tts?.setLanguage(Locale.getDefault())
                }
            }

            tts?.setPitch(1.0f)
            tts?.setSpeechRate(0.98f)

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (res != TextToSpeech.SUCCESS) {
                Log.w(TAG, "tts.speak a retourné le code: $res")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture TTS: ${e.message}")
        }
    }

    /**
     * Arrête toute diction en cours.
     */
    fun stop() {
        try {
            tts?.stop()
            _isSpeaking.value = false
        } catch (_: Exception) {}
    }

    /**
     * Synthétise la voix dans un fichier audio WAV local pour le mixage vidéo.
     */
    fun synthesizeToFile(
        context: Context,
        rawDialogue: String,
        outputFile: File,
        language: String = "fr",
        onCompleted: (Boolean) -> Unit
    ) {
        val textToSpeak = cleanDialogueText(rawDialogue)
        if (textToSpeak.isBlank()) {
            onCompleted(false)
            return
        }

        val action = {
            try {
                val targetLocale = if (language.equals("fr", ignoreCase = true)) Locale.FRENCH else Locale.ENGLISH
                tts?.language = targetLocale
                val utteranceId = "synth_${System.currentTimeMillis()}"

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}

                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            onCompleted(outputFile.exists() && outputFile.length() > 0)
                        }
                    }

                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            onCompleted(false)
                        }
                    }
                })

                val res = tts?.synthesizeToFile(textToSpeak, null, outputFile, utteranceId)
                if (res != TextToSpeech.SUCCESS) {
                    onCompleted(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur synthesizeToFile: ${e.message}")
                onCompleted(false)
            }
        }

        if (!isInitialized) {
            init(context) { action() }
        } else {
            action()
        }
    }

    /**
     * Libère les ressources du moteur vocal.
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            isInitializing = false
        } catch (_: Exception) {}
    }
}
