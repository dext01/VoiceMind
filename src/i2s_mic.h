#pragma once
#include "config.h"
#include "driver/i2s.h"

namespace I2SMic {

// DC-blocker (simple 1st-order high-pass) — убирает смещение ICS-43434.
// y[n] = x[n] - x[n-1] + R*y[n-1], R=0.995 для 16kHz = срез ~13 Hz.
static float s_prevX = 0.0f;
static float s_prevY = 0.0f;

bool begin() {
    Serial.println("[I2S] begin: prepare config");
    i2s_config_t i2s_config = {
        .mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
        .sample_rate          = SAMPLE_RATE,
        .bits_per_sample      = I2S_BITS_PER_SAMPLE_32BIT,
        .channel_format       = I2S_CHANNEL_FMT_ONLY_RIGHT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count        = DMA_BUF_CNT,
        .dma_buf_len          = DMA_BUF_LEN,
        .use_apll             = false,
        .tx_desc_auto_clear   = false,
        .fixed_mclk           = 0
    };

    i2s_pin_config_t pin_config = {
        .mck_io_num   = I2S_PIN_NO_CHANGE,
        .bck_io_num   = I2S_SCK_PIN,
        .ws_io_num    = I2S_WS_PIN,
        .data_out_num = I2S_PIN_NO_CHANGE,
        .data_in_num  = I2S_SD_PIN
    };

    esp_err_t err = i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL);
    if (err != ESP_OK) { Serial.printf("[I2S] driver_install err %d\n", err); return false; }

    err = i2s_set_pin(I2S_NUM_0, &pin_config);
    if (err != ESP_OK) { Serial.printf("[I2S] set_pin err %d\n", err); return false; }

    err = i2s_set_clk(I2S_NUM_0, SAMPLE_RATE, I2S_BITS_PER_SAMPLE_32BIT, I2S_CHANNEL_MONO);
    if (err != ESP_OK) { Serial.printf("[I2S] set_clk err %d\n", err); return false; }

    // Прогрев: первые сэмплы после include мусор.
    int32_t warmup[64];
    size_t dummy = 0;
    i2s_read(I2S_NUM_0, warmup, sizeof(warmup), &dummy, pdMS_TO_TICKS(100));

    Serial.println("[I2S] ICS-43434 OK");
    return true;
}

// Читает maxSamples сэмплов, применяет DC-blocker + gain + clip.
// Возвращает реальное количество прочитанных сэмплов (может быть 0 на timeout).
size_t read(int32_t* rawBuf, int16_t* pcmBuf, size_t maxSamples, uint32_t timeoutMs = 200) {
    size_t bytesRead = 0;
    esp_err_t err = i2s_read(I2S_NUM_0,
                             rawBuf,
                             maxSamples * sizeof(int32_t),
                             &bytesRead,
                             pdMS_TO_TICKS(timeoutMs));
    if (err != ESP_OK || bytesRead == 0) return 0;

    size_t samplesRead = bytesRead / sizeof(int32_t);

    for (size_t i = 0; i < samplesRead; i++) {
        // 24-bit в старших битах int32_t: берём как float для HPF
        float x = (float)((int32_t)rawBuf[i] >> 8); // 24-bit signed
        float y = x - s_prevX + 0.995f * s_prevY;
        s_prevX = x;
        s_prevY = y;

        // Нормализуем к 16-bit: сдвиг (24 - 16) = 8, но с доп. gain
        int32_t s = (int32_t)(y / (float)(1 << (8 - MIC_GAIN_SHIFT)));

        if (s >  MIC_CLIP_MAX) s =  MIC_CLIP_MAX;
        if (s < -MIC_CLIP_MAX) s = -MIC_CLIP_MAX;

        pcmBuf[i] = (int16_t)s;
    }

    return samplesRead;
}

void suspend() { i2s_stop(I2S_NUM_0); }
void resume()  { i2s_start(I2S_NUM_0); }

} // namespace I2SMic
