package com.example.voicemind

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val WHISPER_NAME = "ggml-tiny.bin"
        private const val WHISPER_URL  =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
        private const val WHISPER_MIN_SIZE = 70_000_000L

        private const val LLM_NAME = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
        private const val LLM_URL  =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val LLM_MIN_SIZE = 100_000_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus:    TextView
    private lateinit var tvProgress:  TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDownload: Button
    private lateinit var btnSkip:     Button

    @Volatile private var downloadCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        tvStatus    = findViewById(R.id.tvSetupStatus)
        tvProgress  = findViewById(R.id.tvSetupProgress)
        progressBar = findViewById(R.id.setupProgressBar)
        btnDownload = findViewById(R.id.btnRetry)
        btnSkip     = findViewById(R.id.btnSkip)

        hideButtons()
        checkWhisper()
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadCancelled = true
    }

    // ─── Phase 1: Whisper (required) ─────────────────────────────────────────

    private fun checkWhisper() {
        if (whisperExists()) {
            checkLlm()
            return
        }
        tvStatus.text   = "Загрузка модели распознавания речи (75 МБ)..."
        tvProgress.text = "Только при первом запуске"
        progressBar.visibility = View.VISIBLE
        progressBar.progress   = 0
        downloadCancelled = false
        Thread {
            val dest = File(filesDir, WHISPER_NAME)
            val tmp  = File(filesDir, "$WHISPER_NAME.tmp")
            try {
                val ok = download(WHISPER_URL, tmp, WHISPER_MIN_SIZE) { pct, dlMb, totalMb ->
                    mainHandler.post {
                        progressBar.progress = pct
                        tvProgress.text = "$pct%  •  $dlMb МБ / $totalMb МБ"
                    }
                }
                if (ok) {
                    tmp.renameTo(dest)
                    mainHandler.post { checkLlm() }
                } else {
                    tmp.delete()
                    mainHandler.post { showWhisperError() }
                }
            } catch (e: Exception) {
                tmp.delete()
                mainHandler.post { showWhisperError("Ошибка: ${e.message}") }
            }
        }.start()
    }

    private fun showWhisperError(msg: String = "Ошибка загрузки. Проверьте интернет.") {
        progressBar.visibility = View.GONE
        tvStatus.text   = msg
        tvProgress.text = "Без модели распознавания приложение не работает"
        btnDownload.text = "Попробовать снова"
        btnDownload.setOnClickListener { hideButtons(); checkWhisper() }
        btnDownload.visibility = View.VISIBLE
    }

    // ─── Phase 2: LLM (optional) ─────────────────────────────────────────────

    private fun checkLlm() {
        if (llmExists()) { proceed(); return }
        progressBar.visibility = View.GONE
        tvStatus.text   = "Модель анализа текста не найдена (900 МБ)"
        tvProgress.text = "Выделяет ключевые мысли из записей. Можно пропустить."
        btnDownload.text = "Скачать"
        btnDownload.setOnClickListener { startLlmDownload() }
        btnSkip.setOnClickListener { proceed() }
        btnDownload.visibility = View.VISIBLE
        btnSkip.visibility     = View.VISIBLE
    }

    private fun startLlmDownload() {
        hideButtons()
        progressBar.visibility = View.VISIBLE
        progressBar.progress   = 0
        downloadCancelled = false
        Thread {
            val dest = File(filesDir, LLM_NAME)
            val tmp  = File(filesDir, "$LLM_NAME.tmp")
            try {
                val ok = download(LLM_URL, tmp, LLM_MIN_SIZE) { pct, dlMb, totalMb ->
                    mainHandler.post {
                        progressBar.progress = pct
                        tvStatus.text   = "Загрузка Qwen2.5-1.5B..."
                        tvProgress.text = "$pct%  •  $dlMb МБ / $totalMb МБ"
                    }
                }
                if (ok) { tmp.renameTo(dest); mainHandler.post { proceed() } }
                else    { tmp.delete();       mainHandler.post { showLlmError() } }
            } catch (e: Exception) { tmp.delete(); mainHandler.post { showLlmError("Ошибка: ${e.message}") } }
        }.start()
    }

    private fun showLlmError(msg: String = "Ошибка загрузки.") {
        progressBar.visibility = View.GONE
        tvStatus.text = msg
        btnDownload.text = "Попробовать снова"
        btnDownload.setOnClickListener { startLlmDownload() }
        btnSkip.setOnClickListener { proceed() }
        btnDownload.visibility = View.VISIBLE
        btnSkip.visibility     = View.VISIBLE
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun proceed() { startActivity(Intent(this, MainActivity::class.java)); finish() }

    private fun hideButtons() {
        btnDownload.visibility = View.GONE
        btnSkip.visibility     = View.GONE
    }

    private fun whisperExists(): Boolean {
        val candidates = listOf(WHISPER_NAME, "ggml-base.bin", "ggml-small.bin", "ggml-medium.bin")
        for (name in candidates) {
            if (File(filesDir, name).let { it.exists() && it.length() > WHISPER_MIN_SIZE }) return true
            getExternalFilesDir(null)?.let { ext ->
                if (File(ext, name).let { it.exists() && it.length() > WHISPER_MIN_SIZE }) return true
            }
        }
        return false
    }

    private fun llmExists(): Boolean {
        val candidates = listOf(LLM_NAME, "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf", "llm_model.gguf")
        for (name in candidates) {
            if (File(filesDir, name).let { it.exists() && it.length() > LLM_MIN_SIZE }) return true
            getExternalFilesDir(null)?.let { ext ->
                if (File(ext, name).let { it.exists() && it.length() > LLM_MIN_SIZE }) return true
            }
        }
        return false
    }

    private fun download(
        startUrl:  String,
        dest:      File,
        minSize:   Long,
        onProgress: (Int, Long, Long) -> Unit
    ): Boolean {
        var urlStr = startUrl
        for (i in 0 until 10) {
            if (downloadCancelled) return false
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                urlStr = conn.getHeaderField("Location") ?: return false
                conn.disconnect(); continue
            }
            if (code != 200) { conn.disconnect(); return false }
            val total = conn.contentLengthLong; var downloaded = 0L
            conn.inputStream.use { inp -> dest.outputStream().use { out ->
                val buf = ByteArray(32_768); var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    if (downloadCancelled) return false
                    out.write(buf, 0, n); downloaded += n
                    if (total > 0) onProgress(
                        (downloaded * 100 / total).toInt(),
                        downloaded / 1_000_000,
                        total / 1_000_000
                    )
                }
            }}
            return downloaded > minSize
        }
        return false
    }
}
