#pragma once
#include "Arduino.h"

// ─── I2S Пины (ICS-43434 → NodeMCU ESP32) ────────────────────────
#define I2S_WS_PIN     26   // WS  → GPIO 26
#define I2S_SCK_PIN    25   // SCK → GPIO 25
#define I2S_SD_PIN     22   // SD  → GPIO 22

// ─── GPIO ─────────────────────────────────────────────────────────
#define BTN_PIN         0   // Кнопка BOOT
#define LED_PIN         2   // Встроенный LED
#define BATT_ADC_PIN   34   // АЦП батареи

// ─── Аудио ────────────────────────────────────────────────────────
#define SAMPLE_RATE          16000
#define DMA_BUF_LEN            512   // SAMPLES в одном DMA блоке
#define DMA_BUF_CNT              8

// Размер одного аудиопакета. 256 samples × 2 = 512 байт payload.
// Полный фрейм: 6 header + 512 payload + 1 CRC = 519 байт — безопасно для SPP MTU.
#define AUDIO_SAMPLES_PER_PKT  256

// Программное усиление после >>16. ICS-43434 тихий, голос даёт 200-2000,
// Vosk хорошо работает на 3000-15000. 8x = сдвиг >>13 вместо >>16.
#define MIC_GAIN_SHIFT           3   // множитель 2^3 = 8x
#define MIC_CLIP_MAX         32000

// ─── Bluetooth ────────────────────────────────────────────────────
#define BT_DEVICE_NAME       "VoiceMind-01"
#define PKT_MAX_PAYLOAD        512
#define HEARTBEAT_MS          1000
#define BT_WRITE_TIMEOUT_MS     50

// ─── Типы пакетов ─────────────────────────────────────────────────
#define PKT_AUDIO       0x01
#define PKT_STATUS      0x05
#define PKT_HEARTBEAT   0x06
#define PKT_START_BYTE  0xAA
