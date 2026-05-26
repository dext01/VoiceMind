package com.example.voicemind

import android.util.Log

object SpeakerDiarizer {

    private const val TAG = "SpeakerDiarizer"

    // Cosine similarity threshold for merging into same speaker cluster
    private const val MERGE_THRESHOLD = 0.75f

    // Minimum pause between segments to consider a speaker change (gap-based fallback)
    private const val GAP_THRESHOLD_MS = 1500L

    private val LABELS = listOf("А", "Б", "В", "Г", "Д", "Е", "Ж", "З")

    /**
     * Format dialogue with speaker labels.
     * Uses embedding-based clustering when embedder is available,
     * falls back to pause-based heuristic otherwise.
     *
     * @param segments  list of transcription segments (with timestamps)
     * @param audioMap  segment index → raw PCM of that segment (for embedding)
     * @param embedder  optional ONNX speaker embedder
     */
    fun formatDialogue(
        segments:  List<WhisperHelper.Segment>,
        audioMap:  Map<Int, ShortArray>,
        embedder:  SpeakerEmbedder?
    ): String {
        if (segments.isEmpty()) return ""

        val assignments = if (embedder?.isLoaded == true) {
            Log.i(TAG, "Using ONNX embeddings for ${segments.size} segments")
            assignByEmbedding(segments, audioMap, embedder)
        } else {
            Log.i(TAG, "Using gap heuristic for ${segments.size} segments")
            assignByGap(segments)
        }

        return buildText(segments, assignments)
    }

    // ---- embedding-based clustering -------------------------------------------------------

    private fun assignByEmbedding(
        segments: List<WhisperHelper.Segment>,
        audioMap: Map<Int, ShortArray>,
        embedder: SpeakerEmbedder
    ): IntArray {
        val n          = segments.size
        val embeddings = Array<FloatArray?>(n) { i -> audioMap[i]?.let { embedder.embed(it) } }
        val cluster    = IntArray(n) { it }   // each segment starts in its own cluster

        // Greedy agglomerative: merge any pair whose average pairwise similarity > threshold
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (cluster[i] == cluster[j]) continue
                    val ei = embeddings[i] ?: continue
                    val ej = embeddings[j] ?: continue
                    if (cosineSim(ei, ej) >= MERGE_THRESHOLD) {
                        val old = cluster[j]; val new = cluster[i]
                        for (k in 0 until n) if (cluster[k] == old) cluster[k] = new
                        merged = true
                        break@outer
                    }
                }
            }
        }

        // Remap cluster IDs to 0-based sequential indices
        val remap  = mutableMapOf<Int, Int>()
        var nextId = 0
        for (i in 0 until n) {
            cluster[i] = remap.getOrPut(cluster[i]) { nextId++ }
        }
        Log.i(TAG, "Found ${nextId} speaker(s)")
        return cluster
    }

    // ---- gap-based fallback ---------------------------------------------------------------

    private fun assignByGap(segments: List<WhisperHelper.Segment>): IntArray {
        val assignments = IntArray(segments.size)
        var spk = 0
        for (i in 1 until segments.size) {
            val gap = segments[i].startMs - segments[i - 1].endMs
            if (gap >= GAP_THRESHOLD_MS) spk++
            assignments[i] = spk
        }
        return assignments
    }

    // ---- output formatting ----------------------------------------------------------------

    private fun buildText(segments: List<WhisperHelper.Segment>, assignments: IntArray): String {
        val numSpeakers = (assignments.maxOrNull() ?: 0) + 1

        if (numSpeakers == 1) {
            // Single speaker: return plain concatenated text (no labels)
            return segments.joinToString(" ") { it.text.trim() }.trim()
        }

        // Group consecutive same-speaker segments into blocks
        data class Block(val speaker: Int, val lines: MutableList<String> = mutableListOf())

        val blocks   = mutableListOf<Block>()
        var prevSpk  = -1
        for ((i, seg) in segments.withIndex()) {
            val spk = assignments[i]
            if (spk != prevSpk) { blocks += Block(spk); prevSpk = spk }
            val t = seg.text.trim()
            if (t.isNotEmpty()) blocks.last().lines += t
        }

        return blocks
            .filter { it.lines.isNotEmpty() }
            .joinToString("\n\n") { b ->
                val label = LABELS.getOrElse(b.speaker) { (b.speaker + 1).toString() }
                "[Говорящий $label]\n${b.lines.joinToString(" ")}"
            }
    }

    // ---- math -----------------------------------------------------------------------------

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        // Assumes both vectors are already L2-normalized
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
