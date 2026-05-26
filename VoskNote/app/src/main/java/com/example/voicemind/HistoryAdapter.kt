package com.example.voicemind

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

sealed class HistoryItem {
    data class DateHeader(val date: String)  : HistoryItem()
    data class Entry(val log: LogEntry)      : HistoryItem()
    data class Summary(val text: String)     : HistoryItem()
}

data class LogEntry(
    val timestamp:   Long,
    val rawText:     String,
    val structured:  String,
    val keyThoughts: List<String>,
    val tags:        List<String>,
    val source:      String,
    val audioPath:   String = ""
)

class HistoryAdapter(
    private val items: List<HistoryItem>,
    private val date: String,
    private val fragmentManager: FragmentManager,
    private val onDelete: (Long) -> Unit,
    private val onDeleteSummary: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val TYPE_HEADER  = 0
        private const val TYPE_ENTRY   = 1
        private const val TYPE_SUMMARY = 2
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is HistoryItem.DateHeader -> TYPE_HEADER
        is HistoryItem.Entry      -> TYPE_ENTRY
        is HistoryItem.Summary    -> TYPE_SUMMARY
    }

    inner class EntryVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime:    TextView     = v.findViewById(R.id.tvTime)
        val tvSource:  TextView     = v.findViewById(R.id.tvSource)
        val tvText:    TextView     = v.findViewById(R.id.tvText)
        val tvTags:    TextView     = v.findViewById(R.id.tvTags)
        val btnDel:    ImageButton  = v.findViewById(R.id.btnDeleteEntry)
        val btnOpen:   MaterialButton = v.findViewById(R.id.btnOpenDetail)
        val btnPlay:   MaterialButton = v.findViewById(R.id.btnPlayAudio)
        var player:    MediaPlayer? = null
    }

    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvDateHeader)
    }

    inner class SummaryVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvSummary: TextView    = v.findViewById(R.id.tvSummaryText)
        val btnDelete: android.widget.ImageButton = v.findViewById(R.id.btnDeleteSummary)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER  -> HeaderVH(inflater.inflate(R.layout.item_date_header, parent, false))
            TYPE_SUMMARY -> SummaryVH(inflater.inflate(R.layout.item_day_summary, parent, false))
            else         -> EntryVH(inflater.inflate(R.layout.item_today_entry, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HistoryItem.DateHeader -> (holder as HeaderVH).tvDate.text = item.date
            is HistoryItem.Summary -> {
                val vh = holder as SummaryVH
                vh.tvSummary.text = item.text
                vh.btnDelete.setOnClickListener { onDeleteSummary() }
            }
            is HistoryItem.Entry -> {
                val vh = holder as EntryVH
                val entry = item.log
                vh.tvTime.text   = fmtTime.format(Date(entry.timestamp))
                vh.tvSource.text = if (entry.source == "voice") "Голос" else "Текст"
                vh.tvText.text   = entry.rawText

                if (entry.tags.isNotEmpty()) {
                    vh.tvTags.visibility = View.VISIBLE
                    vh.tvTags.text = entry.tags.joinToString("  ")
                } else {
                    vh.tvTags.visibility = View.GONE
                }

                // Кнопка воспроизведения (audioPath может быть null в старых записях)
                val audioPath = entry.audioPath ?: ""
                val audioFile = if (audioPath.isNotBlank()) File(audioPath) else null
                if (audioFile != null && audioFile.exists()) {
                    vh.btnPlay.visibility = View.VISIBLE
                    vh.btnPlay.setOnClickListener {
                        if (vh.player?.isPlaying == true) {
                            vh.player?.stop()
                            vh.player?.release()
                            vh.player = null
                            vh.btnPlay.text = "▶ Слушать"
                        } else {
                            vh.player?.release()
                            vh.player = MediaPlayer().apply {
                                setDataSource(audioFile.absolutePath)
                                prepare()
                                start()
                                vh.btnPlay.text = "■ Стоп"
                                setOnCompletionListener {
                                    vh.btnPlay.text = "▶ Слушать"
                                    vh.player = null
                                }
                            }
                        }
                    }
                } else {
                    vh.btnPlay.visibility = View.GONE
                }

                val openDetail = {
                    TranscriptionDetailSheet.newInstance(entry)
                        .show(fragmentManager, "detail")
                }
                vh.btnOpen.setOnClickListener { openDetail() }
                vh.itemView.setOnClickListener { openDetail() }

                vh.itemView.setOnLongClickListener {
                    vh.btnDel.visibility = View.VISIBLE
                    true
                }
                vh.btnDel.setOnClickListener { onDelete(entry.timestamp) }
            }
        }
    }

    override fun getItemCount() = items.size
}
