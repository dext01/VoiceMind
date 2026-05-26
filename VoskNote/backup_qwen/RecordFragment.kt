package com.example.voicemind

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.concurrent.thread

class RecordFragment : Fragment(R.layout.fragment_record) {

    companion object {
        private const val TAG = "RecordFragment"
        private const val PERM_REQ_MIC = 2002
    }

    // ─── Views ────────────────────────────────────────────────────────────────

    private lateinit var btnVosk:        MaterialButton
    private lateinit var tvVoskStatus:   TextView
    private lateinit var tvModelBadge:   TextView
    private lateinit var tvMicStatus:    TextView   // id: tvBtStatus (reused)
    private lateinit var dotMicStatus:   View       // id: dotBtStatus (reused)
    private lateinit var tvRecordHint:   TextView
    private lateinit var pulseRing:      View
    private lateinit var cardResult:     MaterialCardView
    private lateinit var tvResult:       TextView
    private lateinit var tvResultMeta:   TextView
    // Analysis card
    private lateinit var cardAnalysis:   MaterialCardView
    private lateinit var tvKeyIdeas:     TextView
    private lateinit var tvAnalysisMeta: TextView
    private lateinit var tvAnalysisTags: TextView
    // Improve button
    private lateinit var btnImproveText: com.google.android.material.button.MaterialButton

    // ─── State ────────────────────────────────────────────────────────────────

    private var whisperHelper:  WhisperHelper?   = null
    private var speakerEmbedder: SpeakerEmbedder? = null
    private var llmHelper:      LlmHelper?       = null
    private var session:        TranscriptionSession? = null
    private var phoneMic:       PhoneMicRecorder? = null

    @Volatile private var isRecording = false
    private var pulseAnimator: ValueAnimator? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnVosk        = view.findViewById(R.id.btnVosk)
        tvVoskStatus   = view.findViewById(R.id.tvVoskStatus)
        tvModelBadge   = view.findViewById(R.id.tvModelBadge)
        tvMicStatus    = view.findViewById(R.id.tvBtStatus)
        dotMicStatus   = view.findViewById(R.id.dotBtStatus)
        tvRecordHint   = view.findViewById(R.id.tvRecordHint)
        pulseRing      = view.findViewById(R.id.pulseRing)
        cardResult     = view.findViewById(R.id.cardResult)
        tvResult       = view.findViewById(R.id.tvResult)
        tvResultMeta   = view.findViewById(R.id.tvResultMeta)
        cardAnalysis   = view.findViewById(R.id.cardAnalysis)
        tvKeyIdeas     = view.findViewById(R.id.tvKeyIdeas)
        tvAnalysisMeta = view.findViewById(R.id.tvAnalysisMeta)
        tvAnalysisTags = view.findViewById(R.id.tvAnalysisTags)
        btnImproveText = view.findViewById(R.id.btnImproveText)

        btnVosk.isEnabled = false
        tvVoskStatus.text = "загрузка модели..."
        setMicStatusInactive()
        initWhisper()

        btnVosk.setOnClickListener {
            if (!isRecording) startRecording() else stopRecording()
        }

        btnImproveText.setOnClickListener { improveTextWithLlm() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPulse()
        phoneMic?.stop()
        phoneMic = null
        session?.release()
        whisperHelper?.release()
        speakerEmbedder?.release()
        llmHelper?.release()
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    private fun initWhisper() {
        val helper = WhisperHelper(requireContext())
        whisperHelper = helper
        helper.init(
            onReady = {
                requireActivity().runOnUiThread {
                    tvVoskStatus.text = "готово"
                    tvModelBadge.text = "Whisper ${helper.loadedModelName
                        .removePrefix("ggml-").removeSuffix(".bin")}"
                }
                thread {
                    val emb = SpeakerEmbedder(requireContext())
                    val embLoaded = emb.load()
                    speakerEmbedder = if (embLoaded) emb else null

                    val llm = LlmHelper(requireContext())
                    val llmLoaded = llm.load()
                    llmHelper = if (llmLoaded) llm else null

                    requireActivity().runOnUiThread {
                        btnVosk.isEnabled = true
                        val badges = mutableListOf<String>()
                        if (embLoaded) badges += "диаризация"
                        if (llmLoaded) badges += "LLM офлайн"
                        if (badges.isNotEmpty())
                            tvModelBadge.text = "${tvModelBadge.text}  •  ${badges.joinToString(" • ")}"
                    }
                }
            },
            onError = { msg ->
                requireActivity().runOnUiThread {
                    btnVosk.isEnabled = true
                    tvVoskStatus.text = "ошибка: $msg"
                }
            }
        )
    }

    // ─── Recording ────────────────────────────────────────────────────────────

    private fun startRecording() {
        // Проверяем разрешение на микрофон
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERM_REQ_MIC
            )
            tvVoskStatus.text = "выдайте разрешение на микрофон"
            return
        }

        val helper = whisperHelper ?: return
        isRecording = true

        val sess = TranscriptionSession(
            whisper  = helper,
            embedder = speakerEmbedder,
            onChunkReady = { _, fullText ->
                requireActivity().runOnUiThread { showTranscript(fullText) }
            },
            onProcessingChanged = { active ->
                requireActivity().runOnUiThread {
                    if (isRecording) tvVoskStatus.text =
                        if (active) "обрабатывается..." else "запись идёт"
                }
            }
        )
        session = sess

        cardResult.visibility   = View.GONE
        cardAnalysis.visibility = View.GONE
        tvRecordHint.text       = "нажмите чтобы остановить"
        setRecordingUi(true)
        startPulse()

        // Запускаем микрофон телефона
        phoneMic?.stop()
        phoneMic = PhoneMicRecorder(
            onAudioChunk = { pcm ->
                if (isRecording) sess.pushAudio(pcm)
            },
            onError = { msg ->
                Log.e(TAG, "Mic error: $msg")
                if (isRecording) {
                    requireActivity().runOnUiThread {
                        tvVoskStatus.text = "ошибка микрофона: $msg"
                        isRecording = false
                        resetUi()
                    }
                }
            }
        )
        phoneMic!!.start()
        tvVoskStatus.text = "запись идёт"
        setMicStatusActive()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        setRecordingUi(false)
        stopPulse()
        btnVosk.isEnabled = false
        tvVoskStatus.text = "финальная обработка..."
        setMicStatusInactive()

        // Останавливаем микрофон телефона
        phoneMic?.stop()
        phoneMic = null

        val sess = session ?: run { resetUi(); return }

        thread {
            requireActivity().runOnUiThread { tvVoskStatus.text = "диаризация..." }
            sess.flushAndWait { fullText ->
                sess.reset()
                session = null
                requireActivity().runOnUiThread {
                    if (fullText.isNotBlank()) saveRecording(fullText)
                    else {
                        tvVoskStatus.text = "голос не распознан"
                        resetUi()
                    }
                }
            }
        }
    }

    // ─── Save & Analyse ───────────────────────────────────────────────────────

    private fun saveRecording(rawText: String) {
        val appCtx = requireContext().applicationContext
        thread {
            try {
                val cleaned = TextFormatter.format(rawText)
                RecordStorage.addToToday(appCtx, cleaned, "voice", null)
                activity?.runOnUiThread {
                    showTranscript(cleaned)
                    tvVoskStatus.text = "сохранено"
                    resetUi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveRecording error: ${e.message}", e)
                activity?.runOnUiThread {
                    tvVoskStatus.text = "ошибка: ${e.message}"
                    resetUi()
                }
            }
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private fun showTranscript(text: String) {
        if (text.isBlank()) return
        cardResult.visibility = View.VISIBLE
        tvResult.text         = text
        tvResultMeta.text     = "${text.length} симв."
        // Показываем кнопку только если LLM загружена
        if (llmHelper?.isReady == true) {
            btnImproveText.visibility = View.VISIBLE
            btnImproveText.isEnabled  = true
            btnImproveText.text       = "✦ Выделить главное"
        }
    }

    private fun improveTextWithLlm() {
        val llm = llmHelper ?: return
        if (!llm.isReady) return
        val rawText = tvResult.text.toString()
        if (rawText.isBlank()) return

        btnImproveText.isEnabled = false
        btnImproveText.text      = "обрабатывается..."
        tvVoskStatus.text        = "Gemma анализирует..."

        thread {
            try {
                val result = llm.structure(rawText)
                requireActivity().runOnUiThread {
                    btnImproveText.text      = "✓ готово"
                    btnImproveText.isEnabled = false
                    tvVoskStatus.text        = "готово"
                    showAnalysis(result)
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    btnImproveText.isEnabled = true
                    btnImproveText.text      = "✦ Выделить главное"
                    tvVoskStatus.text        = "ошибка: ${e.message}"
                }
            }
        }
    }

    private fun showAnalysis(text: String) {
        if (text.isBlank()) return
        cardAnalysis.visibility   = View.VISIBLE
        tvKeyIdeas.text           = text
        tvAnalysisMeta.text       = "Gemma 3"
        tvAnalysisTags.visibility = View.GONE
    }

    private fun setRecordingUi(recording: Boolean) {
        val ctx = requireContext()
        if (recording) {
            btnVosk.setIconResource(R.drawable.ic_stop)
            btnVosk.background = ContextCompat.getDrawable(ctx, R.drawable.bg_record_button_rec)
            btnVosk.app_iconTint(ctx, android.R.color.white)
        } else {
            btnVosk.setIconResource(R.drawable.ic_mic)
            btnVosk.background = ContextCompat.getDrawable(ctx, R.drawable.bg_record_button)
            btnVosk.app_iconTint(ctx, R.color.surface)
        }
        btnVosk.isEnabled = true
    }

    private fun setMicStatusActive() {
        tvMicStatus.text = "микрофон активен"
        dotMicStatus.setBackgroundResource(R.drawable.bg_calendar_dot)
    }

    private fun setMicStatusInactive() {
        tvMicStatus.text = "микрофон не активен"
        val tintColor = ContextCompat.getColor(requireContext(), R.color.textMuted)
        dotMicStatus.background = ContextCompat.getDrawable(
            requireContext(), R.drawable.bg_calendar_dot
        )?.apply { setTint(tintColor) }
    }

    private fun MaterialButton.app_iconTint(ctx: android.content.Context, colorRes: Int) {
        iconTint = ContextCompat.getColorStateList(ctx, colorRes)
    }

    private fun resetUi() {
        isRecording = false
        btnVosk.isEnabled = true
        tvRecordHint.text = "нажмите чтобы начать"
        setRecordingUi(false)
        setMicStatusInactive()
        stopPulse()
    }

    // ─── Pulse animation ─────────────────────────────────────────────────────

    private fun startPulse() {
        stopPulse()
        pulseRing.alpha = 0.5f
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.4f).apply {
            duration     = 1200
            repeatMode   = ValueAnimator.REVERSE
            repeatCount  = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                pulseRing.scaleX = v
                pulseRing.scaleY = v
                pulseRing.alpha  = (1.4f - v) * 1.2f
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRing.scaleX = 1f
        pulseRing.scaleY = 1f
        pulseRing.alpha  = 0f
    }
}
                                                                                                                                                                                                                                                                                                                                                                                                                                        