package com.portionspot.pos.print

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import woyou.aidlservice.jiuiv5.ICallback
import woyou.aidlservice.jiuiv5.IWoyouService
import kotlin.coroutines.resume

/**
 * Prints to the built-in printer of a Sunmi handheld POS via the device's
 * InnerPrinter service (woyou.aidlservice.jiuiv5). We bind the service, hand it
 * the SAME ESC/POS byte stream the Bluetooth path uses (sendRAWData) — the stream
 * already ends with a feed + cut, so no extra commands are needed — then unbind.
 * On non-Sunmi hardware the bind simply fails and we report it.
 *
 * The transaction code for sendRAWData is fixed by the method order in
 * IWoyouService.aidl; see that file for why the order must not change.
 */
object SunmiPrinter {

    private const val SERVICE_PACKAGE = "woyou.aidlservice.jiuiv5"
    private const val SERVICE_ACTION = "woyou.aidlservice.jiuiv5.IWoyouService"

    suspend fun send(context: Context, data: ByteArray): PrintResult =
        withContext(Dispatchers.IO) {
            var connection: ServiceConnection? = null
            try {
                val service = withTimeoutOrNull(4000) {
                    suspendCancellableCoroutine<IWoyouService?> { cont ->
                        val conn = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                                if (cont.isActive) cont.resume(IWoyouService.Stub.asInterface(binder))
                            }
                            override fun onServiceDisconnected(name: ComponentName?) {}
                        }
                        connection = conn
                        val intent = Intent().apply {
                            setPackage(SERVICE_PACKAGE)
                            action = SERVICE_ACTION
                        }
                        val bound = runCatching {
                            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                        }.getOrDefault(false)
                        if (!bound && cont.isActive) cont.resume(null)
                    }
                } ?: return@withContext PrintResult.Error(
                    "Built-in printer not found. Is this a Sunmi device?"
                )

                // Send the raw ESC/POS and wait for the printer's own callback so we
                // report a real success/failure instead of failing silently. The byte[]
                // is copied into the service during the (synchronous) binder transact,
                // so the data is safe even if we unbind right after.
                val outcome = withTimeoutOrNull(8000) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        val callback = object : ICallback.Stub() {
                            override fun onRunResult(isSuccess: Boolean) {
                                if (cont.isActive) cont.resume(isSuccess)
                            }
                            override fun onReturnString(result: String?) {}
                            override fun onRaiseException(code: Int, msg: String?) {
                                if (cont.isActive) cont.resume(false)
                            }
                            override fun onPrintResult(code: Int, msg: String?) {
                                if (cont.isActive) cont.resume(code == 0)
                            }
                        }
                        try {
                            service.sendRAWData(data, callback)
                        } catch (e: RemoteException) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }

                when (outcome) {
                    false -> PrintResult.Error("Built-in printer rejected the job.")
                    // true = printer confirmed. null = the transact succeeded but this
                    // firmware never invoked the callback; the bytes were already
                    // delivered, so treat it as a best-effort success rather than a
                    // false alarm.
                    else -> PrintResult.Success
                }
            } catch (e: Exception) {
                PrintResult.Error(e.message ?: "Built-in printer failed")
            } finally {
                connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            }
        }
}
