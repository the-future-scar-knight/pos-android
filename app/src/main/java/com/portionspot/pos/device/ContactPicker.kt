package com.portionspot.pos.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A name + phone number pulled from the phone's contacts. Either field may be null. */
data class PickedContact(val name: String?, val phone: String?)

/**
 * Opens the system number picker via ACTION_PICK on the Phone data URI and returns
 * the picked Phone-data row URI (`content://.../data/NN`), or null if the user backed
 * out. This flow needs **no runtime permission**: the picker grants a one-shot read on
 * exactly the row the user chose, so we never ask for READ_CONTACTS just to copy one
 * number into a customer record.
 */
object PickPhoneNumberContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Resolve a picked Phone-data URI to a name + number via a ContentResolver query. */
fun resolvePickedNumber(context: Context, uri: Uri): PickedContact? {
    val projection = arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME)
    return context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            PickedContact(
                name = c.getString(0)?.trim()?.ifBlank { null },
                phone = c.getString(1)?.trim()?.ifBlank { null }
            )
        } else null
    }
}

/**
 * Compose helper: returns a `launch()` lambda that opens the contact/number picker and
 * hands back the resolved [PickedContact] on the main thread. The resolution query hops
 * to IO so the ContentResolver is never touched on the main thread. If the user cancels
 * or the row can't be read, [onPicked] is simply not called.
 */
@Composable
fun rememberContactPicker(onPicked: (PickedContact) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(PickPhoneNumberContract) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    runCatching { resolvePickedNumber(context, uri) }.getOrNull()
                }
                if (picked != null && (picked.name != null || picked.phone != null)) {
                    onPicked(picked)
                }
            }
        }
    }
    return { launcher.launch(Unit) }
}

/**
 * Last-9-digits key so a number stored as `+263771234567`, `0771234567`, or
 * `771234567` all collapse to the same key — used to match callers against saved
 * customers and to de-dupe the call log. Returns null when there are no digits.
 */
fun phoneKey(raw: String?): String? {
    val digits = raw?.filter(Char::isDigit) ?: return null
    return if (digits.isEmpty()) null else digits.takeLast(9)
}
