package com.example.voicemind

import android.content.Context
import android.util.Log
import java.io.File

class WhisperHelper(private val context: Context) {

    companion object {
        private const val TAG = "WhisperHelper"

        // Порядок поиска модели: сначала большая в filesDir, потом маленькая в assets
        private val MODEL_CANDIDATES = listOf(
            "ggml-medium.bin",
            "ggml-small.bin",
            "ggml-base.bin",
            "ggml-tiny.bin"
        )

        init {
            System.loadLibrary("voicemind-jni")
        }
    }

    data class Segment(val text: String, val startMs: Long, val endMs: Long)

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeTranscribe(pcmData: ShortArray, initialPrompt: String): String
    private external fun nativeTranscribeWithSegments(pcmData: ShortArray, initialPrompt: String): String
    private external fun nativeFree()

    var isReady = false
        private set
    var loadedModelName = ""
        private set

    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        Thread {
            val modelFile = findModel()
            if (modelFile == null) {
                onError("Модель не найдена. Скачайте ggml-small.bin в filesDir или assets.")
                return@Thread
            }
            loadedModelName = modelFile.name
            Log.i(TAG, "Loading ${modelFile.name} (${modelFile.length() / 1_000_000} MB)...")
            isReady = nativeInit(modelFile.absolutePath)
            if (isReady) onReady() else onError("Не удалось загрузить модель ${modelFile.name}")
        }.start()
    }

    // Синхронный вызов — запускать только из фонового потока
    fun transcribe(pcm: ShortArray, initialPrompt: String = ""): String {
        if (!isReady || pcm.isEmpty()) return ""
        return nativeTranscribe(pcm, initialPrompt)
    }

    // Returns segments with timestamps. Format: "startMs\tendMs\ttext\n" → parsed list.
    fun transcribeSegments(pcm: ShortArray, initialPrompt: String = ""): List<Segment> {
        if (!isReady || pcm.isEmpty()) return emptyList()
        val raw = nativeTranscribeWithSegments(pcm, initialPrompt)
        if (raw.isBlank()) return emptyList()
        return raw.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("\t", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val startMs = parts[0].toLongOrNull() ?: return@mapNotNull null
                val endMs   = parts[1].toLongOrNull() ?: return@mapNotNull null
                val text    = parts[2].trim()
                if (text.isEmpty()) null else Segment(text, startMs, endMs)
            }
    }

    fun release() {
        if (isReady) {
            nativeFree()
            isReady = false
        }
    }

    private fun findModel(): File? {
        // 1. filesDir (пользователь скопировал вручную или через adb)
        for (name in MODEL_CANDIDATES) {
            val f = File(context.filesDir, name)
            if (f.exists() && f.length() > 1_000_000) {
                Log.i(TAG, "Found in filesDir: $name")
                return f
            }
        }
        // 2. assets → копируем во временный файл
        for (name in MODEL_CANDIDATES) {
            try {
                context.assets.open(name).use { _ ->
                    val dest = File(context.filesDir, name)
                    if (!dest.exists()) {
                        Log.i(TAG, "Copying $name from assets...")
                        context.assets.open(name).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    }
                    Log.i(TAG, "Using asset model: $name")
                    return dest
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
