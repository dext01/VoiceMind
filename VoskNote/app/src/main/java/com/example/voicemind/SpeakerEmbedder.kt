package com.example.voicemind

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import android.os.Environment
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * ECAPA-TDNN speaker embedding model (speechbrain/spkrec-ecapa-voxceleb, ONNX export).
 *
 * Input  "wav" : (1, T) — raw float32 PCM, normalized to [-1, 1], 16 kHz
 * Output "emb" : (1, 1, 192) — L2-normalized speaker embedding
 *
 * Place speaker_model.onnx in app's filesDir (via adb push) or in assets/.
 */
class SpeakerEmbedder(private val context: Context) {

    companion object {
        private const val TAG        = "SpeakerEmbedder"
        const val MODEL_NAME         = "speaker_model.onnx"
        private const val INPUT_NAME = "wav"
        const val EMB_DIM            = 192
        private const val MIN_SAMPLES = 8000  // 0.5 sec minimum
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val isLoaded get() = session != null

    fun load(): Boolean {
        val model = findModel()
        if (model == null) {
            Log.i(TAG, "No speaker model — diarization uses gap heuristic")
            return false
        }
        return try {
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            session = env.createSession(model.absolutePath, opts)
            Log.i(TAG, "Speaker model loaded (${model.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}")
            false
        }
    }

    /**
     * Returns L2-normalized 192-dim embedding for the given audio,
     * or null if audio is too short or inference fails.
     */
    fun embed(pcm: ShortArray): FloatArray? {
        val sess = session ?: return null
        if (pcm.size < MIN_SAMPLES) return null

        // Normalize PCM to float [-1, 1]
        val flat = FloatBuffer.allocate(pcm.size)
        for (s in pcm) flat.put(s / 32768.0f)
        flat.rewind()

        val inputTensor = OnnxTensor.createTensor(
            env, flat, longArrayOf(1, pcm.size.toLong())
        )
        return try {
            sess.run(mapOf(INPUT_NAME to inputTensor)).use { output ->
                // Output shape: (1, 1, 192)
                val raw = when (val v = output[0].value) {
                    is Array<*> -> {
                        val outer = v[0]
                        if (outer is Array<*>) outer[0] as FloatArray else outer as FloatArray
                    }
                    is FloatArray -> v
                    else -> { Log.e(TAG, "Unexpected output: ${v?.javaClass}"); return null }
                }
                val emb = raw.copyOf(EMB_DIM)
                normalizeL2(emb)
                emb
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            null
        } finally {
            inputTensor.close()
        }
    }

    fun release() {
        session?.close()
        session = null
    }

    private fun normalizeL2(v: FloatArray) {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-8f) for (i in v.indices) v[i] /= norm
    }

    private fun findModel(): File? {
        val dest = File(context.filesDir, MODEL_NAME)

        // 1. Already in private filesDir
        if (dest.exists() && dest.length() > 10_000) return dest

        // 2. App external files dir — no permission needed
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null) {
            val f = File(extDir, MODEL_NAME)
            if (f.exists() && f.length() > 10_000) return f
        }

        // 3. Downloads (Android ≤12)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val fromDownloads = File(downloads, MODEL_NAME)
        if (fromDownloads.exists() && fromDownloads.length() > 10_000) {
            Log.i(TAG, "Copying model from Downloads...")
            try {
                fromDownloads.inputStream().use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                Log.i(TAG, "Copied from Downloads (${dest.length() / 1024} KB)")
                return dest
            } catch (e: Exception) { Log.e(TAG, "Copy from Downloads failed: ${e.message}") }
        }

        // 3. Bundled in assets
        return try {
            context.assets.open(MODEL_NAME).use { }
            if (!dest.exists()) {
                Log.i(TAG, "Copying $MODEL_NAME from assets...")
                context.assets.open(MODEL_NAME).use { inp ->
                    dest.outputStream().use { inp.copyTo(it) }
                }
            }
            dest
        } catch (_: Exception) { null }
    }
}
