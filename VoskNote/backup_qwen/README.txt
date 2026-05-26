# VoiceMind — Backup: Qwen2.5-0.5B версия
# Дата: Mon May 11 13:27:13 UTC 2026
#
# Чтобы откатиться на Qwen:
#
# 1. Скопируй LlmHelper_QWEN.kt    → app/.../voicemind/LlmHelper.kt
# 2. Скопируй SetupActivity_QWEN.kt → app/.../voicemind/SetupActivity.kt
# 3. RecordFragment.kt, DailyRecord.kt, RecordStorage.kt — текущие файлы
#    совместимы с обеими версиями (analyzer nullable, showAnalysis(String))
#
# Основные отличия от Gemma-версии:
#   - MODEL: Qwen2.5-0.5B-Instruct-Q4_K_M.gguf (~390 MB)
#   - Chat template: ChatML (<|im_start|>system ... <|im_end|>)
#   - Gemma версия: gemma-3-1b-it-Q4_K_M.gguf (~660 MB)
#   - Chat template: <bos><start_of_turn>user ... <end_of_turn>

