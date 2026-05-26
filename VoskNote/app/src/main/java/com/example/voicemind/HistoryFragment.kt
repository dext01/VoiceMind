package com.example.voicemind

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class HistoryFragment : Fragment(R.layout.fragment_history) {

    private lateinit var rvHistory:        RecyclerView
    private lateinit var cardDateSelector: com.google.android.material.card.MaterialCardView
    private lateinit var tvSelectedDate:   TextView
    private lateinit var btnAnalyzeDay:    MaterialButton

    private val fmtKey  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val fmtDisp = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
    private var selectedDateStr = fmtKey.format(Date())

    private var currentEntries: List<DailyRecord.Entry> = emptyList()
    private var savedSummary: String = ""

    // Отмена анализа: каждый запуск получает уникальный ID;
    // если при возврате результата ID не совпадает — результат выбрасывается
    @Volatile private var activeJobId: Long = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvHistory        = view.findViewById(R.id.rvHistory)
        cardDateSelector = view.findViewById(R.id.cardDateSelector)
        tvSelectedDate   = view.findViewById(R.id.tvSelectedDate)
        btnAnalyzeDay    = view.findViewById(R.id.btnAnalyzeDay)

        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        updateDateLabel()
        loadEntriesForDay(selectedDateStr)

        cardDateSelector.setOnClickListener { openDatePicker() }
        btnAnalyzeDay.setOnClickListener { showEntrySelectionDialog() }
        btnAnalyzeDay.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        loadEntriesForDay(selectedDateStr)
    }

    private fun updateDateLabel() {
        val date = fmtKey.parse(selectedDateStr) ?: Date()
        tvSelectedDate.text = fmtDisp.format(date)
    }

    private fun openDatePicker() {
        val millis = fmtKey.parse(selectedDateStr)?.time ?: System.currentTimeMillis()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Выберите дату")
            .setSelection(millis)
            .build()
        picker.addOnPositiveButtonClickListener { ms ->
            selectedDateStr = fmtKey.format(Date(ms))
            updateDateLabel()
            loadEntriesForDay(selectedDateStr)
        }
        picker.show(parentFragmentManager, "date_picker")
    }

    private fun loadEntriesForDay(date: String) {
        thread {
            val records = RecordStorage.getAllRecords(requireContext())
            val record  = records.find { it.date == date }
            val entries = record?.entries?.sortedByDescending { it.timestamp } ?: emptyList()
            val summary = record?.daySummary.orEmpty()

            requireActivity().runOnUiThread {
                currentEntries = entries
                savedSummary   = summary
                btnAnalyzeDay.isEnabled = entries.isNotEmpty()
                rebuildList()
            }
        }
    }

    private fun rebuildList() {
        val items = mutableListOf<HistoryItem>()

        // Сохранённый анализ — первым элементом
        if (savedSummary.isNotBlank()) {
            items += HistoryItem.Summary(savedSummary)
        }

        currentEntries.forEach { entry ->
            items += HistoryItem.Entry(
                LogEntry(
                    timestamp   = entry.timestamp,
                    rawText     = entry.text,
                    structured  = entry.structured,
                    keyThoughts = entry.keyThoughts,
                    tags        = entry.tags,
                    source      = entry.source,
                    audioPath   = entry.audioPath ?: ""
                )
            )
        }

        rvHistory.adapter = HistoryAdapter(
            items           = items,
            date            = selectedDateStr,
            fragmentManager = parentFragmentManager,
            onDelete        = { timestamp ->
                thread {
                    RecordStorage.deleteEntry(requireContext(), selectedDateStr, timestamp)
                    loadEntriesForDay(selectedDateStr)
                }
            },
            onDeleteSummary = { deleteSummary() }
        )
    }

    private fun deleteSummary() {
        thread {
            RecordStorage.saveDaySummary(requireContext(), selectedDateStr, "")
            requireActivity().runOnUiThread {
                savedSummary = ""
                rebuildList()
            }
        }
    }

    // ─── Диалог выбора записей ────────────────────────────────────────────────

    private fun showEntrySelectionDialog() {
        val llm = (activity as? MainActivity)?.llmHelper
        if (llm == null || !llm.isReady) {
            AlertDialog.Builder(requireContext())
                .setTitle("LLM не загружена")
                .setMessage("Модель ещё загружается или не найдена. Подождите немного и попробуйте снова.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (currentEntries.isEmpty()) return

        val fmtTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val labels  = currentEntries.map { entry ->
            val time    = fmtTime.format(Date(entry.timestamp))
            val preview = entry.text.take(60).replace('\n', ' ')
            "$time  —  $preview${if (entry.text.length > 60) "…" else ""}"
        }.toTypedArray()

        val checked = BooleanArray(currentEntries.size) { true }

        AlertDialog.Builder(requireContext())
            .setTitle("Выберите записи для анализа")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Анализировать") { _, _ ->
                val selected = currentEntries.filterIndexed { i, _ -> checked[i] }
                if (selected.isNotEmpty()) runAnalysis(llm, selected)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Фоновый анализ ───────────────────────────────────────────────────────

    private fun runAnalysis(llm: LlmHelper, entries: List<DailyRecord.Entry>) {
        val jobId = System.currentTimeMillis()
        activeJobId = jobId

        btnAnalyzeDay.text = "✕ Остановить"
        btnAnalyzeDay.isEnabled = true
        // Нажатие во время анализа — отмена
        btnAnalyzeDay.setOnClickListener { cancelAnalysis() }

        thread {
            try {
                val combinedText = entries.joinToString("\n\n") { it.text }
                val result = llm.structure(combinedText)

                // Если пользователь отменил пока считалось — выбрасываем результат
                if (activeJobId != jobId) return@thread

                RecordStorage.saveDaySummary(requireContext(), selectedDateStr, result)

                requireActivity().runOnUiThread {
                    if (activeJobId != jobId) return@runOnUiThread
                    resetAnalyzeButton()
                    savedSummary = result
                    rebuildList()
                    rvHistory.scrollToPosition(0)
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    if (activeJobId == jobId) resetAnalyzeButton()
                }
            }
        }
    }

    private fun cancelAnalysis() {
        activeJobId = 0L   // любой пришедший результат будет проигнорирован
        resetAnalyzeButton()
    }

    private fun resetAnalyzeButton() {
        btnAnalyzeDay.text      = "✦ Выделить главное"
        btnAnalyzeDay.isEnabled = currentEntries.isNotEmpty()
        btnAnalyzeDay.setOnClickListener { showEntrySelectionDialog() }
    }
}
