# VoiceMind

VoiceMind — система голосовых заметок с офлайн-транскрипцией и AI-анализом. Состоит из двух частей: носимого устройства на ESP32 с микрофоном и Android-приложения.

Устройство захватывает речь через I2S-микрофон и передаёт аудио на телефон по Bluetooth. Приложение транскрибирует запись с помощью Whisper, определяет говорящих и при наличии LLM-модели выделяет ключевые мысли из текста. Всё работает без интернета.

---

## Что умеет

- Запись с внешнего микрофона ESP32 через Bluetooth SPP
- Офлайн-транскрипция через Whisper (tiny / base / small / medium)
- Диаризация: определение нескольких говорящих через ECAPA-TDNN или по паузам
- Выделение ключевых мыслей через локальную LLM (Qwen 2.5 1.5B)
- История записей по дням с поиском и анализом

---

## Железо

Схема подключения микрофона ICS-43434 к NodeMCU ESP32:

| Микрофон | ESP32 |
|----------|-------|
| WS       | GPIO 26 |
| SCK      | GPIO 25 |
| SD       | GPIO 22 |
| VDD      | 3.3V |
| GND      | GND |
| L/R      | GND (правый канал) |

Прошивка находится в папке `src/`. Сборка через PlatformIO:

```
pio run --target upload
```

Устройство появится в Bluetooth как `VoiceMind-01`. Спарьте его с телефоном в системных настройках Bluetooth до запуска приложения.

---

## Установка приложения

Скачайте APK из раздела [Releases](https://github.com/dext01/VoiceMind/releases) и установите на Android 8.0+. Перед установкой разрешите установку из неизвестных источников.

При первом запуске приложение предложит скачать LLM-модель (~900 МБ). Можно пропустить — транскрипция и диаризация работают без неё.

---

## Модели

Приложение ищет файлы моделей в папке `/data/data/com.example.voicemind/files/` (внутреннее хранилище). Скопировать туда можно через `adb push`.

**Whisper (обязательно для транскрипции)**

Скачайте один из файлов с [huggingface.co/ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp):

| Файл | Размер | Качество |
|------|--------|----------|
| ggml-tiny.bin | 75 МБ | базовое |
| ggml-base.bin | 142 МБ | хорошее |
| ggml-small.bin | 466 МБ | высокое |
| ggml-medium.bin | 1.5 ГБ | очень высокое |

```
adb push ggml-small.bin /data/data/com.example.voicemind/files/
```

**Диаризация (опционально)**

Модель ECAPA-TDNN для определения говорящих (экспорт speechbrain/spkrec-ecapa-voxceleb в ONNX):

```
adb push speaker_model.onnx /data/data/com.example.voicemind/files/
```

Без этого файла диаризация работает по паузам в речи.

**LLM (опционально)**

Qwen 2.5 1.5B для выделения ключевых мыслей. Приложение может скачать модель само при первом запуске, либо:

```
adb push Qwen2.5-1.5B-Instruct-Q4_K_M.gguf /data/data/com.example.voicemind/files/
```

---

## Сборка из исходников

Требования: Android Studio Hedgehog+, NDK 25.1.8937393, CMake 3.22+.

```
git clone --recurse-submodules https://github.com/dext01/VoiceMind
```

Субмодуль `llama.cpp` подтягивается автоматически. Откройте папку `VoskNote` в Android Studio и соберите проект.

---

## Структура репозитория

```
src/              — прошивка ESP32 (PlatformIO / Arduino)
VoskNote/         — Android-приложение (Kotlin + Whisper + llama.cpp)
logo/             — исходник логотипа
```
