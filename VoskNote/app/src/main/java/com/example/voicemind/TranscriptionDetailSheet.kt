package com.example.voicemind

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class TranscriptionDetailSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TEXT      = "text"
        private const val ARG_TIMESTAMP = "timestamp"

        fun newInstance(log: LogEntry) = TranscriptionDetailSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TEXT, log.rawText)
                putLong(ARG_TIMESTAMP, log.timestamp)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_transcription_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val text      = arguments?.getString(ARG_TEXT) ?: ""
        val timestamp = arguments?.getLong(ARG_TIMESTAMP) ?: System.currentTimeMillis()

        val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val fmtDate = SimpleDateFormat("d MMMM yyyy", Locale("ru"))

        view.findViewById<TextView>(R.id.tvDetailTime).text = fmtTime.format(Date(timestamp))
        view.findViewById<TextView>(R.id.tvDetailDate).text = fmtDate.format(Date(timestamp))
        view.findViewById<TextView>(R.id.tvDetailChars).text = "${text.length} симв."
        view.findViewById<TextView>(R.id.tvDetailText).text = text

        view.findViewById<MaterialButton>(R.id.btnDetailCopy).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("transcription", text))
            Toast.makeText(requireContext(), "Скопировано", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<MaterialButton>(R.id.btnDetailSave).setOnClickListener {
            val id   = UUID.randomUUID().toString().take(8)
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
            val filename = "audio_${id}_${date}.txt"
            saveToDownloads(requireContext(), text, filename)
        }

        // Раскрывать на полный экран автоматически
        dialog?.setOnShowListener {
            val bottomSheet = dialog!!.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = 600
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun saveToDownloads(context: Context, text: String, filename: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    Toast.makeText(context, "Сохранено в Загрузки: $filename", Toast.LENGTH_LONG).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, filename).writeText(text)
                Toast.makeText(context, "Сохранено в Загрузки: $filename", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
