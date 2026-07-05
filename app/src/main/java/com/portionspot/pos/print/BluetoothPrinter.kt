package com.portionspot.pos.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.min

/** A paired Bluetooth device the user can print to. */
data class PrinterDevice(val name: String, val mac: String)

/** Outcome of a print attempt. */
sealed class PrintResult {
    object Success : PrintResult()
    data class Error(val message: String) : PrintResult()
}

/**
 * Sends raw ESC/POS bytes to a paired thermal printer over Classic Bluetooth
 * (RFCOMM / Serial Port Profile). We only talk to ALREADY-paired devices, so
 * BLUETOOTH_SCAN/discovery is unnecessary — BLUETOOTH_CONNECT (API 31+) is enough.
 */
object BluetoothPrinter {
    // Standard Serial Port Profile UUID — what virtually all ESC/POS printers expose.
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val CHUNK = 512

    fun hasConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        else true

    /** Bonded (paired) devices, or empty if BT is off / permission missing. */
    @SuppressLint("MissingPermission")
    fun pairedPrinters(context: Context): List<PrinterDevice> {
        if (!hasConnectPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.map { PrinterDevice(it.name ?: it.address, it.address) }
        }.getOrDefault(emptyList())
    }

    /** Open an RFCOMM socket to [mac] and stream [data] in 512-byte chunks. */
    @SuppressLint("MissingPermission")
    suspend fun send(context: Context, mac: String, data: ByteArray): PrintResult =
        withContext(Dispatchers.IO) {
            if (!hasConnectPermission(context))
                return@withContext PrintResult.Error("Bluetooth permission not granted")
            val adapter = adapter(context)
                ?: return@withContext PrintResult.Error("Bluetooth not available on this device")
            if (!adapter.isEnabled)
                return@withContext PrintResult.Error("Bluetooth is turned off")
            val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
                ?: return@withContext PrintResult.Error("Printer not found ($mac)")

            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                // Best-effort: discovery slows a connect, but cancelDiscovery needs
                // BLUETOOTH_SCAN on API 31+ which we don't request — ignore if denied.
                runCatching { adapter.cancelDiscovery() }
                socket.connect()
                socket.outputStream.apply {
                    var i = 0
                    while (i < data.size) {
                        val end = min(i + CHUNK, data.size)
                        write(data, i, end - i)
                        flush()
                        Thread.sleep(30)    // small gap so slow heads keep up
                        i = end
                    }
                    flush()
                }
                Thread.sleep(150)           // let the buffer drain before we close
                PrintResult.Success
            } catch (e: Exception) {
                PrintResult.Error(e.message ?: "Bluetooth print failed — is the printer on?")
            } finally {
                runCatching { socket?.close() }
            }
        }

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}
