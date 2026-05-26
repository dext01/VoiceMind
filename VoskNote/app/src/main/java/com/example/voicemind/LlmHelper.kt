// ─── BACKUP: Qwen2.5-0.5B version ────────────────────────────────────────────
// To restore: copy this file over LlmHelper.kt and rename

package com.example.voicemind

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

class LlmHelper(private val context: Context) {

    companion object {
        private const val TAG = "LlmHelper"

        private val MODEL_CANDIDATES = listOf(
            "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            "llm_model.gguf"
        )

        private const val N_CTX          = 512
        private const val N_THREADS      = 8
        private const val MAX_NEW_TOKENS = 100
        private const val MAX_INPUT_CHARS = 400

        private val SYSTEM_PROMPT =
            "Ты помощник с плохой памятью. Из текста выписываешь только то, что важно не забыть."

        init { System.loadLibrary("voicemind-llm") }
    }

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeInfer(prompt: String, maxNewTokens: Int): String
    private external fun nativeFree()

    var isReady        = false; private set
    var loadedModelName = ""; private set

    fun load(): Boolean {
        val model = findModel() ?: run {
            Log.i(TAG, "No LLM model found"); return false
        }
        loadedModelName = model.name
        Log.i(TAG, "Loading ${model.name} (${model.length() / 1_000_000} MB)...")
        isReady = nativeInit(model.absolutePath, N_CTX, N_THREADS)
        if (isReady) Log.i(TAG, "LLM ready") else Log.e(TAG, "LLM load failed")
        return isReady
    }

    fun structure(rawText: String): String {
        if (!isReady || rawText.isBlank()) return rawText
        val truncated = if (rawText.length > MAX_INPUT_CHARS) rawText.take(MAX_INPUT_CHARS) + "..." else rawText
        val prompt = buildPrompt(truncated)
        Log.i(TAG, "Structuring ${truncated.length} chars (original: ${rawText.length})...")
        val result = nativeInfer(prompt, MAX_NEW_TOKENS).trim()
        Log.i(TAG, "Done: ${truncated.length} -> ${result.length} chars")
        return result.ifBlank { rawText }
    }

    fun release() { if (isReady) { nativeFree(); isReady = false } }

    private fun buildPrompt(text: String): String {
        val userMsg = """
Текст заметки:
$text

Выпиши только то, что важно не забыть — встречи, обещания, договорённости, важные решения.
Если в тексте нет ничего важного — напиши "Ничего важного."
Простые разговоры и приветствия игнорируй. Кратко, списком.
        """.trimIndent()
        return "<|im_start|>system\n$SYSTEM_PROMPT<|im_end|>\n" +
               "<|im_start|>user\n$userMsg<|im_end|>\n" +
               "<|im_start|>assistant\n"
    }

    private fun findModel(): File? {
        for (name in MODEL_CANDIDATES) {
            val f = File(context.filesDir, name)
            if (f.exists() && f.length() > 100_000_000L) return f
        }
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null) {
            for (name in MODEL_CANDIDATES) {
                val f = File(extDir, name)
                if (f.exists() && f.length() > 100_000_000L) return f
            }
        }
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        for (name in MODEL_CANDIDATES) {
            val src = File(dl, name)
            if (src.exists() && src.length() > 100_000_000L) {
                val dest = File(context.filesDir, name)
                if (!dest.exists()) {
                    try { src.inputStream().use { i -> dest.outputStream().use { i.copyTo(it) } } }
                    catch (e: Exception) { continue }
                }
                return dest
            }
        }
        return null
    }
}
