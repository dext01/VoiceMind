package com.example.voicemind

import kotlin.math.*

object FbankExtractor {

    const val SAMPLE_RATE = 16000
    const val NUM_FILTERS = 80

    private const val FRAME_LEN   = 400   // 25 ms at 16 kHz
    private const val FRAME_SHIFT = 160   // 10 ms at 16 kHz
    private const val FFT_SIZE    = 512
    private const val LOW_HZ      = 20.0
    private const val HIGH_HZ     = 8000.0
    private const val PRE_EMPH    = 0.97f

    private val hammingWindow: FloatArray by lazy {
        FloatArray(FRAME_LEN) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_LEN - 1))).toFloat()
        }
    }

    // filterbank[m][k] — weight of FFT bin k for mel filter m
    private val filterbank: Array<FloatArray> by lazy { buildFilterbank() }

    /** Returns log-Fbank features of shape [T][80], CMVN-normalized. */
    fun extract(pcm: ShortArray): Array<FloatArray> {
        if (pcm.size < FRAME_LEN) return emptyArray()

        val signal = FloatArray(pcm.size)
        signal[0] = pcm[0] / 32768.0f
        for (i in 1 until pcm.size) {
            signal[i] = pcm[i] / 32768.0f - PRE_EMPH * (pcm[i - 1] / 32768.0f)
        }

        val numFrames = 1 + (signal.size - FRAME_LEN) / FRAME_SHIFT
        val features  = Array(numFrames) { FloatArray(NUM_FILTERS) }

        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)

        for (f in 0 until numFrames) {
            val start = f * FRAME_SHIFT
            re.fill(0f); im.fill(0f)
            for (i in 0 until FRAME_LEN) re[i] = signal[start + i] * hammingWindow[i]

            fft(re, im)

            val halfFFT = FFT_SIZE / 2 + 1
            for (m in 0 until NUM_FILTERS) {
                var energy = 0.0f
                val fb = filterbank[m]
                for (k in 0 until halfFFT) energy += fb[k] * (re[k] * re[k] + im[k] * im[k])
                features[f][m] = ln(maxOf(energy, 1e-10f))
            }
        }

        applyCmvn(features)
        return features
    }

    private fun applyCmvn(features: Array<FloatArray>) {
        val T = features.size.toFloat()
        val mean = FloatArray(NUM_FILTERS)
        for (frame in features) for (i in 0 until NUM_FILTERS) mean[i] += frame[i]
        for (i in 0 until NUM_FILTERS) mean[i] = mean[i] / T

        val std = FloatArray(NUM_FILTERS)
        for (frame in features) for (i in 0 until NUM_FILTERS) {
            val d = frame[i] - mean[i]; std[i] += d * d
        }
        for (i in 0 until NUM_FILTERS) std[i] = sqrt(std[i] / T + 1e-8f)

        for (frame in features) for (i in 0 until NUM_FILTERS) {
            frame[i] = (frame[i] - mean[i]) / std[i]
        }
    }

    private fun buildFilterbank(): Array<FloatArray> {
        val halfFFT = FFT_SIZE / 2 + 1
        val lowMel  = hzToMel(LOW_HZ)
        val highMel = hzToMel(HIGH_HZ)
        val melPts  = FloatArray(NUM_FILTERS + 2) { i ->
            melToHz(lowMel + i.toDouble() * (highMel - lowMel) / (NUM_FILTERS + 1)).toFloat()
        }
        val bins = FloatArray(NUM_FILTERS + 2) { i ->
            floor((FFT_SIZE + 1) * melPts[i] / SAMPLE_RATE).toFloat()
        }
        return Array(NUM_FILTERS) { m ->
            FloatArray(halfFFT) { k ->
                val kf = k.toFloat()
                when {
                    kf < bins[m] || kf > bins[m + 2] -> 0f
                    kf <= bins[m + 1] -> (kf - bins[m]) / (bins[m + 1] - bins[m])
                    else              -> (bins[m + 2] - kf) / (bins[m + 2] - bins[m + 1])
                }
            }
        }
    }

    private fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    // In-place Cooley-Tukey radix-2 FFT (size must be power of 2)
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half  = len / 2
            val angle = -2.0 * PI / len
            val wRe   = cos(angle).toFloat()
            val wIm   = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1.0f; var wi = 0.0f
                for (k in 0 until half) {
                    val tr = wr * re[i + k + half] - wi * im[i + k + half]
                    val ti = wr * im[i + k + half] + wi * re[i + k + half]
                    re[i + k + half] = re[i + k] - tr
                    im[i + k + half] = im[i + k] - ti
                    re[i + k] += tr
                    im[i + k] += ti
                    val nwr = wr * wRe - wi * wIm
                    wi      = wr * wIm + wi * wRe
                    wr      = nwr
                }
                i += len
            }
            len *= 2
        }
    }
}
