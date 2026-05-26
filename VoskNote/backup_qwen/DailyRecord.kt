package com.example.voicemind

import java.text.SimpleDateFormat
import java.util.*

data class DailyRecord(
    val date: String, // "yyyy-MM-dd"
    val entries: MutableList<Entry> = mutableListOf()
) {
    data class Entry(
        val timestamp: Long,
        val text: String,
        val source: String, // "voice", "text"
        val structured: String = "",
        val keyThoughts: List<String> = emptyList(),
        val tags: List<String> = emptyList()
    )

    fun addEntry(text: String, source: String, analyzer: TextAnalyzer?) {
        val result = analyzer?.analyze(text)
        entries.add(
            Entry(
                timestamp   = System.currentTimeMillis(),
                text        = text,
                source      = source,
                structured  = result?.structured ?: "",
                keyThoughts = result?.keyThoughts ?: emptyList(),
                tags        = result?.tags ?: emptyList()
            )
        )
    }

    fun getAllText(): String = entries.joinToString("\n\n") { it.text }
    fun getStructuredSummary(): String {
        return entries.joinToString("