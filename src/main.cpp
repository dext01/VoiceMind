// ═══════════════════════════════════════════════════════════════
//  VoiceMind — ESP32 MAIN (I2S mic → Bluetooth SPP streaming)
// ═══════════════════════════════════════════════════════════════

#include "Arduino.h"
#include "config.h"
#include "i2s_mic.h"
#include "bt_transport.h"

static int32_t rawBuf[DMA_BUF_LEN];
static int16_t pcmBuf[DMA_BUF_LEN];

static uint32_t lastHeartbeat = 0;
static bool streaming = false;

void setup() {
    delay(500);
    Serial.begin(115200);
    Serial.println("\n=== VoiceMind ESP32 starting ===");

    if (!I2SMic::begin()) {
        Serial.println("[ERR] Mic init failed!");
        while (true) delay(1000);
    }
    Serial.println("[OK] I2S mic ready");

    if (!BTTransport::begin()) {
        Serial.println("[ERR] Bluetooth init failed!");
        while (true) delay(1000);
    }
    Serial.println("[OK] Waiting for Android connection...");
    Serial.printf("     Pair with: %s\n", BT_DEVICE_NAME);
}

void loop() {
    bool connected = BTTransport::isConnected();

    if (connected && !streaming) {
        streaming = true;
        Serial.println("[BT] Client connected — streaming started");
        BTTransport::sendStatus(100, true);
    } else if (!connected && streaming) {
        streaming = false;
        Serial.println("[BT] Client disconnected");
    }

    // Heartbeat когда подключены
    if (connected && millis() - lastHeartbeat > HEARTBEAT_MS) {
        BTTransport::sendHeartbeat();
        lastHeartbeat = millis();
    }

    // Читаем микрофон
    size_t samples = I2SMic::read(rawBuf, pcmBuf, DMA_BUF_LEN, 20);
    if (samples == 0) return;

    if (connected) {
        BTTransport::sendAudio(pcmBuf, samples);
    }
}
