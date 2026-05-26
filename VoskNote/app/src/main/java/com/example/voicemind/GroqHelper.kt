package com.example.voicemind

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GroqHelper {

    private const val TAG     = "GroqHelper"
    private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL   = "llama-3.1-8b-instant"

    var apiKey: String = ""  // вставь свой Groq API ключ

    // ─── Data ─────────────────────────────────────────────────────────────────

    /**
     * Результат полного анализа расшифровки.
     *
     * @param formattedText  отформатированная транскрипция (с разбивкой на абзацы/диалог)
     * @param keyIdeas       3–5 ключевых мыслей/идей, извлечённых из текста
     * @param tags           хэштеги-категории (#работа, #идея и т.п.)
     * @param speakerCount   количество обнаруженных говорящих (1 = монолог)
     */
    data class AnalysisResult(
        val formattedText: String,
        val keyIdeas:      List<String>,
        val tags:          List<String>,
        val speakerCount:  Int = 1,
    )

    // ─── Prompts ──────────────────────────────────────────────────────────────

    private val formatSystemPrompt = """
        Ты редактор аудио-расшифровок. Получаешь текст из распознавания речи и возвращаешь красиво отформатированный текст.

        Правила:
        - Расставь правильную пунктуацию
        - Раздели на абзацы по смыслу (каждые 3-5 предложений или при смене темы)
        - Исправь очевидные ошибки распознавания речи
        - Сохрани язык оригинала — если русский, пиши по-русски; если английский — по-английски
        - Если в тексте есть блоки вида [Говорящий А], [Говорящий Б] — это метки говорящих в диалоге. Сохрани их в точности, не меняй и не удаляй
        - Не добавляй ничего от себя, не интерпретируй, только форматируй
        - Не пиши вступление и заключение — только сам текст
    """.trimIndent()

    private val analysisSystemPrompt = """
        Ты аналитик аудио-расшифровок. Получаешь текст из распознавания речи и возвращаешь JSON-объект.

        Твои задачи:
        1. Отформатировать текст: правильная пунктуация, абзацы по смыслу, исправление ошибок распознавания
        2. Если несколько говорящих — сохрани метки [Говорящий А], [Говорящий Б] (или добавь их, если явно чередуются реплики)
        3. Выдели 3-5 ключевых мыслей/идей — коротко, по одному предложению каждая
        4. Подбери 1-3 тега из списка: #работа #идея #проблема #личное #обучение #финансы #планы #встреча #другое
        5. Укажи количество говорящих (speakerCount)

        Верни ТОЛЬКО валидный JSON без комментариев:
        {
          "text": "отформатированный текст",
          "key_ideas": ["идея 1", "идея 2", "идея 3"],
          "tags": ["#работа", "#идея"],
          "speaker_count": 1
        }

        Язык key_ideas и text — язык оригинала.
        Не добавляй никакого текста вне JSON.
    """.trimIndent()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Полный анализ: форматирование + ключевые мысли + теги.
     * При ошибке возвращает result с оригинальным текстом и пустыми полями.
     * Синхронный вызов — только из фонового потока.
     */
    fun analyzeWithIdeas(rawText: String): AnalysisResult {
        if (apiKey.isBlank() || rawText.isBlank()) {
            return AnalysisResult(rawText, emptyList(), emptyList())
        }
        return try {
            val content = callApi(
                systemPrompt = analysisSystemPrompt,
                userMessage  = rawText,
                temperature  = 0.3,
                maxTokens    = 2048,
                jsonMode     = true
            )
            parseAnalysisResult(content, rawText)
        } catch (e: Exception) {
            Log.e(TAG, "analyzeWithIdeas error: ${e.message}")
            AnalysisResult(rawText, emptyList(), emptyList())
        }
    }

    /**
     * Только форматирование текста (обратная совместимость).
     * Синхронный вызов — только из фонового потока.
     */
    fun structure(rawText: String): String {
        if (apiKey.isBlank()) {
            Log.w(TAG, "API key not set, skipping structuring")
            return rawText
        }
        if (rawText.isBlank()) return rawText
        return try {
            callApi(
                systemPrompt = formatSystemPrompt,
                userMessage  = rawText,
                temperature  = 0.3,
                maxTokens    = 4096,
                jsonMode     = false
            ).also { Log.i(TAG, "Structured ${rawText.length} -> ${it.length} chars") }
        } catch (e: Exception) {
            Log.e(TAG, "structure error: ${e.message}")
            rawText
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun callApi(
        systemPrompt: String,
        userMessage:  String,
        temperature:  Double,
        maxTokens:    Int,
        jsonMode:     Boolean,
    ): String {
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            if (jsonMode) put("response_format", JSONObject().put("type", "json_object"))
        }

        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            doOutput       = true
            connectTimeout = 15_000
            readTimeout    = 45_000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

        val code = conn.responseCode
        if (code != 200) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: ""
            throw IllegalStateException("HTTP $code: $errBody")
        }

        val response = conn.inputStream.bufferedReader().readText()
        return JSONObject(response)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun parseAnalysisResult(jsonStr: String, fallbackText: String): AnalysisResult {
        return try {
            val json         = JSONObject(jsonStr)
            val text         = json.optString("text", fallbackText).ifBlank { fallbackText }
            val ideasArr     = json.optJSONArray("key_ideas")
            val tagsArr      = json.optJSONArray("tags")
            val speakerCount = json.optInt("speaker_count", 1)

            val ideas = buildList {
                if (ideasArr != null) for (i in 0 until ideasArr.length()) add(ideasArr.getString(i))
            }
            val tags = buildList {
                if (tagsArr != null) for (i in 0 until tagsArr.length()) add(tagsArr.getString(i))
            }

            Log.i(TAG, "Analysis: ${ideas.size} ideas, ${tags.size} tags, $speakerCount speaker(s)")
            AnalysisResult(text, ideas, tags, speakerCount)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}")
            AnalysisResult(fallbackText, emptyList(), emptyList())
        }
    }
}
