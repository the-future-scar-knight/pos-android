package com.portionspot.pos.print

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prints via the RawBT print service (prompt §10). RawBT is a separate app the user
 * installs; it drives virtually any Bluetooth/USB/network printer it supports. We hand
 * it the SAME ESC/POS byte stream the Bluetooth and Sunmi paths use, encoded into
 * RawBT's `rawbt:base64,<data>` URL API, so no SDK or binding is needed.
 *
 * The RawBT APK for on-device testing lives at Reference\Rawbtprinter_7.0.3-185.apk —
 * it is installed on the test device, never bundled into this build.
 */
object RawBtPrinter {

    private const val RAWBT_PACKAGE = "ru.a402d.rawbtprinter"

    suspend fun send(context: Context, data: ByteArray): PrintResult = withContext(Dispatchers.Main) {
        val encoded = Base64.encodeToString(data, Base64.DEFAULT)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("rawbt:base64,$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.applicationContext.startActivity(intent)
            PrintResult.Success
        } catch (e: ActivityNotFoundException) {
            PrintResult.Error("RawBT app not found. Install RawBT to use this printer.")
        } catch (e: Exception) {
            PrintResult.Error(e.message ?: "RawBT print failed")
        }
    }

    /** True when the RawBT service app is installed. */
    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(RAWBT_PACKAGE, 0) }.isSuccess
}
