package com.example.voicemind

// VoiceMind Transport v1 framing:
//   START(0xAA,1) + SEQ(2 be) + TYPE(1) + LEN(2 be) + PAYLOAD(LEN) + CRC8(1)
// CRC8 poly 0x31, init 0x00, по байтам от START до последнего байта PAYLOAD.
// Устойчив к мусору: при плохом LEN или несовпадении CRC возвращается в WAIT_START.
class VoiceMindPacketParser(
    private val onAudio:     ((pcm16le: ByteArray) -> Unit)? = null,
    private val onHeartbeat: (() -> Unit)? = null,
    private val onStatus:    ((battPct: Int, isRec: Boolean) -> Unit)? = null,
) {
    private enum class S {
        WAIT_START, READ_SEQ_H, READ_SEQ_L, READ_TYPE,
        READ_LEN_H, READ_LEN_L, READ_PAYLOAD, READ_CRC
    }

    private var state = S.WAIT_START
    private var pktType = 0
    private var payloadLen = 0
    private var payloadPos = 0
    private val payloadBuf = ByteArray(1024)
    private var crc = 0

    fun feed(data: ByteArray, len: Int) {
        for (i in 0 until len) processByte(data[i])
    }

    private fun crc8Update(b: Int) {
        crc = crc xor (b and 0xFF)
        repeat(8) {
            crc = if ((crc and 0x80) != 0) ((crc shl 1) and 0xFF) xor 0x31
                  else (crc shl 1) and 0xFF
        }
    }

    private fun reset() {
        state = S.WAIT_START
    }

    private fun processByte(b: Byte) {
        val ub = b.toInt() and 0xFF

        when (state) {
            S.WAIT_START -> {
                if (ub == 0xAA) {
                    crc = 0
                    crc8Update(ub)
                    state = S.READ_SEQ_H
                }
            }
            S.READ_SEQ_H -> { crc8Update(ub); state = S.READ_SEQ_L }
            S.READ_SEQ_L -> { crc8Update(ub); state = S.READ_TYPE  }
            S.READ_TYPE  -> { pktType = ub; crc8Update(ub); state = S.READ_LEN_H }
            S.READ_LEN_H -> { payloadLen = ub shl 8; crc8Update(ub); state = S.READ_LEN_L }
            S.READ_LEN_L -> {
                payloadLen = payloadLen or ub
                crc8Update(ub)
                if (payloadLen < 0 || payloadLen > payloadBuf.size) { reset(); return }
                if (payloadLen == 0) { state = S.READ_CRC; return }
                payloadPos = 0
                state = S.READ_PAYLOAD
            }
            S.READ_PAYLOAD -> {
                payloadBuf[payloadPos++] = b
                crc8Update(ub)
                if (payloadPos >= payloadLen) state = S.READ_CRC
            }
            S.READ_CRC -> {
                val expected = crc and 0xFF
                if (ub == expected) dispatchPacket()
                // Bad CRC → просто роняем, следующий START поймает
                reset()
            }
        }
    }

    private fun dispatchPacket() {
        when (pktType) {
            0x01 -> onAudio?.invoke(payloadBuf.copyOf(payloadLen))
            0x05 -> if (payloadLen >= 2) {
                val batt = payloadBuf[0].toInt() and 0xFF
                val rec  = (payloadBuf[1].toInt() and 0xFF) != 0
                onStatus?.invoke(batt, rec)
            }
            0x06 -> onHeartbeat?.invoke()
        }
    }
}
