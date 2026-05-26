package com.example.voicemind

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object RecordStorage {
    private const val FILE_NAME = "voicemind_records.json"
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    @Volatile private var cache: MutableMap<String, DailyRecord>? = null

    private fun getStorageFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun loadRecords(context: Context): MutableMap<String, DailyRecord> {
        return cache ?: loadFromDisk(context).also { cache = it }
    }

    private fun loadFromDisk(context: Context): MutableMap<String, DailyRecord> {
        return try {
            val file = getStorageFile(context)
            if (!file.exists()) mutableMapOf()
            else {
                val json = file.readText()
                gson.fromJson(json, object : TypeToken<MutableMap<String, DailyRecord>>() {}.type)
                    ?: mutableMapOf()
            }
        } catch (e: Exception) { mutableMapOf() }
    }

    private fun saveRecords(context: Context, records: Map<String, DailyRecord>) {
        if (cache !== records) cache = records.toMutableMap()
        try {
            val json = gson.toJson(records)
            getStorageFile(context).writeText(json)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getTodayRecord(context: Context): DailyRecord {
        val records = loadRecords(context)
        val today = dateFormat.format(Date())
        return records.getOrPut(today) { DailyRecord(today) }
    }

    fun addToToday(context: Context, text: String, source: String,
                   analyzer: TextAnalyzer?, audioPath: String = "") {
        val records = loadRecords(context)
        val today = dateFormat.format(Date())
        val record = records.getOrPut(today) { DailyRecord(today) }
        record.addEntry(text, source, analyzer, audioPath)
        records[today] = record
        saveRecords(context, records)
    }

    fun deleteEntry(context: Context, date: String, timestamp: Long) {
        val records = loadRecords(context)
        val record = records[date] ?: return
        record.entries.removeAll { it.timestamp == timestamp }
        if (record.entries.isEmpty()) {
            records.remove(date)
        } else {
            records[date] = record
        }
        saveRecords(context, records)
    }

    fun getTodaySummary(context: Context, analyzer: TextAnalyzer): String {
        val record = getTodayRecord(context)
        if (record.entries.isEmpty()) return "📭 Сегодня пока нет записей"
        // Переанализируем весь текст дня для целостной структуры
        val fullText = record.getAllText()
        return analyzer.analyze(fullText).structured
    }

    fun saveDaySummary(context: Context, date: String, summary: String) {
        val records = loadRecords(context)
        val record = records.getOrPut(date) { DailyRecord(date) }
        record.daySummary = summary
        records[date] = record
        saveRecords(context, records)
    }

    fun getAllRecords(context: Context): List<DailyRecord> {
        return loadRecords(context).values.sortedByDescending { it.date }
    }
}
