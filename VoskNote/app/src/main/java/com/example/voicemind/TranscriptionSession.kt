package com.example.voicemind

import android.util.Log
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TranscriptionSession(
    private val whisper:  WhisperHelper,
    private val embedder: SpeakerEmbedder? = null,
    private val sampleRate:  Int = 16000,
    private val chunkSec:    Int = 240,  // 4 minutes per chunk
    private val overlapSec:  Int = 15,   // 15-second overlap for context continuity
    private val onChunkReady:       (chunkText: String, fullText: String) -> Unit,
    private val onProcessingChanged: (active: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "TranscriptionSession"
        private const val PROMPT_MAX_CHARS = 224
    }

    private val chunkSamples   = chunkSec   * sampleRate
    private val overlapSamples = overlapSec  * sampleRate
    private val overlapMs      = overlapSec  * 1000L

    val audioBuffer = AudioBuffer(chunkSamples * 3)
    private val executor = Executors.newSingleThreadExecutor()

    private val totalReceived = AtomicLong(0)
    @Volatile private var nextDrainAt = chunkSamples.toLong()
    @Volatile private var chunkIndex  = 0

    // Live preview text
    private val fullTranscript = StringBuilder()
    private val lastTail = AtomicReference("")

    // Accumulated segments (with per-segment audio) for final diarization
    private val allSegments  = Collections.synchronizedList(mutableListOf<WhisperHelper.Segment>())
    private val segmentAudio = Collections.synchronizedList(mutableListOf<ShortArray>())

    @Volatile private var activeJobs = 0

    fun pushAudio(pcm: ShortArray) {
        audioBuffer.push(pcm)
        val received = totalReceived.addAndGet(pcm.size.toLong())
        if (received >= nextDrainAt) {
            nextDrainAt += chunkSamples
            val chunk = audioBuffer.drainKeepOverlap(overlapSamples)
            if (chunk.isNotEmpty()) enqueue(chunk, isFinal = false, idx = chunkIndex++)
        }
    }

    /** Stop recording, process remaining audio, run diarization, return final formatted text. */
    fun flushAndWait(onDone: (fullText: String) -> Unit) {
        val remaining = audioBuffer.drainAll()
        if (remaining.size > sampleRate / 2) {
            enqueue(remaining, isFinal = true, idx = chunkIndex++)
        }
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.MINUTES)

        onDone(fullTranscript.toString().trim())
    }

    fun reset() {
        executor.shutdownNow()
        audioBuffer.clear()
        fullTranscript.clear()
        allSegments.clear()
        segmentAudio.clear()
        lastTail.set("")
        chunkIndex   = 0
        totalReceived.set(0)
        nextDrainAt  = chunkSamples.toLong()
    }

    fun release() {
        executor.shutdownNow()
    }

    private fun enqueue(chunk: ShortArray, isFinal: Boolean, idx: Int) {
        activeJobs++
        if (activeJobs == 1) onProcessingChanged(true)
        Log.d(TAG, "Enqueued chunk #$idx ${chunk.size / sampleRate}s final=$isFinal")

        executor.submit {
            try {
                val prompt   = lastTail.get()
                val segments = whisper.transcribeSegments(chunk, prompt)

                if (segments.isNotEmpty()) {
                    // For non-first chunks: skip segments in the overlap region
                    // to avoid duplicating text that was already in the previous chunk
                    val filtered = if (idx == 0) segments
                                   else segments.filter { it.startMs >= overlapMs }

                    // Compute absolute timestamp offset for this chunk
                    val offsetMs = if (idx == 0) 0L
                                   else idx.toLong() * (chunkSamples - overlapSamples) * 1000L / sampleRate

                    for (seg in filtered) {
                        val absolute = seg.copy(
                            startMs = seg.startMs + offsetMs,
                            endMs   = seg.endMs   + offsetMs
                        )
                        // Extract audio slice for this segment (local timestamps)
                        val startSample = (seg.startMs * sampleRate / 1000).toInt().coerceIn(0, chunk.size)
                        val endSample   = (seg.endMs   * sampleRate / 1000).toInt().coerceIn(0, chunk.size)
                        val segAudio    = if (endSample > startSample)
                            chunk.sliceArray(startSample until endSample) else ShortArray(0)

                        allSegments.add(absolute)
                        segmentAudio.add(segAudio)
                    }

                    val text = filtered.joinToString(" ") { it.text }.trim()
                    if (text.isNotBlank()) {
                        val snapshot: String
                        synchronized(fullTranscript) {
                            if (fullTranscript.isNotEmpty()) fullTranscript.append(" ")
                            fullTranscript.append(text)
                            lastTail.set(if (text.length > PROMPT_MAX_CHARS) text.takeLast(PROMPT_MAX_CHARS) else text)
                            snapshot = fullTranscript.toString().trim()
                        }
                        onChunkReady(text, snapshot)
                    }
                }
                Log.d(TAG, "Chunk #$idx done. Total segments: ${allSegments.size}")
            } finally {
                activeJobs--
                if (activeJobs == 0) onProcessingChanged(false)
            }
        }
    }
}
