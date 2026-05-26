// ─── BACKUP: Qwen2.5-0.5B version ────────────────────────────────────────────
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
        private const val MODEL_NAME = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
        private const val MODEL_URL  =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val MIN_SIZE   = 100_000_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus:    TextView
    private lateinit var tvProgress:  TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDownload: Button
    private lateinit var btnSkip:     Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        tvStatus    = findViewById(R.id.tvSetupStatus)
        tvProgress  = findViewById(R.id.tvSetupProgress)
        progressBar = findViewById(R.id.setupProgressBar)
        btnDownload = findViewById(R.id.btnRetry)
        btnSkip     = findViewById(R.id.btnSkip)
        btnDownload.setOnClickListener { startDownload() }
        btnSkip.setOnClickListener { proceed() }
        if (modelExists()) proceed()
        else {
            tvStatus.text  = "Модель LLM для улучшения текста не найдена"
            tvProgress.text = "Можно скачать сейчас или пропустить"
            btnDownload.visibility = View.VISIBLE
            btnSkip.visibility     = View.VISIBLE
            progressBar.visibility = View.GONE
        }
    }

    private fun modelExists(): Boolean {
        val candidates = listOf(MODEL_NAME, "llm_model.gguf")
        for (name in candidates) {
            if (File(filesDir, name).let { it.exists() && it.length() > MIN_SIZE }) return true
            getExternalFilesDir(null)?.let { ext ->
                if (File(ext, name).let { it.exists() && it.length() > MIN_SIZE }) return true
            }
        }
        return false
    }

    private fun proceed() { startActivity(Intent(this, MainActivity::class.java)); finish() }

    private fun startDownload() {
        btnDownload.visibility = View.GONE; btnSkip.visibility = View.GONE
        progressBar.visibility = View.VISIBLE; progressBar.progress = 0
        tvStatus.text = "Подключение..."; tvProgress.text = ""
        Thread {
            val dest = File(filesDir, MODEL_NAME); val tmp = File(filesDir, "$MODEL_NAME.tmp")
            try {
                val ok = download(MODEL_URL, tmp) { pct, dlMb, totalMb ->
                    mainHandler.post {
                        progressBar.progress = pct
                        tvStatus.text = "Загрузка Qwen2.5-0.5B..."
                        tvProgress.text = "$pct%  •  $dlMb МБ / $totalMb МБ"
                    }
                }
                if (ok) { tmp.renameTo(dest); mainHandler.post { proceed() } }
                else { tmp.delete(); mainHandler.post { showError("Ошибка загрузки.") } }
            } catch (e: Exception) { tmp.delete(); mainHandler.post { showError("Ошибка: ${e.message}") } }
        }.start()
    }

    private fun showError(msg: String) {
        tvStatus.text = msg; tvProgress.text = ""
        progressBar.visibility = View.GONE
        btnDownload.visibility = View.VISIBLE; btnSkip.visibility = View.VISIBLE
    }

    private fun download(startUrl: String, dest: File, onProgress: (Int, Long, Long) -> Unit): Boolean {
        var urlStr = startUrl
        for (i in 0 until 10) {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 30_000; conn.readTimeout = 60_000; conn.connect()
            val code = conn.responseCode
            if (code in 300..399) { urlStr = conn.getHeaderField("Location") ?: return false; conn.disconnect(); continue }
            if (code != 200) { conn.disconnect(); return false }
            val total = conn.contentLengthLong; var downloaded = 0L
            conn.inputStream.use { inp -> dest.outputStream().use { out ->
                val buf = ByteArray(32_768); var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n); downloaded += n
                    if (total > 0) onProgress((downloaded * 100 / total).toInt(), downloaded / 1_000_000, total / 1_000_000)
                }
            }}
            return downloaded > MIN_SIZE
        }
        return false
    }
}
