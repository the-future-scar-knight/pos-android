package com.portionspot.pos.print

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import woyou.aidlservice.jiuiv5.IWoyouService
import kotlin.coroutines.resume

/**
 * Prints to the built-in printer of a Sunmi handheld POS via the device's
 * InnerPrinter service (woyou.aidlservice.jiuiv5). We bind the service, hand it
 * the SAME ESC/POS byte stream the Bluetooth path uses (sendRAWData), then
 * unbind. On non-Sunmi hardware the bind simply fails and we report it.
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

                // Null callback is accepted by the InnerPrinter for fire-and-forget raw data.
                service.sendRAWData(data, null)
                Thread.sleep(150)
                PrintResult.Success
            } catch (e: Exception) {
                PrintResult.Error(e.message ?: "Built-in printer failed")
            } finally {
                connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            }
        }
}
