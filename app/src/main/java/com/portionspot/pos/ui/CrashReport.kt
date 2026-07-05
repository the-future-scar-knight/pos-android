package com.portionspot.pos.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, dependency-free crash reporter for on-device testing without adb.
 *
 * [install] registers a process-wide uncaught-exception handler that synchronously
 * persists the fatal stack trace (to SharedPreferences + an external file) and then
 * chains to the previous handler so the OS still terminates the process normally.
 *
 * On the NEXT launch, [MainActivity] reads the stored report and shows
 * [CrashReportScreen] (system font, no Room/ViewModel/theme dependencies, so it
 * renders even when the real app cannot) with a Share button — the tester can send
 * the trace over WhatsApp/email instead of needing a USB logcat.
 */
object CrashReporter {
    private const val PREFS = "crash_report"
    private const val KEY = "last_crash"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("ON-SPOT POS crash report\n")
                    append("time:   ")
                        .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                        .append('\n')
                    append("device: ")
                        .append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                        .append("  Android ").append(android.os.Build.VERSION.RELEASE)
                        .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n\n")
                    append(sw.toString())
                }
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, text).commit()
                runCatching {
                    appCtx.getExternalFilesDir(null)?.let { File(it, "last_crash.txt").writeText(text) }
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ON-SPOT POS crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun CrashReportScreen(text: String, onShare: () -> Unit, onContinue: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("The app hit an error", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "These are the technical details of the last crash. Tap “Share report” to send " +
                    "them for diagnosis, then “Continue” to retry.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = onShare) { Text("Share report") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onContinue) { Text("Continue") }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
