#pragma once
#include "config.h"
#include "BluetoothSerial.h"

namespace BTTransport {

BluetoothSerial SerialBT;
static uint16_t seqNum = 0;

static uint8_t crc8(const uint8_t* data, size_t len) {
    uint8_t crc = 0x00;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; bit++) {
            if (crc & 0x80) crc = (crc << 1) ^ 0x31;
            else             crc = (crc << 1);
        }
    }
    return crc;
}

static uint8_t _pktBuf[7 + PKT_MAX_PAYLOAD + 1];

bool begin() {
    if (!SerialBT.begin(BT_DEVICE_NAME)) {
        Serial.println("[BT] Ошибка запуска Bluetooth!");
        return false;
    }
    Serial.printf("[BT] Устройство '%s' видно в Bluetooth\n", BT_DEVICE_NAME);
    return true;
}

bool isConnected() { return SerialBT.hasClient(); }

// Отправка пакета с таймаутом. Возвращает true если записано целиком.
static bool sendPacket(uint8_t type, const uint8_t* payload, uint16_t payloadLen) {
    if (!SerialBT.hasClient()) return false;
    if (payloadLen > PKT_MAX_PAYLOAD) return false;

    _pktBuf[0] = PKT_START_BYTE;
    _pktBuf[1] = (seqNum >> 8) & 0xFF;
    _pktBuf[2] = seqNum & 0xFF;
    _pktBuf[3] = type;
    _pktBuf[4] = (payloadLen >> 8) & 0xFF;
    _pktBuf[5] = payloadLen & 0xFF;
    memcpy(&_pktBuf[6], payload, payloadLen);

    uint16_t totalBeforeCRC = 6 + payloadLen;
    _pktBuf[totalBeforeCRC] = crc8(_pktBuf, totalBeforeCRC);

    uint16_t total = totalBeforeCRC + 1;

    // Контролируем переполнение TX буфера. Если нет места — ждём до BT_WRITE_TIMEOUT_MS.
    uint32_t deadline = millis() + BT_WRITE_TIMEOUT_MS;
    size_t written = 0;
    while (written < total) {
        size_t n = SerialBT.write(&_pktBuf[written], total - written);
        if (n > 0) {
            written += n;
        } else {
            if ((int32_t)(millis() - deadline) > 0) {
                // Пропускаем пакет — лучше потерять 16 мс аудио, чем заблокировать луп
                return false;
            }
            delay(1);
        }
    }
    seqNum++;
    return true;
}

// Отправляет аудио. Если samples > AUDIO_SAMPLES_PER_PKT, бьёт на несколько пакетов.
void sendAudio(const int16_t* pcm, size_t samples) {
    size_t remaining = samples;
    const int16_t* cursor = pcm;
    while (remaining > 0) {
        size_t chunk = remaining < AUDIO_SAMPLES_PER_PKT ? remaining : AUDIO_SAMPLES_PER_PKT;
        sendPacket(PKT_AUDIO, (const uint8_t*)cursor, (uint16_t)(chunk * sizeof(int16_t)));
        cursor    += chunk;
        remaining -= chunk;
    }
}

void sendStatus(uint8_t battPct, bool isRec) {
    uint8_t payload[3] = { battPct, (uint8_t)isRec, 0x01 };
    sendPacket(PKT_STATUS, payload, sizeof(payload));
}

void sendHeartbeat() {
    uint8_t dummy = 0;
    sendPacket(PKT_HEARTBEAT, &dummy, 1);
}

} // namespace BTTransport
