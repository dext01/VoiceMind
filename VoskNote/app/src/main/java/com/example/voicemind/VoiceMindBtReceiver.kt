package com.example.voicemind

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.util.UUID
import kotlin.concurrent.thread

class VoiceMindBtReceiver(
    private val context: Context,
    private val deviceName: String,
    private val onConnected: () -> Unit,
    private val onDisconnected: (reason: String) -> Unit,
    private val onAudioPcm: (pcm16le: ByteArray) -> Unit,
    private val onHeartbeat: (() -> Unit)? = null,
    private val onStatus:    ((battPct: Int, isRec: Boolean) -> Unit)? = null,
) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Bluetooth недоступен на устройстве")

        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth выключен — включите его")

        val device = adapter.bondedDevices.firstOrNull { it.name == deviceName }
            ?: throw IllegalStateException(
                "Устройство '$deviceName' не найдено в паре. " +
                "Сначала спарьте его в настройках Bluetooth."
            )
        connectToDevice(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        cleanup()

        // Сбросить discovery — иначе connect тормозит
        try { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() } catch (_: Exception) {}

        val s = device.createRfcommSocketToServiceRecord(sppUuid)
        socket = s
        s.connect()
        inputStream = s.inputStream
        onConnected()

        worker = thread(name = "bt-voicemind-worker", isDaemon = true) {
            val input = inputStream ?: return@thread
            val parser = VoiceMindPacketParser(
                onAudio     = onAudioPcm,
                onHeartbeat = { onHeartbeat?.invoke() },
                onStatus    = { bp, rec -> onStatus?.invoke(bp, rec) }
            )
            val buf = ByteArray(4096)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    parser.feed(buf, read)
                }
                onDisconnected("Поток закончился")
            } catch (e: Exception) {
                onDisconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                cleanup()
            }
        }
    }

    fun stop() { cleanup() }

    private fun cleanup() {
        try { worker?.interrupt() } catch (_: Exception) {}
        worker = null
        try { inputStream?.close() } catch (_: Exception) {}
        inputStream = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
