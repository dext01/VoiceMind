package com.example.voicemind

import kotlin.math.sqrt

/**
 * Простая energy-based VAD для PCM16LE (16kHz).
 * Идея из гайда: RMS/EMA => принимаем решение "тишина/речь".
 *
 * Для MVP мы используем "gate": кормим Vosk только когда EMA-энергия выше порога,
 * а также несколько кадров после старта речи (чтобы не обрезать начало).
 */
class VADProcessor(
    private val energyThreshold: Float = 500f,
    private val alpha: Float = 0.3f,
    private val hangFramesAfterSpeech: Int = 3,
) {
    private var smoothedEnergy: Float = 0f
    private var isInSpeech: Boolean = false
    private var hangCounter: Int = 0

    /**
     * pcm: ByteArray с PCM16LE. Вернёт true если нужно feed-ить Vosk.
     */
    fun shouldFeed(pcm: ByteArray): Boolean {
        if (pcm.size < 2) return false
        val samples = pcm.size / 2
        var sumSq = 0.0

        // PCM16LE => int16 little-endian
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = sample.toShort().toInt()
            sumSq += s.toDouble() * s.toDouble()
            i += 2
        }

        val rms = sqrt(sumSq / samples.toDouble()).toFloat()
        smoothedEnergy = alpha * rms + (1f - alpha) * smoothedEnergy

        val speechNow = smoothedEnergy > energyThreshold
        if (speechNow) {
            isInSpeech = true
            hangCounter = hangFramesAfterSpeech
            return true
        }

        if (isInSpeech) {
            if (hangCounter > 0) {
                hangCounter--
                return true
            }
            isInSpeech = false
        }

        return false
    }
}

