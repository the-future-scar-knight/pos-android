package com.portionspot.pos.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Everything needed to work offline after the first online login. */
@Serializable
data class CachedAuth(
    val userId: String,
    val email: String,
    val role: String,          // "admin" | "cashier"
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,       // epoch seconds
)

/**
 * Encrypted at-rest storage for the Supabase session and the offline-unlock
 * PIN hash. Uses a hardware-backed (where available) Android Keystore
 * AES-256-GCM key directly — no extra dependencies, minSdk 23 compatible.
 *
 * PIN is never stored: only PBKDF2-HmacSHA256(pin, random salt, 120k rounds).
 */
class SessionVault(context: Context) {

    private val prefs = context.getSharedPreferences("pos_auth_vault", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ── session ───────────────────────────────────────────────────────────

    fun saveSession(auth: CachedAuth) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(auth).toByteArray())
        prefs.edit().putString(KEY_SESSION, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun loadSession(): CachedAuth? {
        val b64 = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            val sealed = Base64.decode(b64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, sealed, 0, IV_LEN))
            val plain = cipher.doFinal(sealed, IV_LEN, sealed.size - IV_LEN)
            json.decodeFromString<CachedAuth>(String(plain))
        } catch (_: Exception) {
            // Corrupt blob or keystore key invalidated: treat as signed out.
            null
        }
    }

    fun clearSession() = prefs.edit().remove(KEY_SESSION).apply()

    // ── PIN ───────────────────────────────────────────────────────────────

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(pbkdf2(pin, salt), Base64.NO_WRAP))
            .putInt(KEY_PIN_FAILS, 0)
            .apply()
    }

    /** True on match. Failed attempts are counted by the caller via [recordPinFailure]. */
    fun verifyPin(pin: String): Boolean {
        val salt = Base64.decode(prefs.getString(KEY_PIN_SALT, null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(KEY_PIN_HASH, null) ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(expected, pbkdf2(pin, salt))
    }

    fun recordPinFailure(): Int {
        val fails = prefs.getInt(KEY_PIN_FAILS, 0) + 1
        prefs.edit().putInt(KEY_PIN_FAILS, fails).apply()
        return fails
    }

    fun resetPinFailures() = prefs.edit().putInt(KEY_PIN_FAILS, 0).apply()

    fun clearPin() = prefs.edit()
        .remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).remove(KEY_PIN_FAILS).apply()

    fun clearAll() = prefs.edit().clear().apply()

    // ── internals ─────────────────────────────────────────────────────────

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256))
            .encoded

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pos_session_vault"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val KEY_SESSION = "session"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_SALT = "pin_salt"
        const val KEY_PIN_FAILS = "pin_fails"
    }
}
